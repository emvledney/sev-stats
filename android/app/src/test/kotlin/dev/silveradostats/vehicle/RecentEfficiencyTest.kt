package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The figure over only the last 15 minutes, at the production cadence: speed at 2 Hz, the rest at 1 Hz. */
class RecentEfficiencyTest {

  private class Truck(warmupMs: Long = 0, var level: Double = 180_000.0) {
    var t = 1_000_000L
    val tracker = SessionTracker(warmupMs) { t }
    /** mW for the kW really flowing; null = on the battery level alone. */
    var rate: (Double) -> Double? = { null }
    var plug: Boolean? = false
    private var tick = 0L

    fun run(seconds: Double, mps: Double, kw: Double) {
      repeat((seconds * 2).toInt()) {
        t += 500
        level -= kw * 1000.0 * 0.5 / 3600.0
        val full = tick % 2 == 0L
        tick += 1
        tracker.add(
          VehicleSample(
            atMs = t,
            speedMps = mps,
            levelWh = if (full) level else null,
            chargeRateMw = if (full) rate(kw) else null,
            plugConnected = if (full) plug else null,
          ),
        )
        // VehicleSession takes one after every sample, which also keeps the window trimmed.
        tracker.snapshot()
      }
    }

    fun s() = tracker.snapshot()
  }

  private val mps = 25.0
  private val windowS = SessionTracker.RECENT_MS / 1000.0

  private fun kw(miPerKwh: Double) = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  private fun mi(mPerWh: Double) = EffUnits.miPerKwh(mPerWh)

  @Test
  fun itFollowsTheLastFifteenMinutesAndTheOverallFigureDoesNot() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    assertEquals(3.0, mi(d.s().recentEfficiency.mPerWh!!), 0.02)
    d.run(windowS, mps, kw(2.0))
    val s = d.s()
    assertEquals("nothing from before the window", 2.0, mi(s.recentEfficiency.mPerWh!!), 0.02)
    assertEquals(mps * windowS, s.recentEfficiency.basisM, 30.0)
    // 30 min at 3.0 and 15 at 2.0: 2.57 mi/kWh overall.
    assertEquals(2.57, mi(s.efficiency.mPerWh!!), 0.02)
  }

  @Test
  fun itMovesWhileTheWindowFillsWithTheNewFigure() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    d.run(windowS / 2, mps, kw(2.0))
    // Half at each, by time: the harmonic mean of the two.
    assertEquals(2.4, mi(d.s().recentEfficiency.mPerWh!!), 0.02)
  }

  @Test
  fun untilThereAreFifteenMinutesItIsTheWholeOfTheFigure() {
    val d = Truck()
    d.run(600.0, mps, kw(2.3))
    val s = d.s()
    assertEquals(s.efficiency.basisM, s.recentEfficiency.basisM, 1e-6)
    assertEquals(s.efficiency.basisWh, s.recentEfficiency.basisWh, 1e-6)
    assertEquals(s.efficiency.mPerWh!!, s.recentEfficiency.mPerWh!!, 1e-9)
  }

  @Test
  fun itAsksForWhatTheOverallFigureAsksFor() {
    val d = Truck()
    d.run(30.0, mps, kw(2.3)) // under a mile
    assertNull(d.s().recentEfficiency.mPerWh)
    assertNull(d.s().efficiency.mPerWh)
    d.run(40.0, mps, kw(2.3))
    assertNotNull(d.s().recentEfficiency.mPerWh)
    assertNotNull(d.s().efficiency.mPerWh)
  }

  @Test
  fun theWarmUpStaysOutOfIt() {
    val d = Truck(SessionTracker.WARMUP_MS)
    d.run(SessionTracker.WARMUP_MS / 1000.0, mps, kw(0.5)) // the BMS settling: the level falls fast
    assertEquals(0.0, d.s().recentEfficiency.basisWh, 0.0)
    d.run(300.0, mps, kw(2.3))
    assertEquals(2.3, mi(d.s().recentEfficiency.mPerWh!!), 0.02)
    assertEquals(mps * 300.0, d.s().recentEfficiency.basisM, 30.0)
  }

  @Test
  fun aStretchWithNothingMeasuredLeavesNoFigureUntilThereIsOne() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    // The truck was off for longer than the window.
    d.t += SessionTracker.RECENT_MS + 60_000
    d.run(2.0, mps, 0.0)
    val gone = d.s().recentEfficiency
    assertNull(gone.mPerWh)
    assertEquals(0.0, gone.basisM, 50.0)
    assertNotNull("the overall figure stands", d.s().efficiency.mPerWh)
    d.run(300.0, mps, kw(2.0))
    assertEquals(2.0, mi(d.s().recentEfficiency.mPerWh!!), 0.02)
    assertEquals(mps * 300.0, d.s().recentEfficiency.basisM, 60.0)
  }

  @Test
  fun aStopCountsAsTimeNotAsDistance() {
    // The window is 15 minutes of the clock: 10 of them at a standstill leave 5 of driving in it.
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    d.run(600.0, 0.0, 0.0)
    assertEquals(mps * 300.0, d.s().recentEfficiency.basisM, 40.0)
  }

  @Test
  fun aLongDescentReadsAsNoFigureWithItsDistance() {
    val d = Truck()
    d.run(1800.0, mps, kw(2.3))
    d.run(windowS, mps, -5.0)
    val r = d.s().recentEfficiency
    assertNull(r.mPerWh)
    assertEquals(mps * windowS, r.basisM, 30.0)
    assertTrue(r.basisWh < 0)
  }

  @Test
  fun aChargeIsNotEnergyUsed() {
    val d = Truck()
    d.run(1800.0, mps, kw(2.3))
    d.run(5.0, 0.0, 0.0)
    d.plug = true
    d.run(120.0, 0.0, -100.0)
    d.plug = false
    d.run(5.0, 0.0, 0.0)
    d.run(120.0, mps, kw(2.3))
    assertFalse(d.s().charging)
    assertEquals(2.3, mi(d.s().recentEfficiency.mPerWh!!), 0.03)
  }

  @Test
  fun aRiseThatTurnsOutToBeAChargeIsTakenBackOutOfIt() {
    // No plug data: the rise counts as negative energy until it is recognised as a charge.
    val d = Truck()
    d.plug = null
    d.run(600.0, mps, kw(2.3))
    d.run(5.0, 0.0, 0.0)
    d.run(120.0, 0.0, -60.0)
    assertTrue(d.s().charging)
    d.run(120.0, mps, kw(2.3))
    assertFalse(d.s().charging)
    assertEquals(2.3, mi(d.s().recentEfficiency.mPerWh!!), 0.03)
  }

  @Test
  fun itComesOutTheSameOnAPowerSignalAndKeepsTheWindowSmall() {
    val d = Truck()
    d.rate = { k -> -k * 1_000_000.0 }
    d.run(3600.0, mps, kw(2.3))
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    assertEquals(2.3, mi(d.s().recentEfficiency.mPerWh!!), 0.02)
    assertEquals(mps * windowS, d.s().recentEfficiency.basisM, 30.0)
  }

  @Test
  fun aCoarseLevelGivesASteadyFigure() {
    // Steps of about 1 kWh: the window runs from step to step, so it holds still between them.
    val d = Truck()
    var last: Double? = null
    var worst = 0.0
    repeat(3600) {
      d.level = d.level - kw(2.3) * 1000.0 / 3600.0
      d.t += 1000
      d.tracker.add(VehicleSample(d.t, speedMps = mps, levelWh = kotlin.math.floor(d.level / 1000.0) * 1000.0))
      val f = d.s().recentEfficiency.mPerWh?.takeIf { _ -> it > 1800 }?.let(::mi)
      if (f != null) {
        last?.let { l -> worst = maxOf(worst, kotlin.math.abs(f - l)) }
        last = f
      }
    }
    assertEquals(2.3, last!!, 0.15)
    assertTrue("jump $worst", worst < 0.15)
  }

  @Test
  fun aResetForgetsIt() {
    val d = Truck()
    d.run(600.0, mps, kw(2.3))
    d.tracker.reset()
    assertEquals(RecentEfficiency(), d.s().recentEfficiency)
    d.run(120.0, mps, kw(2.0))
    assertEquals(2.0, mi(d.s().recentEfficiency.mPerWh!!), 0.03)
  }
}
