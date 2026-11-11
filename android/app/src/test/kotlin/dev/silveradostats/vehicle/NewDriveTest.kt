package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a new drive: everything starts from zero there, and nowhere else. */
class NewDriveTest {
  private val mps = 25.0
  private val kw = 40.0

  private class Truck {
    var t = 1_000_000L
    var level = 150_000.0
    val tracker = SessionTracker { t }
    var plug = false
    var tick = 0L

    /** [gear] is read at 1 Hz like the rest of the slow set; speed at 2 Hz. */
    fun run(seconds: Double, mps: Double, kw: Double, gear: Int?) {
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
            gear = if (full) gear else null,
            plugConnected = if (full) plug else null,
            chargeState = if (full) (if (plug) ChargeState.CHARGING else ChargeState.NOT_CHARGING) else null,
          ),
        )
      }
    }

    /** Time passes without any sample: the truck was off, or another app was in front. */
    fun hole(seconds: Double) {
      t += (seconds * 1000).toLong()
    }

    fun s() = tracker.snapshot()
  }

  private fun Truck.drive(seconds: Double) = run(seconds, 25.0, 40.0, Gear.DRIVE)

  private fun Truck.park(seconds: Double) = run(seconds, 0.0, 1.0, Gear.PARK)

  private val tenMinutesM = mps * 600.0
  private val tenMinutesWh = kw * 1000.0 * 600.0 / 3600.0

  private fun assertFresh(d: Truck, afterSeconds: Double) {
    val s = d.s()
    assertEquals(mps * afterSeconds, s.distanceM, 30.0)
    assertEquals(kw * 1000.0 * afterSeconds / 3600.0, s.usedWh, 30.0)
    assertEquals(0, s.gaps)
    assertTrue("active ${s.activeMs}", s.activeMs <= (afterSeconds * 1000).toLong())
  }

  private fun assertSameDrive(d: Truck, atLeastM: Double = tenMinutesM) {
    assertTrue("distance ${d.s().distanceM}", d.s().distanceM >= atLeastM - 30.0)
    assertTrue("used ${d.s().usedWh}", d.s().usedWh >= tenMinutesWh - 30.0)
  }

  @Test
  fun theFirstTimeOutOfParkStartsTheDrive() {
    val d = Truck()
    d.run(120.0, 0.0, 6.0, Gear.PARK) // Warming the cabin: 0.2 kWh that is not part of the drive.
    assertEquals(200.0, d.s().usedWh, 10.0)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun appOpenedAlreadyDrivingStartsThereToo() {
    val d = Truck()
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun theFiguresStayUntilTheTruckIsDrivenAgain() {
    val d = Truck()
    d.drive(600.0)
    d.park(600.0)
    // A finished drive stays readable however long it stands.
    assertSameDrive(d)
    // Shifting out of Park is enough: the drive starts before the truck moves.
    d.run(2.0, 0.0, 0.0, Gear.DRIVE)
    assertEquals(0.0, d.s().distanceM, 0.0)
    assertEquals(0.0, d.s().usedWh, 1.0)
    assertNull(d.s().rangeEstimate)
    d.drive(10.0)
    assertEquals(mps * 10.0, d.s().distanceM, 30.0)
    assertEquals(kw * 1000.0 * 10.0 / 3600.0, d.s().usedWh, 30.0)
  }

  @Test
  fun aShortStopInParkIsTheSameDrive() {
    val d = Truck()
    d.drive(600.0)
    d.park(240.0)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
  }

  @Test
  fun exactlyTheBreakIsANewDrive() {
    val d = Truck()
    d.drive(600.0)
    d.park(SessionTracker.DRIVE_BREAK_MS / 1000.0)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun switchedOffForAWhileIsANewDrive() {
    val d = Truck()
    d.drive(600.0)
    d.park(5.0)
    d.hole(6 * 60.0)
    d.park(5.0)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun switchedOffBrieflyIsTheSameDrive() {
    val d = Truck()
    d.drive(600.0)
    d.park(5.0)
    d.hole(2 * 60.0)
    d.park(2.0)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
    assertEquals("one pause on record", 1, d.s().gaps)
  }

  @Test
  fun aLongWaitInDriveIsTheSameDrive() {
    val d = Truck()
    d.drive(600.0)
    d.run(900.0, 0.0, 1.0, Gear.DRIVE)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
  }

  @Test
  fun aDriveGoesOnBehindAnotherApp() {
    val d = Truck()
    d.drive(600.0)
    d.hole(20 * 60.0)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
    assertEquals(1, d.s().gaps)
  }

  @Test
  fun backFromAnotherAppAtARedLightIsTheSameDrive() {
    val d = Truck()
    d.drive(600.0)
    d.hole(20 * 60.0)
    // The first reading back is speed-only, so the gear on record is from before the gap.
    if (d.tick % 2 == 0L) d.tick += 1
    d.run(30.0, 0.0, 1.0, Gear.DRIVE)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
  }

  @Test
  fun backFromAnotherAppInParkThenDrivingIsANewDrive() {
    val d = Truck()
    d.drive(600.0)
    d.hole(20 * 60.0)
    // A speed-only reading first: the stale Drive on record must not count as still being driven.
    if (d.tick % 2 == 0L) d.tick += 1
    d.park(5.0)
    assertSameDrive(d)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun aChargeOfAnyLengthIsFollowedByANewDrive() {
    val d = Truck()
    d.drive(600.0)
    d.park(5.0)
    d.plug = true
    d.run(60.0, 0.0, -100.0, Gear.PARK)
    assertTrue(d.s().charging)
    d.plug = false
    d.park(5.0)
    assertFalse(d.s().charging)
    assertSameDrive(d)
    d.drive(10.0)
    assertFresh(d, 10.0)
    assertEquals("the charge belongs to the last drive", 0.0, d.s().chargedWh, 0.0)
  }

  @Test
  fun aTruckThatOffersNoGearGoesByStandingStill() {
    val d = Truck()
    d.run(600.0, mps, kw, null)
    d.run(240.0, 0.0, 1.0, null)
    d.run(10.0, mps, kw, null)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
    d.run(SessionTracker.DRIVE_BREAK_MS / 1000.0, 0.0, 1.0, null)
    d.run(10.0, mps, kw, null)
    assertFresh(d, 10.0)
  }

  /** GEAR_UNKNOWN: the property has no answer, which says nothing about the gear. */
  @Test
  fun anUnknownGearIsNotDriving() {
    val d = Truck()
    d.drive(600.0)
    d.park(600.0)
    d.run(10.0, 0.0, 0.0, DrivingPolicy.GEAR_UNKNOWN)
    assertSameDrive(d)
    assertTrue("the finished drive is still there: ${d.s().samples}", d.s().samples > 1_000)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }

  @Test
  fun anUnknownGearDoesNotEndAWaitInDrive() {
    val d = Truck()
    d.drive(600.0)
    d.run(300.0, 0.0, 1.0, Gear.DRIVE)
    d.run(300.0, 0.0, 1.0, DrivingPolicy.GEAR_UNKNOWN)
    d.drive(10.0)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
  }

  @Test
  fun aTruckThatOnlyEverSaysUnknownGoesByStandingStill() {
    val g = DrivingPolicy.GEAR_UNKNOWN
    val d = Truck()
    d.run(600.0, mps, kw, g)
    d.run(240.0, 0.0, 1.0, g)
    d.run(10.0, mps, kw, g)
    assertSameDrive(d, tenMinutesM + mps * 10.0)
    d.run(SessionTracker.DRIVE_BREAK_MS / 1000.0, 0.0, 1.0, g)
    d.run(10.0, mps, kw, g)
    assertFresh(d, 10.0)
  }

  @Test
  fun theBatteryFiguresCarryOverAcrossANewDrive() {
    val d = Truck()
    d.drive(600.0)
    val level = d.s().levelWh
    d.park(SessionTracker.DRIVE_BREAK_MS / 1000.0)
    d.run(0.5, 0.0, 0.0, Gear.DRIVE)
    // The first reading of a new drive may have no level: the last one stays on screen.
    assertEquals(level!!, d.s().levelWh!!, 200.0)
  }

  @Test
  fun aResetStillStartsAgainAtOnce() {
    val d = Truck()
    d.drive(600.0)
    d.tracker.reset()
    assertEquals(0.0, d.s().distanceM, 0.0)
    d.drive(10.0)
    assertFresh(d, 10.0)
  }
}
