package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Simple tab's mi/kWh: this drive's and the last 15 minutes', no warm-up, moving every 10 s. */
class PlainEfficiencyTest {
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

  private val mps = 25.0
  private val windowS = SessionTracker.RECENT_MS / 1000.0

  private fun kw(miPerKwh: Double) = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  private fun mi(mPerWh: Double) = EffUnits.miPerKwh(mPerWh)

  @Test
  fun bothStartAsSoonAsThereIsSomethingToDivide() {
    val d = Truck()
    assertEquals(PlainEfficiency(), d.s().plainEfficiency)
    var driveAtS: Double? = null
    var recentAtS: Double? = null
    var elapsed = 0.0
    d.run(30.0, mps, kw(2.3)) { s ->
      elapsed += 0.5
      if (driveAtS == null && s.plainEfficiency.driveMPerWh != null) driveAtS = elapsed
      if (recentAtS == null && s.plainEfficiency.recentMPerWh != null) recentAtS = elapsed
    }
    assertTrue("drive at $driveAtS s", driveAtS!! <= 3.0)
    assertTrue("15 min at $recentAtS s", recentAtS!! <= 3.0)
  }

  /** Nothing is left out for settling: the first minutes count in full. */
  @Test
  fun theDriveFigureIsTheTripOverTheEnergyFromTheFirstReading() {
    val d = Truck()
    d.run(300.0, mps, kw(1.5))
    d.run(300.0, mps, kw(3.0))
    val s = d.s()
    assertEquals(2.0, mi(s.plainEfficiency.driveMPerWh!!), 0.03)
    // Within the 10 s a figure stands: what Trip's two figures say.
    assertEquals(s.distanceM / s.usedWh, s.plainEfficiency.driveMPerWh!!, s.plainEfficiency.driveMPerWh!! * 0.02)
  }

  @Test
  fun theRecentFigureIsTheOneTheRangeUses() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    d.run(windowS, mps, kw(2.0))
    val s = d.s()
    assertEquals(s.plainEfficiency.recentMPerWh!!, s.rangeEstimate!!.mPerWh, 0.0)
    assertTrue(s.plainEfficiency.driveMPerWh!! > s.rangeEstimate!!.mPerWh * 1.2)
  }

  @Test
  fun theRecentFigureFollowsTheLastFifteenMinutesAndTheDriveFigureDoesNot() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    assertEquals(3.0, mi(d.s().plainEfficiency.recentMPerWh!!), 0.03)
    d.run(windowS, mps, kw(2.0))
    val p = d.s().plainEfficiency
    assertEquals("nothing from before the window", 2.0, mi(p.recentMPerWh!!), 0.03)
    // 30 min at 3.0 and 15 at 2.0: 2.57 mi/kWh overall.
    assertEquals(2.57, mi(p.driveMPerWh!!), 0.03)
  }

  @Test
  fun untilThereAreFifteenMinutesTheTwoAreTheSame() {
    val d = Truck()
    d.run(600.0, mps, kw(2.3))
    val p = d.s().plainEfficiency
    assertEquals(p.driveMPerWh!!, p.recentMPerWh!!, 1e-9)
  }

  /** With the power changing all the time, as it does: the figures still move only every 10 s. */
  @Test
  fun aFigureStandsForTenSeconds() {
    val d = Truck()
    val changedAt = ArrayList<Long>()
    var last: PlainEfficiency? = null
    repeat(120) { second ->
      d.run(1.0, mps, 30.0 + second) { s ->
        // The first there is shows at once: the 10 s count from that one.
        if (s.plainEfficiency.driveMPerWh != null && s.plainEfficiency != last) {
          changedAt += d.t
          last = s.plainEfficiency
        }
      }
    }
    assertTrue("changes: ${changedAt.size}", changedAt.size in 10..13)
    changedAt.zipWithNext { a, b -> assertTrue("$a then $b", b - a >= SessionTracker.RANGE_EVERY_MS) }
  }

  @Test
  fun netRegenIsNoFigureUntilThereIsEnergyToDivideAgain() {
    val d = Truck()
    d.run(30.0, mps, kw(2.3))
    assertNotNull(d.s().plainEfficiency.driveMPerWh)
    d.run(120.0, mps, -40.0)
    assertTrue(d.s().usedWh < 0)
    assertEquals(PlainEfficiency(), d.s().plainEfficiency)
    d.run(300.0, mps, 40.0)
    assertTrue(d.s().usedWh > 0)
    assertNotNull(d.s().plainEfficiency.driveMPerWh)
    assertNotNull(d.s().plainEfficiency.recentMPerWh)
  }

  @Test
  fun aLongDescentLeavesTheDriveFigureAndBlanksTheRecentOne() {
    val d = Truck()
    d.run(1800.0, mps, kw(2.3))
    d.run(windowS + 15.0, mps, -5.0)
    val p = d.s().plainEfficiency
    assertNotNull(p.driveMPerWh)
    assertNull(p.recentMPerWh)
  }

  @Test
  fun aStretchWithNothingMeasuredLeavesOnlyWhatWasDrivenSince() {
    val d = Truck()
    d.run(1800.0, mps, kw(3.0))
    d.t += SessionTracker.RECENT_MS + 300_000 // Another app in front, the process frozen.
    d.run(300.0, mps, kw(2.0))
    assertEquals(2.0, mi(d.s().plainEfficiency.recentMPerWh!!), 0.05)
  }

  @Test
  fun aResetForgetsBoth() {
    val d = Truck()
    d.run(600.0, mps, kw(2.3))
    d.tracker.reset()
    assertEquals(PlainEfficiency(), d.s().plainEfficiency)
    d.run(120.0, mps, kw(2.0))
    assertEquals(2.0, mi(d.s().plainEfficiency.driveMPerWh!!), 0.05)
    assertEquals(2.0, mi(d.s().plainEfficiency.recentMPerWh!!), 0.05)
  }

  @Test
  fun aNewDriveStartsBothOver() {
    val d = Truck()
    d.run(600.0, mps, kw(1.5))
    d.run(SessionTracker.DRIVE_BREAK_MS / 1000.0 + 30.0, 0.0, 0.0) // Parked for longer than the break.
    d.run(300.0, mps, kw(3.0))
    val p = d.s().plainEfficiency
    assertEquals(3.0, mi(p.driveMPerWh!!), 0.1)
    assertEquals(3.0, mi(p.recentMPerWh!!), 0.1)
  }

  @Test
  fun withNothingToDivideThereIsNoFigure() {
    assertNull("no distance yet", SessionTracker.plainFrom(0.0, 100.0))
    assertNull("no energy used yet", SessionTracker.plainFrom(1_000.0, 0.0))
    assertNull("regen put it all back", SessionTracker.plainFrom(1_000.0, -50.0))
    val e = SessionTracker.MAX_PLAIN_M_PER_WH
    assertNotNull("20 mi/kWh", SessionTracker.plainFrom(e * 1_000.0, 1_000.0))
    assertNull("above it: a moment on a descent, not a figure to read", SessionTracker.plainFrom(e * 1_001.0, 1_000.0))
  }

  /** The simulated drive stops often, with regen: a figure throughout, once it has been moving a minute. */
  @Test
  fun aWholeDemoDriveHasBothFromItsFirstMoments() {
    var now = 0L
    val t = SessionTracker { now }
    val drive = DemoDrive()
    var rel = 0L
    while (rel <= 24 * 60_000L) {
      now = rel
      t.add(drive.sampleAt(rel, rel))
      val p = t.snapshot().plainEfficiency
      if (rel >= DemoDrive.PARK_MS + 60_000L) {
        assertNotNull("drive at $rel ms", p.driveMPerWh)
        assertNotNull("15 min at $rel ms", p.recentMPerWh)
      }
      rel += 500
    }
  }
}
