package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The truck's energy totals -> driving, climate and the rest for the Simple tab's three windows (the owner, 2026-11-16). */
class EnergySplitTest {
  private val min = 60_000L

  /** kWh in, as the profile's fields read. */
  private fun c(total: Double, driving: Double, climate: Double) = EnergyCounters.ofKwh(total, driving, climate)!!

  private fun assertParts(driving: Double, climate: Double, other: Double, p: EnergyParts?) {
    assertEquals(driving, p!!.drivingWh, 0.01)
    assertEquals(climate, p.climateWh, 0.01)
    assertEquals(other, p.otherWh, 0.01)
  }

  @Test
  fun otherIsWhatTheTotalHoldsBeyondTheTwoNamedParts() {
    assertParts(3_000.0, 500.0, 200.0, EnergyParts.between(c(50.0, 40.0, 5.0), c(53.7, 43.0, 5.5)))
    // A total that lags the parts (or has another scale) leaves nothing over: never a negative part.
    assertParts(3_000.0, 500.0, 0.0, EnergyParts.between(c(50.0, 40.0, 5.0), c(51.0, 43.0, 5.5)))
    assertNull(EnergyCounters.ofKwh(50.0, null, 5.0))
    assertNull(EnergyCounters.ofKwh(-1.0, 40.0, 5.0))
  }

  @Test
  fun aDriveCountsFromItsFirstReadingAndANewDriveStartsOver() {
    val t = EnergySplitTracker()
    assertParts(0.0, 0.0, 0.0, t.add(0, c(50.0, 40.0, 5.0), driveId = 1, charge = null).drive)
    assertParts(2_000.0, 300.0, 100.0, t.add(5 * min, c(52.4, 42.0, 5.3), 1, null).drive)
    val next = t.add(6 * min, c(52.5, 42.1, 5.3), driveId = 2, charge = null)
    assertParts(0.0, 0.0, 0.0, next.drive)
    // The last 15 minutes are the whole drive until it is that long.
    assertParts(0.0, 0.0, 0.0, next.recent)
  }

  @Test
  fun theLastFifteenMinutesSlide() {
    val t = EnergySplitTracker()
    var s = EnergySplit()
    // A kWh of driving a minute for half an hour, a reading every 10 s.
    for (i in 0..180) s = t.add(i * 10_000L, c(i / 6.0, i / 6.0, 0.0), 1, null)
    assertParts(30_000.0, 0.0, 0.0, s.drive)
    assertParts(15_000.0, 0.0, 0.0, s.recent)
    // No readings for an hour (another app in front): the window does not reach back across it.
    s = t.add(90 * min, c(60.0, 60.0, 0.0), 1, null)
    assertParts(0.0, 0.0, 0.0, s.recent)
    assertParts(60_000.0, 0.0, 0.0, s.drive)
  }

  @Test
  fun sinceTheChargeCountsFromWhereItEnded() {
    val t = EnergySplitTracker()
    // Charging: nothing counts as used since yet, and what the charge itself took (conditioning) is not the truck's use since.
    t.add(0, c(80.0, 70.0, 8.0), 1, LastCharge(150_000.0, 75.0, usedWh = 0.0))
    assertParts(0.0, 0.0, 0.0, t.add(min, c(81.0, 70.0, 9.0), 1, LastCharge(160_000.0, 80.0, usedWh = 0.0)).charge)
    assertParts(4_000.0, 500.0, 100.0, t.add(30 * min, c(85.6, 74.0, 9.5), 2, LastCharge(160_000.0, 80.0, usedWh = 4_500.0)).charge)
    // Kept between runs of the app.
    val again = EnergySplitTracker(t.chargeBase)
    assertParts(5_000.0, 500.0, 100.0, again.add(0, c(86.6, 75.0, 9.5), 1, LastCharge(160_000.0, 80.0, usedWh = 5_500.0)).charge)
    // Another charge since, seen without the MX+: from the first reading after it.
    assertParts(0.0, 0.0, 0.0, again.add(min, c(90.0, 78.0, 10.0), 1, LastCharge(170_000.0, 85.0, usedWh = 1_200.0)).charge)
  }

  @Test
  fun withNoChargeTrackedItIsTheTrucksOwnSinceItsLastFullCharge() {
    val t = EnergySplitTracker()
    assertParts(40_000.0, 5_000.0, 5_000.0, t.add(0, c(50.0, 40.0, 5.0), 1, null).charge)
    // The demo's last charge has no running total.
    assertParts(40_000.0, 5_000.0, 5_000.0, t.add(min, c(50.0, 40.0, 5.0), 1, LastCharge(186_400.0, 90.9)).charge)
  }

  /**
   * Seen on the truck (2026-11-16): all three windows read the same 5.00 / 0.40 / 0.00, and a drive began at 3 kWh.
   * 27B9 going down is not the totals starting over: only driving or climate going down is.
   */
  @Test
  fun aFallOf27B9AloneRestartsNothing() {
    val t = EnergySplitTracker()
    val charge = LastCharge(99_800.0, 48.3, usedWh = 4_500.0)
    t.add(0, c(0.60, 3.0, 0.2), 1, charge)
    val s = t.add(min, c(0.31, 3.5, 0.3), 1, charge)
    assertParts(500.0, 100.0, 0.0, s.drive)
    assertParts(500.0, 100.0, 0.0, s.recent)
    assertParts(500.0, 100.0, 0.0, s.charge)
    // The next drive starts from its own first reading, not from the truck's totals.
    assertParts(0.0, 0.0, 0.0, t.add(20 * min, c(0.40, 5.0, 0.4), 2, charge).drive)
    assertParts(1_500.0, 100.0, 0.0, t.add(30 * min, c(0.40, 6.5, 0.5), 2, charge).drive)
  }

  /**
   * Still the same in every window on the truck after 0.9.0, and after Reset session (the owner, 2026-11-17): a
   * total going down, as regen makes the driving one, restarts nothing. Each window is a delta from its own first
   * reading, and a part that has gone down since then reads below zero (counted as it is, the same day).
   */
  @Test
  fun regenLoweringATotalRestartsNothing() {
    val t = EnergySplitTracker()
    val charge = LastCharge(99_800.0, 48.3, usedWh = 4_500.0)
    t.add(0, c(10.0, 9.0, 1.0), 1, charge)
    t.add(5 * min, c(12.2, 11.0, 1.2), 1, charge)
    // A new drive, then a stop that puts 0.2 kWh back.
    assertParts(0.0, 0.0, 0.0, t.add(6 * min, c(12.3, 11.1, 1.2), 2, charge).drive)
    val s = t.add(7 * min, c(12.1, 10.9, 1.2), 2, charge)
    assertParts(-200.0, 0.0, 0.0, s.drive)
    assertParts(-200.0, 0.0, 0.0, s.recent)
    assertEquals(-200.0, s.drive!!.totalWh, 0.01)
    assertParts(1_900.0, 200.0, 0.0, s.charge)
    val later = t.add(12 * min, c(13.4, 12.1, 1.3), 2, charge)
    assertParts(1_000.0, 100.0, 0.0, later.drive)
    assertParts(3_100.0, 300.0, 0.0, later.charge)
  }

  /** The truck starts its totals over at a charge: the new charge and the drive after it take their own first readings. */
  @Test
  fun aChargeBringsNewFirstReadings() {
    val t = EnergySplitTracker()
    t.add(0, c(50.0, 40.0, 5.0), 1, LastCharge(150_000.0, 75.0, usedWh = 9_000.0))
    val s = t.add(60 * min, c(0.5, 0.4, 0.1), 2, LastCharge(200_000.0, 100.0, usedWh = 300.0))
    assertParts(0.0, 0.0, 0.0, s.drive)
    assertParts(0.0, 0.0, 0.0, s.charge)
    assertParts(1_000.0, 200.0, 300.0, t.add(70 * min, c(2.0, 1.4, 0.3), 2, LastCharge(200_000.0, 100.0, usedWh = 1_800.0)).charge)
  }
}
