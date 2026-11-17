package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/** SessionTracker: distance, energy, efficiency, warm-up, gaps, charging, quantisation, power. */
class SessionTrackerTest {

  /** A scripted truck: advances time, speed and the true battery level; feeds the tracker. */
  private class Drive(var t: Long = 1_000_000L, var trueLevel: Double = 150_000.0) {
    var now = t
    val tracker = SessionTracker(warmupMs = 0) { now }
    var quantWh = 1.0
    /** Level updates only every this many ms (sample-and-hold), 0 = every sample. */
    var holdMs = 0L
    private var heldLevel: Double? = null
    private var heldAt = Long.MIN_VALUE
    var plug: Boolean? = null
    var chargeState: ChargeState? = null
    var outsideC: Double? = 12.0
    var rangeM: Double? = 300_000.0
    var capacityWh: Double? = 205_080.0
    /** Charge-rate property: null = not offered; else maps battery kW (+ discharge) to the mW value. */
    var rate: ((Double) -> Double)? = { kw -> -kw * 1_000_000.0 }

    fun reported(): Double {
      val q = floor(trueLevel / quantWh) * quantWh
      if (holdMs <= 0) return q
      if (heldLevel == null || t - heldAt >= holdMs) {
        heldLevel = q
        heldAt = t
      }
      return heldLevel!!
    }

    /**
     * [seconds] at [mps] with the battery delivering [kw] (+ discharge, - regen / charge), one
     * sample every [stepMs].
     */
    fun run(seconds: Double, mps: Double, kw: Double, stepMs: Long = 500) {
      val steps = (seconds * 1000 / stepMs).toInt()
      repeat(steps) {
        t += stepMs
        trueLevel -= kw * 1000.0 * stepMs / 1000.0 / 3600.0
        sample(mps, kw)
      }
    }

    fun sample(mps: Double, kw: Double) {
      now = t
      tracker.add(
        VehicleSample(
          atMs = t,
          speedMps = mps,
          levelWh = reported(),
          capacityWh = capacityWh,
          chargeRateMw = rate?.invoke(kw),
          plugConnected = plug,
          chargeState = chargeState,
          gear = if (mps > 0) Gear.DRIVE else Gear.PARK,
          outsideC = outsideC,
          rangeM = rangeM,
        ),
      )
    }

    /** Time passes without any sample (sampling paused); the truck keeps going meanwhile. */
    fun hole(seconds: Double, kw: Double) {
      t += (seconds * 1000).toLong()
      trueLevel -= kw * 1000.0 * seconds / 3600.0
    }

    fun state() = tracker.snapshot()
  }

  /** Battery kW for [mps] at [miPerKwh]. */
  private fun kwFor(mps: Double, miPerKwh: Double): Double = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  private fun mi(m: Double) = m / EffUnits.METERS_PER_MILE

  /** The drive's distance over its energy, as the Simple tab divides them (the warm-up figure went with the report, 2026-11-17). */
  private fun eff(s: SessionState): Double = EffUnits.miPerKwh(s.distanceM / s.usedWh)

  @Test
  fun steadyDrive() {
    val d = Drive()
    val kw = kwFor(25.0, 2.3)
    d.run(600.0, 25.0, kw)
    val s = d.state()
    assertEquals(15_000.0, s.distanceM, 15.0) // 25 m/s x 600 s (the first interval starts at the first sample)
    assertEquals(2.3, eff(s), 0.01)
    assertEquals(kw * 1000 * 599.5 / 3600, s.usedWh, 3.0)
    assertEquals(600_000L - 500, s.activeMs)
    assertEquals(0, s.gaps)
    // Range: remaining Wh x the last 15 minutes' m/Wh, here the whole drive.
    assertEquals(s.levelWh!! * s.distanceM / s.usedWh, s.rangeEstimate!!.m, s.rangeEstimate!!.m * 0.01)
    // Energy from the charge-rate property once it has agreed with the level.
    assertEquals(EnergySource.CHARGE_RATE, s.energySource)
    // Context values.
    assertEquals(12.0, s.outsideC!!, 0.0)
    assertEquals(s.levelWh!! / 205_080.0 * 100, s.socPct!!, 1e-9)
  }

  @Test
  fun regenIsNetAndNeverCountsAsCharging() {
    val d = Drive()
    d.run(130.0, 25.0, kwFor(25.0, 2.0)) // ~2 mi at 2.0
    val before = d.state()
    // ~1 mi downhill with net regen: the level rises while moving.
    d.run(65.0, 25.0, -8.0)
    val s = d.state()
    assertFalse(s.charging)
    assertEquals(0.0, s.chargedWh, 0.0)
    assertTrue("net use falls with regen", s.usedWh < before.usedWh - 100)
    assertTrue("efficiency improves", eff(s) > eff(before) + 0.3)
  }

  @Test
  fun gapsAreNotIntegratedAndTheirEnergyIsExcluded() {
    val d = Drive()
    val kw = kwFor(20.0, 2.5)
    d.run(200.0, 20.0, kw)
    val mid = d.state()
    d.hole(60.0, kw) // 1.2 km driven while nothing was sampled
    d.run(200.0, 20.0, kw)
    val s = d.state()
    assertEquals(1, s.gaps)
    assertEquals(60_500L, s.gapMs)
    // Only the sampled 400 s count (minus one sample interval per stretch).
    assertEquals(20.0 * 399.0, s.distanceM, 25.0)
    // Energy used during the gap is not counted either: efficiency stays true.
    assertEquals(2.5, eff(s), 0.02)
    assertTrue(s.usedWh < mid.usedWh * 2 + 20)
    assertEquals(399_000L, s.activeMs)
  }

  @Test
  fun pluggedChargingIsPausedAndRebased() {
    val d = Drive()
    val kw = kwFor(25.0, 2.5)
    d.run(200.0, 25.0, kw) // ~3.1 mi
    val used1 = d.state().usedWh
    d.run(10.0, 0.0, 0.5)
    d.plug = true
    d.chargeState = ChargeState.CHARGING
    d.run(600.0, 0.0, -60.0) // +10 kWh at a DC charger
    var s = d.state()
    assertTrue(s.charging)
    assertEquals(10_000.0, s.chargedWh, 30.0)
    assertEquals(used1 + 0.5 * 1000 * 10 / 3600, s.usedWh, 30.0)
    d.plug = false
    d.chargeState = ChargeState.NOT_CHARGING
    d.run(130.0, 25.0, kw) // ~2 mi: a new drive, so only this counts
    s = d.state()
    assertFalse(s.charging)
    assertEquals(0.0, s.chargedWh, 0.0)
    assertEquals(kw * 1000 * 130 / 3600, s.usedWh, 30.0)
    assertEquals(2.5, eff(s), 0.05)
  }

  @Test
  fun levelRiseWhileStoppedIsDetectedAsCharging() {
    val d = Drive()
    d.plug = null
    d.chargeState = null
    d.rate = null
    val kw = kwFor(25.0, 2.5)
    d.run(200.0, 25.0, kw)
    val used1 = d.state().usedWh
    d.run(5.0, 0.0, 0.3)
    d.run(600.0, 0.0, -11.0) // Level 2 charger, no plug data: ~1.8 kWh
    var s = d.state()
    assertTrue(s.charging)
    // The first 0.2 kWh of rise (before the detection) is not counted as negative use.
    assertEquals(used1 + 0.3 * 5 / 3.6, s.usedWh, 5.0)
    assertEquals(11.0 * 600 / 3.6, s.chargedWh, 10.0)
    d.run(130.0, 25.0, kw) // drives away: charging ends, and this is a new drive
    s = d.state()
    assertFalse(s.charging)
    assertEquals(0.0, s.chargedWh, 0.0)
    assertEquals(kw * 1000 * 130 / 3600, s.usedWh, 10.0)
    assertEquals(2.5, eff(s), 0.05)
  }

  @Test
  fun aSessionOpenedWhileParkedAndChargingCountsNoNegativeUse() {
    // Opened parked on a charger without plug / charge-state data: the rise is still detected.
    val d = Drive()
    d.plug = null
    d.chargeState = null
    d.run(600.0, 0.0, -7.2) // +1.2 kWh
    var s = d.state()
    assertTrue(s.charging)
    assertEquals(0.0, s.usedWh, 1.0)
    assertEquals(1_200.0, s.chargedWh, 5.0)
    // Opened already plugged in: "charged" counts from the first level, not from zero.
    val e = Drive()
    e.plug = true
    e.chargeState = ChargeState.CHARGING
    e.run(300.0, 0.0, -11.0)
    assertEquals(11.0 * 300 / 3.6, e.state().chargedWh, 10.0)
    e.plug = false
    e.chargeState = ChargeState.NOT_CHARGING
    e.run(130.0, 25.0, kwFor(25.0, 2.5))
    s = e.state()
    assertEquals(kwFor(25.0, 2.5) * 1000 * 129.5 / 3600, s.usedWh, 10.0)
  }

  @Test
  fun smallLevelWobbleWhileStoppedIsNotCharging() {
    val d = Drive()
    d.run(120.0, 20.0, kwFor(20.0, 2.5))
    d.run(30.0, 0.0, -10.0) // ~83 Wh back (a level correction), below the threshold
    assertFalse(d.state().charging)
  }

  @Test
  fun resetStartsAFreshSession() {
    val d = Drive()
    d.run(300.0, 25.0, kwFor(25.0, 2.3))
    assertTrue(d.state().distanceM > 0)
    d.now += 5_000
    d.tracker.reset()
    val s = d.state()
    assertEquals(d.now, s.startedAtMs)
    assertEquals(0.0, s.distanceM, 0.0)
    assertEquals(0.0, s.usedWh, 0.0)
    assertEquals(0L, s.activeMs)
    assertEquals(0, s.gaps)
    // And it works again afterwards.
    d.t = d.now
    d.run(200.0, 25.0, kwFor(25.0, 2.6))
    assertEquals(2.6, eff(d.state()), 0.03)
    assertEquals(0, d.state().gaps)
  }

  @Test
  fun unitConversions() {
    val m = EffUnits.fromMiPerKwh(2.32)
    assertEquals(2.32, EffUnits.miPerKwh(m), 1e-12)
    assertEquals(3.7337, EffUnits.kmPerKwh(m), 1e-3)
    assertEquals(431.0, EffUnits.whPerMi(m)!!, 0.1)
    assertEquals(267.8, EffUnits.whPerKm(m)!!, 0.1)
    assertNull(EffUnits.whPerMi(0.0))
    assertNull(EffUnits.whPerKm(-1.0))
    assertEquals(1609.344, EffUnits.METERS_PER_MILE, 0.0)
  }

  @Test
  fun nothingDividesByZero() {
    var now = 0L
    val t = SessionTracker { now }
    var s = t.snapshot()
    assertFalse(s.hasData)
    assertNull(s.socPct)
    assertNull(s.rangeEstimate)
    // Level only, no speed: energy is counted, distance stays 0, still warming up.
    for (i in 1..120) {
      now = i * 1000L
      t.add(VehicleSample(now, levelWh = 100_000.0 - i * 10, capacityWh = 0.0, rangeM = 0.0, chargeRateMw = Double.NaN))
    }
    s = t.snapshot()
    assertEquals(0.0, s.distanceM, 0.0)
    assertEquals(1190.0, s.usedWh, 1e-6)
    assertNull(s.socPct) // capacity 0 ignored
    // Zero level: no range figure, no NaN.
    t.add(VehicleSample(now + 1000, levelWh = 0.0, speedMps = 0.0))
    s = t.snapshot()
    assertNull(s.rangeEstimate)
    listOf(s.distanceM, s.usedWh).forEach { assertTrue(it.isFinite()) }
  }

  @Test
  fun outOfOrderSamplesAreIgnoredAndReverseCountsAsDistance() {
    var now = 10_000L
    val t = SessionTracker { now }
    t.add(VehicleSample(10_000, speedMps = -2.0, levelWh = 1000.0))
    t.add(VehicleSample(11_000, speedMps = -2.0, levelWh = 1000.0))
    t.add(VehicleSample(10_500, speedMps = 50.0, levelWh = 500.0))
    now = 11_000
    val s = t.snapshot()
    assertEquals(2.0, s.distanceM, 1e-9)
    assertEquals(2L, s.samples)
    assertEquals(1000.0, s.levelWh!!, 0.0)
  }

  @Test
  fun chargeStateHelpers() {
    assertEquals(ChargeState.CHARGING, ChargeState.fromAosp(1))
    assertEquals(ChargeState.UNKNOWN, ChargeState.fromAosp(99))
    assertNull(ChargeState.fromAosp(null))
  }
}
