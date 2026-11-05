package dev.silveradostats.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VehicleSession: sampling only while a UI is attached (+ grace), resume without reset, demo, reset. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSessionTest {

  private class FakeSource : VehicleSource {
    var reads = 0
    var fullReads = 0
    var released = 0
    var permissionChecks = 0
    var level = 100_000.0
    var speed = 20.0

    override fun read(atMs: Long, full: Boolean): SourceRead {
      reads += 1
      if (full) fullReads += 1
      level -= 3.0
      return SourceRead(
        VehicleSample(atMs, speedMps = speed, levelWh = if (full) level else null),
        Feed(FeedKind.VEHICLE, "car"),
      )
    }

    override fun release() {
      released += 1
    }

    override fun onPermissionsChanged() {
      permissionChecks += 1
    }
  }

  private fun TestScope.session(src: FakeSource, demo: DemoSource? = null): VehicleSession =
    VehicleSession(backgroundScope, clock = { testScheduler.currentTime }, sourceFor = { d, fresh ->
      if (d) demo ?: DemoSource(if (fresh) 0 else VehicleSession.DEMO_HEAD_START_MS) else src
    })

  @Test
  fun samplesOnlyWhileAUiIsAttachedPlusTheGracePeriod() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals("nothing attached: no reads", 0, src.reads)
    assertFalse(s.state.value.feed.sampling)

    val ui = Any()
    s.attach(ui)
    runCurrent()
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(21, src.reads) // 2 Hz
    assertEquals(11, src.fullReads) // 1 Hz
    assertTrue(s.state.value.feed.sampling)
    assertEquals(FeedKind.VEHICLE, s.state.value.feed.kind)
    assertEquals(200.0, s.state.value.distanceM, 1e-6)

    s.detach(ui)
    advanceTimeBy(VehicleSession.GRACE_MS - 1_000)
    runCurrent()
    assertTrue("still sampling within the grace period", s.state.value.feed.sampling)
    assertEquals(0, src.released)
    advanceTimeBy(1_500)
    runCurrent()
    assertFalse(s.state.value.feed.sampling)
    assertEquals(1, src.released)
    val reads = src.reads
    val distance = s.state.value.distanceM
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals("stopped: no reads", reads, src.reads)

    // A UI comes back: the same session goes on, with the pause recorded as a gap.
    s.attach(Any())
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    val st = s.state.value
    assertTrue(st.feed.sampling)
    assertEquals(1, st.gaps)
    assertEquals(distance + 20.0 * 5, st.distanceM, 1e-6)
  }

  @Test
  fun aUiReturningWithinTheGraceKeepsSamplingWithoutAGap() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    val dash = Any()
    val glance = Any()
    s.attach(dash)
    runCurrent()
    advanceTimeBy(3_000)
    s.detach(dash) // P -> D: the dashboard goes, the glance comes 5 s later
    advanceTimeBy(5_000)
    s.attach(glance)
    runCurrent()
    advanceTimeBy(30_000)
    runCurrent()
    assertTrue(s.state.value.feed.sampling)
    assertEquals(0, src.released)
    assertEquals(0, s.state.value.gaps)
    // Two clients: one leaving does not stop anything.
    s.attach(dash)
    s.detach(glance)
    advanceTimeBy(60_000)
    runCurrent()
    assertTrue(s.state.value.feed.sampling)
  }

  @Test
  fun demoModeStartsASimulatedSessionWithAHeadStart() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(true)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()
    val st = s.state.value
    assertEquals(FeedKind.DEMO, st.feed.kind)
    assertEquals(0, src.reads)
    assertTrue("head start ${st.activeMs}", st.activeMs >= VehicleSession.DEMO_HEAD_START_MS)
    assertEquals(EffStatus.OK, st.efficiency.status)
    assertTrue(st.distanceM > 10_000)
    assertTrue(st.series.isNotEmpty())

    // Reset: a demo starts from the beginning of its drive (warming up again).
    s.reset()
    runCurrent()
    advanceTimeBy(3_000)
    runCurrent()
    val r = s.state.value
    assertTrue(r.activeMs < 5_000)
    assertEquals(EffStatus.WARMING_UP, r.efficiency.status)
    assertTrue(r.feed.sampling)

    // Demo off: a new session from the car.
    s.setDemo(false)
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(FeedKind.VEHICLE, s.state.value.feed.kind)
    assertTrue(src.reads > 0)
    assertTrue(s.state.value.activeMs <= 2_000)
  }

  @Test
  fun resetKeepsTheCarSourceAndSampling() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(20_000)
    runCurrent()
    assertTrue(s.state.value.distanceM > 300)
    s.reset()
    runCurrent()
    assertEquals(0, src.released)
    assertEquals(testScheduler.currentTime, s.state.value.startedAtMs)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(20.0, s.state.value.distanceM, 1e-6)
    assertTrue(s.state.value.feed.sampling)
  }

  @Test
  fun permissionChangesReachTheSourceAndUnavailableDevicesSaySo() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    s.onPermissionsChanged()
    runCurrent()
    assertEquals(1, src.permissionChecks)

    val none = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ ->
      UnavailableSource(Feed(FeedKind.NOT_AUTOMOTIVE, "not a car"))
    })
    none.setDemo(false)
    none.attach(Any())
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(FeedKind.NOT_AUTOMOTIVE, none.state.value.feed.kind)
    assertFalse(none.state.value.hasData)
  }

  @Test
  fun aFailingReadIsReportedAndSamplingGoesOn() {
    var fail = true
    val src = object : VehicleSource {
      override fun read(atMs: Long, full: Boolean): SourceRead {
        if (fail) throw IllegalStateException("car service died")
        return SourceRead(VehicleSample(atMs, speedMps = 10.0, levelWh = 50_000.0), Feed(FeedKind.VEHICLE))
      }
    }
    runTest {
      val s = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ -> src })
      s.setDemo(false)
      s.attach(Any())
      runCurrent()
      advanceTimeBy(2_000)
      runCurrent()
      assertEquals(FeedKind.NO_CAR_SERVICE, s.state.value.feed.kind)
      assertTrue(s.state.value.feed.message.contains("IllegalStateException"))
      fail = false
      advanceTimeBy(3_000)
      runCurrent()
      assertEquals(FeedKind.VEHICLE, s.state.value.feed.kind)
      assertTrue(s.state.value.distanceM > 0)
    }
  }

  @Test
  fun theGraceEndsBeforeAndroidFreezesTheProcess() {
    assertTrue("freezes were seen 10 s after the last screen", VehicleSession.GRACE_MS < 10_000)
  }

  @Test
  fun noVisibleScreenAndNotMovingStopsSampling() = runTest {
    val src = FakeSource()
    src.speed = 0.0
    val s = session(src)
    s.setDemo(false)
    val ui = Any()
    s.attach(ui)
    s.setVisible(ui, true)
    runCurrent()
    advanceTimeBy(VehicleSession.IDLE_STOP_MS * 2)
    runCurrent()
    assertTrue("a visible screen keeps it sampling, parked", s.state.value.feed.sampling)

    s.setVisible(ui, false) // Home: the ViewModel stays bound
    runCurrent()
    advanceTimeBy(VehicleSession.IDLE_STOP_MS - 1_000)
    runCurrent()
    assertTrue(s.state.value.feed.sampling)
    advanceTimeBy(1_500)
    runCurrent()
    assertFalse("nothing on screen and not moving: no reads", s.state.value.feed.sampling)
    assertEquals(1, src.released)
    val reads = src.reads
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(reads, src.reads)

    // Back on screen: the same session goes on, with the pause recorded.
    val samples = s.state.value.samples
    s.setVisible(ui, true)
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    assertTrue(s.state.value.feed.sampling)
    assertTrue(s.state.value.samples > samples)
    assertEquals(1, s.state.value.gaps)
  }

  @Test
  fun movingKeepsSamplingBehindTheBlockedDashboard() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    val ui = Any()
    s.attach(ui)
    s.setVisible(ui, true)
    runCurrent()
    s.setVisible(ui, false) // Driving with another app in front, such as Maps
    runCurrent()
    advanceTimeBy(VehicleSession.IDLE_STOP_MS * 3)
    runCurrent()
    assertTrue(s.state.value.feed.sampling)
    // The drive ends: two minutes later it stops by itself.
    src.speed = 0.0
    advanceTimeBy(VehicleSession.IDLE_STOP_MS + 1_000)
    runCurrent()
    assertFalse(s.state.value.feed.sampling)
  }

  @Test
  fun aLongPauseStartsANewSessionAndAShortOneDoesNot() = runTest {
    val src = FakeSource()
    val s = session(src)
    s.setDemo(false)
    val ui = Any()
    s.attach(ui)
    runCurrent()
    advanceTimeBy(60_000)
    runCurrent()
    val driven = s.state.value.distanceM
    assertTrue(driven > 1_000)
    s.detach(ui)
    runCurrent()
    advanceTimeBy(VehicleSession.RESTART_AFTER_MS - 60_000)
    val back = Any()
    s.attach(back)
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    assertTrue("a short pause goes on", s.state.value.distanceM > driven)
    assertEquals(1, s.state.value.gaps)

    s.detach(back)
    runCurrent()
    advanceTimeBy(VehicleSession.RESTART_AFTER_MS + 60_000)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(5_000)
    runCurrent()
    val st = s.state.value
    assertEquals("reopened hours later: since the app was opened", 100.0, st.distanceM, 25.0)
    assertEquals(0, st.gaps)
    assertTrue(st.activeMs <= 5_000)
  }

  @Test
  fun aLongFreezeOrSleepWithTheScreenStillAttachedStartsANewSession() = runTest {
    // The process never saw a detach: time just jumps when it runs again.
    var slept = 0L
    val src = FakeSource()
    val s = VehicleSession(backgroundScope, { testScheduler.currentTime + slept }, { _, _ -> src })
    s.setDemo(false)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(60_000)
    runCurrent()
    assertTrue(s.state.value.distanceM > 1_000)
    // A short one is a pause.
    slept += 60_000
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(1, s.state.value.gaps)
    assertTrue(s.state.value.distanceM > 1_000)
    // Overnight is a new session.
    slept += 8 * 3_600_000L
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(0, s.state.value.gaps)
    assertTrue(s.state.value.distanceM < 100)
  }

  @Test
  fun aDemoSessionIsNotRestartedByAPause() = runTest {
    val s = session(FakeSource())
    s.setDemo(true)
    val ui = Any()
    s.attach(ui)
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    val before = s.state.value.activeMs
    s.detach(ui)
    runCurrent()
    advanceTimeBy(VehicleSession.RESTART_AFTER_MS * 2)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertTrue(s.state.value.activeMs >= before)
    assertEquals(1, s.state.value.gaps)
  }

  @Test
  fun anInternalErrorStopsSamplingAndSaysSo() = runTest {
    val failures = mutableListOf<Throwable>()
    val bad = object : VehicleSource {
      var released = 0

      override fun backlog(nowMs: Long): List<VehicleSample> = throw IllegalStateException("broken")

      override fun read(atMs: Long, full: Boolean) = SourceRead(null, Feed(FeedKind.VEHICLE))

      override fun release() {
        released += 1
      }
    }
    val s = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ -> bad }, onFailure = { failures += it })
    s.setDemo(false)
    s.attach(Any())
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()
    val st = s.state.value
    assertEquals(FeedKind.FAILED, st.feed.kind)
    assertFalse("frozen values must not look live", st.feed.sampling)
    assertTrue(st.feed.message.contains("IllegalStateException"))
    assertEquals(1, failures.size)
    assertEquals(1, bad.released)
  }

  @Test
  fun demoDriveIsARealisticSession() {
    val drive = DemoDrive()
    val t = SessionTracker { 0L }
    var regen = 0
    var maxSpeed = 0.0
    var rel = 0L
    while (rel <= 24 * 60_000L) {
      val sample = drive.sampleAt(rel)
      t.add(sample)
      if (drive.powerKw < -1) regen += 1
      maxSpeed = maxOf(maxSpeed, sample.speedMps!!)
      rel += 500
    }
    val st = t.snapshot()
    val miPerKwh = EffUnits.miPerKwh(st.efficiency.mPerWh!!)
    assertTrue("efficiency $miPerKwh", miPerKwh in 2.0..2.7)
    assertTrue("regen samples $regen", regen > 100)
    assertTrue(maxSpeed in 25.0..31.0)
    assertTrue("distance ${st.distanceM}", st.distanceM / EffUnits.METERS_PER_MILE in 12.0..22.0)
    assertEquals(EnergySource.CHARGE_RATE, st.energySource)
    assertEquals(0.0, DemoDrive().targetAt(1_000), 0.0) // parked first
  }
}
