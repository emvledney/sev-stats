package dev.silveradostats.ui.simple

import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.DemoSource
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.EnergySource
import dev.silveradostats.vehicle.Efficiency
import dev.silveradostats.vehicle.EffPoint
import dev.silveradostats.vehicle.Feed
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.RecentEfficiency
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import dev.silveradostats.vehicle.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionState -> the Simple tab's texts and states. */
class SimpleModelTest {
  private val mile = EffUnits.METERS_PER_MILE
  private val recent = EffUnits.fromMiPerKwh(2.18)

  private fun state(
    eff: Efficiency = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(2.32), 1.0, 15_000.0, 4_000.0),
    feed: Feed = Feed(FeedKind.VEHICLE, sampling = true),
  ) = SessionState(
    activeMs = 24 * 60_000L + 30_000,
    samples = 2_900,
    lastSampleAtMs = 5_000_000,
    distanceM = 9.8 * mile,
    usedWh = 4_210.0,
    efficiency = eff,
    recentEfficiency = RecentEfficiency(recent, 9_000.0, 9_000.0 / recent),
    series = (11..98).map { EffPoint(it * mile / 10, EffUnits.fromMiPerKwh(2.2 + it / 1000.0)) },
    speedMps = 45 / 2.2369362920544,
    levelWh = 85_250.0,
    capacityWh = 205_080.0,
    rangeM = 304_000.0,
    outsideC = 12.5,
    feed = feed,
  )

  private fun build(s: SessionState, d: DistUnit = DistUnit.MI, t: TempUnit = TempUnit.C) = SimpleModel.build(s, t, d)

  @Test
  fun theTilesShowTheFigures() {
    val v = build(state())
    assertEquals("Since app opened 0:24 · 9.8 mi", v.header)
    assertNull(v.note)
    assertNull(v.notice)
    assertEquals(Stat("Overall", "2.32", "mi/kWh", listOf("431 Wh/mi")), v.efficiency)
    assertEquals(Stat("Last 15 min", "2.18", "mi/kWh", listOf("459 Wh/mi")), v.recentEfficiency)
    assertEquals("205.1", v.capacity.value)
    assertEquals("kWh", v.capacity.unit)
    assertEquals("API", v.capacity.marker)
    assertEquals("Usable now, from the car's API", v.capacity.lines.first())
    // Range at the measured efficiency: 85.25 kWh x 2.32 mi/kWh = 198 mi; the truck's own 189.
    assertEquals("198", v.range.value)
    assertEquals(listOf("Truck says 189 mi", "85.3 kWh × 2.32 mi/kWh"), v.range.lines)
    // The energy used is a figure of its own beside the distance, with what it is measured from.
    assertEquals(Stat("Trip", "9.8", "mi", listOf("0:24 · avg 24 mph", "outside 12.5 °C")), v.trip)
    assertEquals(Stat("Used", "4.21", "kWh", emptyList(), marker = "battery level"), v.energyUsed)
  }

  @Test
  fun metricShowsKmPerKwhAndWhPerKm() {
    val v = build(state(), DistUnit.KM, TempUnit.F)
    assertEquals("Since app opened 0:24 · 15.8 km", v.header)
    assertEquals(Stat("Overall", "3.73", "km/kWh", listOf("268 Wh/km")), v.efficiency)
    assertEquals(Stat("Last 15 min", "3.51", "km/kWh", listOf("285 Wh/km")), v.recentEfficiency)
    assertEquals("15.8", v.trip.value)
    assertEquals("km", v.trip.unit)
    assertEquals(listOf("0:24 · avg 39 km/h", "outside 54.5 °F"), v.trip.lines)
    assertEquals("4.21", v.energyUsed.value)
  }

  @Test
  fun warmingUpIsLabelled() {
    val s = state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.4, 640.0, 120.0)).copy(
      series = emptyList(),
    )
    val v = build(s)
    assertEquals(
      Stat(
        "Efficiency since opened",
        "Warming up",
        "",
        listOf("40 % of the warm-up", "Needs 1.0 mi and 0.3 kWh measured · so far 0.4 mi · 0.12 kWh"),
        hasValue = false,
        shortTitle = "Efficiency",
      ),
      v.efficiency,
    )
    assertNull("nothing to stand beside", v.recentEfficiency)
    assertTrue(v.spark.isEmpty())
    assertEquals("—", v.range.value)
    assertFalse(v.range.hasValue)
  }

  @Test
  fun theLastFifteenMinutesSayWhyThereIsNoFigure() {
    fun recentOf(r: RecentEfficiency, d: DistUnit = DistUnit.MI) = build(state().copy(recentEfficiency = r), d).recentEfficiency!!
    // Down a long hill: said in words, whatever the overall figure says.
    val back = recentOf(RecentEfficiency(null, 3_000.0, -50.0))
    assertEquals("Net regen", back.value)
    assertEquals("", back.unit)
    assertFalse(back.hasValue)
    assertEquals(listOf("More energy back than used over 1.9 mi"), back.lines)
    assertEquals(listOf("Almost no net energy used over 1.9 mi"), recentOf(RecentEfficiency(null, 3_000.0, 40.0)).lines)
    // Parked, or just back after a stop: a dash, and nothing to read about it while driving.
    val parked = recentOf(RecentEfficiency(null, 200.0, 50.0))
    assertEquals("—", parked.value)
    assertEquals("mi/kWh", parked.unit)
    assertFalse(parked.hasValue)
    assertFalse(parked.linesWhileDriving)
    assertEquals(listOf("Under 1 mi measured"), parked.lines)
    assertEquals(listOf("Under 1.6 km measured"), recentOf(RecentEfficiency(), DistUnit.KM).lines)
    // Beside a net regen overall too; only with no overall figure at all is it left out.
    assertNotNull(build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, -120.0))).recentEfficiency)
    assertNull(build(state(eff = Efficiency.NONE)).recentEfficiency)
  }

  @Test
  fun theRingShowsTheBattery() {
    val v = build(state())
    assertEquals("41.6", v.ring.soc)
    assertEquals("85.3 kWh", v.ring.remaining)
    assertEquals("of 205.1 kWh max", v.ring.max)
    assertEquals("Truck range 189 mi", v.ring.truckRange)
    assertEquals(0.4157, v.ring.fraction!!, 1e-3)
    val nominal = build(state(feed = Feed(FeedKind.VEHICLE, sampling = true, capacityNominal = true)))
    assertEquals("of 205.1 kWh nominal", nominal.ring.max)
  }

  @Test
  fun feedProblemsBecomeNoticesAndHints() {
    val denied = build(SessionState(feed = Feed(FeedKind.VEHICLE, missingPermissions = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED))))
    assertEquals(NoticeAction.GRANT, denied.notice!!.action)
    assertTrue(denied.notice!!.text, denied.notice!!.text.contains("CAR_ENERGY and CAR_SPEED"))
    // Missing permission: the tiles ask for access, they do not blame the car.
    val blocked = build(
      SessionState(
        samples = 3,
        feed = Feed(FeedKind.VEHICLE, sampling = true, missingPermissions = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED), missing = setOf(Signal.SPEED, Signal.LEVEL, Signal.RANGE)),
      ),
    )
    assertEquals("No access", blocked.efficiency.value)
    assertEquals(listOf(SimpleModel.NEEDS_ACCESS), blocked.efficiency.lines)
    assertNull(blocked.recentEfficiency)
    assertNull("the access notice comes first", blocked.hint)
    assertEquals(SimpleModel.NEEDS_ACCESS, blocked.range.lines[1])
    // While driving that tile shows just the word: nothing to read, and the notice says why.
    assertFalse(blocked.efficiency.linesWhileDriving)
    assertTrue("a figure's own line stays", build(state()).efficiency.linesWhileDriving)
    assertEquals("Truck range —", blocked.range.lines[0])
    val phone = build(SessionState(feed = Feed(FeedKind.NOT_AUTOMOTIVE, "x")))
    assertEquals(NoticeAction.OPEN_DIAGNOSTICS, phone.notice!!.action)
    assertEquals("No car data on this device", phone.notice!!.title)
    assertEquals("Connecting to the car service…", build(SessionState()).notice!!.title)
    // A property the car does not offer: a dash and a hint, never a made-up value.
    val noSpeed = build(state().copy(speedMps = null, feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.SPEED, Signal.RANGE))))
    assertEquals("Since app opened 0:24 · —", noSpeed.header)
    assertEquals("—", noSpeed.trip.value)
    assertFalse(noSpeed.trip.hasValue)
    val noLevel = build(SessionState(samples = 3, feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.LEVEL))))
    assertEquals("No data", noLevel.efficiency.value)
    assertEquals(listOf("Needs the battery level: not offered by this car"), noLevel.efficiency.lines)
    assertEquals(
      listOf("Needs vehicle speed: not offered by this car"),
      build(state(eff = Efficiency.NONE).copy(feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.SPEED)))).efficiency.lines,
    )
    // Measuring hit an internal error: the screen says so instead of looking live.
    val failed = build(state(feed = Feed(FeedKind.FAILED, "IllegalStateException: details")))
    assertEquals("Measuring stopped", failed.notice!!.title)
    assertEquals("IllegalStateException: details", failed.notice!!.text)
    assertNull("not a pause that resumes by itself", failed.note)
    assertEquals("—", noLevel.ring.soc)
  }

  @Test
  fun notesSayWhatIsLeftOut() {
    val s = state().copy(gaps = 2, gapMs = 180_000, chargedWh = 10_400.0, feed = Feed(FeedKind.DEMO, sampling = false))
    assertEquals(
      "Simulated drive (demo data) · Paused: resumes while a screen of the app is open · +10.40 kWh charged, not counted · 2 pauses left out (3 min)",
      build(s).note,
    )
    assertEquals("Charging: energy not counted (+1.20 kWh)", build(state().copy(charging = true, chargedWh = 1_200.0)).note)
    // Before any drive: how to get a whole drive measured, as plain advice.
    assertEquals(SimpleModel.MEASURING_HINT, build(state()).hint)
    assertFalse(build(state()).hintWarn)
    // A pause while driving (the app was frozen): the same words, now a warning.
    assertEquals(SimpleModel.MEASURING_HINT, build(state().copy(gaps = 1, gapMs = 700_000)).hint)
    assertTrue(build(state().copy(gaps = 1, gapMs = 700_000)).hintWarn)
    assertNull(build(s).hint) // demo
  }

  /**
   * The complaint from the truck: a drive under 5 mi never got a figure of its own, and the tile's one
   * line while driving is the truck's. 10 min at 24 mph and 15 kW: 4 mi, slow enough to outlast any warm-up.
   */
  @Test
  fun aShortDriveGetsMilesOfItsOwn() {
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
    assertEquals(SimpleModel.EARLY, v.marker)
  }

  @Test
  fun theLastSettledFigureCarriesTheTileUntilThisDriveSettles() {
    val last = EffUnits.fromMiPerKwh(2.1)
    // 85.25 kWh x 2.1 mi/kWh = 179 mi, whatever this drive is doing.
    val expected = Stat("Range at your efficiency", "179", "mi", listOf("Truck says 189 mi", "85.3 kWh × 2.10 mi/kWh"), marker = SimpleModel.LAST_DRIVE, shortTitle = "Range")
    val warming = Efficiency.NONE
    val early = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(1.43), 1.0, 1_640.0, 710.0, early = true)
    val regen = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, -120.0)
    val high = EffUnits.fromMiPerKwh(29.32)
    val descent = Efficiency(EffStatus.OK, high, 1.0, 30_000.0, 30_000.0 / high)
    for (e in listOf(warming, early, regen, descent)) {
      assertEquals(expected, build(state(eff = e).copy(lastSettledMPerWh = last)).range)
    }
    // Settled: this drive's own figure, unmarked.
    val own = build(state().copy(lastSettledMPerWh = last)).range
    assertEquals("198", own.value)
    assertNull(own.marker)
    assertEquals("85.3 kWh × 2.32 mi/kWh", own.lines[1])
    // Nothing to multiply it by.
    assertFalse(build(state(eff = warming).copy(levelWh = null, lastSettledMPerWh = last)).range.hasValue)
  }

  @Test
  fun earlyFiguresAreMarkedAndSoIsTheirRange() {
    val early = state(eff = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(1.43), 1.0, 1_640.0, 710.0, early = true)).copy(distanceM = 1_700.0)
    val v = build(early)
    assertEquals("122", v.range.value)
    assertTrue(v.range.hasValue)
    assertEquals(SimpleModel.EARLY, v.range.marker)
    assertEquals(listOf("Truck says 189 mi", "85.3 kWh × 1.43 mi/kWh"), v.range.lines)
    // An early figure from a descent still projects nothing.
    val high = EffUnits.fromMiPerKwh(29.32)
    val down = state(eff = Efficiency(EffStatus.OK, high, 1.0, 1_640.0, 1_640.0 / high, early = true))
    assertEquals("—", build(down).range.value)
    assertEquals(listOf("Truck says 189 mi", "After 5 mi and 2 kWh measured"), build(down).range.lines)
    assertEquals("After 8 km and 2 kWh measured", build(down, DistUnit.KM).range.lines[1])
    assertEquals(SimpleModel.EARLY, v.efficiency.marker)
    assertEquals("1.43", v.efficiency.value)
  }

  @Test
  fun aDescentReadsAsSuch() {
    // 29 mi/kWh: a figure, but no range from it.
    val high = EffUnits.fromMiPerKwh(29.32)
    val v = build(state(eff = Efficiency(EffStatus.OK, high, 1.0, 30_000.0, 30_000.0 / high)))
    assertEquals("29.32", v.efficiency.value)
    assertEquals("—", v.range.value)
    assertEquals("Not from this efficiency: mostly downhill so far", v.range.lines[1])

    // Net regen once warm: said in words, with no way back to "Warming up".
    val regen = build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, -120.0)))
    assertEquals("Net regen", regen.efficiency.value)
    assertEquals("Overall", regen.efficiency.title)
    assertEquals(listOf("More energy back than used over 6.5 mi"), regen.efficiency.lines)
    assertEquals(listOf("Almost no net energy used over 6.5 mi"), build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, 40.0))).efficiency.lines)
    assertEquals("Not from this efficiency: mostly downhill so far", regen.range.lines[1])
  }

  @Test
  fun warmUpTextsSayWhatIsReallyNeeded() {
    val coarse = build(state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.33, 3_700.0, 1_000.0, needWh = 3_000.0)).copy(series = emptyList()))
    assertEquals("Needs 1.0 mi and 3.0 kWh measured · so far 2.3 mi · 1.00 kWh", coarse.efficiency.lines[1])

    // The first minutes of a drive: the time left, and the clock has not started while parked.
    val driving = build(state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.3, 0.0, 0.0, warmupLeftMs = 150_001)).copy(series = emptyList()))
    assertEquals("Warming up", driving.efficiency.value)
    assertEquals(listOf("30 % of the warm-up", "First 5 min of a drive not counted · 3 min left"), driving.efficiency.lines)
    assertTrue("the progress is what a driver keeps", driving.efficiency.linesWhileDriving)
    assertNull("said once, on the tile", driving.note)
    val parked = build(state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.0, 0.0, 0.0, warmupLeftMs = SessionTracker.WARMUP_MS)).copy(series = emptyList()))
    assertEquals("First 5 min of a drive not counted · starts when moving", parked.efficiency.lines[1])

    // A later drive of the session: the figure stands, and the note says why.
    val later = build(state(eff = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(2.32), 1.0, 15_000.0, 4_000.0, warmupLeftMs = 60_000)))
    assertEquals("2.32", later.efficiency.value)
    assertEquals("First 5 min of a drive not counted · 1 min left", later.note)

    // A level that never moves gets an explanation, whatever the header's distance says.
    val stuck = state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.0, 0.0, 0.0)).copy(series = emptyList(), distanceM = 18.6 * mile, levelUnchangedM = 18.6 * mile)
    assertEquals("Battery level unchanged for 18.6 mi: nothing to measure yet", build(stuck).efficiency.lines[1])
    assertNull(build(stuck).note)
    // Once there is a figure, the note says it stopped moving.
    assertEquals("Battery level unchanged for 4.0 mi", build(state().copy(levelUnchangedM = 4 * mile)).note)
    assertNull(build(state().copy(levelUnchangedM = 2 * mile)).note)
  }

  @Test
  fun theSparklineEndsAtTheFigure() {
    val m = EffUnits.fromMiPerKwh(2.32)
    val v = build(state(eff = Efficiency(EffStatus.OK, m, 1.0, 15_000.0, 15_000.0 / m, atM = 9.85 * mile)))
    assertEquals(89, v.spark.size)
    assertEquals(1.1, v.spark.first().x, 1e-9)
    assertEquals(9.85, v.spark.last().x, 1e-9)
    assertEquals(2.32, v.spark.last().y, 1e-9)
    // Exactly on a series point: that point takes the figure.
    val on = build(state(eff = Efficiency(EffStatus.OK, m, 1.0, 15_000.0, 15_000.0 / m, atM = 9.8 * mile)))
    assertEquals(88, on.spark.size)
    assertEquals(2.32, on.spark.last().y, 1e-9)
    // No figure: the line is the series alone.
    val warm = build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 15_000.0, -5.0, atM = 9.85 * mile)))
    assertEquals(88, warm.spark.size)
  }

  @Test
  fun theDrivingTextsAreShortAndKeepWhatChangesTheMeaning() {
    val v = build(state())
    assertEquals("0:24 · 9.8 mi", v.summary)
    assertEquals("Since app opened 0:24 · 9.8 mi", v.header)
    assertNull("nothing left out", v.pauses)
    assertEquals("1 pause", build(state().copy(gaps = 1, gapMs = 700_000)).pauses)
    assertEquals("3 pauses", build(state().copy(gaps = 3, gapMs = 700_000)).pauses)
    assertEquals("Range", v.range.shortTitle)
    assertEquals("Overall", v.efficiency.shortTitle)
    assertEquals("Last 15 min", v.recentEfficiency!!.shortTitle)
    assertEquals("Efficiency", build(state(eff = Efficiency.NONE)).efficiency.shortTitle)
    assertEquals("mi", v.range.shortUnit)
    // Every first line a driver keeps is a figure, not an explanation of one.
    assertTrue(v.range.lines.first(), v.range.lines.first().startsWith("Truck says "))
    assertEquals("0:24 · avg 24 mph", v.trip.lines.first())
    // Measuring: what is true now that the dashboard stays on screen in Drive.
    assertTrue(SimpleModel.MEASURING_HINT.contains("this app or Battery Glance"))
    assertTrue(SimpleModel.MEASURING_HINT.contains("Maps"))
    assertFalse(SimpleModel.MEASURING_HINT.contains("keep Battery Glance"))
  }

  @Test
  fun theApiCapacityIsAlwaysThereOrSaysWhyNot() {
    val rated = build(state(feed = Feed(FeedKind.VEHICLE, sampling = true, ratedWh = 205_000.0))).capacity
    assertEquals(listOf("Usable now, from the car's API", "Rated 205.0 kWh when new"), rated.lines)
    val nominal = build(state(feed = Feed(FeedKind.VEHICLE, sampling = true, capacityNominal = true, ratedWh = 205_080.0))).capacity
    assertEquals(listOf("Nominal, from the car's API"), nominal.lines)
    val waiting = build(SessionState(feed = Feed(FeedKind.VEHICLE, sampling = true))).capacity
    assertFalse(waiting.hasValue)
    assertEquals(listOf("Waiting for the capacity"), waiting.lines)
    val absent = build(SessionState(feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.CAPACITY)))).capacity
    assertEquals(listOf("Capacity not offered by this car"), absent.lines)
    assertFalse("no sentence to read while driving", absent.linesWhileDriving)
  }

  @Test
  fun theEnergyUsedSaysWhatItIsMeasuredFrom() {
    val level = build(state())
    assertEquals("battery level", level.energyUsed.marker)
    assertNull(level.note)
    val mx = build(state().copy(energySource = EnergySource.MX_PACK, energyOutWh = 5_020.0, energyInWh = 810.0, levelUsedWh = 4_680.0))
    assertEquals("MX+", mx.energyUsed.marker)
    assertEquals("Energy from the MX+: 5.02 kWh out, 0.81 kWh back (battery level says 4.68 kWh used)", mx.note)
    assertEquals("charge rate", build(state().copy(energySource = EnergySource.CHARGE_RATE)).energyUsed.marker)
    assertNull("nothing measured yet", build(state().copy(usedWh = 0.0)).energyUsed.marker)
    assertEquals("205.1", build(state().copy(usedWh = 205_080.0)).energyUsed.value)
    assertFalse(build(SessionState()).energyUsed.hasValue)
  }

  @Test
  fun formattingHelpers() {
    assertEquals("0:00", SimpleModel.duration(0))
    assertEquals("1:05", SimpleModel.duration(65 * 60_000L + 59_000))
    assertEquals("0:00", SimpleModel.duration(-5))
    assertEquals("4.21", SimpleModel.kwhValue(4_210.0))
    assertEquals("4.21 kWh", SimpleModel.kwhText(4_210.0))
    assertEquals("205.1 kWh", SimpleModel.kwhText(205_080.0))
    assertEquals("— Wh/mi", SimpleModel.consumptionText(0.0, DistUnit.MI))
    assertEquals("0 mph", SimpleModel.speedText(0.0, DistUnit.MI))
  }

  /** The demo source makes a full-looking session, all of it formatted without dashes. */
  @Test
  fun demoSessionLooksReal() {
    var now = 0L
    val t = SessionTracker { now }
    val demo = DemoSource(22 * 60_000L)
    demo.backlog(22 * 60_000L).forEach { now = it.atMs; t.add(it) }
    val v = build(t.snapshot().copy(feed = DemoSource.FEED.copy(sampling = true)))
    assertTrue(v.spark.size > 50)
    assertFalse(listOf(v.capacity, v.range, v.efficiency, v.recentEfficiency!!, v.trip, v.energyUsed).any { it.value == "—" || !it.hasValue })
    assertTrue(v.note, v.note!!.startsWith("Simulated drive (demo data) · Energy from the truck's charge rate: "))
  }
}
