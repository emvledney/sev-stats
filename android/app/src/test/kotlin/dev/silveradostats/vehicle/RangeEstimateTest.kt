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

/** The app's own range figure: which efficiency it goes by, and that a settled one outlives the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeEstimateTest {
  private val last = EffUnits.fromMiPerKwh(2.1)

  /** 40 kW at 25 m/s, at the production cadence. */
  private class Driving : VehicleSource {
    var level = 150_000.0

    override fun read(atMs: Long, full: Boolean): SourceRead {
      level -= 40_000.0 * 0.5 / 3600.0
      return SourceRead(VehicleSample(atMs, speedMps = 25.0, levelWh = if (full) level else null), Feed(FeedKind.VEHICLE, "car"))
    }
  }

  private fun state(eff: Efficiency, lastSettled: Double? = null, level: Double? = 100_000.0) =
    SessionState(efficiency = eff, levelWh = level, lastSettledMPerWh = lastSettled)

  @Test
  fun itGoesByThisDriveThenTheLastSettledThenThisDrivesEarlyFigure() {
    val settled = Efficiency(EffStatus.OK, 4.0, 1.0, 10_000.0, 2_500.0)
    val early = settled.copy(mPerWh = 3.0, early = true)
    val descent = settled.copy(mPerWh = 12.0)
    val regen = Efficiency(EffStatus.NET_REGEN, null, 1.0, 5_000.0, -100.0)

    assertEquals(RangeEstimate(400_000.0, 4.0, RangeBasis.THIS_DRIVE), state(settled).rangeEstimate)
    assertEquals(RangeEstimate(400_000.0, 4.0, RangeBasis.THIS_DRIVE), state(settled, last).rangeEstimate)
    for (e in listOf(early, descent, regen, Efficiency.NONE)) {
      assertEquals(RangeEstimate(100_000.0 * last, last, RangeBasis.LAST_DRIVE), state(e, last).rangeEstimate)
    }
    assertEquals(RangeEstimate(300_000.0, 3.0, RangeBasis.EARLY), state(early).rangeEstimate)
    for (e in listOf(descent, regen, Efficiency.NONE)) assertNull(state(e).rangeEstimate)
    assertNull(state(settled, last, level = null).rangeEstimate)
  }

  @Test
  fun aWholeDriveAlwaysHasAFigureAndItMoves() {
    val t = SessionTracker { 0L }
    val drive = DemoDrive()
    val alone = HashSet<RangeBasis>()
    val kept = ArrayList<RangeEstimate>()
    var rel = 0L
    while (rel <= 24 * 60_000L) {
      t.add(drive.sampleAt(rel, rel))
      val s = t.snapshot()
      s.rangeEstimate?.let { alone += it.basis }
      kept += assertNotNull2(s.copy(lastSettledMPerWh = last).rangeEstimate)
      rel += 500
    }
    assertEquals(setOf(RangeBasis.EARLY, RangeBasis.THIS_DRIVE), alone)
    assertEquals(RangeBasis.LAST_DRIVE, kept.first().basis)
    assertEquals(RangeBasis.THIS_DRIVE, kept.last().basis)
    // Before this drive settles it still goes down with the battery, mile by mile.
    val before = kept.filter { it.basis == RangeBasis.LAST_DRIVE }
    assertTrue("${before.first().m} to ${before.last().m}", before.first().m - before.last().m > 1_000.0)
  }

  private fun assertNotNull2(r: RangeEstimate?): RangeEstimate {
    assertNotNull(r)
    return r!!
  }

  @Test
  fun theLastSettledFigureOutlivesTheSession() = runTest {
    val saved = ArrayList<Double>()
    val src = Driving()
    val s = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ -> src }, savedEfficiency = last, onEfficiency = { saved += it })
    s.setDemo(false)
    s.attach(Any())
    advanceTimeBy(1_000)
    runCurrent()
    // From the start of the drive, before this drive has measured anything.
    assertEquals(RangeBasis.LAST_DRIVE, s.state.value.rangeEstimate?.basis)
    assertEquals(last, s.state.value.rangeEstimate!!.mPerWh, 0.0)
    assertTrue(saved.isEmpty())

    // A long enough drive replaces it, written out about once a minute.
    advanceTimeBy(15 * 60_000L)
    runCurrent()
    val st = s.state.value
    assertEquals(RangeBasis.THIS_DRIVE, st.rangeEstimate?.basis)
    assertEquals(st.efficiency.mPerWh!!, st.lastSettledMPerWh!!, 1e-9)
    assertTrue("saved ${saved.size}", saved.size in 2..16)
    assertEquals(st.efficiency.mPerWh!!, saved.last(), 0.05)

    // The next drive starts from it.
    val kept = st.lastSettledMPerWh!!
    s.reset()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(RangeBasis.LAST_DRIVE, s.state.value.rangeEstimate?.basis)
    assertEquals(kept, s.state.value.rangeEstimate!!.mPerWh, 1e-9)
  }

  @Test
  fun demoFiguresAreNeitherKeptNorUsed() = runTest {
    val saved = ArrayList<Double>()
    val s = VehicleSession(
      backgroundScope,
      { testScheduler.currentTime },
      { _, _ -> DemoSource(VehicleSession.DEMO_HEAD_START_MS) },
      savedEfficiency = last,
      onEfficiency = { saved += it },
    )
    s.setDemo(true)
    s.attach(Any())
    advanceTimeBy(2 * 60_000L)
    runCurrent()
    assertEquals(RangeBasis.THIS_DRIVE, s.state.value.rangeEstimate?.basis)
    assertNull(s.state.value.lastSettledMPerWh)
    assertTrue(saved.isEmpty())
  }

  @Test
  fun aSavedFigureNoDriveCouldGiveIsIgnored() = runTest {
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 100.0)) {
      val s = VehicleSession(backgroundScope, { testScheduler.currentTime }, { _, _ -> Driving() }, savedEfficiency = bad)
      s.setDemo(false)
      s.attach(Any())
      advanceTimeBy(1_000)
      runCurrent()
      assertNull("saved $bad", s.state.value.lastSettledMPerWh)
    }
  }
}
