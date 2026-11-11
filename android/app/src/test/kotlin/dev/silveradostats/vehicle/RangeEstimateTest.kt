package dev.silveradostats.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own range: the energy left x the last 15 minutes' distance / energy, no warm-up, moving every 10 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeEstimateTest {
  private val mile = EffUnits.METERS_PER_MILE
  private val mps = 25.0
  private val windowS = SessionTracker.RECENT_MS / 1000.0

  private fun kw(miPerKwh: Double) = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  /** At the production cadence: speed at 2 Hz, the rest at 1 Hz. */
  private class Truck {
    var t = 1_000_000L
    var level = 150_000.0
    val tracker = SessionTracker { t }
    private var tick = 0L

    fun run(seconds: Double, mps: Double, kw: Double, each: (SessionState) -> Unit = {}) {
      repeat((seconds * 2).toInt()) {
        t += 500
        level -= kw * 1000.0 * 0.5 / 3600.0
        val full = tick % 2 == 0L
        tick += 1
        tracker.add(VehicleSample(t, speedMps = mps, levelWh = if (full) level else null))
        each(tracker.snapshot())
      }
    }

    fun s() = tracker.snapshot()
  }

  /** The owner's photo: 72.7 kWh left, 5.8 mi on 2.74 kWh. The truck said 162 mi. */
  @Test
  fun itIsTheEnergyLeftAtTheEfficiencyItIsGiven() {
    val r = SessionTracker.rangeFrom(72_700.0, 5.8 * mile / 2_740.0)!!
    assertEquals(154.0, r.m / mile, 0.5)
    assertEquals(2.12, EffUnits.miPerKwh(r.mPerWh), 0.01)
  }

  @Test
  fun withNothingToGoOnThereIsNoFigure() {
    assertNull("no energy left known", SessionTracker.rangeFrom(null, 2.0))
    assertNull("no efficiency", SessionTracker.rangeFrom(72_700.0, null))
    assertNull("no efficiency to speak of", SessionTracker.rangeFrom(72_700.0, 0.0))
    // Only a descent gets above 6 mi/kWh, and thousands of miles would read as a fault.
    val e = SessionTracker.MAX_RANGE_M_PER_WH
    assertNotNull(SessionTracker.rangeFrom(72_700.0, e))
    assertNull(SessionTracker.rangeFrom(72_700.0, e * 1.001))
  }

  /** 30 minutes at 3.0 mi/kWh, then 15 at 2.0: the drive says 2.57, the range goes by the 2.0. */
  @Test
  fun itGoesByTheLastFifteenMinutesNotTheWholeDrive() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    d.run(windowS, mps, kw(2.0))
    val s = d.s()
    val r = s.rangeEstimate!!
    assertEquals(2.0, EffUnits.miPerKwh(r.mPerWh), 0.03)
    assertEquals(2.57, EffUnits.miPerKwh(s.plainEfficiency.driveMPerWh!!), 0.03)
    assertEquals(s.levelWh!! * r.mPerWh, r.m, r.m * 0.01)
  }

  /** At every reading of a drive with the power changing, not only at the end: the figure on the "last 15 min" tile. */
  @Test
  fun onTheMoveItIsAlwaysTheFigureOnTheFifteenMinuteTile() {
    val d = Truck()
    repeat(2400) { second ->
      d.run(1.0, mps, 30.0 + second % 40) { s ->
        s.rangeEstimate?.let { assertEquals("at $second s", s.plainEfficiency.recentMPerWh!!, it.mPerWh, 0.0) }
      }
    }
    assertNotNull(d.s().rangeEstimate)
  }

  /** The window must not slide down to the last moments of braking and then to nothing. */
  @Test
  fun standingStillItKeepsTheFigureItTookOnTheMove() {
    val d = Truck()
    d.run(1200.0, mps, kw(2.0))
    d.run(20.0, 8.0, -30.0) // Braking to a stop, all regen.
    d.run(20.0, 0.0, 0.0)
    val held = d.s().rangeEstimate!!.mPerWh
    d.run(windowS + 300.0, 0.0, 0.0) { s -> assertEquals(held, s.rangeEstimate!!.mPerWh, 0.0) }
    assertNull("the tile has nothing left to divide", d.s().plainEfficiency.recentMPerWh)

    d.run(120.0, mps, kw(3.0))
    val s = d.s()
    assertEquals("on the move again", 3.0, EffUnits.miPerKwh(s.rangeEstimate!!.mPerWh), 0.1)
    assertEquals(s.plainEfficiency.recentMPerWh!!, s.rangeEstimate!!.mPerWh, 0.0)
  }

  /** On a short hop a step is a large part of the figure: the range must not stop one short of the tile beside it. */
  @Test
  fun itTakesTheLevelsLastStepWhichLandsAfterTheWheelsStop() {
    val d = Truck()
    d.run(120.0, mps, kw(2.0))
    d.level -= 300.0
    d.run(20.0, 0.0, 0.0)
    val s = d.s()
    assertEquals(s.plainEfficiency.recentMPerWh!!, s.rangeEstimate!!.mPerWh, 0.0)
    assertEquals(s.levelWh!! * s.rangeEstimate!!.mPerWh, s.rangeEstimate!!.m, s.rangeEstimate!!.m * 0.001)
  }

  @Test
  fun atAChargerItClimbsWithTheEnergyAtTheSameFigure() {
    val d = Truck()
    d.run(1200.0, mps, kw(2.0))
    d.run(20.0, 0.0, 0.0)
    val before = d.s().rangeEstimate!!
    d.run(1800.0, 0.0, -100.0)
    val after = d.s().rangeEstimate!!
    assertTrue(d.s().charging)
    assertEquals(before.mPerWh, after.mPerWh, 0.0)
    assertEquals("50 kWh at 2 mi/kWh", 100.0, (after.m - before.m) / mile, 3.0)
  }

  @Test
  fun thereIsNoWarmUp() {
    val d = Truck()
    assertNull(d.s().rangeEstimate)
    var firstAtS: Double? = null
    var elapsed = 0.0
    d.run(60.0, 25.0, 40.0) { s ->
      elapsed += 0.5
      if (firstAtS == null && s.rangeEstimate != null) firstAtS = elapsed
    }
    assertTrue("first figure at $firstAtS s", firstAtS!! <= 3.0)
  }

  @Test
  fun untilThereAreFifteenMinutesItCountsTheWholeDriveFromItsFirstReading() {
    val d = Truck()
    // Twice the energy per mile for the first 5 minutes: nothing is left out for settling.
    d.run(300.0, 25.0, 80.0)
    d.run(300.0, 25.0, 40.0)
    val s = d.s()
    // Within the 10 s a figure stands.
    assertEquals(s.levelWh!! * s.distanceM / s.usedWh, s.rangeEstimate!!.m, s.rangeEstimate!!.m * 0.01)
    assertEquals(15_000.0, s.distanceM, 30.0)
    assertEquals(10_000.0, s.usedWh, 30.0)
  }

  @Test
  fun aFigureStandsForTenSeconds() {
    val d = Truck()
    val changedAt = ArrayList<Long>()
    var last: Double? = null
    d.run(120.0, 25.0, 40.0) { s ->
      val m = s.rangeEstimate?.m
      if (m != last) {
        changedAt += d.t
        last = m
      }
    }
    assertTrue("changes: ${changedAt.size}", changedAt.size in 10..13)
    changedAt.zipWithNext { a, b -> assertTrue("$a then $b", b - a >= SessionTracker.RANGE_EVERY_MS) }
  }

  @Test
  fun readingItAgainDoesNotMoveIt() {
    val d = Truck()
    d.run(30.0, 25.0, 40.0)
    val first = d.s().rangeEstimate
    repeat(5) { assertEquals(first, d.s().rangeEstimate) }
  }

  @Test
  fun netRegenBlanksItAtTheNextUpdateAndItComesBackAtOnce() {
    val d = Truck()
    d.run(30.0, 25.0, 40.0) // 0.33 kWh out
    assertNotNull(d.s().rangeEstimate)
    d.run(60.0, 25.0, -40.0) // 0.67 kWh back
    assertTrue(d.s().usedWh < 0)
    assertNull(d.s().rangeEstimate)
    d.run(120.0, 25.0, 40.0) // Past what a descent gets to: back under 6 mi/kWh.
    assertTrue(d.s().usedWh > 0)
    assertNotNull(d.s().rangeEstimate)
  }

  @Test
  fun aWholeDemoDriveHasAFigureFromItsFirstMoments() {
    var now = 0L
    val t = SessionTracker { now }
    val drive = DemoDrive()
    var rel = 0L
    while (rel <= 24 * 60_000L) {
      now = rel
      t.add(drive.sampleAt(rel, rel))
      val s = t.snapshot()
      if (rel >= DemoDrive.PARK_MS + 60_000L) assertNotNull("at $rel ms", s.rangeEstimate)
      rel += 500
    }
    val r = t.snapshot().rangeEstimate!!
    assertEquals(t.snapshot().levelWh!! * r.mPerWh, r.m, r.m * 0.01)
  }

  @Test
  fun theSessionPublishesIt() = runTest {
    var level = 150_000.0
    val src = object : VehicleSource {
      override fun read(atMs: Long, full: Boolean): SourceRead {
        level -= 40_000.0 * 0.5 / 3600.0
        return SourceRead(VehicleSample(atMs, speedMps = 25.0, levelWh = if (full) level else null), Feed(FeedKind.VEHICLE, "car"))
      }
    }
    val s = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ -> src })
    s.setDemo(false)
    s.attach(Any())
    advanceTimeBy(5_000)
    runCurrent()
    assertNotNull(s.state.value.rangeEstimate)
    s.reset()
    runCurrent()
    assertNull("a reset starts with no figure", s.state.value.rangeEstimate)
  }
}
