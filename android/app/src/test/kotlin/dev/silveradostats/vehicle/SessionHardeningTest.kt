package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * SessionTracker against bad, stale and awkward data, at the production cadence: speed at 2 Hz, the
 * rest at 1 Hz (VehicleSession). Includes the scenarios found by adversarial review.
 */
class SessionHardeningTest {

  private class Truck(var t: Long = 1_000_000L, var level: Double = 150_000.0) {
    var now = t
    val tracker = SessionTracker(warmupMs = 0) { now }
    var quantWh = 10.0
    var holdMs = 0L
    private var held: Double? = null
    private var heldAt = Long.MIN_VALUE
    var rate: (Double) -> Double? = { kw -> -kw * 1_000_000.0 }
    var rateAge: () -> Long? = { null }
    var plug: Boolean? = false
    var state: ChargeState? = ChargeState.NOT_CHARGING
    var levelOverride: Double? = null
    var speedOverride: Double? = null
    private var tick = 0L
    var trueM = 0.0
    var trueWh = 0.0

    private fun reported(): Double {
      levelOverride?.let { return it }
      val q = floor(level / quantWh) * quantWh
      if (holdMs <= 0) return q
      if (held == null || t - heldAt >= holdMs) {
        held = q
        heldAt = t
      }
      return held!!
    }

    fun run(seconds: Double, mps: Double, kw: Double, each: (SessionState) -> Unit = {}) {
      repeat((seconds * 2).toInt()) {
        t += 500
        now = t
        level -= kw * 1000.0 * 0.5 / 3600.0
        trueM += mps * 0.5
        trueWh += kw * 1000.0 * 0.5 / 3600.0
        val full = tick % 2 == 0L
        tick += 1
        tracker.add(
          VehicleSample(
            atMs = t,
            speedMps = speedOverride ?: mps,
            levelWh = if (full) reported() else null,
            chargeRateMw = if (full) rate(kw) else null,
            chargeRateAgeMs = if (full) rateAge() else null,
            plugConnected = if (full) plug else null,
            chargeState = if (full) state else null,
            capacityWh = if (full) 205_080.0 else null,
            rangeM = if (full) level * 3.05 else null,
            gear = if (full) (if (mps > 0) Gear.DRIVE else Gear.PARK) else null,
          ),
        )
        each(tracker.snapshot())
      }
    }

    /** The process does not run for [ms] (frozen or asleep); the truck may change meanwhile. */
    fun freeze(ms: Long) {
      t += ms
    }

    fun s() = tracker.snapshot()
  }

  private fun kwFor(mps: Double, miPerKwh: Double) = mps / EffUnits.fromMiPerKwh(miPerKwh) * 3.6

  private fun mi(m: Double) = m / EffUnits.METERS_PER_MILE

  private fun eff(s: SessionState) = EffUnits.miPerKwh(s.efficiency.mPerWh!!)

  // --- Charging and gaps ------------------------------------------------------------------------

  @Test
  fun aChargeDuringAFreezeIsNotEnergyUsed() {
    val d = Truck()
    val kw = kwFor(25.0, 2.3)
    d.run(300.0, 25.0, kw)
    d.run(20.0, 0.0, 0.5)
    val used = d.s().usedWh
    d.freeze(3_600_000)
    d.level += 50_000.0 // charged and unplugged while frozen
    d.run(10.0, 0.0, 0.5)
    var s = d.s()
    assertEquals(used, s.usedWh, 10.0)
    assertFalse("no charger at the thaw", s.charging)
    assertEquals(0.0, s.chargedWh, 0.0)
    assertEquals(1, s.gaps)
    d.run(120.0, 25.0, kw)
    s = d.s()
    assertEquals(used + kw * 1000 * 120 / 3600, s.usedWh, 20.0)
    assertEquals(2.3, eff(s), 0.03)
  }

  @Test
  fun aChargeDuringAFreezeWhenTheFirstReadingsHaveNoLevel() {
    var now = 1_000L
    val t = SessionTracker { now }
    fun add(ms: Long, v: Double, level: Double?) {
      now += ms
      t.add(VehicleSample(now, speedMps = v, levelWh = level))
    }
    add(0, 0.0, 100_000.0)
    add(500, 0.0, null)
    add(60_000, 1.0, null) // thaws slowing down: speed only (a 2 Hz tick)
    add(500, 0.0, 130_000.0) // stopped, and the level is 30 kWh higher than before the freeze
    add(500, 0.0, null)
    val s = t.snapshot()
    assertFalse(s.charging)
    assertEquals(0.0, s.usedWh, 0.0)
  }

  @Test
  fun aLowerLevelAfterAFreezeThenACharge() {
    val d = Truck()
    d.plug = null
    d.state = null
    val kw = kwFor(25.0, 2.3)
    d.run(300.0, 25.0, kw)
    d.run(20.0, 0.0, 0.5)
    val used = d.s().usedWh
    d.freeze(600_000)
    d.level -= 10_000.0 // driven while frozen, parked again
    d.run(10.0, 0.0, 0.5)
    d.run(1800.0, 0.0, -11.0) // Level 2, no plug data: 5.5 kWh
    val s = d.s()
    assertTrue(s.charging)
    // Measured by the rate while the rise is still being recognised: the level's 10 Wh steps either way.
    assertEquals(used, s.usedWh, 20.0)
    assertEquals(5_500.0, s.chargedWh, 30.0)
  }

  @Test
  fun lateRegenAfterAHardStopIsNotCharging() {
    // The level is held for 17 s, so a stop's regen can land on the level after the stop.
    val d = Truck()
    d.holdMs = 17_000
    d.quantWh = 1.0
    var chargingSeen = false
    repeat(10) {
      d.run(90.0, 30.0, 45.0)
      for (i in 0 until 12) d.run(1.0, 30.0 - i * 2.5, -70.0)
      d.run(40.0, 0.0, 0.8) { s -> chargingSeen = chargingSeen || s.charging }
    }
    d.run(10.0, 30.0, 45.0)
    val s = d.s()
    assertFalse(chargingSeen)
    assertEquals(0.0, s.chargedWh, 0.0)
    val truth = EffUnits.miPerKwh(d.trueM / d.trueWh)
    assertEquals(truth, eff(s), truth * 0.02)
  }

  @Test
  fun aStalePlugOrChargeStateDoesNotDiscardADrive() {
    val kw = kwFor(25.0, 2.3)
    for (stale in listOf<Truck.() -> Unit>({ plug = true }, { state = ChargeState.CHARGING })) {
      val d = Truck()
      d.stale()
      d.run(1200.0, 25.0, kw)
      val s = d.s()
      assertFalse(s.charging)
      assertEquals(2.3, eff(s), 0.03)
      assertEquals(kw * 1000 * 1200 / 3600, s.usedWh, 30.0)
      // Stale at the next stop too: a held value is not a charger.
      d.run(60.0, 0.0, 0.5)
      assertFalse(d.s().charging)
    }
    // CHARGING, then the property stops answering: the last value is all there is, and it is stale.
    val r = Truck()
    r.state = ChargeState.CHARGING
    r.run(10.0, 0.0, -10.0)
    assertTrue(r.s().charging)
    r.state = null
    r.plug = null
    r.run(1200.0, 25.0, kw)
    assertFalse(r.s().charging)
    assertEquals(2.3, eff(r.s()), 0.03)
  }

  @Test
  fun aRealChargeStillCountsAfterAStaleOne() {
    val d = Truck()
    d.plug = true
    val kw = kwFor(25.0, 2.3)
    d.run(120.0, 25.0, kw)
    d.run(20.0, 0.0, 0.5)
    assertFalse(d.s().charging)
    d.plug = false
    d.run(2.0, 0.0, 0.5)
    d.plug = true
    d.state = ChargeState.CHARGING
    d.run(60.0, 0.0, -60.0)
    assertTrue(d.s().charging)
    assertEquals(1_000.0, d.s().chargedWh, 30.0)
  }

  // --- Bad readings -----------------------------------------------------------------------------

  @Test
  fun oneBadLevelReadingChangesNothing() {
    val kw = kwFor(25.0, 2.3)
    // At a stop: without the check this stop would be read as a charge from 0.
    val stop = Truck()
    stop.run(300.0, 25.0, kw)
    stop.run(10.0, 0.0, 0.5)
    val usedBefore = stop.s().usedWh
    stop.levelOverride = 0.0
    stop.run(1.0, 0.0, 0.5)
    stop.levelOverride = null
    stop.run(5.0, 0.0, 0.5)
    assertEquals(usedBefore, stop.s().usedWh, 5.0)
    assertFalse(stop.s().charging)
    stop.run(300.0, 25.0, kw)
    assertEquals(2.3, eff(stop.s()), 0.03)

    // Moving: the figure and the range never see it.
    val move = Truck()
    move.run(300.0, 25.0, kw)
    move.levelOverride = 0.0
    move.run(1.0, 25.0, kw) { s ->
      assertEquals(2.3, eff(s), 0.05)
      assertTrue(s.levelWh!! > 100_000)
    }
    move.levelOverride = null
    move.run(60.0, 25.0, kw)
    assertEquals(2.3, eff(move.s()), 0.03)
  }

  @Test
  fun aLevelJumpThatStaysIsANewStartNotEnergy() {
    val d = Truck()
    val kw = kwFor(25.0, 2.3)
    d.run(300.0, 25.0, kw)
    val used = d.s().usedWh
    d.level -= 20_000.0 // no drive does this in a second, but the property insists
    d.run(5.0, 25.0, kw)
    val s = d.s()
    assertEquals("the level follows after ${SessionTracker.LEVEL_CONFIRM} readings", d.level, s.levelWh!!, 50.0)
    assertEquals(used + kw * 1000 * 5 / 3600, s.usedWh, 30.0)
    d.run(300.0, 25.0, kw)
    assertEquals(2.3, eff(d.s()), 0.03)

    // A junk first reading: the real level takes over, and nothing is counted for the difference.
    val first = Truck()
    first.levelOverride = 0.0
    first.run(1.0, 25.0, kw)
    first.levelOverride = null
    first.run(120.0, 25.0, kw)
    assertTrue(first.s().levelWh!! > 100_000)
    assertEquals(kw * 1000 * 120 / 3600, first.s().usedWh, 30.0)
  }

  @Test
  fun coarseAndHeldLevelsAreNotJumps() {
    // 1 % steps of a 205 kWh pack (2.05 kWh at once) at a modest power, and a level held for 90 s.
    for ((quant, hold) in listOf(2_050.8 to 0L, 1.0 to 90_000L)) {
      // On the level alone, and with a rate that takes over once the level has moved far enough.
      for (rate in listOf<(Double) -> Double?>({ null }, { kw -> -kw * 1_000_000.0 })) {
        val d = Truck()
        d.rate = rate
        d.quantWh = quant
        d.holdMs = hold
        d.run(3600.0, 27.0, kwFor(27.0, 2.3))
        assertEquals("step $quant hold $hold", 2.3, eff(d.s()), 0.1)
        assertEquals("step $quant hold $hold", kwFor(27.0, 2.3) * 1000, d.s().usedWh, 2_200.0)
      }
    }
    // A fast charge with the level held for 60 s: the rises are real.
    val c = Truck()
    c.holdMs = 60_000
    c.state = ChargeState.CHARGING
    c.run(600.0, 0.0, -300.0)
    assertEquals(50_000.0, c.s().chargedWh, 5_100.0)
  }

  @Test(timeout = 20_000)
  fun badSpeedReadingsAreIgnored() {
    val kw = kwFor(25.0, 2.3)
    for (bad in listOf(655.35, 65_535.0, 1e10, Float.MAX_VALUE.toDouble())) {
      val d = Truck()
      d.run(300.0, 25.0, kw)
      val dist = d.s().distanceM
      d.speedOverride = bad
      d.run(0.5, 25.0, kw)
      d.speedOverride = null
      d.run(60.0, 25.0, kw)
      val s = d.s()
      assertEquals("speed $bad", dist + 25.0 * 60.5, s.distanceM, 30.0)
      assertEquals("speed $bad", 2.3, eff(s), 0.03)
    }
  }

  @Test
  fun aSpeedOutageIsLeftOutLikeAGap() {
    val d = Truck()
    val kw = kwFor(25.0, 2.3)
    d.run(300.0, 25.0, kw)
    val used = d.s().usedWh
    val dist = d.s().distanceM
    val active = d.s().activeMs
    // 5 min with no speed while the level keeps updating at the same power.
    var at = d.t
    repeat(600) {
      at += 500
      d.now = at
      d.level -= kw * 1000 * 0.5 / 3600
      d.tracker.add(VehicleSample(at, speedMps = null, levelWh = if (it % 2 == 0) floor(d.level / 10) * 10 else null))
    }
    d.t = at
    var s = d.s()
    assertNull("no stale speed on the tiles", s.speedMps)
    assertEquals(1, s.gaps)
    // The 5 s before an outage is declared have energy and no distance: under 2 % of a 4-mile figure, once.
    assertEquals(2.3, eff(s), 0.05)
    assertTrue(s.usedWh < used + kw * 1000 * 6 / 3600)
    assertEquals(dist, s.distanceM, 0.0)
    assertTrue(s.activeMs < active + 10_000)
    // Speed is back: measuring goes on from there, with the outage on the books.
    d.run(120.0, 25.0, kw)
    s = d.s()
    assertEquals(25.0, s.speedMps!!, 0.0)
    assertEquals(1, s.gaps)
    assertTrue("gap ms ${s.gapMs}", s.gapMs in 290_000L..301_000L)
    assertEquals(2.3, eff(s), 0.03)
    // A car that never offered speed is not an outage (energy is still counted).
    val none = SessionTracker { 0L }
    for (i in 1..30) none.add(VehicleSample(i * 1000L, levelWh = 100_000.0 - i * 100))
    assertEquals(0, none.snapshot().gaps)
    assertEquals(2_900.0, none.snapshot().usedWh, 1e-6)
  }

  // --- Early figures ----------------------------------------------------------------------------

  @Test
  fun firstFiguresAreMarkedEarlyAndProjectNoRange() {
    val t = SessionTracker { 0L }
    val drive = DemoDrive()
    var rel = 0L
    var earlySeen = false
    var settled = false
    while (rel <= 24 * 60_000L) {
      t.add(drive.sampleAt(rel, rel))
      val s = t.snapshot()
      val e = s.efficiency
      if (e.status == EffStatus.OK) {
        val short = e.basisM < SessionTracker.EARLY_M || e.basisWh < SessionTracker.EARLY_WH
        assertEquals(short, e.early)
        if (e.early) {
          earlySeen = true
          assertNull(s.rangeAtMeasuredM)
        } else {
          settled = true
          assertNotNull(s.rangeAtMeasuredM)
        }
      }
      rel += 500
    }
    assertTrue(earlySeen && settled)
  }

  @Test
  fun aDescentProjectsNoRangeAndNeverGoesBackToWarmingUp() {
    val d = Truck()
    d.quantWh = 1.0
    d.run(240.0, 25.0, 40.0) // 3.7 mi, 2.7 kWh: past the warm-up
    assertEquals(EffStatus.OK, d.s().efficiency.status)
    d.run(1800.0, 25.0, 1.0) // the long way down, almost no net energy
    val high = d.s()
    assertEquals(EffStatus.OK, high.efficiency.status)
    assertFalse(high.efficiency.early)
    assertTrue(EffUnits.miPerKwh(high.efficiency.mPerWh!!) > 6.0)
    assertNull("no 3000 mi range", high.rangeAtMeasuredM)

    // Net regen after the figure was OK: it says so, it does not start warming up again.
    val f = Truck()
    f.quantWh = 1.0
    f.run(120.0, 25.0, kwFor(25.0, 2.3))
    assertEquals(EffStatus.OK, f.s().efficiency.status)
    f.run(300.0, 25.0, -15.0) { s -> assertTrue(s.efficiency.status != EffStatus.WARMING_UP) }
    val s = f.s()
    assertEquals(EffStatus.NET_REGEN, s.efficiency.status)
    assertNull(s.efficiency.mPerWh)
    assertNull(s.rangeAtMeasuredM)
    assertEquals(1.0, s.efficiency.progress, 0.0)
    assertEquals(mi(s.distanceM), mi(s.efficiency.basisM), 0.05)
    // A new session starts cold.
    f.tracker.reset()
    assertEquals(EffStatus.WARMING_UP, f.s().efficiency.status)
  }

  @Test
  fun theWarmUpReportsWhatItReallyNeeds() {
    val d = Truck()
    d.rate = { null } // on the level alone
    d.quantWh = 1_000.0
    d.run(300.0, 25.0, kwFor(25.0, 2.3))
    val s = d.s()
    assertEquals(1_000.0, s.levelStepWh!!, 0.0)
    assertEquals(3_000.0, s.efficiency.needWh, 0.0)
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    assertEquals(SessionTracker.WARMUP_WH, SessionTracker { 0L }.snapshot().efficiency.needWh, 0.0)
  }

  @Test
  fun aLevelThatNeverChangesIsMeasuredAsSuch() {
    val d = Truck()
    d.levelOverride = 85_250.0
    d.run(600.0, 25.0, 40.0)
    var s = d.s()
    assertEquals(s.distanceM, s.levelUnchangedM, 1e-6)
    assertEquals(EffStatus.WARMING_UP, s.efficiency.status)
    // It moves: the watch restarts.
    d.levelOverride = 85_200.0
    d.run(1.0, 25.0, 40.0)
    s = d.s()
    assertTrue(s.levelUnchangedM < 50)
  }

  @Test
  fun theFigureEndsAtTheLatestLevelStep() {
    val d = Truck()
    d.quantWh = 500.0
    d.run(600.0, 25.0, kwFor(25.0, 2.3))
    val e = d.s().efficiency
    assertTrue(e.atM in (d.s().distanceM - 5_000)..d.s().distanceM)
    assertEquals(e.basisM / e.basisWh, e.mPerWh!!, 1e-9)
  }

  @Test
  fun aResetForgetsTheLevelItWasJudgingAgainst() {
    val d = Truck()
    d.run(60.0, 25.0, 30.0)
    d.tracker.reset()
    d.level = 60_000.0
    d.run(1.0, 25.0, 30.0)
    assertTrue(d.s().levelWh!! < 61_000)
  }
}
