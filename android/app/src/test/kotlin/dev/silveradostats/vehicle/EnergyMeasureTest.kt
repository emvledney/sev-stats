package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session's energy is measured from, at the production cadence (speed at 2 Hz, the rest at
 * 1 Hz). Power actually drawn is [Truck.run]'s kw; what each signal says about it is up to the test.
 */
class EnergyMeasureTest {

  private class Truck(var level: Double = 150_000.0) {
    var t = 1_000_000L
    val tracker = SessionTracker(warmupMs = 0) { t }
    /** mW for the kW really flowing; null = not offered. AOSP sign by default. */
    var rate: (Double) -> Double? = { kw -> -kw * 1_000_000.0 }
    var rateAge: Long? = null
    /** kW the MX+ reads for the kW really flowing; null = not connected. */
    var pack: ((Double) -> Double)? = null
    /** How much the level moves per Wh that really flowed. */
    var levelGain = 1.0
    /** A wobble of the level property that is not energy (load, temperature), Wh. */
    var wobble: (Long) -> Double = { 0.0 }
    /** The level property has stopped answering, while everything else goes on. */
    var levelOut = false
    var plug = false
    var used = 0.0
    private var tick = 0L

    fun run(seconds: Double, mps: Double, kw: Double) {
      repeat((seconds * 2).toInt()) {
        t += 500
        val wh = kw * 1000.0 * 0.5 / 3600.0
        level -= wh * levelGain
        used += wh
        val full = tick % 2 == 0L
        tick += 1
        pack?.let { tracker.addPack(t, it(kw)) }
        tracker.add(
          VehicleSample(
            atMs = t,
            speedMps = mps,
            levelWh = if (full && !levelOut) level + wobble(t) else null,
            chargeRateMw = if (full) rate(kw) else null,
            chargeRateAgeMs = if (full) rateAge else null,
            plugConnected = if (full) plug else null,
            chargeState = if (full) (if (plug) ChargeState.CHARGING else ChargeState.NOT_CHARGING) else null,
          ),
        )
      }
    }

    fun hole(seconds: Double, kw: Double) {
      t += (seconds * 1000).toLong()
      level -= kw * 1000.0 * seconds / 3600.0
    }

    fun s() = tracker.snapshot()
  }

  /** 40 kW at 25 m/s: the same for every test, about 1.4 mi/kWh. */
  private val kw = 40.0
  private val mps = 25.0
  private val miPerKwh = mps * 3600.0 / 40.0 / 1000.0 * 1000.0 / EffUnits.METERS_PER_MILE

  private fun eff(s: SessionState) = EffUnits.miPerKwh(s.efficiency.mPerWh!!)

  @Test
  fun aLevelThatOverstatesTheEnergyDoesNotCount() {
    // The complaint from the truck: far more kWh used than it says, and a mi/kWh to match.
    val d = Truck()
    d.levelGain = 1.3
    d.run(1200.0, mps, kw)
    val s = d.s()
    assertEquals(EnergySource.CHARGE_RATE, s.energySource)
    assertEquals(d.used, s.usedWh, d.used * 0.03)
    assertEquals(d.used * 1.3, s.levelUsedWh, 40.0)
    assertEquals(miPerKwh, eff(s), 0.08)
    assertEquals(1, s.chargeRateCheck.sign)
  }

  @Test
  fun aWobblingLevelDoesNotMoveTheFigure() {
    // Wherever the drive starts in the wobble: a rising level must never settle the sign the wrong way.
    for (phase in 0 until 40) {
      val d = Truck()
      d.wobble = { at -> if (((at + phase * 1_000L) / 20_000) % 2 == 0L) 400.0 else -400.0 }
      // What Trip and Range go by: this drive's distance over its energy.
      val figures = mutableListOf<Double>()
      repeat(40) {
        d.run(30.0, mps, kw)
        val s = d.s()
        if (it > 10) figures += EffUnits.miPerKwh(s.distanceM / s.usedWh)
      }
      assertEquals("phase $phase", EnergySource.CHARGE_RATE, d.s().energySource)
      assertEquals("phase $phase", 1, d.s().chargeRateCheck.sign)
      assertTrue("phase $phase steady: $figures", figures.max() - figures.min() < 0.2)
      assertEquals("phase $phase", miPerKwh, figures.last(), 0.05)
      assertEquals("phase $phase", miPerKwh, eff(d.s()), 0.15)
    }
  }

  @Test
  fun aBatteryLevelOutageIsNotPricedAtTheLatestPower() {
    val d = Truck()
    d.run(120.0, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    d.levelOut = true
    d.run(300.0, mps, 10.0)
    d.run(5.0, mps, 150.0) // A hard pull just as the level comes back: not what the whole stretch drew.
    d.levelOut = false
    d.run(1.0, mps, 150.0)
    assertEquals("the level's own step covers the outage", d.used, d.s().usedWh, 100.0)
    d.run(60.0, mps, kw)
    assertEquals("and the signal carries on", EnergySource.CHARGE_RATE, d.s().energySource)
    assertEquals(d.used, d.s().usedWh, 100.0)
  }

  @Test
  fun aLevelRiseDoesNotSettleTheSign() {
    val d = Truck()
    d.run(90.0, mps, -30.0) // Net regen from the start: the level rises 0.75 kWh with the signal.
    assertEquals(0, d.s().chargeRateCheck.sign)
    assertEquals(EnergySource.BATTERY_LEVEL, d.s().energySource)
  }

  @Test
  fun aSettledSignDoesNotFlip() {
    val d = Truck()
    d.run(120.0, mps, kw)
    assertEquals(1, d.s().chargeRateCheck.sign)
    // The signal turns round and runs the other way for long enough to outweigh everything so far.
    d.rate = { k -> k * 1_000_000.0 }
    d.run(1_200.0, mps, kw)
    // It loses its trust and the level takes over; it never counts backwards.
    assertEquals(0, d.s().chargeRateCheck.sign)
    assertEquals(EnergySource.BATTERY_LEVEL, d.s().energySource)
  }

  @Test
  fun aRateWithTheOppositeSignCountsTheRightWayRound() {
    val d = Truck()
    d.rate = { k -> k * 1_000_000.0 } // + discharging: the opposite of what AOSP documents
    d.run(600.0, mps, kw)
    val s = d.s()
    assertEquals(-1, s.chargeRateCheck.sign)
    assertEquals(EnergySource.CHARGE_RATE, s.energySource)
    assertEquals(d.used, s.usedWh, d.used * 0.03)
    assertTrue("nothing counted as regen", s.energyInWh < 1.0)
  }

  @Test
  fun aDeadStuckOrWronglyScaledRateLeavesTheLevel() {
    val cases = listOf<Pair<String, (Double) -> Double?>>(
      "zero" to { _ -> 0.0 },
      "absent" to { _ -> null },
      "watts, not milliwatts" to { k -> -k * 1_000.0 },
      "stuck on a charge" to { _ -> 11_000_000.0 },
    )
    for ((name, rate) in cases) {
      val d = Truck()
      d.rate = rate
      d.run(600.0, mps, kw)
      val s = d.s()
      assertEquals(name, EnergySource.BATTERY_LEVEL, s.energySource)
      assertEquals(name, s.levelUsedWh, s.usedWh, 1.0)
      assertEquals(name, 0, s.chargeRateCheck.sign)
    }
  }

  @Test
  fun aRateThatStopsChangingIsDropped() {
    val d = Truck()
    d.run(300.0, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    d.rate = { _ -> -kw * 1_000_000.0 }
    d.rateAge = 100_000L
    d.run(70.0, mps, 80.0)
    assertEquals(EnergySource.BATTERY_LEVEL, d.s().energySource)
  }

  @Test
  fun theMxPackComesBeforeTheRateAndHandsBackWhenItGoes() {
    val d = Truck()
    d.rate = { k -> -k * 1_000_000.0 * 1.2 }
    d.pack = { it }
    d.run(600.0, mps, kw)
    assertEquals(EnergySource.MX_PACK, d.s().energySource)
    assertEquals(1, d.s().mxPackCheck.sign)
    assertEquals(d.used, d.s().usedWh, d.used * 0.03)
    // Disconnected: the rate takes over within a poll gap, and nothing jumps.
    val before = d.s().usedWh
    d.pack = null
    d.run(120.0, mps, kw)
    assertEquals(EnergySource.CHARGE_RATE, d.s().energySource)
    assertEquals(before + kw * 1000 * 120 / 3600, d.s().usedWh, 400.0)
  }

  @Test
  fun aSlowPollCycleStillIntegrates() {
    // A cycle that reads the cell groups takes seconds: the pack readings come in every 3 s.
    val d = Truck()
    d.levelGain = 1.1
    var n = 0
    val inner = { k: Double -> k }
    d.run(2.0, mps, kw)
    repeat(600) {
      n += 1
      if (n % 6 == 0) d.tracker.addPack(d.t, inner(kw))
      d.run(0.5, mps, kw)
    }
    assertEquals(EnergySource.MX_PACK, d.s().energySource)
    assertEquals(d.used, d.s().usedWh, d.used * 0.05)
  }

  @Test
  fun anUnreadStretchOfThePackIsNotEnergy() {
    val d = Truck()
    d.tracker.addPack(d.t, 500.0)
    d.t += SessionTracker.PACK_GAP_MS + 1
    d.tracker.addPack(d.t, 500.0)
    d.run(1.0, mps, 0.0)
    assertEquals(0.0, d.s().usedWh, 1.0)
  }

  @Test
  fun regenIsSplitOutAndNetted() {
    val d = Truck()
    d.run(300.0, mps, kw)
    d.run(60.0, 15.0, -30.0)
    val s = d.s()
    assertEquals(EnergySource.CHARGE_RATE, s.energySource)
    assertEquals(30.0 * 1000 * 60 / 3600, s.energyInWh, 30.0)
    assertEquals(d.used, s.usedWh, d.used * 0.03)
    assertEquals(s.usedWh, s.levelUsedWh + (s.usedWh - s.levelUsedWh), 1e-9)
  }

  @Test
  fun gapsAndChargesAreNotCounted() {
    val d = Truck()
    d.run(300.0, mps, kw)
    val before = d.s().usedWh
    d.hole(60.0, kw)
    d.run(60.0, mps, kw)
    assertEquals(before + kw * 1000 * 60 / 3600, d.s().usedWh, 60.0)
    assertEquals(1, d.s().gaps)
    val beforeCharge = d.s().usedWh
    d.run(5.0, 0.0, 0.0)
    d.plug = true
    d.run(120.0, 0.0, -100.0)
    assertEquals(beforeCharge, d.s().usedWh, 30.0)
    assertTrue(d.s().charging)
  }

  @Test
  fun aParkedSessionStaysOnTheLevelUntilItHasMoved() {
    val d = Truck()
    d.run(120.0, 0.0, 0.4)
    assertEquals(EnergySource.BATTERY_LEVEL, d.s().energySource)
    assertEquals(0, d.s().chargeRateCheck.sign)
  }

  @Test
  fun aBadRateReadingIsIgnored() {
    val d = Truck()
    d.run(300.0, mps, kw)
    val good = d.rate
    d.rate = { _ -> 1e15 }
    d.run(2.0, mps, kw)
    d.rate = good
    d.run(30.0, mps, kw)
    assertEquals(d.used, d.s().usedWh, d.used * 0.03)
  }

  @Test
  fun aResetForgetsTheVerdicts() {
    val d = Truck()
    d.run(300.0, mps, kw)
    d.tracker.reset()
    val s = d.s()
    assertEquals(EnergySource.BATTERY_LEVEL, s.energySource)
    assertEquals(SignalVerdict(), s.chargeRateCheck)
    assertEquals(0.0, s.energyOutWh, 0.0)
  }
}
