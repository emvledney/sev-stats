package dev.silveradostats.link

import dev.silveradostats.bt.BtErrors
import dev.silveradostats.obd.AdapterTimeoutError
import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.FatalTransportError
import dev.silveradostats.obd.LinkLostError
import dev.silveradostats.obd.SimTransport
import dev.silveradostats.obd.TestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * LinkEngine review findings, on real threads and a loopback fake adapter: visibility churn (grace
 * period), leaving during connect / setup, automatic recovery after a lost link or a timeout (and
 * its budget), and the retry after a permission grant.
 */
class LinkEngineLifecycleTest {
  private val engines = mutableListOf<LinkEngine>()
  private val fakes = mutableListOf<MultiFakeElm>()

  /** Engine clock = monotonic + offset, so tests can jump past the >= 15 s reconnect spacing. */
  private val offset = AtomicLong()
  private fun clock(): Long = MonoClock.ms() + offset.get()

  private fun engine(graceMs: Long = LinkEngine.GRACE_MS, demo: (() -> SimTransport)? = null): LinkEngine {
    val e = if (demo != null) {
      LinkEngine(TestSupport.profile, clock = ::clock, demoTransport = demo, graceMs = graceMs)
    } else {
      LinkEngine(TestSupport.profile, clock = ::clock, graceMs = graceMs)
    }
    return e.also { engines += it }
  }

  private fun fake(f: MultiFakeElm): MultiFakeElm = f.also { fakes += it }

  @After
  fun tearDown() {
    engines.forEach {
      it.close()
      assertTrue("worker thread ends", it.awaitTermination(15_000))
    }
    fakes.forEach { it.close() }
  }

  /** Records when the engine closed the link. */
  private class Recording(val inner: ByteLink) : ByteLink {
    @Volatile var closedAtNanos = -1L
    override fun write(bytes: ByteArray) = inner.write(bytes)
    override fun readAvailable(timeoutMs: Long): ByteArray = inner.readAvailable(timeoutMs)
    override fun close() {
      if (closedAtNanos < 0) closedAtNanos = System.nanoTime()
      inner.close()
    }
  }

  private fun source(open: () -> ByteLink) = LinkSource.Adapter("00:04:3E:00:00:09", "Fake MX+", open)

  private fun await(e: LinkEngine, what: String, timeoutMs: Long = 15_000, cond: (LiveState) -> Boolean): LiveState {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      val s = e.state.value
      if (cond(s)) return s
      Thread.sleep(10)
    }
    fail("timed out waiting for $what; last state: ${e.state.value.status} ${e.state.value.message}")
    error("unreachable")
  }

  // --- Link test called off (driving mode, or the app left the screen) ---------------------------

  /** Answers nothing: a link test on it stays in its setup until the link is closed. */
  private class SilentLink : ByteLink {
    private val closed = java.util.concurrent.CountDownLatch(1)
    override fun write(bytes: ByteArray) {}
    override fun readAvailable(timeoutMs: Long): ByteArray {
      if (closed.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) throw LinkLostError("closed")
      return ByteArray(0)
    }
    override fun close() = closed.countDown()
  }

  @Test
  fun cancellingEndsARunningLinkTestAtOnce() {
    val e = engine()
    val opened = java.util.concurrent.CountDownLatch(1)
    val done = java.util.concurrent.CountDownLatch(1)
    var result: LinkCheckResult? = null
    e.requestCheck("slow adapter", source {
      opened.countDown()
      SilentLink()
    }) {
      result = it
      done.countDown()
    }
    assertTrue(opened.await(10, java.util.concurrent.TimeUnit.SECONDS))
    Thread.sleep(200) // into the adapter setup
    val t0 = System.nanoTime()
    e.cancelChecks()
    assertTrue("the running test answers", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue("well before any adapter timeout", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    assertEquals(false, result?.ok)
    assertEquals(LinkEngine.CHECK_CALLED_OFF, result?.error)
  }

  @Test
  fun cancellingAnswersQueuedLinkTestsWithoutOpeningAnything() {
    val e = engine()
    val first = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val opens = AtomicInteger()
    val results = java.util.concurrent.ConcurrentLinkedQueue<LinkCheckResult>()
    val blockingOpen = source {
      opens.incrementAndGet()
      first.countDown()
      release.await()
      SilentLink()
    }
    e.requestCheck("first", blockingOpen) { results += it }
    assertTrue(first.await(10, java.util.concurrent.TimeUnit.SECONDS))
    e.requestCheck("second", source { error("a called-off test must not open a link") }) { results += it }
    e.cancelChecks()
    assertEquals("the queued one answered at once", listOf("second"), results.map { it.target })
    // The one still opening its link closes it, untouched, as soon as the connect returns.
    release.countDown()
    val end = System.currentTimeMillis() + 5_000
    while (results.size < 2 && System.currentTimeMillis() < end) Thread.sleep(10)
    assertEquals(listOf("second", "first"), results.map { it.target })
    assertTrue(results.all { !it.ok && it.error == LinkEngine.CHECK_CALLED_OFF })
    assertEquals(1, opens.get())
  }

  @Test
  fun aNewLinkTestRunsAfterACancel() {
    val fake = fake(MultiFakeElm())
    val e = engine()
    e.cancelChecks()
    val done = java.util.concurrent.CountDownLatch(1)
    var result: LinkCheckResult? = null
    e.requestCheck("back in Park", source { fake.connect() }) {
      result = it
      done.countDown()
    }
    assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
    assertEquals(result?.error, true, result?.ok)
  }

  // --- Visibility churn -------------------------------------------------------------------------

  @Test
  fun visibilityChurnKeepsOneSession() {
    val fake = fake(MultiFakeElm())
    val e = engine()
    e.setSource(source { fake.connect() })
    val token = Any()
    e.attach(token)
    await(e, "first reads") { fake.busReads > 0 && it.polling }
    // Glance <-> dashboard, P <-> R: the screen flips every 0.3-0.4 s.
    repeat(8) {
      e.detach(token)
      Thread.sleep(300)
      e.attach(token)
      Thread.sleep(400)
    }
    await(e, "polling again") { it.polling }
    assertEquals("one Bluetooth connect", 1, fake.accepts.get())
    assertEquals("one adapter reset", 1, fake.received.count { it == "ATZ" })
    assertTrue("socket never closed", fake.closedAt.isEmpty())
  }

  @Test
  fun parkedSessionIsSilentThenReleasesTheAdapter() {
    val fake = fake(MultiFakeElm())
    val e = engine(graceMs = 1_500)
    e.setSource(source { fake.connect() })
    val token = Any()
    e.attach(token)
    await(e, "reads") { fake.busReads >= 3 }
    val detachAt = System.nanoTime()
    e.detach(token)
    await(e, "parked") { !it.polling }
    Thread.sleep(100) // a request in flight at the detach is answered
    val n = fake.received.size
    Thread.sleep(1_000)
    assertEquals("nothing at all is sent while parked", n, fake.received.size)
    assertTrue("link still open within the grace period", fake.closedAt.isEmpty())
    val end = System.currentTimeMillis() + 5_000
    while (fake.closedAt.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
    val ms = (fake.closedAt.first() - detachAt) / 1_000_000
    assertTrue("closed $ms ms after the last detach", ms in 1_400..3_500)
    assertEquals(n, fake.received.size)
  }

  /** A quick re-visit while the truck sleeps stays bus-silent: the parked poller keeps its asleep state. */
  @Test
  fun quickRevisitWhileAsleepSendsNothing() {
    val sim = SimTransport().apply {
      silent = true
      volts = 12.4
    }
    val e = engine(demo = { sim })
    e.setSource(LinkSource.Demo)
    val token = Any()
    e.attach(token)
    await(e, "asleep", 20_000) { it.status == LinkStatus.TRUCK_ASLEEP }
    val sent = sim.sent
    assertTrue("at most ~4 unanswered requests while falling asleep ($sent)", sent in 1..4)
    e.detach(token)
    await(e, "parked") { !it.polling }
    e.attach(token)
    await(e, "resumed") { it.polling }
    Thread.sleep(1_500)
    assertEquals("no request on the re-visit", sent, sim.sent)
    assertEquals(LinkStatus.TRUCK_ASLEEP, e.state.value.status)
  }

  // --- Leaving during connect / setup -----------------------------------------------------------

  @Test
  fun leavingDuringTheBluetoothConnectWritesNothing() {
    val fake = fake(MultiFakeElm())
    val opens = AtomicInteger()
    val e = engine()
    e.setSource(source {
      opens.incrementAndGet()
      Thread.sleep(1_000) // RFCOMM connect in progress
      fake.connect()
    })
    val token = Any()
    e.attach(token)
    Thread.sleep(200)
    e.detach(token)
    val end = System.currentTimeMillis() + 5_000
    while (fake.closedAt.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
    assertTrue("socket closed right after the connect returned", fake.closedAt.isNotEmpty())
    Thread.sleep(200)
    assertEquals("nothing written to the adapter", emptyList<String>(), fake.received.toList())
    assertNotEquals("a called-off connect is not an error", LinkStatus.STOPPED_ERROR, e.state.value.status)
    // A new visit connects normally (nothing was blocked).
    e.attach(token)
    await(e, "reads after the new visit") { fake.busReads > 0 && it.polling }
    assertEquals(2, opens.get())
  }

  @Test
  fun leavingDuringAdapterSetupClosesTheLinkAtOnce() {
    val fake = fake(MultiFakeElm(atzDelayMs = 3_000))
    val links = mutableListOf<Recording>()
    val e = engine()
    e.setSource(source { Recording(fake.connect()).also { synchronized(links) { links += it } } })
    val token = Any()
    e.attach(token)
    val end = System.currentTimeMillis() + 5_000
    while ("ATZ" !in fake.received && System.currentTimeMillis() < end) Thread.sleep(5)
    Thread.sleep(100)
    val detachAt = System.nanoTime()
    e.detach(token)
    val link = synchronized(links) { links.single() }
    assertTrue("closed by detach itself", link.closedAtNanos > 0)
    assertTrue("closed ${(link.closedAtNanos - detachAt) / 1_000_000} ms after detach", link.closedAtNanos - detachAt < 300_000_000L)
    await(e, "setup called off", 2_000) { it.message == LinkMessages.IDLE }
    Thread.sleep(3_500) // the fake's late ATZ reply arrives at a closed socket
    assertEquals("only ATZ was written", listOf("ATZ"), fake.received.toList())
    assertNotEquals(LinkStatus.STOPPED_ERROR, e.state.value.status)
  }

  // --- Automatic recovery -----------------------------------------------------------------------

  @Test
  fun lostLinkReconnectsAutomaticallyAfter15s() {
    val fake = fake(MultiFakeElm(dropAfterReads = 3))
    val e = engine()
    e.setSource(source { fake.connect() })
    val token = Any()
    e.attach(token)
    val s = await(e, "link lost") { it.status == LinkStatus.STOPPED_ERROR }
    assertTrue(s.message, s.message.startsWith("Connection to the MX+ was lost") && s.message.contains("Reconnecting automatically"))
    assertTrue(s.canRetry)
    val next = s.nextAutoRetryAtMs!!
    assertTrue("next attempt ${next - clock()} ms away", next - clock() >= RetryPolicy.MIN_AUTO_RETRY_MS - 1_000)
    assertEquals("Link lost · Retry", dev.silveradostats.glance.GlanceModel.status(s))
    Thread.sleep(1_000)
    assertEquals("no reconnect before 15 s", 1, fake.accepts.get())
    offset.addAndGet(16_000)
    e.attach(token) // same client: just wakes the worker
    val back = await(e, "reconnected") { fake.accepts.get() == 2 && it.polling && it.status == LinkStatus.CONNECTED }
    assertNull(back.nextAutoRetryAtMs)
    assertEquals("a full adapter setup again", 2, fake.received.count { it == "ATZ" })
  }

  @Test
  fun timeoutReconnectsAutomatically() {
    val fake = fake(MultiFakeElm(stallRead = 2))
    val e = engine()
    e.setSource(source { fake.connect() })
    val token = Any()
    e.attach(token)
    val s = await(e, "timeout") { it.status == LinkStatus.STOPPED_ERROR }
    assertTrue(s.message, s.message.startsWith("The MX+ did not answer in time"))
    assertTrue(s.nextAutoRetryAtMs != null)
    offset.addAndGet(16_000)
    e.attach(token)
    await(e, "reconnected") { fake.accepts.get() == 2 && it.polling && it.status == LinkStatus.CONNECTED }
    // The stalled session's link was closed before the new connection was opened.
    assertTrue(fake.closedAt.isNotEmpty())
  }

  @Test
  fun recoveryBudgetThenRetryIsNeeded() {
    val fake = fake(MultiFakeElm(dropAfterReads = 1, dropConnections = 100))
    val e = engine()
    e.setSource(source { fake.connect() })
    val token = Any()
    e.attach(token)
    for (i in 1..LinkEngine.MAX_AUTO_RECOVERIES) {
      await(e, "stop $i") { fake.accepts.get() == i && it.status == LinkStatus.STOPPED_ERROR && it.nextAutoRetryAtMs != null }
      offset.addAndGet(70_000)
      e.attach(token)
    }
    val n = LinkEngine.MAX_AUTO_RECOVERIES + 1
    val last = await(e, "blocked") { fake.accepts.get() == n && it.status == LinkStatus.STOPPED_ERROR && it.nextAutoRetryAtMs == null }
    assertTrue(last.canRetry)
    assertTrue(last.message, last.message.contains("tap Retry"))
    offset.addAndGet(70_000)
    e.attach(token)
    Thread.sleep(500)
    assertEquals("no automatic reconnect after the budget", n, fake.accepts.get())
    e.retry()
    await(e, "manual retry") { fake.accepts.get() == n + 1 }
  }

  @Test
  fun recoveryMessages() {
    // An LV RESET reply (not a lost link or a timeout) still waits for Retry: LinkEngineTest's
    // fatalErrorDuringPollingStopsAndNeedsRetry. Here the texts.
    assertTrue(LinkMessages.recovering(LinkLostError("adapter closed the connection")).contains("Reconnecting automatically"))
    assertTrue(LinkMessages.recovering(AdapterTimeoutError("adapter timed out on '2227C6'")).startsWith("The MX+ did not answer in time"))
    assertTrue(LinkMessages.stopped(LinkLostError("adapter closed the connection")).startsWith("Connection to the MX+ was lost"))
    assertTrue(LinkMessages.stopped(FatalTransportError("adapter reported 'LV RESET' on 'ATRV'; stopping")).contains("tap Retry"))
  }

  // --- Permission grant -------------------------------------------------------------------------

  @Test
  fun permissionGrantRetriesOnlyThatStop() {
    val opens = AtomicInteger()
    val e = engine()
    e.setSource(source {
      opens.incrementAndGet()
      throw ConnectFailure(BtErrors.NO_CONNECT_PERMISSION, retryable = false)
    })
    e.attach(Any())
    val s = await(e, "no permission") { it.status == LinkStatus.ADAPTER_UNREACHABLE }
    assertNull(s.nextAutoRetryAtMs)
    e.retryIfStoppedBy(BtErrors.BLUETOOTH_OFF)
    Thread.sleep(300)
    assertEquals("other reasons are left alone", 1, opens.get())
    e.retryIfStoppedBy(BtErrors.NO_CONNECT_PERMISSION)
    await(e, "retried") { opens.get() == 2 && it.status == LinkStatus.ADAPTER_UNREACHABLE }
  }

  @Test
  fun disconnectClosesTheLinkAndNothingReconnectsUntilConnect() {
    val fake = fake(MultiFakeElm())
    val opens = AtomicInteger()
    val links = ArrayList<Recording>()
    val e = engine()
    e.setSource(source {
      opens.incrementAndGet()
      Recording(fake.connect()).also { links += it }
    })
    val token = Any()
    e.attach(token)
    await(e, "polling") { it.polling && it.status == LinkStatus.CONNECTED }

    e.setDisconnected(true)
    val off = await(e, "disconnected") { it.status == LinkStatus.DISCONNECTED }
    assertEquals(LinkMessages.DISCONNECTED, off.message)
    assertEquals(false, off.canRetry)
    assertTrue("the socket is closed", links.single().closedAtNanos > 0)

    // Nothing brings it back: not time, not Retry, not another screen coming and going.
    offset.addAndGet(120_000)
    e.retry()
    e.detach(token)
    e.attach(token)
    Thread.sleep(400)
    assertEquals(1, opens.get())
    assertEquals(LinkStatus.DISCONNECTED, e.state.value.status)

    e.setDisconnected(false)
    await(e, "connected again") { it.polling && it.status == LinkStatus.CONNECTED }
    assertEquals(2, opens.get())
  }

  @Test
  fun disconnectedFromTheStartNeverOpensTheLink() {
    val opens = AtomicInteger()
    val e = engine()
    e.setDisconnected(true)
    e.setSource(source {
      opens.incrementAndGet()
      throw ConnectFailure("not reached", retryable = true)
    })
    e.attach(Any())
    await(e, "disconnected") { it.status == LinkStatus.DISCONNECTED }
    Thread.sleep(200)
    assertEquals(0, opens.get())
  }

  @Test
  fun disconnectDoesNotStopTheSimulator() {
    val e = engine()
    e.setDisconnected(true)
    e.setSource(LinkSource.Demo)
    e.attach(Any())
    await(e, "demo") { it.status == LinkStatus.DEMO && it.polling }
  }
}
