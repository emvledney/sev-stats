package dev.silveradostats.ui.simple

import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.DemoSource
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.EnergyParts
import dev.silveradostats.vehicle.EnergySplit
import dev.silveradostats.vehicle.Feed
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import dev.silveradostats.vehicle.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionState -> the API page's texts and states, and the MX+'s windows -> the OBD page's. */
class SimpleModelTest {
  private val mile = EffUnits.METERS_PER_MILE

  private fun state(feed: Feed = Feed(FeedKind.VEHICLE, sampling = true)) = SessionState(
    activeMs = 24 * 60_000L + 30_000,
    samples = 2_900,
    lastSampleAtMs = 5_000_000,
    distanceM = 9.8 * mile,
    usedWh = 4_210.0,
    rangeEstimate = SessionTracker.rangeFrom(85_250.0, 9.8 * mile / 4_210.0),
    speedMps = 45 / 2.2369362920544,
    levelWh = 85_250.0,
    capacityWh = 205_080.0,
    rangeM = 304_000.0,
    outsideC = 12.5,
    feed = feed,
  )

  private fun build(s: SessionState) = SimpleModel.build(s)

  @Test
  fun theTilesShowTheFigures() {
    val v = build(state())
    assertNull(v.notice)
    // No tag: the page is the API's alone (the owner, 2026-11-18). Three figures, one decimal each.
    assertEquals(Stat("Capacity", "205.1", "kWh", emptyList()), v.capacity)
    assertEquals(Stat("Remaining", "85.3", "kWh", emptyList()), v.remaining)
    assertEquals(Stat("State of charge", "41.6", "%", emptyList()), v.soc)
  }

  /**
   * The OBD page's windows (the owner, 2026-11-18): the truck's own counts. Since the charge as read, 27C0's miles over
   * 27B9's kWh, as Car Scanner shows them; the other two by difference.
   */
  @Test
  fun theObdWindowsAreTheTrucksOwnCounts() {
    val split = EnergySplit(
      drive = EnergyParts(6_910.0, 820.0, 230.0),
      recent = null,
      charge = EnergyParts(11_200.0, 3_700.0, 1_000.0),
      driveM = 9.8 * mile,
      chargeM = 24.85 * mile,
    )
    val (drive, recent, charge) = SimpleModel.windows(split, DistUnit.MI)
    assertEquals(Stat("This drive", SimpleModel.effText(9.8 * mile / 7_960.0, DistUnit.MI), "mi/kWh", emptyList()), drive.efficiency)
    assertEquals(Stat("This drive", "9.8", "mi", emptyList()), drive.miles)
    // 24.85 mi over 15.9 kWh, the two figures Car Scanner showed on the truck.
    assertEquals(Stat("Since charge", "1.56", "mi/kWh", emptyList()), charge.efficiency)
    assertEquals(Stat("Since charge", "24.9", "mi", emptyList()), charge.miles)
    assertEquals("15.9 kWh", charge.energy.total)
    // Nothing to go by yet: dashes, not zeros.
    assertFalse(recent.efficiency.hasValue)
    assertFalse(recent.miles.hasValue)
    // A window with its kWh but no miles has no mi/kWh either.
    assertFalse(SimpleModel.windows(split.copy(driveM = null), DistUnit.MI)[0].efficiency.hasValue)
    // Kilometres where the user has them.
    assertEquals(Stat("Since charge", "40.0", "km", emptyList()), SimpleModel.windows(split, DistUnit.KM)[2].miles)
  }

  /** The energy band (the owner, 2026-11-16): driving, climate and the rest for three windows, from the MX+ only. */
  @Test
  fun theEnergyBandSplitsEachWindow() {
    val split = EnergySplit(drive = EnergyParts(6_910.0, 820.0, 230.0), recent = null, charge = EnergyParts(35_900.0, 4_600.0, 1_500.0))
    val (drive, recent, charge) = SimpleModel.windows(split, DistUnit.MI).map { it.energy }
    assertEquals("This drive", drive.title)
    // One decimal (the owner, 2026-11-17): the truck counts in tenths of a kWh.
    assertEquals("8.0 kWh", drive.total)
    assertEquals(listOf("Driving" to "6.9", "Climate" to "0.8", "Other" to "0.2"), drive.parts.map { it.label to it.value })
    assertEquals(1f, drive.parts.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
    assertEquals(6_910f / 7_960f, drive.parts[0].share, 0.001f)
    // Linked, but this window has nothing to go by yet.
    assertEquals(EnergyWindow("Last 15 min", "no reading", listOf("Driving", "Climate", "Other").map { EnergyPart(it, "—", 0f) }, hasValue = false), recent)
    assertEquals("Since charge" to "42.0 kWh", charge.title to charge.total)
    // No MX+: the car's API has no such figures, and nothing is made up for them.
    SimpleModel.windows(null, DistUnit.MI).forEach { w ->
      assertEquals("needs the MX+", w.energy.total)
      assertFalse(w.energy.hasValue)
      assertTrue(w.energy.parts.all { it.value == "—" && it.share == 0f })
      assertFalse(w.efficiency.hasValue)
    }
  }

  @Test
  fun theFirstColumnIsWhatIsLeftOverThePercentage() {
    val v = build(state())
    assertEquals(Stat("Remaining", "85.3", "kWh", emptyList()), v.remaining)
    assertEquals(Stat("State of charge", "41.6", "%", emptyList()), v.soc)
    // Unknown: dashes, not zeros, and drawn dim.
    val none = build(SessionState())
    assertEquals(Stat("Remaining", "—", "kWh", emptyList(), hasValue = false), none.remaining)
    assertEquals(Stat("State of charge", "—", "%", emptyList(), hasValue = false), none.soc)
  }

  @Test
  fun theApiCapacitySaysWhyNotWhenThereIsNone() {
    val waiting = build(SessionState(feed = Feed(FeedKind.VEHICLE, sampling = true))).capacity
    assertFalse(waiting.hasValue)
    assertNull("nothing to say it came from", waiting.marker)
    assertEquals(listOf("Waiting for the capacity"), waiting.lines)
    val absent = build(SessionState(feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.CAPACITY)))).capacity
    assertEquals(listOf("Capacity not offered by this car"), absent.lines)
  }

  @Test
  fun feedProblemsBecomeNotices() {
    val denied = build(SessionState(feed = Feed(FeedKind.VEHICLE, missingPermissions = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED))))
    assertEquals(NoticeAction.GRANT, denied.notice!!.action)
    assertTrue(denied.notice!!.text, denied.notice!!.text.contains("CAR_ENERGY and CAR_SPEED"))
    // Missing permission: the tiles ask for access, they do not blame the car.
    val blocked = build(
      SessionState(
        samples = 3,
        feed = Feed(FeedKind.VEHICLE, sampling = true, missingPermissions = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED), missing = setOf(Signal.SPEED, Signal.LEVEL, Signal.RANGE, Signal.CAPACITY)),
      ),
    )
    assertEquals(listOf(SimpleModel.NEEDS_ACCESS), blocked.capacity.lines)
    val phone = build(SessionState(feed = Feed(FeedKind.NOT_AUTOMOTIVE, "x")))
    assertEquals(NoticeAction.OPEN_DIAGNOSTICS, phone.notice!!.action)
    assertEquals("No car data on this device", phone.notice!!.title)
    assertEquals("Connecting to the car service…", build(SessionState()).notice!!.title)
    // Measuring hit an internal error: the screen says so instead of looking live.
    val failed = build(state(feed = Feed(FeedKind.FAILED, "IllegalStateException: details")))
    assertEquals("Measuring stopped", failed.notice!!.title)
    assertEquals("IllegalStateException: details", failed.notice!!.text)
  }

  @Test
  fun nothingOnThePageTalksAboutAWarmUp() {
    val starting = SessionTracker { 0L }
    starting.add(VehicleSample(1_000, speedMps = 10.0, levelWh = 100_000.0))
    val v = build(starting.snapshot().copy(feed = Feed(FeedKind.VEHICLE, sampling = true)))
    val texts = listOf(v.capacity, v.remaining, v.soc).flatMap { it.lines + it.title + it.value + it.unit + it.marker.orEmpty() }
    assertFalse(texts.toString(), texts.any { it.contains("warm", ignoreCase = true) || it.contains("early", ignoreCase = true) })
  }

  @Test
  fun formattingHelpers() {
    assertEquals("2.32", SimpleModel.effText(EffUnits.fromMiPerKwh(2.32), DistUnit.MI))
  }

  /** The demo source makes a full-looking session, all of it formatted without dashes. */
  @Test
  fun demoSessionLooksReal() {
    var now = 0L
    val t = SessionTracker { now }
    val demo = DemoSource(22 * 60_000L)
    demo.backlog(22 * 60_000L).forEach { now = it.atMs; t.add(it) }
    val v = build(t.snapshot().copy(feed = DemoSource.FEED.copy(sampling = true)))
    assertFalse(listOf(v.capacity, v.remaining, v.soc).any { it.value == "—" || !it.hasValue })
  }
}
