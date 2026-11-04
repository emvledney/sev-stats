package dev.silveradostats.link

import dev.silveradostats.obd.AdapterTimeoutError
import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.DidReader
import dev.silveradostats.obd.ElmLink
import dev.silveradostats.obd.ElmTransport
import dev.silveradostats.obd.FatalTransportError
import dev.silveradostats.obd.LinkLostError
import dev.silveradostats.obd.Poller
import dev.silveradostats.obd.PollerState
import dev.silveradostats.obd.Profile
import dev.silveradostats.obd.SimTransport
import dev.silveradostats.obd.Transport
import dev.silveradostats.obd.TransportError
import dev.silveradostats.obd.UdsClient
import dev.silveradostats.obd.UnsafeRequestError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil

/** Where the live data comes from. Two sources are the same when their [key]s are equal. */
sealed class LinkSource {
  abstract val key: String
  abstract val description: String

  /** The simulated truck ([SimTransport]): demo mode. */
  object Demo : LinkSource() {
    override val key = "demo"
    override val description = "Simulated Silverado EV (demo data)"
  }

  /**
   * An ELM327 / OBDLink adapter reached through [open] (Bluetooth RFCOMM for the MX+). [open] blocks,
   * returns the raw byte link and throws [ConnectFailure] when the adapter can't be reached.
   */
  class Adapter(val address: String, override val description: String, val open: () -> ByteLink) : LinkSource() {
    override val key = "adapter:$address"
  }
}

/**
 * Automatic reconnect spacing after an unreachable adapter: [minMs], doubling up to [maxMs].
 * Never faster than [MIN_AUTO_RETRY_MS] (refused at construction).
 */
class RetryPolicy(val minMs: Long = MIN_AUTO_RETRY_MS, val maxMs: Long = 60_000) {
  init {
    require(minMs >= MIN_AUTO_RETRY_MS) { "refusing to reconnect more often than every ${MIN_AUTO_RETRY_MS / 1000} s" }
    require(maxMs >= minMs)
  }

  /** Delay before the next attempt after [failures] consecutive failures (>= 1). */
  fun delayMs(failures: Int): Long {
    val shift = (failures - 1).coerceIn(0, 20)
    return minOf(maxMs, minMs shl shift).coerceAtLeast(minMs)
  }

  companion object {
    const val MIN_AUTO_RETRY_MS = 15_000L
  }
}

/** User-facing texts for link problems (pure, unit tested). */
object LinkMessages {
  const val NO_ADAPTER = "Choose your OBDLink MX+ under Diagnostics › OBDLink MX+ link, or turn on demo data there."
  const val IDLE = "Connects while the app is on screen."
  const val ASLEEP_SILENT = "Truck asleep: nothing is sent on the bus. The adapter's own voltage is checked once a minute; " +
    "polling resumes when the truck wakes."
  const val ASLEEP_NO_VOLTAGE = "Truck asleep: single checks at 1, 5 and 30 minutes (this adapter can't report its voltage)."
  const val POLLING_STOPPED = "Polling stopped: the truck stayed asleep. Tap Retry once the truck is on."
  const val POLLING_DEMO = "Simulated truck: these values are not from your battery."
  const val POLLING = "Reading the truck through the MX+ (read-only, one request at a time)."

  /** Message for a lost link or a timeout that an automatic full reconnect will follow. */
  fun recovering(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return if (e is AdapterTimeoutError) {
      "The MX+ did not answer in time ($m). Reconnecting automatically with a full adapter setup; tap Retry to do it now."
    } else {
      "Connection to the MX+ was lost ($m). Reconnecting automatically; tap Retry to do it now."
    }
  }

  /** Message for an error that stopped the session. */
  fun stopped(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return when {
      e is LinkLostError || m.startsWith("adapter link failed") || m.startsWith("adapter closed the connection") || m == "adapter link is closed" ->
        "Connection to the MX+ was lost ($m). Check that it is plugged in and awake, then tap Retry."
      e is UnsafeRequestError -> "Stopped: a request was refused by the read-only guard ($m). Nothing was sent."
      e is FatalTransportError -> "Stopped: $m. Nothing more was sent; tap Retry to reconnect and set the adapter up again."
      e is TransportError -> "Stopped: $m. Tap Retry to reconnect."
      else -> "Stopped by an unexpected error: ${e.javaClass.simpleName}: $m"
    }
  }

  fun setupFailed(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return if (e is TransportError) "Adapter setup failed: $m" else "Adapter setup failed: ${e.javaClass.simpleName}: $m"
  }
}

/**
 * Owns the OBD session: the transport (MX+ or simulator), the [Poller] on one background thread,
 * and the published [LiveState]. Pure JVM (unit tested); LinkService wraps it for Android.
 *
 * Rules:
 *  - polling runs only while at least one client is attached ([attach] / [detach]; the app attaches
 *    while a screen is visible). When the last one detaches, the poller stops before its next
 *    request. The session is then parked: nothing at all is sent (not even adapter-local commands),
 *    and if no screen comes back within [graceMs] the link (the Bluetooth socket) is closed,
 *    releasing the MX+. A screen that comes back in time resumes the same session and poller (no
 *    new connect, ATZ or all-DID burst, and the poller keeps its asleep state);
 *  - a connect or adapter setup in progress is cut short as soon as nobody needs it any more (last
 *    detach, source switch, link test, shutdown): the link being set up is closed from the calling
 *    thread, so the worker's blocked read fails at once;
 *  - strictly one thread talks to the adapter; every request goes through the core's guards;
 *  - a transport error stops the session (the link is closed). A lost connection or a timeout
 *    ([LinkLostError], [AdapterTimeoutError]) is followed by an automatic full reconnect (new
 *    socket, settle, ATZ, required settings, new poller), at most [MAX_AUTO_RECOVERIES] times in a
 *    row, spaced by [RetryPolicy]. Anything else (adapter reset, bus error, rejected setting,
 *    refused request) waits for Retry. An unreachable adapter is retried automatically too. No
 *    automatic attempt comes sooner than [RetryPolicy.MIN_AUTO_RETRY_MS] after the previous one;
 *  - the poller runs in watch mode (stopWhenAsleep = false), exactly as the probe's `serve`: it goes
 *    bus-silent while the truck sleeps.
 */
class LinkEngine(
  private val profile: Profile,
  private val clock: () -> Long = MonoClock::ms,
  private val retry: RetryPolicy = RetryPolicy(),
  private val demoTransport: () -> Transport = { SimTransport().apply { volts = DEMO_ADAPTER_VOLTS } },
  private val elm: (ByteLink) -> ElmLink = { ElmLink(it) },
  /** How long a session stays parked (silent, link open) after the last client detached. */
  private val graceMs: Long = GRACE_MS,
  threadName: String = "obd-link",
) : Closeable {
  private val lock = ReentrantLock()
  private val wake = lock.newCondition()

  // Guarded by lock.
  private val clients = LinkedHashSet<Any>()
  private var source: LinkSource? = null
  private var blocked = false
  private var blockedAtMs = 0L
  private var retryRequested = false
  private var autoRetryAtMs: Long? = null
  private var failures = 0
  private val checks = ArrayDeque<CheckRequest>()
  private var shutdown = false
  private var nextCycleAtMs = 0L
  private var session: Session? = null

  /** When the last client detached (start of the grace period of a parked session). */
  private var parkedAtMs = 0L

  /** The worker has published that the current session is parked (polling = false). */
  private var parkShown = false

  /** Automatic recoveries (lost link / timeout) since the last reset; at most [MAX_AUTO_RECOVERIES]. */
  private var recoveries = 0

  /** The adapter link while connect() sets it up; closed from other threads to cut the setup short. */
  private var settingUp: ByteLink? = null

  /** The adapter link the running link test is using; closed from other threads to end it at once. */
  private var checking: ByteLink? = null

  /** The running link test was called off ([cancelChecks]). */
  private var checkCancelled = false

  /** Set to stop the running poller before its next request (checked between requests). */
  @Volatile private var abort = false

  // Worker thread only.
  private val history = PowerHistory()
  private val values = LinkedHashMap<String, LiveValue>()
  private var dataKey: String? = null
  private val periods: Map<String, Double> = profile.didFields.associate { it.name to it.period }

  // Published state.
  private val publishLock = Any()
  private var base = LiveState(message = LinkMessages.NO_ADAPTER)
  private var pairing = false
  private val _state = MutableStateFlow(base)
  val state: StateFlow<LiveState> = _state.asStateFlow()

  private class Session(val source: LinkSource, val transport: Transport, val poller: Poller, val startedAtMs: Long)

  private class CheckRequest(val target: String, val source: LinkSource.Adapter, val onResult: (LinkCheckResult) -> Unit)

  private class SessionAborted : RuntimeException("session closed")

  private sealed class Action {
    object Exit : Action()
    object Close : Action()
    object ResetData : Action()
    object Park : Action()
    object Resume : Action()
    object Cycle : Action()
    class Connect(val source: LinkSource) : Action()
    class Check(val request: CheckRequest) : Action()
  }

  private val worker = Thread({ run() }, threadName).apply {
    isDaemon = true
    start()
  }

  // --- Public API (any thread) ----------------------------------------------------------------

  /** A visible UI client. Polling runs while at least one is attached. */
  fun attach(token: Any) {
    val n = lock.withLock {
      if (clients.isEmpty()) {
        // A new visit: the automatic-recovery budget starts again, and after a stop it tries again
        // (still never sooner than the auto-retry floor).
        recoveries = 0
        if (blocked && clock() - blockedAtMs >= retry.minMs) blocked = false
      }
      clients += token
      wake.signalAll()
      clients.size
    }
    publish { it.copy(clients = n) }
  }

  fun detach(token: Any) {
    val n = lock.withLock {
      val had = clients.isNotEmpty()
      clients -= token
      if (clients.isEmpty()) lastClientGone(had)
      wake.signalAll()
      clients.size
    }
    publish { it.copy(clients = n) }
  }

  fun detachAll() {
    lock.withLock {
      val had = clients.isNotEmpty()
      clients.clear()
      lastClientGone(had)
      wake.signalAll()
    }
    publish { it.copy(clients = 0) }
  }

  /** Lock held. Stops the poller before its next request, parks the session, cuts a setup short. */
  private fun lastClientGone(hadClients: Boolean) {
    abort = true
    if (hadClients) parkedAtMs = clock()
    abortSetup()
  }

  /** Lock held. Closes the link connect() is setting up, so its blocked read fails at once. */
  private fun abortSetup() {
    settingUp?.let { closeQuietly(it) }
    settingUp = null
  }

  /** The adapter (or the simulator) to use; null = none chosen. Switching closes the current session. */
  fun setSource(src: LinkSource?) {
    lock.withLock {
      if (src?.key == source?.key) return
      source = src
      abort = true
      abortSetup()
      blocked = false
      retryRequested = false
      autoRetryAtMs = null
      failures = 0
      recoveries = 0
      wake.signalAll()
    }
  }

  /** Re-runs the full connect and adapter setup (after an error, an unreachable adapter or a stop). */
  fun retry() {
    lock.withLock {
      retryRequested = true
      recoveries = 0
      wake.signalAll()
    }
  }

  /**
   * Retries at once if the link stopped with exactly [reason] (a [ConnectFailure] message), e.g. the
   * Bluetooth permission that has just been granted. Any other stop keeps waiting for Retry.
   */
  fun retryIfStoppedBy(reason: String) {
    val s = synchronized(publishLock) { base }
    if (s.canRetry && s.message == reason) retry()
  }

  /**
   * Diagnostics › Test link: connect + adapter setup only, no vehicle request. A live session on
   * an adapter is closed first (the MX+ serves one connection) and reopened afterwards if a screen
   * is still visible. [onResult] runs on the link thread.
   */
  fun requestCheck(target: String, source: LinkSource.Adapter, onResult: (LinkCheckResult) -> Unit) {
    lock.withLock {
      checks += CheckRequest(target, source, onResult)
      if (session?.source is LinkSource.Adapter) abort = true
      abortSetup() // the MX+ serves one connection: a connect in progress would hold it
      wake.signalAll()
    }
  }

  /**
   * Calls off every link test: queued ones answer at once, the running one fails as soon as its link
   * is closed (or, if it is still opening one, as soon as that returns). Each [requestCheck]
   * caller still gets its result, marked [CHECK_CALLED_OFF].
   */
  fun cancelChecks() {
    val dropped = lock.withLock {
      val queued = checks.toList()
      checks.clear()
      checkCancelled = true
      checking?.let { closeQuietly(it) }
      checking = null
      wake.signalAll()
      queued
    }
    for (c in dropped) {
      try {
        c.onResult(calledOff(c.target))
      } catch (_: Exception) {
      }
    }
  }

  private fun calledOff(target: String) =
    LinkCheckResult(target, false, "connect", null, null, null, null, null, null, emptyList(), CHECK_CALLED_OFF, 0, System.currentTimeMillis())

  /** Discovery / pairing in progress (shown as [LinkStatus.PAIRING] while no session is running). */
  fun setPairing(active: Boolean) {
    synchronized(publishLock) {
      pairing = active
      _state.value = effective(base)
    }
  }

  /** Stops polling, closes the link and ends the worker thread. */
  override fun close() {
    lock.withLock {
      shutdown = true
      abort = true
      abortSetup()
      wake.signalAll()
    }
  }

  /** For tests: waits until the worker thread has ended. */
  fun awaitTermination(timeoutMs: Long): Boolean {
    worker.join(timeoutMs)
    return !worker.isAlive
  }

  // --- Worker ---------------------------------------------------------------------------------

  private fun run() {
    try {
      while (true) {
        when (val a = nextAction()) {
          Action.Exit -> return
          Action.Close -> closeSession()
          Action.ResetData -> resetData()
          Action.Park -> publish { it.copy(polling = false) }
          Action.Resume -> publish { it.copy(polling = true) }
          Action.Cycle -> cycle()
          is Action.Connect -> connect(a.source)
          is Action.Check -> runCheck(a.request)
        }
      }
    } catch (_: InterruptedException) {
      // Shutting down.
    } finally {
      closeSession()
      publish { it.copy(polling = false) }
    }
  }

  private fun nextAction(): Action = lock.withLock {
    while (true) {
      if (shutdown) return Action.Exit
      checks.removeFirstOrNull()?.let {
        checkCancelled = false
        return Action.Check(it)
      }
      val src = source
      val s = session
      if (s != null && (src == null || s.source.key != src.key)) return Action.Close
      if (s != null && clients.isEmpty()) {
        // Parked: nothing is sent; the link stays open for the grace period, then closes.
        if (!parkShown) {
          parkShown = true
          return Action.Park
        }
        val wait = parkedAtMs + graceMs - clock()
        if (wait > 0) {
          wake.await(wait, TimeUnit.MILLISECONDS)
          continue
        }
        return Action.Close
      }
      if (s == null && dataKey != src?.key) return Action.ResetData
      if (src == null || clients.isEmpty()) {
        wake.await()
        continue
      }
      if (s != null) {
        retryRequested = false
        if (parkShown) {
          // A screen came back within the grace period: the same session and poller go on.
          parkShown = false
          return Action.Resume
        }
        val wait = nextCycleAtMs - clock()
        if (wait > 0) {
          wake.await(wait, TimeUnit.MILLISECONDS)
          continue
        }
        // Every reason to stop (no client, source switch, link test, shutdown) was handled above.
        abort = false
        return Action.Cycle
      }
      if (retryRequested) {
        retryRequested = false
        blocked = false
        autoRetryAtMs = null
        return Action.Connect(src)
      }
      if (blocked) {
        wake.await()
        continue
      }
      val at = autoRetryAtMs
      if (at != null) {
        val wait = at - clock()
        if (wait > 0) {
          wake.await(wait, TimeUnit.MILLISECONDS)
          continue
        }
      }
      return Action.Connect(src)
    }
    @Suppress("UNREACHABLE_CODE")
    Action.Exit
  }

  private fun resetData() {
    val src = lock.withLock {
      dataKey = source?.key
      source
    }
    values.clear()
    history.clear()
    publish {
      it.copy(
        status = if (src == null) LinkStatus.NO_ADAPTER else LinkStatus.CONNECTING,
        message = if (src == null) LinkMessages.NO_ADAPTER else LinkMessages.IDLE,
        demo = src is LinkSource.Demo,
        adapter = src?.description,
        adapterVolts = null,
        adapterVoltsAtMs = null,
        values = emptyMap(),
        derived = emptyMap(),
        lastDataAtMs = null,
        power = emptyList(),
        errors = emptyMap(),
        canRetry = false,
        nextAutoRetryAtMs = null,
        polling = false,
      )
    }
  }

  private fun block() = lock.withLock {
    blocked = true
    blockedAtMs = clock()
    autoRetryAtMs = null
  }

  /**
   * After an error: schedules an automatic full reconnect when [e] is a lost link or a timeout and
   * the budget allows (returns when), else blocks until Retry (returns null). A session that ran
   * for [STABLE_SESSION_MS] before it failed starts the budget again.
   */
  private fun recoverOrBlock(e: Throwable, ranMs: Long): Long? = lock.withLock {
    if (!shutdown && (e is LinkLostError || e is AdapterTimeoutError)) {
      if (ranMs >= STABLE_SESSION_MS) recoveries = 0
      if (recoveries < MAX_AUTO_RECOVERIES) {
        recoveries += 1
        blocked = false
        val at = clock() + retry.delayMs(recoveries)
        autoRetryAtMs = at
        return@withLock at
      }
    }
    blocked = true
    blockedAtMs = clock()
    autoRetryAtMs = null
    null
  }

  /** Lock held. connect() should go on with [src]: a client still wants it and nothing else is pending. */
  private fun stillWanted(src: LinkSource): Boolean =
    !shutdown && !abort && clients.isNotEmpty() && source?.key == src.key && checks.isEmpty()

  /** Opens the adapter and runs the setup; null when the connect was called off meanwhile. */
  private fun openAdapter(src: LinkSource.Adapter): Transport? {
    val raw = src.open()
    val go = lock.withLock {
      if (stillWanted(src)) {
        settingUp = raw
        true
      } else {
        false
      }
    }
    if (!go) {
      closeQuietly(raw) // nothing was written
      return null
    }
    try {
      return ElmTransport(elm(raw))
    } finally {
      lock.withLock { if (settingUp === raw) settingUp = null }
    }
  }

  /** A connect that nobody needs any more ended quietly (no error, no block). */
  private fun connectCalledOff() {
    publish { it.copy(status = LinkStatus.CONNECTING, message = LinkMessages.IDLE, polling = false) }
  }

  private fun connect(src: LinkSource) {
    abort = false
    val demo = src is LinkSource.Demo
    publish {
      it.copy(
        status = LinkStatus.CONNECTING,
        message = if (demo) "Starting the simulator…" else "Connecting to ${src.description}…",
        demo = demo,
        adapter = src.description,
        canRetry = false,
        nextAutoRetryAtMs = null,
        polling = false,
      )
    }
    val startedAt = clock()
    val transport: Transport = try {
      when (src) {
        is LinkSource.Demo -> demoTransport()
        is LinkSource.Adapter -> openAdapter(src) ?: return connectCalledOff()
      }
    } catch (e: ConnectFailure) {
      val next = lock.withLock {
        failures += 1
        if (e.retryable) {
          autoRetryAtMs = clock() + retry.delayMs(failures)
        } else {
          blocked = true
          blockedAtMs = clock()
          autoRetryAtMs = null
        }
        autoRetryAtMs
      }
      publish {
        it.copy(status = LinkStatus.ADAPTER_UNREACHABLE, message = e.message ?: "adapter unreachable", canRetry = true, nextAutoRetryAtMs = next)
      }
      return
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      // Cut short on purpose (the link was closed under it): not an error.
      if (lock.withLock { !stillWanted(src) }) return connectCalledOff()
      // TransportError (fatal or not), UnsafeRequestError, or anything unexpected during setup.
      val next = recoverOrBlock(e, clock() - startedAt)
      publish {
        it.copy(
          status = LinkStatus.STOPPED_ERROR,
          message = LinkMessages.setupFailed(e) + if (next != null) " Retrying automatically." else "",
          canRetry = true,
          nextAutoRetryAtMs = next,
        )
      }
      return
    }
    val poller = try {
      Poller(profile, transport, stopWhenAsleep = false, clientFactory = { ch -> abortable(UdsClient(ch)) })
    } catch (e: Exception) {
      transport.close()
      block()
      publish { it.copy(status = LinkStatus.STOPPED_ERROR, message = LinkMessages.setupFailed(e), canRetry = true) }
      return
    }
    val keep = lock.withLock {
      if (stillWanted(src)) {
        session = Session(src, transport, poller, clock())
        parkShown = false
        failures = 0
        autoRetryAtMs = null
        blocked = false
        nextCycleAtMs = clock()
        true
      } else {
        false
      }
    }
    if (!keep) {
      transport.close()
      return
    }
    val elmT = transport as? ElmTransport
    val now = clock()
    val volts = if (elmT != null) ElmTransport.parseVolts(elmT.voltage) else transport.adapterVoltage()
    publish {
      it.copy(
        status = if (demo) LinkStatus.DEMO else LinkStatus.CONNECTED,
        message = if (demo) LinkMessages.POLLING_DEMO else LinkMessages.POLLING,
        adapter = if (elmT != null) "${src.description} · ${elmT.ident}" else src.description,
        adapterVolts = volts ?: it.adapterVolts,
        adapterVoltsAtMs = if (volts != null) now else it.adapterVoltsAtMs,
        canRetry = false,
        nextAutoRetryAtMs = null,
        polling = true,
      )
    }
  }

  private fun abortable(inner: DidReader): DidReader = DidReader { did ->
    if (abort) throw SessionAborted()
    inner.readDid(did)
  }

  private fun cycle() {
    val s = lock.withLock { session } ?: return
    val sample = try {
      s.poller.cycle()
    } catch (_: SessionAborted) {
      // Stopped between two requests (the link is clean). nextAction parks, resumes or closes it.
      lock.withLock { parkShown = true }
      publish { it.copy(polling = false) }
      return
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      stop(s, e)
      return
    }
    val now = clock()
    for ((name, r) in sample.values) {
      values[name] = LiveValue(name, r.label, r.v, r.unit, r.status, periods[name] ?: 1.0, now)
    }
    if ("pack_current" in sample.values) {
      (sample.derived["power_kw"] as? Number)?.let { history.add(now, it.toDouble()) }
    }
    val wait = s.poller.nextWait()
    lock.withLock { nextCycleAtMs = if (wait != null) now + ceil(wait * 1000).toLong() else 0L }
    val demo = s.source is LinkSource.Demo
    val (status, message) = when (sample.state) {
      PollerState.AWAKE -> (if (demo) LinkStatus.DEMO else LinkStatus.CONNECTED) to (if (demo) LinkMessages.POLLING_DEMO else LinkMessages.POLLING)
      else -> LinkStatus.TRUCK_ASLEEP to (if (sample.busSilent) LinkMessages.ASLEEP_SILENT else LinkMessages.ASLEEP_NO_VOLTAGE)
    }
    val snapshot = LinkedHashMap(values)
    val power = history.snapshot(now)
    publish {
      it.copy(
        status = status,
        message = message,
        values = snapshot,
        derived = sample.derived,
        lastDataAtMs = if (sample.values.isNotEmpty()) now else it.lastDataAtMs,
        power = power,
        errors = if (sample.values.isNotEmpty() || sample.errors.isNotEmpty()) sample.errors else it.errors,
        adapterVolts = if (sample.adapterVoltsRead) sample.adapterVolts else it.adapterVolts,
        adapterVoltsAtMs = if (sample.adapterVoltsRead && sample.adapterVolts != null) now else it.adapterVoltsAtMs,
        polling = true,
      )
    }
    if (wait == null) {
      // The poller stopped for good (truck asleep and no way to notice it waking).
      closeSession()
      block()
      publish { it.copy(status = LinkStatus.TRUCK_ASLEEP, message = LinkMessages.POLLING_STOPPED, canRetry = true) }
    }
  }

  private fun stop(s: Session, e: Throwable) {
    closeSession()
    val next = recoverOrBlock(e, clock() - s.startedAtMs)
    publish {
      it.copy(
        status = LinkStatus.STOPPED_ERROR,
        message = if (next != null) LinkMessages.recovering(e) else LinkMessages.stopped(e),
        canRetry = true,
        nextAutoRetryAtMs = next,
        polling = false,
      )
    }
  }

  private fun closeQuietly(link: ByteLink) {
    try {
      link.close()
    } catch (_: Exception) {
    }
  }

  private fun closeSession() {
    val s = lock.withLock {
      val cur = session
      session = null
      cur
    } ?: return
    try {
      s.transport.close()
    } catch (_: Exception) {
    }
    publish { it.copy(polling = false) }
  }

  private fun runCheck(req: CheckRequest) {
    if (lock.withLock { session?.source is LinkSource.Adapter }) {
      closeSession()
      publish { it.copy(status = LinkStatus.CONNECTING, message = "Paused for the link test; reconnecting afterwards.") }
    }
    val open: () -> ByteLink = {
      val raw = req.source.open()
      val go = lock.withLock {
        if (!checkCancelled) checking = raw
        !checkCancelled
      }
      if (!go) {
        closeQuietly(raw) // nothing was written
        throw ConnectFailure(CHECK_CALLED_OFF, retryable = false)
      }
      raw
    }
    val result = LinkCheck.run(req.target, open, elm)
    val cancelled = lock.withLock {
      checking = null
      checkCancelled
    }
    try {
      req.onResult(if (cancelled) result.copy(ok = false, error = CHECK_CALLED_OFF) else result)
    } catch (_: Exception) {
    }
  }

  // --- Publishing -----------------------------------------------------------------------------

  private fun publish(f: (LiveState) -> LiveState) {
    synchronized(publishLock) {
      base = f(base)
      _state.value = effective(base)
    }
  }

  private fun effective(s: LiveState): LiveState =
    if (pairing && s.status in PAIRING_OVERRIDES) s.copy(status = LinkStatus.PAIRING, message = "Pairing with the adapter… (Diagnostics › OBDLink MX+ link)") else s

  companion object {
    /** The error of a link test ended by [cancelChecks]. */
    const val CHECK_CALLED_OFF = "Test link called off: the app is in driving mode or left the screen."

    /** What the simulator's 'adapter' reports as the 12 V at the OBD port. */
    const val DEMO_ADAPTER_VOLTS = 13.9

    /** A parked session keeps its (silent) link this long for a screen that comes back. */
    const val GRACE_MS = 15_000L

    /** Automatic reconnects after a lost link / timeout before Retry is needed (per visit). */
    const val MAX_AUTO_RECOVERIES = 5

    /** A session that ran this long before failing starts the automatic-recovery budget again. */
    const val STABLE_SESSION_MS = 5 * 60_000L

    private val PAIRING_OVERRIDES = setOf(LinkStatus.NO_ADAPTER, LinkStatus.ADAPTER_UNREACHABLE, LinkStatus.STOPPED_ERROR)
  }
}
