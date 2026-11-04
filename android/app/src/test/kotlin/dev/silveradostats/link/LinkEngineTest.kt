package dev.silveradostats.link

import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.FakeElm
import dev.silveradostats.obd.FatalTransportError
import dev.silveradostats.obd.TestSupport
import dev.silveradostats.obd.TransportError
import dev.silveradostats.obd.UnsafeRequestError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LinkEngine on real threads: the demo simulator, scripted unreachable adapters and a fake ELM327
 * on a loopback socket (FakeElm), all through the real OBD core.
 */
class LinkEngineTest {
  private val engines = mutableListOf<LinkEngine>()
  private val fakes = mutableListOf<FakeElm>()

  private fun engine(): LinkEngine = LinkEngine(TestSupport.profile).also { engines += it }

  @After
  fun tearDown() {
    engines.forEach {
      it.close()
      assertTrue("worker thread ends", it.awaitTermination(15_000))
    }
    fakes.forEach { it.close() }
  }

  private fun awaitState(e: LinkEngine, timeoutMs: Long = 10_000, what: String, cond: (LiveState) -> Boolean): LiveState {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      val s = e.state.value
      if (cond(s)) return s
      Thread.sleep(20)
    }
    fail("timed out waiting for $what; last state: ${e.state.value.status} ${e.state.value.message}")
    error("unreachable")
  }

  /** An adapter source that counts connect attempts and fails each one. */
  private class FailingSource(retryable: Boolean) {
    val opens = AtomicInteger()
    val source = LinkSource.Adapter("00:04:3E:00:00:01", "OBDLink MX+ (test)") {
      opens.incrementAndGet()
      throw ConnectFailure("MX+ not reachable (test)", retryable)
    }
  }

  private fun fakeSource(fake: FakeElm, opens: AtomicInteger = AtomicInteger()): LinkSource.Adapter =
    LinkSource.Adapter("00:04:3E:00:00:02", "Fake ELM") {
      opens.incrementAndGet()
      fake.connect()
    }

  @Test
  fun demoPollsOnlyWhileAttachedAndPublishesValues() {
    val e = engine()
    e.setSource(LinkSource.Demo)
    Thread.sleep(300)
    assertFalse("no client: nothing polls", e.state.value.polling)
    assertTrue(e.state.value.values.isEmpty())

    val client = Any()
    e.attach(client)
    val s = awaitState(e, what = "demo values") { it.status == LinkStatus.DEMO && it.values.containsKey("cells_181_192") }
    assertTrue(s.demo)
    assertTrue(s.polling)
    assertEquals(1, s.clients)
    assertEquals(LinkEngine.DEMO_ADAPTER_VOLTS, s.adapterVolts!!, 0.0)
    assertNotNull(s.derived["power_kw"])
    assertNotNull(s.derived["bms_energy_at_100_kwh"])
    assertNotNull(s.lastDataAtMs)
    // The 1 s current / voltage reads build up the power history.
    val withPower = awaitState(e, what = "power history") { it.power.size >= 2 }
    assertTrue(withPower.power.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })

    e.detach(client)
    val idle = awaitState(e, what = "polling to stop") { !it.polling }
    assertEquals(0, idle.clients)
    val n = idle.values.size
    Thread.sleep(1_500)
    assertFalse(e.state.value.polling)
    assertEquals("values kept, nothing new read", n, e.state.value.values.size)
  }

  @Test
  fun unreachableAdapterIsRetriedNoFasterThanEvery15s() {
    val e = engine()
    val f = FailingSource(retryable = true)
    e.setSource(f.source)
    e.attach(Any())
    val s = awaitState(e, what = "unreachable") { it.status == LinkStatus.ADAPTER_UNREACHABLE }
    assertTrue(s.canRetry)
    assertTrue(s.message.contains("not reachable"))
    val next = s.nextAutoRetryAtMs!!
    assertTrue("next attempt ${next - MonoClock.ms()} ms away", next - MonoClock.ms() >= RetryPolicy.MIN_AUTO_RETRY_MS - 2_000)
    Thread.sleep(1_500)
    assertEquals("no automatic reconnect within 15 s", 1, f.opens.get())
    // Retry is immediate (user action).
    e.retry()
    awaitState(e, what = "second attempt") { f.opens.get() == 2 && it.status == LinkStatus.ADAPTER_UNREACHABLE }
    Thread.sleep(500)
    assertEquals(2, f.opens.get())
  }

  @Test
  fun nonRetryableFailureWaitsForRetry() {
    val e = engine()
    val f = FailingSource(retryable = false)
    e.setSource(f.source)
    e.attach(Any())
    val s = awaitState(e, what = "unreachable") { it.status == LinkStatus.ADAPTER_UNREACHABLE }
    assertNull("no automatic retry scheduled", s.nextAutoRetryAtMs)
    assertTrue(s.canRetry)
    Thread.sleep(800)
    assertEquals(1, f.opens.get())
    e.retry()
    awaitState(e, what = "retry") { f.opens.get() == 2 }
  }

  @Test
  fun fatalErrorDuringPollingStopsAndNeedsRetry() {
    // The battery module's 2AF5 read gets "LV RESET": the adapter reset mid-session.
    val fake = FakeElm(resetOn = "222AF5").also { fakes += it }
    val opens = AtomicInteger()
    val e = engine()
    e.setSource(fakeSource(fake, opens))
    e.attach(Any())
    val s = awaitState(e, timeoutMs = 20_000, what = "stopped") { it.status == LinkStatus.STOPPED_ERROR }
    assertTrue(s.canRetry)
    assertTrue(s.message, s.message.contains("LV RESET"))
    assertFalse(s.polling)
    assertNull(s.nextAutoRetryAtMs)
    // The reset hit the first cycle: what it had read is discarded with it (as in the probe).
    assertTrue(s.values.isEmpty())
    val sentBefore = fake.received.size
    Thread.sleep(1_500)
    assertEquals("no automatic reconnect after a fatal error", 1, opens.get())
    assertEquals("nothing more was written", sentBefore, fake.received.size)
    // Every line the fake saw was an allowlisted adapter command or a single-DID read.
    assertTrue(fake.received.toString(), fake.received.all { FakeElm.allowed(it) })
  }

  @Test
  fun linkCheckPausesTheSessionAndReportsIdentity() {
    val fake = FakeElm(custom = { c -> if (c == "STI") "STN2255 v5.8.1" else null }).also { fakes += it }
    val e = engine()
    e.setSource(LinkSource.Demo)
    e.attach(Any())
    awaitState(e, what = "demo running") { it.status == LinkStatus.DEMO }
    val result = AtomicReference<LinkCheckResult>()
    val done = CountDownLatch(1)
    e.requestCheck("Fake ELM test", fakeSource(fake)) {
      result.set(it)
      done.countDown()
    }
    assertTrue(done.await(20, TimeUnit.SECONDS))
    val r = result.get()
    assertTrue(r.error, r.ok)
    assertEquals("ELM327 v1.5", r.banner)
    assertEquals("ELM327 v1.5 / STN2255 v5.8.1", r.ident)
    assertEquals(12.6, r.volts!!, 1e-9)
    assertTrue("setup only: no vehicle request", r.sent.all { it.startsWith("AT") || it.startsWith("ST") })
    assertTrue(fake.received.none { it.startsWith("22") || it.startsWith("0") })
    // The demo session is untouched (different source) and keeps polling.
    awaitState(e, what = "demo still running") { it.status == LinkStatus.DEMO && it.polling }
  }

  @Test
  fun switchingSourceClearsData() {
    val e = engine()
    e.setSource(LinkSource.Demo)
    e.attach(Any())
    awaitState(e, what = "demo values") { it.values.isNotEmpty() }
    e.setSource(null)
    val s = awaitState(e, what = "no adapter") { it.status == LinkStatus.NO_ADAPTER }
    assertTrue(s.values.isEmpty())
    assertTrue(s.power.isEmpty())
    assertFalse(s.demo)
    assertEquals(LinkMessages.NO_ADAPTER, s.message)
  }

  @Test
  fun pairingShowsWhileNoSessionRuns() {
    val e = engine()
    e.setPairing(true)
    assertEquals(LinkStatus.PAIRING, e.state.value.status)
    e.setPairing(false)
    assertEquals(LinkStatus.NO_ADAPTER, e.state.value.status)
  }

  @Test
  fun retryPolicyNeverFasterThan15s() {
    val p = RetryPolicy()
    assertEquals(15_000L, p.delayMs(1))
    assertEquals(30_000L, p.delayMs(2))
    assertEquals(60_000L, p.delayMs(3))
    assertEquals(60_000L, p.delayMs(50))
    assertEquals(15_000L, p.delayMs(0))
    for (n in 0..100) assertTrue(p.delayMs(n) >= RetryPolicy.MIN_AUTO_RETRY_MS)
    try {
      RetryPolicy(minMs = 5_000)
      fail("a 5 s reconnect loop must be refused")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test
  fun stopMessages() {
    assertTrue(LinkMessages.stopped(FatalTransportError("adapter link failed: Broken pipe")).startsWith("Connection to the MX+ was lost"))
    assertTrue(LinkMessages.stopped(FatalTransportError("adapter closed the connection")).contains("Retry"))
    assertTrue(LinkMessages.stopped(FatalTransportError("adapter reported 'LV RESET' on '22 2AF5'; stopping")).contains("LV RESET"))
    assertTrue(LinkMessages.stopped(TransportError("adapter timed out on '222AF5'")).contains("timed out"))
    assertTrue(LinkMessages.stopped(UnsafeRequestError("x")).contains("read-only guard"))
  }

  @Test
  fun linkCheckReportsConnectFailure() {
    val r = LinkCheck.run("x", { throw ConnectFailure("MX+ not reachable", true) })
    assertFalse(r.ok)
    assertEquals("connect", r.stage)
    assertEquals("MX+ not reachable", r.error)
    assertTrue(r.sent.isEmpty())
  }

  @Test
  fun linkCheckClosesTheLinkOnSuccess() {
    val fake = FakeElm().also { fakes += it }
    var link: ByteLink? = null
    val r = LinkCheck.run("fake", { fake.connect().also { link = it } })
    assertTrue(r.error, r.ok)
    try {
      link!!.readAvailable(0)
      fail("link must be closed after the check")
    } catch (_: FatalTransportError) {
    }
  }
}
