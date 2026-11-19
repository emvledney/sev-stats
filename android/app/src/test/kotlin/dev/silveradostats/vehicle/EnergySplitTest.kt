package dev.silveradostats.vehicle

import dev.silveradostats.link.LiveValue
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
    assertParts(0.0, 0.0, 0.0, t.add(0, c(50.0, 40.0, 5.0), driveId = 1).drive)
    assertParts(2_000.0, 300.0, 100.0, t.add(5 * min, c(52.4, 42.0, 5.3), 1).drive)
    val next = t.add(6 * min, c(52.5, 42.1, 5.3), driveId = 2)
    assertParts(0.0, 0.0, 0.0, next.drive)
    // The last 15 minutes are the whole drive until it is that long.
    assertParts(0.0, 0.0, 0.0, next.recent)
  }

  @Test
  fun theLastFifteenMinutesSlide() {
    val t = EnergySplitTracker()
    var s = EnergySplit()
    // A kWh of driving a minute for half an hour, a reading every 10 s.
    for (i in 0..180) s = t.add(i * 10_000L, c(i / 6.0, i / 6.0, 0.0), 1)
    assertParts(30_000.0, 0.0, 0.0, s.drive)
    assertParts(15_000.0, 0.0, 0.0, s.recent)
    // No readings for an hour (another app in front): the window does not reach back across it.
    s = t.add(90 * min, c(60.0, 60.0, 0.0), 1)
    assertParts(0.0, 0.0, 0.0, s.recent)
    assertParts(60_000.0, 0.0, 0.0, s.drive)
  }

  /** The mi/kWh of a window divide the metres since its own first reading: the MX+ can come up mid-drive. */
  @Test
  fun aWindowsMetresStartAtItsFirstReading() {
    val t = EnergySplitTracker()
    assertEquals(0.0, t.add(0, c(50.0, 40.0, 5.0), 1, distanceM = 16_000.0).driveM!!, 0.01)
    val s = t.add(5 * min, c(52.4, 42.0, 5.3), 1, distanceM = 24_000.0)
    assertEquals(8_000.0, s.driveM!!, 0.01)
    assertEquals(8_000.0, s.recentM!!, 0.01)
    assertEquals(0.0, t.add(6 * min, c(52.5, 42.1, 5.3), 2, distanceM = 0.0).driveM!!, 0.01)
  }

  /**
   * The miles are the truck's count too (the owner, 2026-11-18): 27C0 since the charge as read, its change over the
   * drive and the last 15 minutes. The session's distance only where a window's ends lack it.
   */
  @Test
  fun theMilesAreTheTrucksDistanceSinceTheCharge() {
    val mile = EffUnits.METERS_PER_MILE
    fun v(x: Double) = LiveValue("", "", x, "", "", 10.0, 1_000)
    val values = mapOf(
      EnergyCounters.TOTAL to v(15.9),
      EnergyCounters.DRIVING to v(11.2),
      EnergyCounters.CLIMATE to v(3.7),
      EnergyCounters.DISTANCE to v(24.85),
    )
    val read = EnergyCounters.read(values)!!.second
    assertEquals(24.85 * mile, read.distanceM!!, 0.01)
    assertNull(EnergyCounters.read(values - EnergyCounters.DISTANCE)!!.second.distanceM)
    val t = EnergySplitTracker()
    // The session counted from 0, the truck from 24.85 mi: the windows go by the truck's count.
    t.add(0, read, 1, distanceM = 0.0)
    val s = t.add(5 * min, c(16.5, 11.7, 3.8).copy(distanceM = 26.85 * mile), 1, distanceM = 1_000.0)
    assertEquals(2 * mile, s.driveM!!, 0.01)
    assertEquals(2 * mile, s.recentM!!, 0.01)
    assertEquals(26.85 * mile, s.chargeM!!, 0.01)
    // A drive whose first reading had no 27C0: the session's distance for it.
    val u = EnergySplitTracker()
    u.add(0, c(15.9, 11.2, 3.7), 1, distanceM = 500.0)
    assertEquals(1_500.0, u.add(min, read, 1, distanceM = 2_000.0).driveM!!, 0.01)
  }

  /**
   * Since the charge is the truck's own totals as read, no delta (the owner, 2026-11-17: a base kept from the
   * app's last charge still read wrong on the truck after three builds). A new drive changes nothing in it.
   */
  @Test
  fun sinceTheChargeIsTheTrucksTotalsAsRead() {
    val t = EnergySplitTracker()
    assertParts(40_000.0, 5_000.0, 5_000.0, t.add(0, c(50.0, 40.0, 5.0), 1).charge)
    assertParts(42_000.0, 5_300.0, 5_100.0, t.add(5 * min, c(52.4, 42.0, 5.3), 2).charge)
    // 27B9 below the two parts, as on the truck: nothing left over for "other".
    assertParts(3_000.0, 200.0, 0.0, EnergySplitTracker().add(0, c(0.60, 3.0, 0.2), 1).charge)
    // The one figure the session's energy goes by is that window's total.
    assertEquals(3_200.0, c(0.60, 3.0, 0.2).usedWh, 0.01)
    assertEquals(50_000.0, c(50.0, 40.0, 5.0).usedWh, 0.01)
  }

  /**
   * Seen on the truck (2026-11-16): all three windows read the same 5.00 / 0.40 / 0.00, and a drive began at 3 kWh.
   * 27B9 going down is not the totals starting over: only driving or climate going down is.
   */
  @Test
  fun aFallOf27B9AloneRestartsNothing() {
    val t = EnergySplitTracker()
    t.add(0, c(0.60, 3.0, 0.2), 1)
    val s = t.add(min, c(0.31, 3.5, 0.3), 1)
    assertParts(500.0, 100.0, 0.0, s.drive)
    assertParts(500.0, 100.0, 0.0, s.recent)
    assertParts(3_500.0, 300.0, 0.0, s.charge)
    // The next drive starts from its own first reading, not from the truck's totals.
    assertParts(0.0, 0.0, 0.0, t.add(20 * min, c(0.40, 5.0, 0.4), 2).drive)
    assertParts(1_500.0, 100.0, 0.0, t.add(30 * min, c(0.40, 6.5, 0.5), 2).drive)
  }

  /**
   * Still the same in every window on the truck after 0.9.0, and after Reset session (the owner, 2026-11-17): a
   * total going down, as regen makes the driving one, restarts nothing. Each window is a delta from its own first
   * reading, and a part that has gone down since then reads below zero (counted as it is, the same day).
   */
  @Test
  fun regenLoweringATotalRestartsNothing() {
    val t = EnergySplitTracker()
    t.add(0, c(10.0, 9.0, 1.0), 1)
    t.add(5 * min, c(12.2, 11.0, 1.2), 1)
    // A new drive, then a stop that puts 0.2 kWh back.
    assertParts(0.0, 0.0, 0.0, t.add(6 * min, c(12.3, 11.1, 1.2), 2).drive)
    val s = t.add(7 * min, c(12.1, 10.9, 1.2), 2)
    assertParts(-200.0, 0.0, 0.0, s.drive)
    assertParts(-200.0, 0.0, 0.0, s.recent)
    assertEquals(-200.0, s.drive!!.totalWh, 0.01)
    assertParts(10_900.0, 1_200.0, 0.0, s.charge)
    val later = t.add(12 * min, c(13.4, 12.1, 1.3), 2)
    assertParts(1_000.0, 100.0, 0.0, later.drive)
    assertParts(12_100.0, 1_300.0, 0.0, later.charge)
  }
}
