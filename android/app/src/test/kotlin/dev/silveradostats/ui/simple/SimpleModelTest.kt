package dev.silveradostats.ui.simple

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.DemoDrive
import dev.silveradostats.vehicle.DemoSource
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.EnergySource
import dev.silveradostats.vehicle.Feed
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.LastCharge
import dev.silveradostats.vehicle.PlainEfficiency
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import dev.silveradostats.vehicle.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionState -> the Simple tab's texts and states. */
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

  private fun build(s: SessionState, d: DistUnit = DistUnit.MI, mxKwh: Double? = null, lastCharge: LastCharge? = null) = SimpleModel.build(s, d, mxKwh, lastCharge)

  @Test
  fun theTilesShowTheFigures() {
    val v = build(state())
    assertNull(v.notice)
    assertEquals(Stat("Capacity", "205.1", "kWh", emptyList(), marker = "API"), v.capacity)
    // 85.25 kWh x 9.8 mi / 4.21 kWh = 198 mi; the truck's own 189 under it.
    assertEquals(Stat("Range", "198", "mi", listOf("Truck says 189 mi")), v.range)
    // Miles and kWh for this drive and nothing else.
    assertEquals(Stat("Trip", "9.8", "mi", emptyList()), v.trip)
    assertEquals(Stat("Used", "4.21", "kWh", emptyList()), v.energyUsed)
  }

  @Test
  fun metricShowsKilometres() {
    val v = build(state(), DistUnit.KM)
    assertEquals(Stat("Trip", "15.8", "km", emptyList()), v.trip)
    assertEquals(Stat("Range", "319", "km", listOf("Truck says 304 km")), v.range)
    assertEquals("4.21", v.energyUsed.value)
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
  fun theCapacitySaysWhereItComesFrom() {
    assertEquals("API", build(state()).capacity.marker)
    // The MX+'s figure once it has read one, whatever the API says.
    val mx = build(state(), mxKwh = 208.44).capacity
    assertEquals(Stat("Capacity", "208.4", "kWh", emptyList(), marker = "MX+"), mx)
    // Even before the car's API has answered.
    assertEquals("MX+", build(SessionState(), mxKwh = 208.44).capacity.marker)
  }

  @Test
  fun theMxCapacityIsOnlyALiveReading() {
    val read = mapOf<String, Any>("capacity_kwh" to 208.44)
    assertEquals(208.44, SimpleModel.mxCapacityKwh(LiveState(status = LinkStatus.CONNECTED, derived = read))!!, 0.0)
    assertEquals(208.44, SimpleModel.mxCapacityKwh(LiveState(status = LinkStatus.DEMO, derived = read))!!, 0.0)
    assertNull("connected, not read yet", SimpleModel.mxCapacityKwh(LiveState(status = LinkStatus.CONNECTED)))
    // An estimate from energy ÷ SoC is not the BECM's reading.
    assertNull(SimpleModel.mxCapacityKwh(LiveState(status = LinkStatus.CONNECTED, derived = mapOf("bms_energy_at_100_kwh" to 205.0))))
    for (st in listOf(LinkStatus.TRUCK_ASLEEP, LinkStatus.STOPPED_ERROR, LinkStatus.ADAPTER_UNREACHABLE, LinkStatus.CONNECTING, LinkStatus.NO_ADAPTER)) {
      assertNull("$st", SimpleModel.mxCapacityKwh(LiveState(status = st, derived = read)))
    }
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
  fun aRangeWithNothingToDivideIsADashBesideTheTrucks() {
    val r = build(state().copy(rangeEstimate = null)).range
    assertEquals("—", r.value)
    assertEquals("mi", r.unit)
    assertFalse(r.hasValue)
    assertEquals(listOf("Truck says 189 mi"), r.lines)
    assertEquals(listOf("Truck range —"), build(state().copy(rangeEstimate = null, rangeM = null)).range.lines)
    val notOffered = state(feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.RANGE))).copy(rangeM = null)
    assertEquals(listOf("Truck range not offered"), build(notOffered).range.lines)
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
    assertEquals("Truck range —", blocked.range.lines[0])
    val phone = build(SessionState(feed = Feed(FeedKind.NOT_AUTOMOTIVE, "x")))
    assertEquals(NoticeAction.OPEN_DIAGNOSTICS, phone.notice!!.action)
    assertEquals("No car data on this device", phone.notice!!.title)
    assertEquals("Connecting to the car service…", build(SessionState()).notice!!.title)
    // A property the car does not offer: a dash and a hint, never a made-up value.
    val noSpeed = build(state().copy(speedMps = null, feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.SPEED, Signal.RANGE))))
    assertEquals("—", noSpeed.trip.value)
    assertFalse(noSpeed.trip.hasValue)
    // Measuring hit an internal error: the screen says so instead of looking live.
    val failed = build(state(feed = Feed(FeedKind.FAILED, "IllegalStateException: details")))
    assertEquals("Measuring stopped", failed.notice!!.title)
    assertEquals("IllegalStateException: details", failed.notice!!.text)
  }

  @Test
  fun theUsedTileHasNoBadgeWhateverItIsMeasuredFrom() {
    val mx = build(state().copy(energySource = EnergySource.MX_PACK, energyOutWh = 5_020.0, energyInWh = 810.0, levelUsedWh = 4_680.0))
    assertNull(mx.energyUsed.marker)
    assertEquals("205.1", build(state().copy(usedWh = 205_080.0)).energyUsed.value)
    assertFalse(build(SessionState()).energyUsed.hasValue)
  }

  @Test
  fun miPerKwhIsTheDrivesAndTheLastFifteenMinutes() {
    val plain = PlainEfficiency(driveMPerWh = EffUnits.fromMiPerKwh(2.22), recentMPerWh = EffUnits.fromMiPerKwh(2.31))
    val v = build(state().copy(plainEfficiency = plain))
    assertEquals(Stat("This drive", "2.22", "mi/kWh", emptyList()), v.efficiency)
    assertEquals(Stat("Last 15 min", "2.31", "mi/kWh", emptyList()), v.efficiencyRecent)
    val km = build(state().copy(plainEfficiency = plain), DistUnit.KM)
    assertEquals(Stat("This drive", "3.57", "km/kWh", emptyList()), km.efficiency)
    assertEquals("km/kWh", km.efficiencyRecent.unit)
    // Nothing to divide yet, or net regen: dashes, not zeros, and it still says what it would be.
    val none = build(state())
    assertEquals(Stat("This drive", "—", "mi/kWh", emptyList(), hasValue = false), none.efficiency)
    assertEquals(Stat("Last 15 min", "—", "mi/kWh", emptyList(), hasValue = false), none.efficiencyRecent)
  }

  @Test
  fun theLastChargeSaysWhatItReachedAndWhatHasGoneSince() {
    val c = build(state().copy(levelWh = 144_400.0), lastCharge = LastCharge(186_400.0, 90.88)).charge
    assertEquals(Stat("last charged to", "186.4", "kWh", emptyList()), c.to)
    assertEquals(Stat("of capacity", "90.9", "%", emptyList()), c.toPct)
    assertEquals(Stat("used since", "42.0", "kWh", emptyList()), c.used)
    // Just charged: nothing used yet, rather than a wobble below zero.
    assertEquals("0.0", build(state().copy(levelWh = 186_500.0), lastCharge = LastCharge(186_400.0, 90.88)).charge.used.value)
  }

  /** Up is +, down is -, in the owner's words: 104, 2 used, and the level at 97 is -5.0. */
  @Test
  fun theDriftShowsWhichWayItWent() {
    fun drift(level: Double?, charge: LastCharge = LastCharge(104_000.0, 50.7, 2_000.0)) = build(state().copy(levelWh = level), lastCharge = charge).charge.drift
    assertEquals(Stat("drift", "\u22125.0", "kWh", emptyList()), drift(97_000.0))
    assertEquals(Stat("drift", "+4.0", "kWh", emptyList()), drift(106_000.0))
    assertEquals("no sign on none", "0.0", drift(102_000.0).value)
    assertEquals("nor on what rounds to none", "0.0", drift(101_960.0).value)
    // Nothing to compare yet: a dash, as for the rest of the line.
    assertEquals(Stat("drift", "\u2014", "kWh", emptyList(), hasValue = false), build(state()).charge.drift)
    assertFalse(drift(null).hasValue)
  }

  /** The running total where there is one: with the truck on only, whatever the level has done in between. */
  @Test
  fun whatIsUsedIsTheRunningTotalNotTheLevel() {
    val c = build(state().copy(levelWh = 144_400.0), lastCharge = LastCharge(186_400.0, 90.88, 39_500.0)).charge
    assertEquals(Stat("used since", "39.5", "kWh", emptyList()), c.used)
    assertEquals("known before the level is", "39.5", build(state().copy(levelWh = null), lastCharge = LastCharge(186_400.0, 90.88, 39_500.0)).charge.used.value)
    assertEquals("regen just after a charge is not less than none", "0.0", build(state(), lastCharge = LastCharge(186_400.0, 90.88, -200.0)).charge.used.value)
  }

  /** 88.2 mi on 39.4 kWh. Miles that are not known, or nothing yet used: a dash, not a figure with half its facts. */
  @Test
  fun theEfficiencySinceIsTheMilesOverWhatWasUsed() {
    fun since(charge: LastCharge, d: DistUnit = DistUnit.MI) = build(state().copy(levelWh = 144_500.0), d, lastCharge = charge).charge.efficiency
    val driven = 88.2 * EffUnits.METERS_PER_MILE
    assertEquals(Stat("since", "2.24", "mi/kWh", emptyList()), since(LastCharge(186_400.0, 90.88, 39_400.0, drivenM = driven)))
    assertEquals(Stat("since", "3.60", "km/kWh", emptyList()), since(LastCharge(186_400.0, 90.88, 39_400.0, drivenM = driven), DistUnit.KM))
    assertEquals("a charge from before the miles were counted", "—", since(LastCharge(186_400.0, 90.88, 39_400.0)).value)
    assertFalse(since(LastCharge(186_400.0, 90.88, 39_400.0)).hasValue)
    assertEquals("nothing used yet", "—", since(LastCharge(186_400.0, 90.88, 0.0, drivenM = 0.0)).value)
    assertEquals("regen just after a charge", "—", since(LastCharge(186_400.0, 90.88, -200.0, drivenM = 800.0)).value)
  }

  @Test
  fun aLastChargeNotKnownSaysSo() {
    val none = build(state()).charge
    assertEquals("—", none.to.value)
    assertFalse(none.to.hasValue)
    assertEquals("—", none.toPct.value)
    assertEquals(SimpleModel.NO_CHARGE_YET, none.to.title)
    assertEquals(Stat(SimpleModel.USED_SINCE, "—", "kWh", emptyList(), hasValue = false), none.used)
    assertEquals(Stat(SimpleModel.SINCE, "—", "mi/kWh", emptyList(), hasValue = false), none.efficiency)
    // Known, but the level is not yet: the charge stands, what it means now waits.
    val noLevel = build(state().copy(levelWh = null), lastCharge = LastCharge(186_400.0, null)).charge
    assertEquals("186.4", noLevel.to.value)
    assertEquals("—", noLevel.toPct.value)
    assertEquals(SimpleModel.LAST_CHARGED_TO, noLevel.to.title)
    assertEquals(Stat(SimpleModel.USED_SINCE, "—", "kWh", emptyList(), hasValue = false), noLevel.used)
  }

  /** 10 min at 24 mph and 15 kW: 4 mi, the kind of drive a warm-up left without a figure. */
  @Test
  fun aShortDriveHasAFigureOfItsOwn() {
    var at = 0L
    val tracker = SessionTracker { at }
    var level = 150_000.0
    repeat(600) {
      at += 1_000
      level -= 15_000.0 / 3600.0
      tracker.add(VehicleSample(at, speedMps = 10.7, levelWh = level, rangeM = 300_000.0))
    }
    val v = SimpleModel.range(tracker.snapshot(), DistUnit.MI)
    assertEquals("Truck says 186 mi", v.lines[0])
    assertTrue("tile: $v", v.hasValue)
    assertEquals(236.0, v.value.toDouble(), 5.0)
  }

  @Test
  fun nothingOnThePageTalksAboutAWarmUp() {
    val starting = SessionTracker { 0L }
    starting.add(VehicleSample(1_000, speedMps = 10.0, levelWh = 100_000.0))
    val v = build(starting.snapshot().copy(feed = Feed(FeedKind.VEHICLE, sampling = true)))
    val texts = listOf(v.capacity, v.range, v.trip, v.energyUsed, v.efficiency, v.efficiencyRecent, v.charge.to, v.charge.toPct, v.charge.used, v.charge.efficiency, v.charge.drift).flatMap { it.lines + it.title + it.value + it.unit + it.marker.orEmpty() }
    assertFalse(texts.toString(), texts.any { it.contains("warm", ignoreCase = true) || it.contains("early", ignoreCase = true) })
  }

  @Test
  fun formattingHelpers() {
    assertEquals("4.21", SimpleModel.kwhValue(4_210.0))
    assertEquals("205.1", SimpleModel.kwhValue(205_080.0))
    assertEquals("2.32", SimpleModel.effText(EffUnits.fromMiPerKwh(2.32), DistUnit.MI))
  }

  /** The demo source makes a full-looking session, all of it formatted without dashes. */
  @Test
  fun demoSessionLooksReal() {
    var now = 0L
    val t = SessionTracker { now }
    val demo = DemoSource(22 * 60_000L)
    demo.backlog(22 * 60_000L).forEach { now = it.atMs; t.add(it) }
    val v = build(t.snapshot().copy(feed = DemoSource.FEED.copy(sampling = true)), lastCharge = DemoDrive.lastCharge(t.snapshot().distanceM))
    assertFalse(listOf(v.capacity, v.range, v.trip, v.energyUsed, v.efficiency, v.efficiencyRecent, v.charge.to, v.charge.toPct, v.charge.used, v.charge.efficiency, v.charge.drift).any { it.value == "—" || !it.hasValue })
  }
}
