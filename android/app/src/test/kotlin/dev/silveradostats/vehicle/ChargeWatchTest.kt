package dev.silveradostats.vehicle

import dev.silveradostats.log.LogCol
import dev.silveradostats.log.LogRow
import dev.silveradostats.log.LogRows
import dev.silveradostats.log.LogSource
import dev.silveradostats.log.LogTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last charge and what has been used since, from nothing but readings of the battery level. */
class ChargeWatchTest {
  private val capacity = 205_080.0

  /** Monotonic time: each reading ten minutes after the last unless a test says otherwise. */
  private var now = 1_000_000L

  private fun next(afterMs: Long) = (now + afterMs).also { now = it }

  private fun parked(level: Double, charging: Boolean = false, on: Boolean = true, awake: Boolean = true, afterMs: Long = 600_000L) =
    ChargeReading(level, capacity, charging, moving = false, on = on, atMs = next(afterMs), awake = awake)

  private fun driving(level: Double, afterMs: Long = 600_000L) =
    ChargeReading(level, capacity, charging = false, moving = true, on = true, atMs = next(afterMs))

  private fun watch(vararg readings: ChargeReading) = ChargeWatch().also { w -> readings.forEach(w::add) }

  private fun ChargeWatch.to() = state.charge?.toWh

  @Test
  fun aChargeNothingWatchedIsSeenAtTheNextReading() {
    val w = watch(parked(100_000.0), parked(180_000.0))
    assertEquals(180_000.0, w.to()!!, 0.0)
    assertEquals(87.77, w.state.charge!!.toPct!!, 0.01)
  }

  @Test
  fun theFirstReadingIsNeverACharge() {
    assertNull(watch(parked(180_000.0)).state.charge)
    assertNull(watch(parked(180_000.0, charging = true)).state.charge)
  }

  @Test
  fun aWatchedChargeFollowsTheLevelUpAndStopsWhenTheTruckMoves() {
    val w = watch(parked(100_000.0))
    w.add(parked(100_000.0, charging = true))
    w.add(parked(100_300.0, charging = true))
    assertNull("not yet past what late regen can be", w.state.charge)
    w.add(parked(100_600.0, charging = true))
    assertEquals(100_600.0, w.to()!!, 0.0)
    for (level in listOf(120_000.0, 150_000.0, 180_000.0)) w.add(parked(level, charging = true))
    assertEquals(180_000.0, w.to()!!, 0.0)
    w.add(parked(180_000.0))
    w.add(driving(179_500.0))
    assertFalse(w.state.inCharge)
    // Regen on the way down the first hill is not more charge.
    w.add(driving(179_800.0))
    assertEquals(180_000.0, w.to()!!, 0.0)
  }

  @Test
  fun whatIsUsedSinceIsWhatTheLevelHasFallen() {
    val c = LastCharge(186_400.0, 90.9)
    assertEquals(42_000.0, c.usedSinceWh(144_400.0)!!, 1e-6)
    assertEquals("never below zero, should the level wobble up", 0.0, c.usedSinceWh(186_500.0)!!, 0.0)
    assertNull(c.usedSinceWh(null))
  }

  @Test
  fun pluggingInWithoutChargingCountsForNothing() {
    val w = watch(parked(100_000.0), parked(180_000.0), parked(100_000.0)) // A charge, then a drive with no reading in between.
    repeat(5) { w.add(parked(100_000.0, charging = true)) }
    assertEquals(180_000.0, w.to()!!, 0.0)
  }

  @Test
  fun regenIsNotACharge() {
    val w = watch(driving(100_000.0))
    var level = 100_000.0
    repeat(4_000) {
      level += 2.0 // 8 kWh back on a long descent, reading by reading.
      w.add(driving(level))
    }
    assertNull(w.state.charge)
    // A hard stop's regen landing after the stop.
    w.add(parked(level + 370.0))
    assertNull(w.state.charge)
  }

  @Test
  fun aJumpWhileMovingHasToBeFarMoreThanADescentCouldBe() {
    assertNull("a descent behind Maps", watch(driving(100_000.0), driving(104_000.0)).state.charge)
    val w = watch(driving(100_000.0), driving(150_000.0))
    assertEquals("charged and back on the road before anything read it", 150_000.0, w.to()!!, 0.0)
    assertFalse(w.state.inCharge)
    assertNull("short of a charge, parked too", watch(parked(100_000.0), parked(104_500.0)).state.charge)
  }

  @Test
  fun aZeroFromACarServiceJustUpIsNoReading() {
    val w = watch(parked(150_000.0), parked(0.0), parked(150_000.0))
    assertNull(w.state.charge)
    assertEquals(150_000.0, w.state.seenWh!!, 0.0)
    assertNull(watch(parked(150_000.0), ChargeReading(null, capacity, false, false), ChargeReading(Double.NaN, capacity, false, false)).state.charge)
  }

  @Test
  fun aSmallerChargeLaterIsTheLastChargeNow() {
    val w = watch(parked(100_000.0), parked(180_000.0), driving(100_000.0), parked(100_000.0), parked(150_000.0))
    assertEquals(150_000.0, w.to()!!, 0.0)
  }

  /** "In a charge" from yesterday must not hide today's, driven to and read only afterwards. */
  @Test
  fun aChargeDrivenDownWithNoReadingIsOver() {
    val w = watch(parked(100_000.0), parked(180_000.0))
    assertTrue(w.state.inCharge)
    w.add(parked(100_000.0))
    assertFalse(w.state.inCharge)
    w.add(parked(100_100.0, charging = true))
    w.add(parked(100_700.0, charging = true))
    assertEquals(100_700.0, w.to()!!, 0.0)
  }

  /** Plugged in after a charge, with the climate on: used is what has gone, and no new charge per kWh drawn. */
  @Test
  fun aLevelFallingWhilePluggedInDoesNotWalkTheChargeDown() {
    val w = watch(parked(100_000.0), parked(180_000.0, charging = true))
    for (level in listOf(179_500.0, 178_900.0, 178_300.0, 177_000.0)) w.add(parked(level, charging = true))
    assertEquals(180_000.0, w.to()!!, 0.0)
    assertFalse(w.state.inCharge)
    // It tops up: that is a charge.
    w.add(parked(177_700.0, charging = true))
    assertEquals(177_700.0, w.to()!!, 0.0)
  }

  @Test
  fun itCarriesOnFromWhatWasSaved() {
    val before = watch(parked(100_000.0), parked(120_000.0, charging = true))
    val after = ChargeWatch(before.state)
    after.add(parked(180_000.0, charging = true))
    assertEquals(180_000.0, after.to()!!, 0.0)
    // And a charge the process slept through.
    val later = ChargeWatch(ChargeWatchState(LastCharge(180_000.0, 87.8), seenWh = 90_000.0))
    later.add(parked(170_000.0))
    assertEquals(170_000.0, later.to()!!, 0.0)
  }

  @Test
  fun thePercentageIsWorkedOutLikeTheRingsEvenWithoutACapacityNow() {
    val w = watch(parked(100_000.0), parked(180_000.0))
    w.add(driving(100_000.0))
    w.add(ChargeReading(160_000.0, null, false, false))
    assertEquals(160_000.0, w.to()!!, 0.0)
    assertEquals(160_000.0 / capacity * 100.0, w.state.charge!!.toPct!!, 1e-6)
    val none = ChargeWatch()
    none.add(ChargeReading(100_000.0, null, false, false))
    none.add(ChargeReading(150_000.0, null, false, false))
    assertEquals(150_000.0, none.to()!!, 0.0)
    assertNull("unknown, not made up", none.state.charge!!.toPct)
  }

  @Test
  fun theCarsReadingsSayChargingOnlyAtAStandstill() {
    val plugged = ChargeReading.fromCar(100_000.0, capacity, plugged = true, chargeState = null, speedMps = 0.0)
    assertTrue(plugged.charging)
    assertFalse(plugged.moving)
    assertTrue(ChargeReading.fromCar(100_000.0, capacity, null, ChargeState.CHARGING, null).charging)
    assertFalse(ChargeReading.fromCar(100_000.0, capacity, false, ChargeState.NOT_CHARGING, 0.0).charging)
    val stale = ChargeReading.fromCar(100_000.0, capacity, plugged = true, chargeState = ChargeState.CHARGING, speedMps = 20.0)
    assertFalse("a held value: a truck does not drive on a cable", stale.charging)
    assertTrue(stale.moving)
    assertTrue("in reverse too", ChargeReading.fromCar(100_000.0, capacity, null, null, -3.0).moving)
  }

  @Test
  fun theSessionsReadingsSayWhatTheSessionWorkedOut() {
    val s = SessionState(levelWh = 100_000.0, capacityWh = capacity, chargeSignal = true, speedMps = 0.0)
    assertEquals(ChargeReading(100_000.0, capacity, charging = true, moving = false, tripM = 0.0), ChargeReading.fromSession(s))
    assertTrue(ChargeReading.fromSession(s.copy(chargeSignal = false, speedMps = 20.0)).moving)
    // A level that rose, which the session calls charging as well, is not the plug or charge state saying so.
    assertFalse(ChargeReading.fromSession(s.copy(chargeSignal = false, charging = true)).charging)
  }

  /** The first run goes through the log, so that the figure need not wait for the next charge. */
  @Test
  fun aChargeAlreadyInTheLogIsFound() {
    fun row(at: Long, kwh: Double, kmh: Double = 0.0, plugged: Boolean = false) = LogRow(
      at,
      0,
      LogSource.CAR,
      LogTrigger.JOB,
      mapOf(LogCol.CAR_ENERGY_KWH to kwh, LogCol.CAR_CAPACITY_KWH to 205.08, LogCol.CAR_SPEED_KMH to kmh, LogCol.CAR_PLUGGED to if (plugged) 1.0 else 0.0),
    )
    val log = listOf(row(1, 120.0, 70.0), row(2, 95.0), row(3, 95.0, plugged = true), row(4, 130.0, plugged = true), row(5, 186.4, plugged = true), row(6, 186.4), row(7, 170.0, 100.0))
    val w = ChargeWatch()
    log.forEach { w.add(LogRows.chargeReading(it)) }
    assertEquals(186_400.0, w.to()!!, 1e-6)
    assertEquals(90.89, w.state.charge!!.toPct!!, 0.01)
    assertFalse(w.state.inCharge)
    assertEquals(170_000.0, w.state.seenWh!!, 1e-6)
    // Rows an hour apart cannot say what was used with the truck on: nothing is counted across them, and the level says the rest.
    assertEquals(0.0, w.state.charge!!.usedWh!!, 1e-6)
    w.usedFromLevels()
    assertEquals(16_400.0, w.state.charge!!.usedWh!!, 1e-6)
    // What the rows are read for is what the reading uses.
    assertTrue(LogRows.CHARGE_COLS.containsAll(listOf(LogCol.CAR_ENERGY_KWH, LogCol.CAR_CAPACITY_KWH, LogCol.CAR_PLUGGED, LogCol.CAR_CHARGE_STATE, LogCol.CAR_SPEED_KMH)))
    // Rows from before a column existed have no value in it.
    assertNotNull(LogRows.chargeReading(LogRow(1, 0, LogSource.CAR, LogTrigger.JOB, mapOf(LogCol.CAR_ENERGY_KWH to 95.0))).levelWh)
  }

  @Test
  fun itIsSavedWhenTheFiguresHaveMovedNotAtEveryReading() {
    val start = ChargeWatchState(LastCharge(180_000.0, 87.8), seenWh = 150_000.0)
    assertTrue("nothing saved yet", ChargeWatch.saveDue(null, start))
    assertFalse("same", ChargeWatch.saveDue(start, start))
    assertFalse("a little lower", ChargeWatch.saveDue(start, start.copy(seenWh = 149_950.0)))
    assertTrue("far enough", ChargeWatch.saveDue(start, start.copy(seenWh = 149_900.0)))
    assertFalse("a little higher", ChargeWatch.saveDue(start, start.copy(charge = LastCharge(180_050.0, 87.8))))
    assertTrue(ChargeWatch.saveDue(start, start.copy(charge = LastCharge(180_100.0, 87.9))))
    assertTrue("what is used", ChargeWatch.saveDue(start, start.copy(charge = LastCharge(180_000.0, 87.8, 100.0))))
    assertTrue("the charge began", ChargeWatch.saveDue(start, start.copy(inCharge = true)))
    assertTrue("the truck went off: what was used by then must not be lost", ChargeWatch.saveDue(start.copy(on = true), start))
    assertTrue("the first charge", ChargeWatch.saveDue(ChargeWatchState(seenWh = 150_000.0), start))
  }

  // --- What is used since: with the truck on only -------------------------------------------------

  private fun ChargeWatch.used() = state.charge?.usedWh ?: error("no running total")

  /** Parked, to [level] with the truck on and up: a charge, and the settling time over. */
  private fun charged(level: Double): ChargeWatch {
    val w = watch(parked(level - 40_000.0), parked(level, charging = true))
    w.add(parked(level, afterMs = 200_000L))
    return w
  }

  /**
   * The owner's own case: 104 kWh when the charge ends, 102 or 106 the next time the truck is on. Neither moves
   * what it reached, neither is usage, and neither is a charge.
   */
  @Test
  fun theLevelMovingWhileTheTruckIsOffIsNotUsageNorACharge() {
    for (woke in listOf(102_000.0, 106_000.0)) {
      val w = charged(104_000.0)
      assertEquals(104_000.0, w.to()!!, 0.0)
      w.add(parked(104_000.0, on = false))
      w.add(parked(woke, awake = false))
      assertEquals(104_000.0, w.to()!!, 0.0)
      assertEquals(0.0, w.used(), 0.0)
      // From what it settled at, what the truck then uses is all that counts.
      w.add(driving(woke - 10_000.0))
      assertEquals(10_000.0, w.used(), 1e-6)
      assertEquals(104_000.0, w.to()!!, 0.0)
    }
  }

  @Test
  fun itAddsUpOverDrivesAndLeavesOutWhatHappensInBetween() {
    val w = charged(104_000.0)
    w.add(driving(100_000.0))
    assertEquals(4_000.0, w.used(), 1e-6)
    // Off for the night, with the head unit up for a while: parked drain and the level settling.
    w.add(parked(99_500.0, on = false))
    w.add(parked(98_000.0, on = false))
    assertEquals(4_000.0, w.used(), 1e-6)
    // Next morning, with the head unit having slept.
    w.add(parked(99_000.0, awake = false))
    w.add(driving(94_000.0))
    assertEquals(9_000.0, w.used(), 1e-6)
    assertEquals(104_000.0, w.to()!!, 0.0)
    // So it need not agree with the level any more: 104 - 94 is 10, but 1 of it was not used.
    assertEquals(10_000.0, w.state.charge!!.usedSinceWh(94_000.0)!!, 1e-6)
  }

  @Test
  fun regenComesOff() {
    val w = charged(104_000.0)
    w.add(driving(100_000.0))
    w.add(driving(100_800.0))
    assertEquals(3_200.0, w.used(), 1e-6)
  }

  /** On is on: climate in Park counts, once the level has had its moment to settle. */
  @Test
  fun parkedWithTheTruckOnCounts() {
    val w = charged(104_000.0)
    w.add(parked(103_000.0))
    assertEquals(1_000.0, w.used(), 1e-6)
  }

  @Test
  fun theFirstMomentsAfterComingOnDoNotCountUnlessItMoves() {
    val w = charged(104_000.0)
    w.add(parked(104_000.0, on = false))
    w.add(parked(104_000.0, afterMs = 1_000L))
    w.add(parked(103_400.0, afterMs = 30_000L))
    w.add(parked(103_000.0, afterMs = 30_000L))
    assertEquals("still settling", 0.0, w.used(), 1e-6)
    w.add(parked(102_900.0, afterMs = 100_000L))
    assertEquals(100.0, w.used(), 1e-6)
    // Moving ends it at once.
    val x = charged(104_000.0)
    x.add(parked(104_000.0, on = false))
    x.add(parked(104_000.0, afterMs = 1_000L))
    x.add(driving(103_900.0, afterMs = 1_000L))
    assertEquals(100.0, x.used(), 1e-6)
  }

  /** With the truck on the battery's own figure can be corrected as well, which is no more usage than a settling is. */
  @Test
  fun aStepNoTruckCouldUseIsTheFigureBeingCorrected() {
    val w = charged(104_000.0)
    w.add(driving(103_000.0, afterMs = 60_000L))
    assertEquals(1_000.0, w.used(), 1e-6)
    w.add(driving(101_000.0, afterMs = 1_000L))
    assertEquals(1_000.0, w.used(), 1e-6)
    w.add(driving(103_000.0, afterMs = 1_000L))
    assertEquals(1_000.0, w.used(), 1e-6)
    // And goes on from there.
    w.add(driving(102_900.0, afterMs = 1_000L))
    assertEquals(1_100.0, w.used(), 1e-6)
  }

  /** The app kept from running with the truck on, the head unit up: all of it was used. */
  @Test
  fun aStretchTheAppDidNotRunWithTheHeadUnitUpStillCounts() {
    val w = charged(104_000.0)
    w.add(driving(103_000.0, afterMs = 1_000L))
    w.add(driving(95_000.0, afterMs = 20 * 60_000L))
    assertEquals(9_000.0, w.used(), 1e-6)
  }

  @Test
  fun aNewChargeStartsItOver() {
    val w = charged(104_000.0)
    w.add(driving(60_000.0, afterMs = 3_600_000L))
    assertEquals(44_000.0, w.used(), 1e-6)
    w.add(parked(120_000.0, awake = false))
    assertEquals(120_000.0, w.to()!!, 0.0)
    assertEquals(0.0, w.used(), 0.0)
  }

  /** Off and plugged in, the level rising: a charge, not usage, and it follows the level only until it finishes. */
  @Test
  fun whatTheChargeReachedIsWhereItFinished() {
    val w = watch(parked(100_000.0, on = false))
    for (level in listOf(100_000.0, 100_700.0, 102_000.0, 104_000.0)) w.add(parked(level, charging = true, on = false))
    assertEquals(104_000.0, w.to()!!, 0.0)
    assertEquals(0.0, w.used(), 0.0)
    assertTrue(w.state.inCharge)
    // Finished, still parked: the level goes on moving, and what the truck thought it had when the charge ended stands.
    w.add(parked(105_500.0, on = false))
    assertFalse(w.state.inCharge)
    assertEquals(104_000.0, w.to()!!, 0.0)
  }

  /** A long charge: what it added is no rise left over for what the level does afterwards. Found on the emulator. */
  @Test
  fun aSettlingAfterALongChargeIsNotANewOne() {
    val w = watch(parked(60_000.0, on = false))
    for (level in listOf(60_000.0, 62_000.0, 70_000.0, 85_000.0, 100_000.0, 102_000.0, 104_000.0)) w.add(parked(level, charging = true, on = false))
    assertEquals(104_000.0, w.to()!!, 0.0)
    w.add(parked(104_000.0, on = false))
    w.add(parked(106_000.0, on = false))
    assertEquals(104_000.0, w.to()!!, 0.0)
    w.add(parked(102_000.0, awake = false))
    assertEquals(104_000.0, w.to()!!, 0.0)
    assertEquals(0.0, w.used(), 0.0)
    // Nor a charge seen only as a jump.
    val jump = watch(parked(100_000.0), parked(180_000.0))
    jump.add(parked(182_000.0))
    assertEquals(180_000.0, jump.to()!!, 0.0)
  }

  /** Plugged in overnight, a level that settled up by the morning is no charge either. */
  @Test
  fun aSettlingWhilePluggedInIsNotACharge() {
    val w = charged(104_000.0)
    w.add(driving(100_000.0))
    w.add(parked(100_000.0, on = false))
    w.add(parked(100_000.0, on = false, charging = true, afterMs = 1_000L))
    w.add(parked(102_000.0, charging = true, awake = false, afterMs = 8 * 3_600_000L))
    w.add(parked(102_300.0, charging = true, afterMs = 30_000L))
    assertEquals(104_000.0, w.to()!!, 0.0)
    assertEquals(4_000.0, w.used(), 1e-6)
    // Still rising once the time for settling is over: then it really is charging.
    w.add(parked(102_900.0, charging = true, afterMs = 200_000L))
    assertEquals(102_900.0, w.to()!!, 0.0)
  }

  /** With the readings a few seconds apart and no plug or charge state to read, no one step is a jump. */
  @Test
  fun aSlowRiseWithNothingSayingChargingIsSeenOnceItIsPastSettling() {
    val w = watch(parked(100_000.0, on = false))
    var level = 100_000.0
    repeat(400) {
      level += 11.0
      w.add(parked(level, on = false, afterMs = 4_000L))
    }
    assertNull("short of it: 4.4 kWh", w.state.charge)
    repeat(100) {
      level += 11.0
      w.add(parked(level, on = false, afterMs = 4_000L))
    }
    assertEquals(105_000.0, w.to()!!, 11.0)
  }

  @Test
  fun aFinishedChargeIsNotChargingEvenPluggedIn() {
    assertFalse(ChargeReading.saysCharging(true, ChargeState.FULLY_CHARGED))
    assertFalse(ChargeReading.saysCharging(true, ChargeState.NOT_CHARGING))
    assertTrue(ChargeReading.saysCharging(true, ChargeState.UNKNOWN))
    assertTrue(ChargeReading.saysCharging(true, null))
    assertTrue(ChargeReading.saysCharging(false, ChargeState.CHARGING))
    assertFalse(ChargeReading.saysCharging(false, null))
  }

  @Test
  fun whatCountsAsTheTruckBeingOn() {
    val off = 2
    val acc = 3
    val on = 4
    val start = 5
    assertTrue(ChargeReading.truckOn(on, Gear.PARK, moving = false))
    assertTrue(ChargeReading.truckOn(start, Gear.PARK, moving = false))
    assertFalse(ChargeReading.truckOn(off, Gear.PARK, moving = false))
    assertFalse(ChargeReading.truckOn(acc, Gear.PARK, moving = false))
    assertTrue("out of Park, whatever a held value says", ChargeReading.truckOn(off, Gear.DRIVE, moving = false))
    assertTrue("moving too", ChargeReading.truckOn(off, Gear.PARK, moving = true))
    assertTrue("nothing to read: the head unit is up", ChargeReading.truckOn(null, Gear.PARK, moving = false))
    assertTrue("undefined is nothing to read", ChargeReading.truckOn(0, null, moving = false))
  }

  @Test
  fun aChargeFromBeforeTheRunningTotalStartsFromTheLevel() {
    val old = ChargeWatch(ChargeWatchState(LastCharge(180_000.0, 87.8), seenWh = 150_000.0))
    assertEquals(30_000.0, old.used(), 1e-6)
    // One from after it keeps its own, whatever the level says.
    val kept = ChargeWatch(ChargeWatchState(LastCharge(180_000.0, 87.8, 28_000.0), seenWh = 150_000.0))
    assertEquals(28_000.0, kept.used(), 1e-6)
  }

  @Test
  fun aFullRunCarriesOnFromWhatWasSaved() {
    val w = charged(104_000.0)
    w.add(driving(100_000.0))
    val again = ChargeWatch(w.state)
    again.add(driving(99_000.0))
    assertEquals("the app restarted during the drive", 5_000.0, again.used(), 1e-6)
  }

  /** The owner's own example: 104 at the end of the charge, 2 used, a level of 97: 5 below what is expected. */
  @Test
  fun theDriftIsTheLevelAgainstTheChargeAndWhatWasUsed() {
    assertEquals(-5_000.0, LastCharge(104_000.0, null, 2_000.0).driftWh(97_000.0)!!, 1e-6)
    assertEquals("and above it", 4_000.0, LastCharge(104_000.0, null, 2_000.0).driftWh(106_000.0)!!, 1e-6)
    assertEquals("none when they agree", 0.0, LastCharge(104_000.0, null, 2_000.0).driftWh(102_000.0)!!, 1e-6)
    assertNull("no level, no figure", LastCharge(104_000.0, null, 2_000.0).driftWh(null))
    // The reading its running total goes with comes first, so that a newer level is not set against an older total.
    assertEquals(-5_000.0, LastCharge(104_000.0, null, 2_000.0, levelWh = 97_000.0).driftWh(96_000.0)!!, 1e-6)
    // A figure with no running total has only the level to go by: nothing to tell apart.
    assertEquals(0.0, LastCharge(104_000.0, null).driftWh(97_000.0)!!, 1e-6)
  }

  /** What the running total leaves out is what the drift shows, whichever way it went. */
  @Test
  fun everythingNotCountedAsUsageEndsUpInTheDrift() {
    val w = charged(104_000.0)
    fun drift(level: Double) = w.state.charge!!.driftWh(level)!!
    assertEquals(0.0, drift(104_000.0), 1e-6)
    w.add(parked(106_000.0, on = false))
    assertEquals("settled up", 2_000.0, drift(106_000.0), 1e-6)
    w.add(parked(106_000.0, afterMs = 1_000L))
    w.add(driving(104_000.0))
    assertEquals(2_000.0, w.used(), 1e-6)
    assertEquals("the drive moves the level and the total together", 2_000.0, drift(104_000.0), 1e-6)
    w.add(parked(97_000.0, on = false))
    assertEquals("the owner's example: parked, down to 97", -5_000.0, drift(97_000.0), 1e-6)
    assertEquals(2_000.0, w.used(), 1e-6)
    // A new charge starts it over.
    w.add(parked(150_000.0, on = false))
    assertEquals(0.0, drift(150_000.0), 1e-6)
  }

  /** The head unit sleeping or restarting tells the truck was off; the app merely not running does not. */
  @Test
  fun theClocksTellASleepFromTheAppNotRunning() {
    val at = ReadingClock(elapsedMs = 10_000_000L, uptimeMs = 9_000_000L, wallMs = 1_700_000_000_000L)
    fun later(elapsed: Long, uptime: Long, wall: Long = elapsed) = ReadingClock(at.elapsedMs + elapsed, at.uptimeMs + uptime, at.wallMs + wall)
    assertTrue("a second later", later(1_000, 1_000).stayedUpSince(at))
    assertTrue("the app frozen for 20 minutes", later(1_200_000, 1_200_000).stayedUpSince(at))
    assertFalse("asleep for 8 hours", later(28_800_000, 1_000).stayedUpSince(at))
    assertFalse("asleep for 5 minutes", later(300_000, 1_000).stayedUpSince(at))
    assertTrue("a moment asleep, as when the screen blinks", later(60_000, 30_000).stayedUpSince(at))
    assertFalse("up but no reading for hours, not even the hourly job", later(3 * 3_600_000, 3 * 3_600_000).stayedUpSince(at))
    // Restarted: elapsed time starts over from zero.
    val restarted = ReadingClock(elapsedMs = 60_000L, uptimeMs = 60_000L, wallMs = at.wallMs + 3_600_000L)
    assertFalse(restarted.stayedUpSince(at))
    // Restarted long enough ago that elapsed time has passed where it was.
    val longAgo = ReadingClock(elapsedMs = at.elapsedMs + 60_000L, uptimeMs = at.uptimeMs + 60_000L, wallMs = at.wallMs + 3_600_000L)
    assertFalse(longAgo.stayedUpSince(at))
  }

  // --- The efficiency since: the miles at the readings that count, over what those readings used ---

  private fun ChargeWatch.driven() = state.charge?.drivenM

  private fun moving(level: Double, tripM: Double, afterMs: Long = 1_000L) =
    ChargeReading(level, capacity, charging = false, moving = true, on = true, atMs = next(afterMs), tripM = tripM)

  @Test
  fun theMilesAddUpOverDrivesFromTheCharge() {
    val w = charged(104_000.0)
    assertEquals("a charge starts them at none", 0.0, w.driven()!!, 0.0)
    w.add(moving(104_000.0, 0.0))
    w.add(moving(103_900.0, 500.0))
    w.add(moving(103_800.0, 1_000.0))
    assertEquals(1_000.0, w.driven()!!, 1e-6)
    // A new drive, or a reset: the session's count starts over and the total does not.
    w.add(moving(103_700.0, 200.0))
    w.add(moving(103_600.0, 700.0))
    assertEquals(1_700.0, w.driven()!!, 1e-6)
    assertEquals(400.0, w.used(), 1e-6)
    assertEquals(1_700.0 / 400.0, w.state.charge!!.efficiencyMPerWh(w.used())!!, 1e-9)
  }

  @Test
  fun milesOnlyCountWhereUsageDoes() {
    val w = charged(104_000.0)
    w.add(moving(104_000.0, 0.0))
    w.add(moving(103_900.0, 500.0))
    // Off: nothing counts, whatever the readings say, and the next on-reading counts only from there.
    w.add(ChargeReading(103_900.0, capacity, charging = false, moving = false, on = false, atMs = next(1_000L), tripM = 500.0))
    w.add(ChargeReading(103_900.0, capacity, charging = false, moving = false, on = false, atMs = next(1_000L), tripM = 900.0))
    w.add(moving(103_800.0, 1_400.0))
    assertEquals(500.0, w.driven()!!, 1e-6)
    w.add(moving(103_700.0, 1_500.0))
    assertEquals(600.0, w.driven()!!, 1e-6)
  }

  @Test
  fun aRunThatPicksUpMidDriveCountsFromItsOwnFirstReading() {
    val w = charged(104_000.0)
    w.add(moving(104_000.0, 0.0))
    w.add(moving(103_900.0, 500.0))
    val again = ChargeWatch(w.state)
    again.add(moving(103_900.0, 3_000.0))
    assertEquals("the session's count is no distance yet", 500.0, again.driven()!!, 1e-6)
    again.add(moving(103_800.0, 3_400.0))
    assertEquals(900.0, again.driven()!!, 1e-6)
  }

  /** kWh with no miles against them would read low for the rest of the charge: no figure until the next one. */
  @Test
  fun energyGoneWithNothingMeasuringLosesTheMilesUntilTheNextCharge() {
    val w = charged(104_000.0)
    w.add(moving(104_000.0, 0.0))
    w.add(moving(103_900.0, 500.0))
    // Near a mile of energy, twenty minutes on. It still counts as used.
    w.add(moving(100_000.0, 600.0, afterMs = 20 * 60_000L))
    assertEquals(4_000.0, w.used(), 1e-6)
    assertNull(w.driven())
    assertNull(w.state.charge!!.efficiencyMPerWh(w.used()))
    w.add(moving(99_900.0, 1_100.0))
    assertNull("not back by itself", w.driven())

    w.add(parked(60_000.0, afterMs = 60 * 60_000L))
    w.add(parked(160_000.0))
    assertEquals(160_000.0, w.to()!!, 0.0)
    assertEquals("a new charge starts them over", 0.0, w.driven()!!, 0.0)
  }

  @Test
  fun aSmallStepAfterAShortGapKeepsTheMiles() {
    val w = charged(104_000.0)
    w.add(moving(104_000.0, 0.0))
    w.add(moving(103_900.0, 500.0, afterMs = 30_000L))
    assertEquals(500.0, w.driven()!!, 1e-6)
    val again = ChargeWatch(w.state)
    again.add(moving(103_800.0, 0.0))
    assertEquals("a restart moments later", 500.0, again.driven()!!, 1e-6)
  }

  @Test
  fun aChargeFromBeforeTheMilesHasNoneUntilTheNext() {
    val w = ChargeWatch(ChargeWatchState(LastCharge(104_000.0, 50.7, 2_000.0), seenWh = 102_000.0, on = true))
    w.add(moving(102_000.0, 0.0))
    w.add(moving(101_900.0, 500.0))
    assertEquals(2_100.0, w.used(), 1e-6)
    assertNull(w.driven())
    // And one the log found: what the level says went, with no miles to set against it.
    val logged = ChargeWatch()
    logged.add(parked(100_000.0, awake = false))
    logged.add(parked(180_000.0, awake = false))
    logged.usedFromLevels()
    assertNull(logged.driven())
  }

  @Test
  fun theMilesAreSavedWhenTheyHaveMoved() {
    val charge = LastCharge(180_000.0, 87.8, 0.0, drivenM = 1_000.0)
    val start = ChargeWatchState(charge, seenWh = 150_000.0)
    assertFalse(ChargeWatch.saveDue(start, start.copy(charge = charge.copy(drivenM = 1_400.0))))
    assertTrue(ChargeWatch.saveDue(start, start.copy(charge = charge.copy(drivenM = 1_500.0))))
    assertTrue("lost", ChargeWatch.saveDue(start, start.copy(charge = charge.copy(drivenM = null))))
  }

  @Test
  fun theSessionsReadingCarriesItsDistance() {
    assertEquals(1_234.0, ChargeReading.fromSession(SessionState(distanceM = 1_234.0)).tripM!!, 0.0)
    assertNull("the hourly job has none", ChargeReading.fromCar(100_000.0, capacity, false, null, 0.0).tripM)
  }

  @Test
  fun theDemosLastChargeIsItsOwn() {
    assertEquals(186.4, DemoDrive.LAST_CHARGE.toWh / 1000.0, 1e-9)
    assertEquals(90.9, DemoDrive.LAST_CHARGE.toPct!!, 0.05)
    // From the drive's start: the 34 kWh gone before it, at the figure it is made to read.
    val demo = DemoDrive.lastCharge(0.0)
    assertEquals(2.25, EffUnits.miPerKwh(demo.efficiencyMPerWh(demo.usedNowWh(152_400.0))!!), 0.01)
    assertEquals("this drive's miles on top", 16_000.0, DemoDrive.lastCharge(16_000.0).drivenM!! - demo.drivenM!!, 1e-6)
  }
}
