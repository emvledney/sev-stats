package dev.silveradostats.ui.simple

import dev.silveradostats.link.PowerPoint
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Tone
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.DemoSource
import dev.silveradostats.vehicle.EffSegment
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.Efficiency
import dev.silveradostats.vehicle.EffPoint
import dev.silveradostats.vehicle.Feed
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.PowerDirection
import dev.silveradostats.vehicle.PowerNow
import dev.silveradostats.vehicle.PowerSource
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionState -> the Simple tab's texts, chart scales and states (layouts A and B). */
class SimpleModelTest {
  private val mile = EffUnits.METERS_PER_MILE

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
    series = (11..98).map { EffPoint(it * mile / 10, EffUnits.fromMiPerKwh(2.2 + it / 1000.0)) },
    miSegments = (1..9).map { EffSegment(it, mile, mile, mile / EffUnits.fromMiPerKwh(2.0 + it * 0.05)) } +
      EffSegment(10, mile, mile, -40.0),
    power = PowerNow(23.4, PowerDirection.DISCHARGING, PowerSource.CHARGE_RATE),
    peakDischargeKw = 123.4,
    peakRegenKw = -35.1,
    speedMps = 45 / 2.2369362920544,
    levelWh = 85_250.0,
    capacityWh = 205_080.0,
    rangeM = 304_000.0,
    outsideC = 12.5,
    feed = feed,
  )

  private fun build(s: SessionState, d: DistUnit = DistUnit.MI, t: TempUnit = TempUnit.C) = SimpleModel.build(s, t, d, 5_000_000)

  @Test
  fun layoutAShowsTheMeasuredEfficiencyFirst() {
    val v = build(state())
    assertEquals("Since app opened 0:24 · 9.8 mi", v.header)
    assertNull(v.note)
    assertNull(v.notice)
    assertEquals("2.32", v.hero.value)
    assertEquals("mi/kWh", v.hero.unit)
    assertFalse(v.hero.warming)
    // What the figure is measured over (session totals are on the This session tile).
    assertEquals("431 Wh/mi · 4.00 kWh used over 9.3 mi", v.hero.secondary)
    assertEquals(HeroKind.VALUE, v.hero.kind)
    assertFalse(v.hero.early)
    assertEquals("Truck's range implies 2.22 mi/kWh", v.hero.truck)
    assertEquals(Stat("Battery", "85.3", "/ 205.1 kWh", listOf("41.6 % remaining", "Remaining / usable capacity"), shortUnit = "kWh"), v.battery)
    assertEquals(0.4157, v.batteryFraction!!, 1e-3)
    assertEquals("+23.4", v.power.value)
    assertEquals("Discharging", v.power.pill)
    assertEquals(Tone.DISCHARGE, v.power.pillTone)
    assertEquals(listOf("Speed 45 mph", "From the truck's charge rate"), v.power.lines)
    assertNull(v.power.marker)
    // Range at the measured efficiency: 85.25 kWh x 2.32 mi/kWh = 198 mi; the truck's own 189.
    assertEquals("198", v.range.value)
    assertEquals(listOf("Truck says 189 mi", "85.3 kWh × 2.32 mi/kWh"), v.range.lines)
    assertEquals("9.8", v.session.value)
    assertEquals(listOf("4.21 kWh used · 0:24", "avg 24 mph · outside 12.5 °C"), v.session.lines)
  }

  @Test
  fun metricShowsKmPerKwhAndWhPerKm() {
    val v = build(state(), DistUnit.KM, TempUnit.F)
    assertEquals("Since app opened 0:24 · 15.8 km", v.header)
    assertEquals("3.73", v.hero.value)
    assertEquals("km/kWh", v.hero.unit)
    assertEquals("268 Wh/km · 4.00 kWh used over 15.0 km", v.hero.secondary)
    assertEquals("Speed 72 km/h", v.power.lines[0])
    assertEquals("avg 39 km/h · outside 54.5 °F", v.session.lines[1])
    assertEquals("km", v.chart.distUnit)
  }

  @Test
  fun warmingUpAndEstimatedPowerAreLabelled() {
    val s = state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.4, 640.0, 120.0)).copy(
      power = PowerNow(31.0, PowerDirection.DISCHARGING, PowerSource.ESTIMATED),
      series = emptyList(),
    )
    val v = build(s)
    assertTrue(v.hero.warming)
    assertEquals("Warming up", v.hero.value)
    assertEquals(0.4, v.hero.progress, 0.0)
    assertEquals("Needs 1.0 mi and 0.3 kWh measured · so far 0.4 mi · 0.12 kWh", v.hero.secondary)
    assertTrue(v.chart.warming)
    assertEquals("—", v.range.value)
    assertFalse(v.range.hasValue)
    assertEquals("est.", v.power.marker)
    assertEquals("Estimated from the battery level (30 s)", v.power.lines[1])
    assertTrue(v.powerChart.estimated)
    assertEquals("Warming up", v.efficiencyMini.value)
    assertEquals(listOf("40 % of the warm-up"), v.efficiencyMini.lines)
  }

  @Test
  fun chartScalesAreNiceAndBarsKeepTheirMiles() {
    val c = build(state()).chart
    assertEquals(10.0, c.xMax, 0.0)
    assertEquals(2.0, c.xStep, 0.0)
    assertEquals(3.0, c.yMax, 0.0)
    assertEquals(0.5, c.yStep, 0.0)
    assertEquals(88, c.line.size)
    assertEquals(1.1, c.line.first().x, 1e-9)
    assertEquals(10, c.bars.size)
    assertEquals(2.05, c.bars[0].y!!, 1e-9)
    assertEquals(0.0, c.bars[0].x0, 0.0)
    assertEquals(1.0, c.bars[0].x1, 0.0)
    val regen = c.bars.last()
    assertNull(regen.y)
    assertTrue(regen.regen)
    assertEquals(25.0, SimpleModel.niceCeil(23.1), 0.0)
    assertEquals(0.3, SimpleModel.niceCeil(0.3), 1e-9)
    assertEquals(30.0, SimpleModel.niceCeil(26.0), 0.0)
    assertEquals(30.0, SimpleModel.niceCeil(28.3), 0.0)
    assertEquals(150.0, SimpleModel.niceCeil(101.0), 0.0)
    assertEquals(60.0, SimpleModel.niceCeil(51.0), 0.0)
    assertEquals(1.0, SimpleModel.niceCeil(1.0), 0.0)
    assertEquals(0.5, SimpleModel.niceCeil(0.42), 0.0)
    assertEquals(200.0, SimpleModel.niceCeil(150.1), 0.0)
  }

  @Test
  fun tappedBarsExplainThemselves() {
    val c = build(state()).chart
    val seven = SimpleModel.barAt(c, 6.5)!!
    assertEquals(7, seven.index)
    assertEquals("Mile 7: 2.35 mi/kWh · cumulative 2.27 at 7.0 mi", SimpleModel.barDetail(c, seven))
    assertEquals("Mile 10: net regen (more energy back than used) · cumulative 2.30 at 9.8 mi", SimpleModel.barDetail(c, SimpleModel.barAt(c, 9.99)!!))
    assertNull(SimpleModel.barAt(c, 10.5))
    assertNull(SimpleModel.barAt(c, -0.1))
    val km = build(state().let { it.copy(kmSegments = it.miSegments) }, DistUnit.KM).chart
    assertTrue(SimpleModel.barDetail(km, km.bars.first()).startsWith("Km 1: "))
  }

  @Test
  fun layoutBRingPowerAndMiniTiles() {
    val v = build(state())
    assertEquals("41.6", v.ring.soc)
    assertEquals("85.3 kWh", v.ring.remaining)
    assertEquals("of 205.1 kWh max", v.ring.max)
    assertEquals("Truck range 189 mi", v.ring.truckRange)
    assertEquals("+23.4 kW", v.powerChart.now)
    assertEquals("+123 kW", v.powerChart.peak)
    assertEquals("−35 kW", v.powerChart.regen)
    assertEquals(SimpleModel.WINDOW_MS, v.powerChart.windowMs)
    assertEquals(Stat("Efficiency since opened", "2.32", "mi/kWh", listOf("431 Wh/mi"), shortTitle = "Efficiency"), v.efficiencyMini)
    assertEquals(v.chart.line, v.spark)
    assertEquals(listOf("4.21 kWh used", "0:24 · avg 24 mph"), v.sessionMini.lines)
    val nominal = build(state(feed = Feed(FeedKind.VEHICLE, sampling = true, capacityNominal = true)))
    assertEquals("of 205.1 kWh nominal", nominal.ring.max)
  }

  @Test
  fun tappedPowerPointsSayWhenAndHowMuch() {
    val pts = listOf(PowerPoint(4_880_000, 45.2), PowerPoint(4_990_000, -12.0))
    val v = build(state().copy(powerHistory = pts)).powerChart
    assertEquals(pts[0], SimpleModel.powerAt(v, 4_883_000))
    assertNull(SimpleModel.powerAt(v, 4_930_000))
    assertEquals("2 min ago: +45.2 kW", SimpleModel.powerDetail(v, pts[0]))
    assertEquals("10 s ago: −12.0 kW", SimpleModel.powerDetail(v, pts[1]))
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
    assertEquals(SimpleModel.NEEDS_ACCESS, blocked.hero.secondary)
    assertEquals("No access", blocked.hero.value)
    assertEquals(HeroKind.BLOCKED, blocked.hero.kind)
    assertFalse(blocked.hero.warming)
    assertEquals("No access", blocked.efficiencyMini.value)
    assertEquals(listOf(SimpleModel.NEEDS_ACCESS), blocked.efficiencyMini.lines)
    assertNull("the access notice comes first", blocked.hint)
    assertEquals(SimpleModel.NEEDS_ACCESS, blocked.chart.warmupText)
    assertEquals(SimpleModel.NEEDS_ACCESS, blocked.range.lines[1])
    assertEquals(listOf(SimpleModel.NEEDS_ACCESS), blocked.battery.lines)
    // While driving those two tiles show just the dash: nothing to read, and the notice says why.
    assertFalse(blocked.battery.linesWhileDriving)
    assertFalse(blocked.efficiencyMini.linesWhileDriving)
    assertTrue("a figure's own line stays", state().let { build(it) }.battery.linesWhileDriving)
    assertEquals(listOf("Speed —", SimpleModel.NEEDS_ACCESS), blocked.power.lines)
    assertEquals("Truck range —", blocked.range.lines[0])
    val phone = build(SessionState(feed = Feed(FeedKind.NOT_AUTOMOTIVE, "x")))
    assertEquals(NoticeAction.OPEN_DIAGNOSTICS, phone.notice!!.action)
    assertEquals("No car data on this device", phone.notice!!.title)
    assertEquals("Connecting to the car service…", build(SessionState()).notice!!.title)
    // A property the car does not offer: a dash and a hint, never a made-up value.
    val noSpeed = build(state().copy(speedMps = null, feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.SPEED, Signal.RANGE))))
    assertEquals("Since app opened 0:24 · —", noSpeed.header)
    assertEquals("—", noSpeed.session.value)
    assertEquals("Speed not offered", noSpeed.power.lines[0])
    val noLevel = build(SessionState(samples = 3, feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.LEVEL))))
    assertEquals(listOf("Battery level not offered by this car"), noLevel.battery.lines)
    assertEquals("No data", noLevel.hero.value)
    assertEquals("Needs the battery level: not offered by this car", noLevel.hero.secondary)
    assertEquals("Needs vehicle speed: not offered by this car", build(state(eff = Efficiency.NONE).copy(feed = Feed(FeedKind.VEHICLE, sampling = true, missing = setOf(Signal.SPEED)))).hero.secondary)
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

  @Test
  fun earlyFiguresAreMarkedAndProjectNoRange() {
    val early = state(eff = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(1.43), 1.0, 1_640.0, 710.0, early = true)).copy(distanceM = 1_700.0)
    val v = build(early)
    assertEquals("1.43", v.hero.value)
    assertTrue(v.hero.early)
    assertEquals(HeroKind.VALUE, v.hero.kind)
    assertEquals("—", v.range.value)
    assertFalse(v.range.hasValue)
    assertEquals(listOf("Truck says 189 mi", "After 5 mi and 2 kWh measured"), v.range.lines)
    assertEquals("After 8 km and 2 kWh measured", build(early, DistUnit.KM).range.lines[1])
    assertEquals(SimpleModel.EARLY, v.efficiencyMini.marker)
    assertEquals("1.43", v.efficiencyMini.value)
  }

  @Test
  fun aDescentReadsAsSuch() {
    // 29 mi/kWh: a figure, but no range from it, and the chart keeps the ordinary miles readable.
    val high = EffUnits.fromMiPerKwh(29.32)
    val down = state(eff = Efficiency(EffStatus.OK, high, 1.0, 30_000.0, 30_000.0 / high)).copy(
      series = (11..190).map { EffPoint(it * mile / 10, if (it < 40) EffUnits.fromMiPerKwh(29.0) else EffUnits.fromMiPerKwh(3.0)) },
      miSegments = (1..19).map { EffSegment(it, mile, mile, mile / EffUnits.fromMiPerKwh(2.5)) },
    )
    val v = build(down)
    assertEquals("29.32", v.hero.value)
    assertEquals("—", v.range.value)
    assertEquals("Not from this efficiency: mostly downhill so far", v.range.lines[1])
    assertTrue("y axis ${v.chart.yMax}", v.chart.yMax <= 5.0)
    val allDown = down.copy(miSegments = (1..19).map { EffSegment(it, mile, mile, mile / EffUnits.fromMiPerKwh(60.0)) })
    assertTrue(build(allDown).chart.yMax <= 8.0)
    assertTrue(build(allDown, DistUnit.KM).chart.yMax <= 15.0)

    // Net regen once warm: said in words, with no way back to "Warming up".
    val regen = build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, -120.0)))
    assertEquals("Net regen", regen.hero.value)
    assertEquals(HeroKind.NET_REGEN, regen.hero.kind)
    assertEquals("More energy back than used over 6.5 mi", regen.hero.secondary)
    assertEquals("Almost no net energy used over 6.5 mi", build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, 40.0))).hero.secondary)
    assertEquals("Net regen", regen.efficiencyMini.value)
    assertEquals("Not from this efficiency: mostly downhill so far", regen.range.lines[1])
  }

  @Test
  fun warmUpTextsSayWhatIsReallyNeeded() {
    val coarse = build(state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.33, 3_700.0, 1_000.0, needWh = 3_000.0)).copy(series = emptyList()))
    assertEquals("Needs 1.0 mi and 3.0 kWh measured · so far 2.3 mi · 1.00 kWh", coarse.hero.secondary)
    assertEquals("The line starts after the warm-up (1 mi and 3.0 kWh)", coarse.chart.warmupText)
    assertEquals("The line starts after the warm-up (1.6 km and 0.3 kWh)", build(state(), DistUnit.KM).chart.warmupText)

    // A level that never moves gets an explanation, whatever the header's distance says.
    val stuck = state(eff = Efficiency(EffStatus.WARMING_UP, null, 0.0, 0.0, 0.0)).copy(series = emptyList(), distanceM = 18.6 * mile, levelUnchangedM = 18.6 * mile)
    assertEquals("The truck's battery level has not changed for 18.6 mi: nothing to measure yet", build(stuck).hero.secondary)
    assertNull(build(stuck).note)
    // Once there is a figure, the note says it stopped moving.
    assertEquals("Battery level unchanged for 4.0 mi", build(state().copy(levelUnchangedM = 4 * mile)).note)
    assertNull(build(state().copy(levelUnchangedM = 2 * mile)).note)
  }

  @Test
  fun theChartLineEndsAtTheHeroFigure() {
    val m = EffUnits.fromMiPerKwh(2.32)
    val v = build(state(eff = Efficiency(EffStatus.OK, m, 1.0, 15_000.0, 15_000.0 / m, atM = 9.85 * mile)))
    assertEquals(89, v.chart.line.size)
    assertEquals(9.85, v.chart.line.last().x, 1e-9)
    assertEquals(2.32, v.chart.line.last().y, 1e-9)
    assertEquals(v.chart.line, v.spark)
    // Exactly on a series point: that point takes the hero's figure.
    val on = build(state(eff = Efficiency(EffStatus.OK, m, 1.0, 15_000.0, 15_000.0 / m, atM = 9.8 * mile)))
    assertEquals(88, on.chart.line.size)
    assertEquals(2.32, on.chart.line.last().y, 1e-9)
    // No figure: the line is the series alone.
    val warm = build(state(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 15_000.0, -5.0, atM = 9.85 * mile)))
    assertEquals(88, warm.chart.line.size)
  }

  @Test
  fun peakLabelsSayWhatTheyAre() {
    val v = build(state())
    assertEquals("Peak drive", v.powerChart.peakTitle)
    assertEquals("Peak regen / charge", v.powerChart.regenTitle)
    val est = build(state().copy(powerHistoryEstimated = true))
    assertEquals("Peak drive (est.)", est.powerChart.peakTitle)
    assertEquals("Peak regen / charge (est.)", est.powerChart.regenTitle)
  }

  @Test
  fun theDrivingTextsAreShortAndKeepWhatChangesTheMeaning() {
    val v = build(state())
    assertEquals("0:24 · 9.8 mi", v.summary)
    assertEquals("Since app opened 0:24 · 9.8 mi", v.header)
    assertNull("nothing left out", v.pauses)
    assertEquals("431 Wh/mi", v.hero.consumption)
    assertEquals("1 pause", build(state().copy(gaps = 1, gapMs = 700_000)).pauses)
    assertEquals("3 pauses", build(state().copy(gaps = 3, gapMs = 700_000)).pauses)
    assertEquals("Range", v.range.shortTitle)
    assertEquals("Efficiency", v.efficiencyMini.shortTitle)
    assertEquals(v.battery.title, v.battery.shortTitle)
    assertEquals("/ 205.1 kWh", v.battery.unit)
    assertEquals("kWh", v.battery.shortUnit)
    assertEquals("mi", v.range.shortUnit)
    // Every first line a driver keeps is a figure, not an explanation of one.
    assertEquals("Speed 45 mph", v.power.lines.first())
    assertTrue(v.range.lines.first(), v.range.lines.first().startsWith("Truck says "))
    // Measuring: what is true now that the dashboard stays on screen in Drive.
    assertTrue(SimpleModel.MEASURING_HINT.contains("this app or Battery Glance"))
    assertTrue(SimpleModel.MEASURING_HINT.contains("Maps"))
    assertFalse(SimpleModel.MEASURING_HINT.contains("keep Battery Glance"))
  }

  @Test
  fun formattingHelpers() {
    assertEquals("0:00", SimpleModel.duration(0))
    assertEquals("1:05", SimpleModel.duration(65 * 60_000L + 59_000))
    assertEquals("0:00", SimpleModel.duration(-5))
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
    assertFalse(v.hero.warming)
    assertTrue(v.chart.line.size > 50)
    assertTrue(v.chart.bars.size >= 10)
    assertTrue(v.powerChart.points.size > 500)
    assertFalse(listOf(v.battery, v.power, v.range, v.session).any { it.value == "—" })
    assertEquals("Simulated drive (demo data)", v.note)
  }
}
