package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

/**
 * The session's energy while the MX+ reads the truck's own count (the owner, 2026-11-17): the count's change, not
 * the level's, which drifts; the level again when the count stops arriving.
 */
class EnergyCounterTest {
  private var t = 1_000_000L
  private var level = 150_000.0
  private var real = 0.0
  private var counterOn = true
  private var counterBase = 40_000.0
  private val tracker = SessionTracker(warmupMs = 0) { t }

  /** A reading a second at 40 kW. The level says 1.3 times what flowed; the count moves in 0.01 kWh and is read every 10 s. */
  private fun run(seconds: Int) = repeat(seconds) { i ->
    t += 1000
    val wh = 40_000.0 / 3600.0
    real += wh
    level -= wh * 1.3
    if (counterOn && i % 10 == 0) tracker.addCounter(t, floor((counterBase + real) / 10.0) * 10.0)
    tracker.add(VehicleSample(atMs = t, speedMps = 25.0, levelWh = level))
  }

  @Test
  fun theTrucksCountIsTheEnergyWhileItArrives() {
    run(1200)
    val s = tracker.snapshot()
    assertEquals(EnergySource.MX_COUNTER, s.energySource)
    assertEquals(real, s.usedWh, 300.0)
    assertEquals(real * 1.3, s.levelUsedWh, 50.0)
    // The last 15 minutes and the mi/kWh go by the same figure.
    assertEquals(real * 0.75, s.recentUsedWh!!, 300.0)
    assertEquals(s.distanceM / real, s.plainEfficiency.driveMPerWh!!, 0.05)
  }

  @Test
  fun theLevelTakesOverWhenTheCountStops() {
    run(600)
    val counted = real
    counterOn = false
    run(600)
    val s = tracker.snapshot()
    assertEquals(EnergySource.BATTERY_LEVEL, s.energySource)
    // Nothing lost and nothing counted twice across the hand-over.
    assertEquals(counted + (real - counted) * 1.3, s.usedWh, 300.0)
    // And back: the count again from its first reading after the gap, not across it.
    counterOn = true
    val before = real
    run(600)
    assertEquals(EnergySource.MX_COUNTER, tracker.snapshot().energySource)
    assertEquals(counted + (before - counted) * 1.3 + (real - before), tracker.snapshot().usedWh, 500.0)
  }

  @Test
  fun theTruckStartingItsCountOverIsNotEnergy() {
    run(600)
    counterBase = -real
    run(600)
    assertEquals(real, tracker.snapshot().usedWh, 500.0)
  }
}
