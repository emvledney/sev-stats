package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first minutes of each drive, at the production cadence (speed at 2 Hz, the rest at 1 Hz). The
 * complaint from the truck: the BMS takes about 5 minutes to settle, and until then the level falls
 * far faster than the energy used, which ruins a figure measured from the start of the drive.
 */
class DriveWarmupTest {

  private class Truck(var level: Double = 150_000.0) {
    var t = 1_000_000L
    val tracker = SessionTracker { t }
    /** mW for the kW really flowing; null = not offered: on the level alone by default. */
    var rate: (Double) -> Double? = { null }
    var plug = false
    private var tick = 0L

    /** [settlingKw]: what the level loses on top of the power really flowing. */
    fun run(seconds: Double, mps: Double, kw: Double, settlingKw: Double = 0.0, each: (SessionState) -> Unit = {}) {
      repeat((seconds * 2).toInt()) {
        t += 500
        level -= (kw + settlingKw) * 1000.0 * 0.5 / 3600.0
        val full = tick % 2 == 0L
        tick += 1
        tracker.add(
          VehicleSample(
            atMs = t,
            speedMps = mps,
            levelWh = if (full) level else null,
            chargeRateMw = if (full) rate(kw) else null,
            plugConnected = if (full) plug else null,
            chargeState = if (full) (if (plug) ChargeState.CHARGING else ChargeState.NOT_CHARGING) else null,
          ),
        )
        each(tracker.snapshot())
      }
    }

    /** Time passes without any sample (frozen, or the truck was off). */
    fun hole(seconds: Double) {
      t += (seconds * 1000).toLong()
    }

    fun s() = tracker.snapshot()
  }

  private val warmupS = SessionTracker.WARMUP_MS / 1000.0
  private val mps = 25.0
  private val miPerKwh = 2.3
  private val kw = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  /** Three times the real power on top: what a figure from the start of the drive would be ruined by. */
  private val settling = kw * 3

  private fun eff(s: SessionState) = EffUnits.miPerKwh(s.efficiency.mPerWh!!)

  @Test
  fun theStartOfADriveIsLeftOutOfTheFigure() {
    val d = Truck()
    var firstFigureAtS: Double? = null
    var elapsed = 0.0
    d.run(warmupS, mps, kw, settling) { s ->
      elapsed += 0.5
      assertEquals("at $elapsed s", EffStatus.WARMING_UP, s.efficiency.status)
      assertEquals("at $elapsed s", 0.0, s.efficiency.basisM, 0.0)
      assertEquals("at $elapsed s", 0.0, s.efficiency.basisWh, 0.0)
    }
    d.run(900.0, mps, kw) { s ->
      elapsed += 0.5
      if (firstFigureAtS == null && s.efficiency.status == EffStatus.OK) firstFigureAtS = elapsed
    }
    val s = d.s()
    // The figure is the settled part's, from its first reading to now.
    assertEquals(miPerKwh, eff(s), 0.02)
    assertEquals(mps * 900.0, s.efficiency.basisM, 30.0)
    // Then the usual 1 mi and 0.3 kWh, about a minute at this speed.
    assertTrue("first figure at $firstFigureAtS s", firstFigureAtS!! in warmupS + 60..warmupS + 70)
    // The session's own totals still have all of it.
    assertEquals(mps * (warmupS + 900.0), s.distanceM, 30.0)
    assertEquals((kw + settling) * 1000 * warmupS / 3600 + kw * 1000 * 900.0 / 3600, s.usedWh, 30.0)
  }

  @Test
  fun theFigureComesOutTheSameOnAPowerSignal() {
    val d = Truck()
    d.rate = { k -> -k * 1_000_000.0 }
    d.run(warmupS, mps, kw, settling)
    assertEquals(EffStatus.WARMING_UP, d.s().efficiency.status)
    d.run(900.0, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    assertEquals(miPerKwh, eff(d.s()), 0.02)
    assertEquals(mps * 900.0, d.s().efficiency.basisM, 30.0)
  }

  @Test
  fun aSettlingLevelDoesNotCostAPowerSignalItsTrust() {
    // The level says four times the energy, so the signal fails the check while it settles.
    val d = Truck()
    d.rate = { k -> -k * 1_000_000.0 }
    d.run(warmupS, mps, kw, settling)
    assertEquals(EnergySource.BATTERY_LEVEL, d.s().energySource)
    assertEquals(0, d.s().chargeRateCheck.sign)
    // Judged again from the end of the warm-up, it is back after the 0.5 kWh that takes.
    d.run(90.0, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    assertEquals(1, d.s().chargeRateCheck.sign)
    assertEquals(d.s().chargeRateCheck.signalWh, d.s().chargeRateCheck.levelWh, 20.0)
  }

  @Test
  fun aSignalAlreadyTrustedStaysInUseThroughTheEndOfTheWarmUp() {
    val d = Truck()
    d.rate = { k -> -k * 1_000_000.0 }
    d.run(warmupS - 1, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    d.run(10.0, mps, kw) { s -> assertEquals(EnergySource.CHARGE_RATE, s.energySource) }
    assertEquals(kw * 1000 * (warmupS + 9) / 3600, d.s().usedWh, 30.0)
  }

  @Test
  fun theWarmUpStartsWhenTheTruckMoves() {
    val d = Truck()
    d.run(600.0, 0.0, 2.0)
    var e = d.s().efficiency
    assertEquals(EffStatus.WARMING_UP, e.status)
    assertEquals(SessionTracker.WARMUP_MS, e.warmupLeftMs)
    assertEquals(0.0, e.progress, 0.0)
    assertEquals("nothing while parked", 0.0, e.basisWh, 0.0)
    d.run(120.0, mps, kw)
    e = d.s().efficiency
    assertEquals(EffStatus.WARMING_UP, e.status)
    assertEquals((SessionTracker.WARMUP_MS - 120_000L).toDouble(), e.warmupLeftMs.toDouble(), 1_000.0)
    d.run(warmupS - 120 + 70, mps, kw)
    assertEquals(EffStatus.OK, d.s().efficiency.status)
    assertEquals(0L, d.s().efficiency.warmupLeftMs)
    assertEquals(miPerKwh, eff(d.s()), 0.02)
  }

  @Test
  fun aShortStopOrPauseDoesNotRestartIt() {
    // The BMS's clock runs on through a red light and through a stretch the app did not see.
    val d = Truck()
    d.run(60.0, mps, kw, settling)
    d.run(60.0, 0.0, 1.0)
    d.run(60.0, mps, kw, settling)
    d.hole(60.0)
    d.run(55.0, mps, kw, settling)
    assertEquals(5_000.0, d.s().efficiency.warmupLeftMs.toDouble(), 1_000.0)
    d.run(80.0, mps, kw)
    assertEquals(EffStatus.OK, d.s().efficiency.status)
    assertEquals(miPerKwh, eff(d.s()), 0.05)
    // Past the warm-up, the same: a red light is not a new drive.
    val before = d.s().efficiency.basisM
    d.run(240.0, 0.0, 1.0)
    d.run(10.0, mps, kw) { s -> assertEquals(0L, s.efficiency.warmupLeftMs) }
    assertTrue(d.s().efficiency.basisM > before)
  }

  @Test
  fun eachDriveWarmsUpAgain() {
    // Truck off between the drives: a new drive, whose figure starts from nothing.
    val d = Truck()
    d.run(warmupS, mps, kw, settling)
    d.run(600.0, mps, kw)
    d.run(10.0, 0.0, 0.0)
    d.hole(20 * 60.0)
    d.run(2.0, 0.0, 0.0)
    d.run(warmupS - 1, mps, kw, settling) { s -> assertEquals(EffStatus.WARMING_UP, s.efficiency.status) }
    assertTrue(d.s().efficiency.warmupLeftMs in 1L..2_000L)
    d.run(600.0, mps, kw)
    assertEquals(0L, d.s().efficiency.warmupLeftMs)
    assertEquals(miPerKwh, eff(d.s()), 0.02)
    assertEquals(mps * 600.0, d.s().efficiency.basisM, 60.0)
  }

  @Test
  fun aLongStopWithTheAppOpenIsANewDrive() {
    val d = Truck()
    d.run(warmupS + 300, mps, kw)
    d.run(SessionTracker.DRIVE_BREAK_MS / 1000.0, 0.0, 1.0)
    d.run(10.0, mps, kw)
    assertTrue(d.s().efficiency.warmupLeftMs > SessionTracker.WARMUP_MS - 11_000)
  }

  @Test
  fun aChargeOfAnyLengthIsFollowedByANewDrive() {
    val d = Truck()
    d.run(warmupS + 300, mps, kw)
    d.run(5.0, 0.0, 0.0)
    d.plug = true
    d.run(60.0, 0.0, -100.0)
    assertTrue(d.s().charging)
    d.plug = false
    d.run(5.0, 0.0, 0.0)
    assertEquals(SessionTracker.WARMUP_MS, d.s().efficiency.warmupLeftMs)
    d.run(10.0, mps, kw)
    assertTrue(d.s().efficiency.warmupLeftMs > SessionTracker.WARMUP_MS - 11_000)
  }

  @Test
  fun progressOnlyEverRises() {
    val d = Truck()
    var last = 0.0
    var atWarmupEnd = 0.0
    var elapsed = 0.0
    d.run(10.0, 0.0, 1.0)
    d.run(warmupS + 90, mps, kw) { s ->
      elapsed += 0.5
      assertTrue("at $elapsed s", s.efficiency.progress >= last)
      last = s.efficiency.progress
      if (elapsed == warmupS - 1) atWarmupEnd = last
    }
    assertEquals(1.0, last, 0.0)
    assertEquals(0.8, atWarmupEnd, 0.01)
  }

  @Test
  fun aResetStartsItAgain() {
    val d = Truck()
    d.run(warmupS + 300, mps, kw)
    d.tracker.reset()
    assertEquals(SessionTracker.WARMUP_MS, d.s().efficiency.warmupLeftMs)
    d.run(10.0, mps, kw)
    assertEquals(EffStatus.WARMING_UP, d.s().efficiency.status)
    assertTrue(d.s().efficiency.warmupLeftMs > SessionTracker.WARMUP_MS - 11_000)
    assertEquals(0.0, d.s().efficiency.basisWh, 0.0)
  }
}
