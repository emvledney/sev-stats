package dev.silveradostats.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): the session's sampling lifecycle. Unit tested on the JVM
 * with virtual time; SessionHub (Android) builds the one instance of the process.
 *
 * Rules:
 *  - sampling runs while at least one UI is attached (the Simple / Live / Diagnostics screens'
 *    ViewModel, or Battery Glance) and for [graceMs] after the last one detaches; then it stops and
 *    the car connection is released. It also stops, after [idleStopMs], when no attached UI is
 *    visible and the truck is not moving (moving, it goes on behind another app until Android freezes
 *    the process: measured on API 34 from 10 s to about 6 min with no screen of the app visible). A
 *    frozen stretch simply arrives as a gap, which the tracker leaves out;
 *  - the session survives a short pause (with a recorded gap). A reset, a data-source switch, the
 *    end of the process, or no sample for over [restartAfterMs] ("the next time the truck is used",
 *    even if the process was only frozen or asleep) starts a new one: "since the app was opened";
 *  - it never touches the OBD link (LinkEngine): opening only the Simple tab samples the truck's
 *    built-in data and nothing else;
 *  - one coroutine samples: speed every [tickMs] (2 Hz), the rest every other tick (1 Hz).
 *
 * [scope] must be confined to one thread (Dispatchers.IO.limitedParallelism(1) in the app): every
 * public call is posted to it, so the tracker and the source are only ever used from that thread.
 */

/** Where samples come from: the car service, the demo drive, or nothing on this device. */
interface VehicleSource {
  /**
   * Reads the truck once at [atMs]; [full] also reads the 1 Hz set (level, charge rate, range,
   * gear, charge state, ...), otherwise speed only. May block (binder calls).
   */
  fun read(atMs: Long, full: Boolean): SourceRead

  /** Samples from before [nowMs] to replay first (the demo's head start). Asked once per source. */
  fun backlog(nowMs: Long): List<VehicleSample> = emptyList()

  /** Sampling paused: let go of the car connection ([read] reconnects). */
  fun release() {}

  /** The runtime permissions may have changed: re-check them (and reconnect after a new grant). */
  fun onPermissionsChanged() {}
}

data class SourceRead(val sample: VehicleSample?, val feed: Feed)

/** No vehicle data on this device (and demo data is off). */
class UnavailableSource(private val feed: Feed) : VehicleSource {
  override fun read(atMs: Long, full: Boolean) = SourceRead(null, feed)
}

/**
 * The simulated drive. With a [headStartMs] the session starts that far into the drive (replayed
 * from [backlog]), so the dashboard looks like a real session at once.
 */
class DemoSource(private val headStartMs: Long, private val drive: DemoDrive = DemoDrive()) : VehicleSource {
  private var startMs: Long? = null

  override fun backlog(nowMs: Long): List<VehicleSample> {
    if (startMs != null) return emptyList()
    val start = nowMs - headStartMs
    startMs = start
    return (0 until headStartMs step BACKLOG_STEP_MS).map { rel -> drive.sampleAt(rel, start + rel) }
  }

  override fun read(atMs: Long, full: Boolean): SourceRead {
    val start = startMs ?: atMs.also { startMs = it }
    return SourceRead(drive.sampleAt(max(0L, atMs - start), atMs), FEED)
  }

  companion object {
    const val BACKLOG_STEP_MS = 1_000L
    val FEED = Feed(FeedKind.DEMO, "Simulated drive (demo data): not your truck")
  }
}

class VehicleSession(
  private val scope: CoroutineScope,
  private val clock: () -> Long,
  /** The source for demo on / off; [fresh] = a demo without head start (after a reset). */
  private val sourceFor: (demo: Boolean, fresh: Boolean) -> VehicleSource,
  private val graceMs: Long = GRACE_MS,
  private val tickMs: Long = TICK_MS,
  private val idleStopMs: Long = IDLE_STOP_MS,
  private val restartAfterMs: Long = RESTART_AFTER_MS,
  /** An internal error stopped the sampling (the app keeps it in its crash log). */
  private val onFailure: (Throwable) -> Unit = {},
) {
  private val _state = MutableStateFlow(SessionState(startedAtMs = clock()))
  val state: StateFlow<SessionState> = _state.asStateFlow()

  // Confined to the scope's thread.
  private val clients = LinkedHashSet<Any>()
  private val visible = HashSet<Any>()
  private var idleSinceMs = 0L
  private var demo: Boolean? = null
  private var source: VehicleSource? = null
  private var tracker = SessionTracker(clock)
  private var feed = Feed()
  private var loop: Job? = null
  private var stopTimer: Job? = null

  /** A UI that shows the session (bound for its lifetime): sampling runs while any is attached. */
  fun attach(token: Any) {
    scope.launch {
      clients += token
      stopTimer?.cancel()
      stopTimer = null
      startIfWanted()
    }
  }

  /** The UI is gone; sampling stops [graceMs] after the last one, unless one comes back. */
  fun detach(token: Any) {
    scope.launch {
      visible -= token
      if (!clients.remove(token) || clients.isNotEmpty() || stopTimer != null) return@launch
      stopTimer = scope.launch {
        delay(graceMs)
        stopTimer = null
        if (clients.isEmpty()) stopSampling()
      }
    }
  }

  /** Whether this UI's screen is on display. A visible UI restarts sampling that stopped while idle. */
  fun setVisible(token: Any, on: Boolean) {
    scope.launch {
      if (on) {
        visible += token
        startIfWanted()
      } else if (visible.remove(token) && visible.isEmpty()) {
        idleSinceMs = clock()
      }
    }
  }

  /** Demo data on / off (AppSettings). A switch starts a new session from the new source. */
  fun setDemo(on: Boolean) {
    scope.launch {
      if (demo == on) return@launch
      val wasFirst = demo == null
      demo = on
      replaceSource(sourceFor(on, false), newSession = !wasFirst)
    }
  }

  /** Starts a new session now ("Reset session"). */
  fun reset() {
    scope.launch {
      val d = demo ?: return@launch
      // A demo restarts from the beginning of its drive; the car source is kept.
      replaceSource(if (d) sourceFor(true, true) else source ?: sourceFor(false, false), newSession = true)
    }
  }

  fun onPermissionsChanged() {
    scope.launch { source?.onPermissionsChanged() }
  }

  private val wanted: Boolean get() = clients.isNotEmpty() || stopTimer != null

  private fun replaceSource(next: VehicleSource, newSession: Boolean) {
    val running = loop != null
    loop?.cancel()
    loop = null
    if (source !== next) source?.release()
    source = next
    if (newSession) tracker = SessionTracker(clock) else tracker.reset()
    feed = Feed()
    publish(sampling = false)
    if (running || wanted) startIfWanted()
  }

  private fun startIfWanted() {
    if (!wanted || loop?.isActive == true || demo == null) return
    val src = source ?: sourceFor(demo!!, false).also { source = it }
    idleSinceMs = clock()
    loop = scope.launch {
      try {
        for (s in src.backlog(clock())) tracker.add(s)
        var n = 0L
        var next = clock()
        while (isActive) {
          val last = _state.value.lastSampleAtMs
          if (demo == false && last != null && clock() - last > restartAfterMs) tracker = SessionTracker(clock)
          val r = try {
            src.read(clock(), full = n % FULL_EVERY == 0L)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            SourceRead(null, Feed(FeedKind.NO_CAR_SERVICE, "Reading the car failed: ${e.javaClass.simpleName}"))
          }
          n += 1
          feed = r.feed
          r.sample?.let { tracker.add(it) }
          publish(sampling = true)
          val now = clock()
          if (visible.isNotEmpty() || (_state.value.speedMps ?: 0.0) > SessionTracker.MOVING_MPS) idleSinceMs = now
          if (now - idleSinceMs >= idleStopMs) {
            stopSampling()
            return@launch
          }
          next += tickMs
          if (next < now - tickMs) next = now // fell behind (a slow read): no burst to catch up
          delay(max(0L, next - now))
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        fail(e)
      }
    }
  }

  /** Never a dashboard that looks live with frozen values: stop, say so, and keep the cause. */
  private fun fail(e: Throwable) {
    loop = null
    feed = Feed(FeedKind.FAILED, "${e.javaClass.simpleName}: details in the diagnostics report. Reset session to try again.")
    _state.value = _state.value.copy(feed = feed)
    onFailure(e)
    source?.release()
  }

  private fun stopSampling() {
    loop?.cancel()
    loop = null
    source?.release()
    publish(sampling = false)
  }

  private fun publish(sampling: Boolean) {
    _state.value = tracker.snapshot().copy(feed = feed.copy(sampling = sampling))
  }

  companion object {
    /**
     * Sampling goes on this long after the last UI detached (glance <-> dashboard, P <-> D). Short
     * enough to end before Android freezes a process with no screen (seen after 10 s), so that the
     * car connection really is released.
     */
    const val GRACE_MS = 8_000L

    /** With no visible screen and the truck not moving, sampling stops after this. */
    const val IDLE_STOP_MS = 2 * 60_000L

    /** No sample for this long: the next one starts a new session, not a pause in the old one. */
    const val RESTART_AFTER_MS = 30 * 60_000L

    /** Speed every tick (2 Hz); everything else every [FULL_EVERY] ticks (1 Hz). */
    const val TICK_MS = 500L
    const val FULL_EVERY = 2L

    /** Demo mode starts this far into the simulated drive. */
    const val DEMO_HEAD_START_MS = 22 * 60_000L
  }
}
