package dev.silveradostats.ui.live

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.link.SavedCapacity
import dev.silveradostats.link.PowerPoint
import dev.silveradostats.obd.Poller
import dev.silveradostats.obd.Profile
import dev.silveradostats.obd.SimTransport
import dev.silveradostats.obd.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** LiveState -> dashboard mapping, derived-value formatting and unit conversion. */
class DashboardModelTest {

  private val periods = TestSupport.profile.didFields.associate { it.name to it.period }

  /** One full poller cycle against the simulator (t = 0: 40 A discharge, SoC 72 %). */
  private fun simState(): LiveState {
    val now = 1000.0
    val sim = SimTransport(clock = { now }, sleeper = {})
    val poller = Poller(TestSupport.profile, sim, clock = { now }, wallClock = { 0.0 })
    val sample = poller.cycle()
    val values = poller.latest.mapValues { (k, r) -> LiveValue(k, r.label, r.v, r.unit, r.status, periods[k] ?: 1.0, NOW) }
    return LiveState(
      status = LinkStatus.DEMO,
      demo = true,
      values = values,
      derived = sample.derived,
      lastDataAtMs = NOW,
      adapterVolts = 13.9,
      power = listOf(PowerPoint(NOW - 400_000, 99.0), PowerPoint(NOW - 2_000, 15.0), PowerPoint(NOW - 1_000, -20.0)),
      polling = true,
    )
  }

  private fun build(live: LiveState, vhal: VhalFigures = VhalFigures(), t: TempUnit = TempUnit.C, d: DistUnit = DistUnit.MI, now: Long = NOW) =
    DashboardModel.build(live, vhal, t, d, now)

  private fun value(name: String, v: Any?, status: String = "confirmed", atMs: Long = NOW, period: Double = 1.0) =
    LiveValue(name, name, v, "", status, period, atMs)

  /** Every text the dashboard would draw. */
  private fun texts(d: Dashboard): List<String> =
    listOf(d.soc, d.capacity, d.capacityAh, d.capacityDeviation, d.spread).flatMap { listOf(it.title, it.value, it.unit) + it.lines + listOfNotNull(it.marker) } +
      (d.pack + d.temps).flatMap { listOfNotNull(it.label, it.value, it.marker) } + listOfNotNull(d.status.message, d.obdHint)

  @Test
  fun simulatorStateMapsToAllTiles() {
    val d = build(simState())
    // SoC: OBD value, kWh and BMS range.
    assertEquals("72.0", d.soc.value)
    assertEquals("%", d.soc.unit)
    assertNull("confirmed: no marker", d.soc.marker)
    assertEquals(listOf("147.9 kWh remaining", "Range 317 mi (BMS)"), d.soc.lines)
    // Capacity: both read from the BECM (27AE, 4676), each next to what backs it up.
    assertEquals("205.20", d.capacity.value)
    assertEquals("kWh", d.capacity.unit)
    assertEquals("unverified", d.capacity.marker)
    assertEquals("Battery capability, from the BECM", d.capacity.lines[0])
    assertTrue(d.capacity.lines[1], d.capacity.lines[1].startsWith("Energy ÷ SoC: 205."))
    assertEquals("24 modules · 96 series groups", d.capacity.lines[2])
    assertEquals("593.4", d.capacityAh.value)
    assertEquals("Ah", d.capacityAh.unit)
    assertEquals("unverified", d.capacityAh.marker)
    // The deviation's DID is unknown: no figure rather than a guess.
    assertFalse(d.capacityDeviation.hasValue)
    assertEquals("PID not identified yet", d.capacityDeviation.lines[0])
    // Pack: V and A, + = discharge, and the cell spread.
    assertEquals(listOf("Pack voltage", "Pack current", "Cell spread"), d.pack.map { it.label })
    assertEquals("+40.0 A", d.pack[1].value)
    assertTrue(d.pack[2].value, d.pack[2].value.endsWith(" mV"))
    // The simulated truck draws 40 A: the array was read under load, so the spread is the larger
    // per-half spread from the BMS summaries (half A holds the low outlier, group 47 in module 6).
    val heat = d.heatmap!!
    assertTrue(heat.underLoad)
    val halfA = DashboardModel.halfSummaries(simState().values).single { it.half == "a" }
    assertEquals(Units.num(halfA.spreadMv, 1), d.spread.value)
    assertEquals("half A ${Units.num(halfA.lowV, 4)}–${Units.num(halfA.highV, 4)} V · under load", d.spread.lines[0])
    assertEquals("low: group 47 · module 6", d.spread.lines[1])
    assertTrue(d.spread.lines[2], d.spread.lines[2].startsWith("high: group "))
    assertNull(d.obdHint)
    // Status.
    assertEquals("Demo: simulated truck", d.status.chip)
    assertTrue(d.status.demo)
    assertEquals("13.9 V at the adapter", d.status.adapterVolts)
  }

  /** The simulator state with the pack at rest (0.3 A, ~0.1 kW around the cell reads). */
  private fun atRest(live: LiveState): LiveState = live.copy(
    values = live.values + ("pack_current" to live.values.getValue("pack_current").copy(v = 0.3)),
    power = listOf(PowerPoint(NOW - 2_000, 0.12), PowerPoint(NOW - 1_000, 0.11), PowerPoint(NOW, 0.1)),
  )

  @Test
  fun cellsReadAtRestUseTheFullArray() {
    val d = build(atRest(simState()))
    val heat = d.heatmap!!
    assertFalse(heat.underLoad)
    assertEquals("low: group 47 · module 6", d.spread.lines[1])
    assertTrue(d.spread.lines[2].startsWith("high: group ${heat.max.group} · module ${heat.max.module}"))
    assertEquals("${Units.num(heat.min.volts!!, 4)}–${Units.num(heat.max.volts!!, 4)} V", d.spread.lines[0])
    assertEquals(Units.num((heat.max.volts!! - heat.min.volts!!) * 1000, 1), d.spread.value)
    assertTrue(DashboardModel.spreadFromArray(atRest(simState()).values, heat))
  }

  /**
   * Review finding: the 16 cell DIDs are read over ~2 s. With a 250 A step between the first and
   * the last read, groups read later sag more, and the array spread doubles. The per-half BMS
   * summaries (one read each) do not see the step.
   */
  @Test
  fun loadStepDuringTheCellReadIsNotShownAsSpread() {
    val rest = atRest(simState())
    val cells = rest.values.filterKeys { it.startsWith("cells_") }.toSortedMap()
    // Same pack, but DIDs 9-16 (groups 97-192) read after a 250 A step: 0.04 mV/A lower (the sim's slope).
    val stepped = cells.entries.withIndex().associate { (i, e) ->
      val list = e.value.v as List<*>
      e.key to e.value.copy(v = if (i >= 8) list.map { (it as Double) - 250 * 0.00004 } else list)
    }
    val power = listOf(PowerPoint(NOW - 2_500, 0.1), PowerPoint(NOW - 1_000, 95.0), PowerPoint(NOW, 96.0))
    val skewed = rest.copy(values = rest.values + stepped, power = power)
    val d = build(skewed)
    assertTrue(d.heatmap!!.underLoad)
    // The array would claim several mV more than the real spread (16.0 vs 11.6 mV here) …
    val arrayMv = (d.heatmap!!.max.volts!! - d.heatmap!!.min.volts!!) * 1000
    val restMv = build(rest).spread.value.toDouble()
    assertTrue("array spread $arrayMv vs at rest $restMv", arrayMv > restMv + 4)
    // … the tile shows the per-half summary instead, which no load step between DIDs can inflate.
    val halves = DashboardModel.halfSummaries(skewed.values)
    assertEquals(Units.num(halves.maxOf { it.spreadMv }, 1), d.spread.value)
    assertTrue(d.spread.value.toDouble() <= restMv + 0.05)
    assertTrue(d.spread.lines[0].endsWith(" · under load"))
  }

  @Test
  fun underLoadRules() {
    val cells = mapOf("cells_001_012" to value("cells_001_012", List(12) { 3.7 }, atMs = NOW, period = 5.0))
    fun under(power: List<PowerPoint>, current: LiveValue? = null): Boolean {
      val v = if (current != null) cells + ("pack_current" to current) else cells
      return DashboardModel.readUnderLoad(cells.values.toList(), v, power)
    }
    assertFalse("no current information: at rest", under(emptyList()))
    assertFalse(under(listOf(PowerPoint(NOW - 2_000, 0.2), PowerPoint(NOW, -0.3))))
    assertTrue("discharging", under(listOf(PowerPoint(NOW - 1_000, 25.0))))
    assertTrue("regen", under(listOf(PowerPoint(NOW, -12.0))))
    assertTrue("load change of 1.5 kW", under(listOf(PowerPoint(NOW - 1_000, 0.2), PowerPoint(NOW, 1.7))))
    assertFalse("load long before the read", under(listOf(PowerPoint(NOW - 10_000, 80.0), PowerPoint(NOW, 0.1))))
    assertFalse("load after the read", under(listOf(PowerPoint(NOW + 1_000, 80.0))))
    assertTrue("current alone", under(emptyList(), value("pack_current", 12.0, atMs = NOW)))
    assertTrue("current alone, charging", under(emptyList(), value("pack_current", -8.0, atMs = NOW - 2_000)))
    assertFalse(under(emptyList(), value("pack_current", 0.6, atMs = NOW)))
    assertFalse("current read long before", under(emptyList(), value("pack_current", 40.0, atMs = NOW - 20_000)))
    assertFalse("nothing read", DashboardModel.readUnderLoad(emptyList(), cells, listOf(PowerPoint(NOW, 50.0))))
  }

  @Test
  fun underLoadWithoutSummariesKeepsTheArrayButSaysSo() {
    val live = simState()
    val noSummaries = live.copy(values = live.values.filterKeys { !it.matches(Regex("cell_(min|max|avg)_.*")) })
    val d = build(noSummaries)
    val heat = d.heatmap!!
    assertTrue(heat.underLoad)
    assertTrue(DashboardModel.spreadFromArray(noSummaries.values, heat))
    assertEquals(Units.num((heat.max.volts!! - heat.min.volts!!) * 1000, 1), d.spread.value)
    assertTrue(d.spread.lines[0], d.spread.lines[0].endsWith(" V · under load"))
  }

  @Test
  fun summariesUnderLoadArePerHalf() {
    val v = mapOf(
      "cell_min_a" to value("cell_min_a", 3.6692), "cell_min_index_a" to value("cell_min_index_a", 41L), "cell_min_module_a" to value("cell_min_module_a", 6L),
      "cell_max_a" to value("cell_max_a", 3.6769), "cell_max_index_a" to value("cell_max_index_a", 81L), "cell_max_module_a" to value("cell_max_module_a", 11L),
      "cell_min_b" to value("cell_min_b", 3.6609), "cell_min_index_b" to value("cell_min_index_b", 180L), "cell_min_module_b" to value("cell_min_module_b", 23L),
      "cell_max_b" to value("cell_max_b", 3.6700), "cell_max_index_b" to value("cell_max_index_b", 105L), "cell_max_module_b" to value("cell_max_module_b", 14L),
    )
    val derived = mapOf("cell_min_v" to 3.6609, "cell_max_v" to 3.6769, "cell_spread_mv" to 16.0)
    // At rest: both halves together, as Derive computes them.
    assertEquals("16.0", DashboardModel.spread(v, derived, null, NOW).value)
    // Under load the two reads (2AF6, 2AF5) may straddle a load change: the larger per-half spread.
    val k = DashboardModel.spread(v, derived, null, NOW, listOf(PowerPoint(NOW, 40.0)))
    assertEquals("9.1", k.value)
    assertEquals(listOf("half B 3.6609–3.6700 V · under load", "low: group 180 · module 23", "high: group 105 · module 14"), k.lines)
  }

  @Test
  fun heatmapMatchesTheArrayAndDerive() {
    val live = simState()
    val heat = build(live).heatmap!!
    assertEquals(24, heat.modules)
    assertEquals(8, heat.groupsPerModule)
    assertEquals(192, heat.read)
    assertEquals(192, heat.cells.size)
    assertEquals(12, heat.halfAfter)
    // Same first-lowest / first-highest as Derive.
    assertEquals((live.derived["array_min_group"] as Number).toInt(), heat.min.group)
    assertEquals((live.derived["array_max_group"] as Number).toInt(), heat.max.group)
    assertEquals(47, heat.min.group)
    assertEquals(6, heat.min.module)
    // Grid position: module 6, row 6 is group 47.
    assertEquals(47, heat.cell(6, 6)!!.group)
    val arr = DashboardModel.cellArray(live.values).map { it!! }
    val avg = arr.sum() / arr.size
    assertEquals(avg, heat.avgV, 1e-12)
    assertEquals((arr[46] - avg) * 1000, heat.cell(6, 6)!!.devMv!!, 1e-9)
    assertTrue(heat.cells.all { it.module == (it.group - 1) / 8 + 1 && it.row == (it.group - 1) % 8 })
    // Colour scale covers the largest deviation.
    assertTrue(heat.cells.all { kotlin.math.abs(it.devMv!!) <= heat.scaleMv })
    assertTrue(DashboardModel.cellDetail(heat.min).startsWith("group 47 · module 6 · 3.9"))
    assertTrue(DashboardModel.cellDetail(heat.min).endsWith("mV vs avg"))
  }

  private fun cellsOf(volts: List<Double>): Map<String, LiveValue> =
    volts.chunked(12).withIndex().associate { (i, chunk) ->
      val name = String.format(Locale.US, "cells_%03d_%03d", i * 12 + 1, i * 12 + 12)
      name to value(name, chunk, period = 5.0)
    }

  @Test
  fun smallerPacksDrawWhatTheArrayReturns() {
    for ((groups, modules) in listOf(160 to 20, 112 to 14)) {
      val volts = List(192) { i -> if (i < groups) 3.7 + i * 1e-5 else 0.0 }
      val heat = DashboardModel.heatmap(cellsOf(volts), NOW)!!
      assertEquals(modules, heat.modules)
      assertEquals(groups, heat.read)
      assertEquals(groups, heat.cells.size)
      assertEquals(1, heat.min.group)
      assertEquals(groups, heat.max.group)
    }
    // Padding in the middle stays empty; the grid still reaches the last real group.
    val gaps = List(192) { i -> if (i in 88..95) 0.0 else 3.7 }
    val heat = DashboardModel.heatmap(cellsOf(gaps), NOW)!!
    assertEquals(24, heat.modules)
    assertEquals(184, heat.read)
    assertNull(heat.cell(12, 0)!!.volts)
    assertNull(heat.cell(12, 0)!!.devMv)
    assertEquals("group 89 · module 12 · no data", DashboardModel.cellDetail(heat.cell(12, 0)!!))
    // Nothing real: no heatmap.
    assertNull(DashboardModel.heatmap(cellsOf(List(192) { 0.0 }), NOW))
    assertNull(DashboardModel.heatmap(emptyMap(), NOW))
  }

  @Test
  fun temperaturesAndUnits() {
    val c = build(simState(), t = TempUnit.C).temps
    assertEquals(
      listOf("Modules min · avg · max", "Battery coolant 1", "Battery coolant 2", "Outside (car API)", "12 V system"),
      c.map { it.label },
    )
    assertEquals("likely", c[0].marker)
    assertTrue(c[0].value, c[0].value.matches(Regex("""2\d · 2\d\.\d · 2\d °C""")))
    assertEquals("21.0 °C", c[1].value)
    assertEquals("24.5 °C", c[2].value)
    assertEquals("—", c[3].value)
    assertEquals("12.5 V", c[4].value)
    val f = build(simState(), vhal = VhalFigures(outsideC = -3.5), t = TempUnit.F).temps
    assertEquals("69.8 °F", f[1].value)
    assertEquals("76.1 °F", f[2].value)
    assertEquals("25.7 °F", f[3].value)
    assertTrue(f[0].value.endsWith("°F"))
    // Module temperature strip: 24 values, "likely".
    val mt = build(simState()).moduleTemps!!
    assertEquals(24, mt.celsius.size)
    assertEquals("likely", mt.marker)
  }

  @Test
  fun moduleTempPaddingIsDropped() {
    val raw = List(24) { i -> if (i < 20) 17.0 else -80.0 }
    val mt = DashboardModel.moduleTemps(mapOf("module_temps" to value("module_temps", raw, status = "unverified", period = 10.0)), NOW)!!
    assertEquals(20, mt.celsius.size)
    assertTrue(mt.celsius.all { it == 17.0 })
  }

  @Test
  fun neverShowsTheWrongBcmTemperatures() {
    val live = simState()
    assertTrue("the simulator answers them", Profile.NOT_DISPLAYED.all { it in live.values })
    val all = texts(build(live))
    for (bad in listOf("HV battery temperature", "Battery temperature sensor", "hv_battery_temp", "battery_temp_a", "battery_temp_b")) {
      assertTrue("dashboard shows '$bad'", all.none { it.contains(bad) })
    }
    // Only those values: treated as no OBD data at all.
    val only = LiveState(values = Profile.NOT_DISPLAYED.associateWith { value(it, 60.0, status = "unverified") })
    val d = build(only)
    assertNotNull(d.obdHint)
    assertTrue(texts(d).none { it.contains("60") })
  }

  @Test
  fun unverifiedValuesAreMarked() {
    val v = mapOf(
      "coolant_temp_1" to value("coolant_temp_1", 21.0, status = "unverified"),
      "coolant_temp_2" to value("coolant_temp_2", 20.0),
    )
    val t = build(LiveState(values = v)).temps
    assertEquals("unverified", t[1].marker)
    assertNull(t[2].marker)
    assertEquals("likely", DashboardModel.marker(value("module_temps", listOf(17.0), status = "unverified")))
    assertEquals("unverified", DashboardModel.marker(value("pack_temp_candidate", 20.0, status = "community-ultium")))
    assertNull(DashboardModel.marker(value("soc", 50.0)))
  }

  @Test
  fun theAppsOwnRangeComesFirstWithTheTrucksBesideIt() {
    val vhal = VhalFigures(energyWh = 85_300.0, nominalWh = 205_000.0, rangeM = 300_000.0)
    val off = DashboardModel.build(LiveState(status = LinkStatus.ADAPTER_UNREACHABLE), vhal, TempUnit.C, DistUnit.KM, NOW, ownRangeM = 320_000.0)
    assertEquals(listOf("85.3 kWh remaining (VHAL)", "Range 320 km · truck 300 km"), off.soc.lines)
    assertEquals(listOf("85.3 kWh", "Range 320 km · truck 300 km"), off.soc.drivingLines)
    val obd = DashboardModel.build(simState(), VhalFigures(), TempUnit.C, DistUnit.KM, NOW, ownRangeM = 320_000.0)
    assertEquals("Range 320 km · truck 510 km", obd.soc.lines[1])
    val alone = DashboardModel.build(LiveState(status = LinkStatus.ADAPTER_UNREACHABLE), VhalFigures(energyWh = 85_300.0), TempUnit.C, DistUnit.KM, NOW, ownRangeM = 320_000.0)
    assertEquals("Range 320 km", alone.soc.lines[1])
  }

  @Test
  fun vhalFallbackWhenObdIsUnavailable() {
    val vhal = VhalFigures(energyWh = 85_300.0, nominalWh = 205_000.0, rangeM = 300_000.0, outsideC = -3.5)
    val d = build(LiveState(status = LinkStatus.ADAPTER_UNREACHABLE), vhal, TempUnit.C, DistUnit.KM)
    assertEquals("41.6", d.soc.value)
    assertEquals("VHAL", d.soc.marker)
    assertEquals(listOf("85.3 kWh remaining (VHAL)", "Range 300 km (VHAL)"), d.soc.lines)
    // The API's figure is not passed off as the BECM's.
    assertFalse(d.capacity.hasValue)
    assertEquals(listOf("Needs the OBDLink MX+", "The car's API says 205.0 kWh"), d.capacity.lines)
    assertFalse(d.capacityAh.hasValue)
    assertEquals("—", d.pack[0].value)
    assertFalse(d.spread.hasValue)
    assertNull(d.heatmap)
    assertEquals("−3.5 °C", d.temps[3].value)
    assertNotNull(d.obdHint)
    assertEquals("MX+ unreachable", d.status.chip)
    // Usable capacity is preferred over nominal for the SoC.
    assertEquals(50.0, VhalFigures(energyWh = 100.0, usableWh = 200.0, nominalWh = 400.0).socPct!!, 1e-9)
    assertNull(VhalFigures(energyWh = 100.0).socPct)
    // Nothing at all.
    val empty = build(LiveState())
    assertFalse(empty.soc.hasValue)
    assertEquals("—", empty.soc.value)
  }

  @Test
  fun statusChipsAgesAndStaleness() {
    val chips = LinkStatus.entries.associateWith { DashboardModel.chip(it, demo = false) }
    assertEquals("MX+ connected" to Tone.GOOD, chips[LinkStatus.CONNECTED])
    assertEquals("MX+ unreachable" to Tone.WARN, chips[LinkStatus.ADAPTER_UNREACHABLE])
    assertEquals("Truck asleep" to Tone.DIM, chips[LinkStatus.TRUCK_ASLEEP])
    assertEquals("Stopped: error" to Tone.ERROR, chips[LinkStatus.STOPPED_ERROR])
    assertEquals("No adapter", chips.getValue(LinkStatus.NO_ADAPTER).first)
    assertTrue(chips.values.all { it.first.isNotBlank() })
    val s = DashboardModel.status(
      LiveState(status = LinkStatus.ADAPTER_UNREACHABLE, lastDataAtMs = NOW - 12_000, nextAutoRetryAtMs = NOW + 14_500, canRetry = true),
      NOW,
    )
    assertEquals("data 12 s old", s.dataAge)
    assertTrue(s.dataStale)
    assertEquals("auto retry in 14 s", s.retryIn)
    assertTrue(s.canRetry)
    assertNull(DashboardModel.status(LiveState(), NOW).dataAge)
    // Per-value age: SoC (2 s period) is stale after 6 s.
    val fresh = DashboardModel.soc(mapOf("soc" to value("soc", 50.0, atMs = NOW - 4_200, period = 2.0)), VhalFigures(), DistUnit.MI, NOW)
    assertEquals("4.2 s", fresh.age)
    assertFalse(fresh.stale)
    val old = DashboardModel.soc(mapOf("soc" to value("soc", 50.0, atMs = NOW - 7_000, period = 2.0)), VhalFigures(), DistUnit.MI, NOW)
    assertTrue(old.stale)
    assertEquals(5_000L, DashboardModel.staleAfterMs(1.0))
    assertEquals(30_000L, DashboardModel.staleAfterMs(10.0))
  }

  @Test
  fun spreadFallsBackToTheHalfSummaries() {
    val v = mapOf(
      "cell_min_a" to value("cell_min_a", 3.6692), "cell_min_index_a" to value("cell_min_index_a", 41L), "cell_min_module_a" to value("cell_min_module_a", 6L),
      "cell_max_a" to value("cell_max_a", 3.6769), "cell_max_index_a" to value("cell_max_index_a", 81L), "cell_max_module_a" to value("cell_max_module_a", 11L),
      "cell_min_b" to value("cell_min_b", 3.6709), "cell_min_index_b" to value("cell_min_index_b", 180L), "cell_min_module_b" to value("cell_min_module_b", 23L),
      "cell_max_b" to value("cell_max_b", 3.6769), "cell_max_index_b" to value("cell_max_index_b", 105L), "cell_max_module_b" to value("cell_max_module_b", 14L),
    )
    val derived = mapOf("cell_min_v" to 3.6692, "cell_max_v" to 3.6769, "cell_spread_mv" to 7.7)
    val k = DashboardModel.spread(v, derived, null, NOW)
    assertEquals("7.7", k.value)
    assertEquals(listOf("3.6692–3.6769 V", "low: group 41 · module 6", "high: group 81 · module 11"), k.lines)
  }

  @Test
  fun unitConversion() {
    assertEquals(32.0, Units.temp(0.0, TempUnit.F), 1e-9)
    assertEquals(212.0, Units.temp(100.0, TempUnit.F), 1e-9)
    assertEquals(-40.0, Units.temp(-40.0, TempUnit.F), 1e-9)
    assertEquals(21.6, Units.temp(21.6, TempUnit.C), 1e-9)
    assertEquals("21.6 °C", Units.tempText(21.6, TempUnit.C))
    assertEquals("70.9 °F", Units.tempText(21.6, TempUnit.F))
    assertEquals("—", Units.tempText(null, TempUnit.C))
    assertEquals("—", Units.tempText(Double.NaN, TempUnit.C))
    assertEquals("188 mi", Units.distText(187.6, DistUnit.MI))
    assertEquals("302 km", Units.distText(187.6, DistUnit.KM))
    assertEquals(1.0, Units.distFromMeters(1609.344, DistUnit.MI), 1e-12)
    assertEquals(1.609344, Units.distFromMeters(1609.344, DistUnit.KM), 1e-12)
    assertEquals(160.9344, Units.distFromMiles(100.0, DistUnit.KM), 1e-9)
  }

  @Test
  fun numberFormatting() {
    assertEquals("0.0", Units.num(-0.04, 1))
    assertEquals("0.0", Units.num(-0.0, 1))
    assertEquals("−0.1", Units.num(-0.06, 1))
    assertEquals("+12.3", Units.signed(12.34, 1))
    assertEquals("−8.0", Units.signed(-8.0, 1))
    assertEquals("0.0", Units.signed(0.0, 1))
    assertEquals("0.0", Units.signed(0.04, 1))
    assertEquals("3.6692", Units.num(3.6692, 4))
    assertEquals("850 ms", Units.age(850))
    assertEquals("4.2 s", Units.age(4_200))
    assertEquals("37 s", Units.age(37_000))
    assertEquals("3 min", Units.age(180_000))
    assertEquals("3 h", Units.age(3 * 3_600_000L))
    assertEquals("0 ms", Units.age(-5))
  }

  private companion object {
    const val NOW = 1_000_000L
    const val MAX_DRIVING_LINE = 24
  }

  @Test
  fun capacityFallsBackToCalculatedThenToTheLastRead() {
    val noRead = simState().let { it.copy(derived = it.derived - "capacity_kwh" - "capacity_ah") }
    val calc = DashboardModel.build(noRead, VhalFigures(), TempUnit.C, DistUnit.MI, NOW)
    assertEquals("est.", calc.capacity.marker)
    assertEquals("Calculated: energy ÷ SoC", calc.capacity.lines[0])
    assertEquals("est.", calc.capacityAh.marker)
    assertEquals("Calculated: kWh ÷ (96 series × 3.6 V)", calc.capacityAh.lines[0])
    val saved = SavedCapacity(208.44, 602.2, atWallMs = 1_000_000L)
    val off = DashboardModel.build(LiveState(status = LinkStatus.DISCONNECTED), VhalFigures(), TempUnit.C, DistUnit.MI, NOW, saved, 1_000_000L + 7_200_000L)
    assertEquals("208.44", off.capacity.value)
    assertEquals("602.2", off.capacityAh.value)
    assertEquals(DashboardModel.LAST_READ, off.capacity.marker)
    assertEquals(listOf("Read 2 h ago"), off.capacityAh.drivingLines)
    assertEquals("MX+ disconnected", off.status.chip)
  }

  @Test
  fun tilesSayWhatStaysWhileDriving() {
    val d = DashboardModel.build(simState(), VhalFigures(), TempUnit.C, DistUnit.MI, NOW)
    assertEquals("Charge", d.soc.shortTitle)
    assertEquals(2, d.soc.drivingLines.size)
    assertEquals("Capacity's lines only explain it", emptyList<String>(), d.capacity.drivingLines)
    // Cell min / max stays in its tile.
    assertEquals("Cell spread", d.spread.shortTitle)
    assertEquals(listOf(d.spread.shortLine), d.spread.drivingLines)
    assertTrue(d.spread.lines.first(), d.spread.lines.first().contains(" V"))
    assertTrue(d.spread.shortLine!!, Regex("""\d\.\d{4}–\d\.\d{4} V( · load)?""").matches(d.spread.shortLine!!))
  }

  /** No adapter in Drive: the dash and its marker, or a bare figure. No sentence to read or cut off. */
  @Test
  fun whileDrivingATileHasNoSentences() {
    val noAdapter = DashboardModel.build(LiveState(), VhalFigures(), TempUnit.C, DistUnit.MI, NOW)
    for (k in listOf(noAdapter.soc, noAdapter.capacity, noAdapter.capacityAh, noAdapter.capacityDeviation, noAdapter.spread)) {
      assertEquals(Units.DASH, k.value)
      assertEquals(k.title, emptyList<String>(), k.drivingLines)
    }
    val vhal = DashboardModel.build(LiveState(), VhalFigures(energyWh = 150_000.0, usableWh = 200_000.0, nominalWh = 205_000.0, rangeM = 400_000.0), TempUnit.C, DistUnit.MI, NOW)
    assertEquals(listOf("150.0 kWh", "Range 249 mi"), vhal.soc.drivingLines)
    assertEquals("the marker says where it is from", "VHAL", vhal.soc.marker)
    assertEquals(emptyList<String>(), vhal.capacity.drivingLines)
    for (d in listOf(noAdapter, vhal, DashboardModel.build(simState(), VhalFigures(), TempUnit.C, DistUnit.MI, NOW))) {
      for (k in listOf(d.soc, d.capacity, d.capacityAh, d.capacityDeviation, d.spread)) {
        for (line in k.drivingLines) {
          assertTrue(line, line.length <= MAX_DRIVING_LINE)
          assertTrue(line, line.any { it.isDigit() })
        }
      }
    }
  }

  @Test
  fun theDrivingSummaryIsFourShortLines() {
    val d = DashboardModel.build(simState(), VhalFigures(outsideC = 21.6), TempUnit.C, DistUnit.MI, NOW)
    assertEquals(5, d.temps.size)
    assertEquals(4, d.tempSummary.size)
    assertEquals(listOf("Modules", "Coolant", "Outside", "12 V"), d.tempSummary.map { it.shortLabel })
    assertTrue(d.tempSummary[0].shortValue, d.tempSummary[0].shortValue.contains("–"))
    assertTrue(d.tempSummary[1].shortValue, d.tempSummary[1].shortValue.contains(" · "))
    assertEquals("22 °C".substringAfter(" "), d.tempSummary[2].shortValue.substringAfter(" "))
    // Nothing known: dashes, not a blank or a made-up figure.
    val empty = DashboardModel.tempSummary(DashboardModel.temps(emptyMap(), VhalFigures(), LiveState(), TempUnit.C, NOW))
    assertEquals(4, empty.size)
    assertTrue(empty.all { it.shortValue == Units.DASH })
  }
}
