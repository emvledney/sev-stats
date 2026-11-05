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

  private fun eff(s: SessionState): Double = EffUnits.miPerKwh(s.efficiency.mPerWh!!)

  @Test
  fun steadyDrive() {
    val d = Drive()
    val kw = kwFor(25.0, 2.3)
    d.run(600.0, 25.0, kw)
    val s = d.state()
    assertEquals(15_000.0, s.distanceM, 15.0) // 25 m/s x 600 s (the first interval starts at the first sample)
    assertEquals(EffStatus.OK, s.efficiency.status)
    assertEquals(2.3, eff(s), 0.01)
    assertEquals(kw * 1000 * 599.5 / 3600, s.usedWh, 3.0)
    assertEquals(600_000L - 500, s.activeMs)
    assertEquals(25.0, s.avgSpeedMps!!, 0.05)
    assertEquals(0, s.gaps)
    // One series point per 0.1 mi after the first mile; all close to 2.3.
    val expected = (mi(s.distanceM) * 10).toInt() - 10
    assertTrue("${s.series.size} vs $expected", abs(s.series.size - expected) <= 2)
    s.series.forEach { assertEquals(2.3, EffUnits.miPerKwh(it.mPerWh), 0.05) }
    assertEquals(0.1 * EffUnits.METERS_PER_MILE, s.series[1].distM - s.series[0].distM, 1e-6)
    // Range at the measured efficiency: remaining Wh x m/Wh.
    assertEquals(s.levelWh!! * s.efficiency.mPerWh!!, s.rangeAtMeasuredM!!, 1e-6)
    // Energy from the charge-rate property once it has agreed with the level.
    assertEquals(EnergySource.CHARGE_RATE, s.energySource)
    assertTrue(s.chargeRateSeenNonZero)
    // Context values.
    assertEquals(12.0, s.outsideC!!, 0.0)
    assertEquals(s.levelWh!! / 205_080.0 * 100, s.socPct!!, 1e-9)
  }

  @Test
  fun warmUpNeedsBothDistanceAndEnergy() {
    val d = Drive()
    d.run(32.0, 25.0, kwFor(25.0, 2.3)) // 0.5 mi
    var s = d.state()
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    assertNull(s.efficiency.mPerWh)
    assertNull(s.rangeAtMeasuredM)
    assertEquals(0.5, s.efficiency.progress, 0.03)
    assertTrue(s.series.isEmpty())
    d.run(36.0, 25.0, kwFor(25.0, 2.3)) // past 1 mi, ~0.46 kWh
    s = d.state()
    assertEquals(EffStatus.OK, s.efficiency.status)
    assertEquals(1.0, s.efficiency.progress, 0.0)

    // 1.5 mi on very little energy (0.25 kWh, a long downhill): still warming up, on energy.
    val e = Drive()
    e.run(100.0, 25.0, kwFor(25.0, 6.0))
    s = e.state()
    assertTrue(mi(s.distanceM) > 1.5)
    assertTrue(s.efficiency.basisWh < 300)
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    assertEquals(s.efficiency.basisWh / 300.0, s.efficiency.progress, 1e-9)
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
    assertTrue(d.state().charging)
    d.plug = false
    d.chargeState = ChargeState.NOT_CHARGING
    d.run(130.0, 25.0, kw) // ~2 mi
    val s = d.state()
    assertFalse(s.charging)
    assertEquals(10_000.0, s.chargedWh, 30.0)
    val expectedUsed = used1 + 0.5 * 1000 * 10 / 3600 + kw * 1000 * 130 / 3600
    assertEquals(expectedUsed, s.usedWh, 30.0)
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
    d.run(130.0, 25.0, kw) // drives away: charging ends, accounting resumes
    s = d.state()
    assertFalse(s.charging)
    assertEquals(11.0 * 600 / 3.6, s.chargedWh, 10.0)
    assertEquals(used1 + 0.3 * 5 / 3.6 + kw * 1000 * 130 / 3600, s.usedWh, 10.0)
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
    e.plug = false
    e.chargeState = ChargeState.NOT_CHARGING
    e.run(130.0, 25.0, kwFor(25.0, 2.5))
    s = e.state()
    assertEquals(11.0 * 300 / 3.6, s.chargedWh, 10.0)
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
  fun quantisedLevelHoldsEfficiencySteady() {
    // (step, hold): value steps of 0.5 kWh; 0.1 kWh steps that also update only every 17 s;
    // 10 Wh steps; a fine value held for 17 s (time-quantised, like the charge rate on GM).
    for ((quant, hold) in listOf(500.0 to 0L, 100.0 to 17_000L, 10.0 to 0L, 1.0 to 17_000L)) {
      val d = Drive()
      d.rate = null // the level alone: no power signal to go by
      d.quantWh = quant
      d.holdMs = hold
      val kw = kwFor(27.0, 2.3)
      val figures = mutableListOf<Pair<Int, Double>>()
      for (sec in 1..1800) {
        d.run(1.0, 27.0, kw)
        d.state().efficiency.mPerWh?.let { figures += sec to EffUnits.miPerKwh(it) }
      }
      val tag = "step $quant Wh, hold $hold ms"
      assertTrue("$tag: ${figures.size} figures", figures.size > 1000)
      // Never wild, from the first figure on; exact-edge steps and fine values are close at once.
      val early = if (hold == 0L || quant <= 1.0) 0.12 else 0.35
      figures.forEach { (_, f) -> assertEquals(tag, 2.3, f, early) }
      // Settled: no sawtooth between steps.
      val settled = figures.filter { it.first > 600 }.map { it.second }
      settled.forEach { assertEquals(tag, 2.3, it, 0.06) }
      val jumps = settled.zipWithNext { a, b -> abs(b - a) }
      assertTrue("$tag: max jump ${jumps.max()}", jumps.max() < 0.06)
      assertEquals(tag, 2.3, figures.last().second, 0.03)
      val s = d.state()
      assertTrue(tag, s.levelStepWh!! >= quant - 1e-6)
      // A coarse step lengthens the warm-up (3 steps of energy).
      if (quant == 500.0) assertTrue(tag, figures.first().first * kw / 3.6 >= 1500.0)
    }
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
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    assertTrue(s.series.isEmpty())
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
    assertNull(s.avgSpeedMps)
    assertNull(s.socPct)
    assertNull(s.rangeAtMeasuredM)
    // Level only, no speed: energy is counted, distance stays 0, still warming up.
    for (i in 1..120) {
      now = i * 1000L
      t.add(VehicleSample(now, levelWh = 100_000.0 - i * 10, capacityWh = 0.0, rangeM = 0.0, chargeRateMw = Double.NaN))
    }
    s = t.snapshot()
    assertEquals(0.0, s.distanceM, 0.0)
    assertEquals(1190.0, s.usedWh, 1e-6)
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    assertEquals(0.0, s.efficiency.progress, 0.0)
    assertNull(s.socPct) // capacity 0 ignored
    // Zero level: no range figure, no NaN.
    t.add(VehicleSample(now + 1000, levelWh = 0.0, speedMps = 0.0))
    s = t.snapshot()
    assertNull(s.rangeAtMeasuredM)
    listOf(s.distanceM, s.usedWh, s.efficiency.progress).forEach { assertTrue(it.isFinite()) }
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
    assertNotNull(Efficiency.NONE)
  }
}
