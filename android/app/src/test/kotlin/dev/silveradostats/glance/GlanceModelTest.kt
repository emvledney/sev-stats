package dev.silveradostats.glance

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.obd.Poller
import dev.silveradostats.obd.Profile
import dev.silveradostats.obd.SimTransport
import dev.silveradostats.obd.TestSupport
import dev.silveradostats.ui.live.DashboardModel
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.Efficiency
import dev.silveradostats.vehicle.Feed
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.PowerDirection
import dev.silveradostats.vehicle.PowerNow
import dev.silveradostats.vehicle.PowerSource
import dev.silveradostats.vehicle.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LiveState -> Battery Glance rows: fixed titles, values, markers, staleness and the header label. */
class GlanceModelTest {

  private val periods = TestSupport.profile.didFields.associate { it.name to it.period }

  /** One full poller cycle against the simulator (t = 0: 40 A discharge, SoC 72 %). */
  private fun simState(atMs: Long = NOW): LiveState {
    val now = 1000.0
    val sim = SimTransport(clock = { now }, sleeper = {})
    val poller = Poller(TestSupport.profile, sim, clock = { now }, wallClock = { 0.0 })
    val sample = poller.cycle()
    val values = poller.latest.mapValues { (k, r) -> LiveValue(k, r.label, r.v, r.unit, r.status, periods[k] ?: 1.0, atMs) }
    return LiveState(status = LinkStatus.DEMO, demo = true, values = values, derived = sample.derived, lastDataAtMs = atMs, polling = true)
  }

  private fun value(name: String, v: Any?, status: String = "confirmed", atMs: Long = NOW, period: Double = 1.0) =
    LiveValue(name, name, v, "", status, period, atMs)

  private fun build(live: LiveState, t: TempUnit = TempUnit.C, d: DistUnit = DistUnit.MI, now: Long = NOW) = GlanceModel.build(live, t, d, now)

  private fun texts(g: GlanceView): List<String> = listOf(g.title, g.status) + g.rows.flatMap { listOf(it.title) + it.lines }

  private fun row(g: GlanceView, title: String): List<String> = g.rows.single { it.title == title }.lines

  @Test
  fun simulatorStateFillsTheFourRows() {
    val live = simState()
    val g = build(live)
    assertEquals("Battery Glance", g.title)
    assertEquals("DEMO DATA", g.status)
    assertTrue(g.demo)
    // Power: signed kW with direction, then V · A (+ = discharge).
    val power = row(g, "Power")
    assertTrue(power[0], power[0].matches(Regex("""\+15\.\d kW · Discharging""")))
    assertTrue(power[1], power[1].matches(Regex("""37\d\.\d V · \+40\.0 A""")))
    // Battery: SoC % · kWh remaining, then the BMS range.
    assertEquals(listOf("72.0 % · 147.9 kWh remaining", "Range 317 mi"), row(g, "Battery"))
    // Cells: the simulated truck draws 40 A, so the spread is the larger per-half BMS spread (the
    // array is read over ~2 s and would mix load changes into it), as on the dashboard.
    val half = DashboardModel.halfSummaries(live.values).maxBy { it.spreadMv }
    assertEquals(
      listOf(
        "${Units.num(half.spreadMv, 1)} mV spread",
        "half ${half.half.uppercase()} ${Units.num(half.lowV, 4)}–${Units.num(half.highV, 4)} V · under load",
      ),
      row(g, "Cells"),
    )
    // At rest it is the full array's spread and range.
    val rest = live.copy(values = live.values + ("pack_current" to live.values.getValue("pack_current").copy(v = 0.2)))
    val heat = DashboardModel.heatmap(rest.values, NOW)!!
    assertEquals(
      listOf("${Units.num((heat.max.volts!! - heat.min.volts!!) * 1000, 1)} mV spread", "${Units.num(heat.min.volts, 4)}–${Units.num(heat.max.volts, 4)} V"),
      row(build(rest), "Cells"),
    )
    // Temps: module average (2AF1, not confirmed) and both coolant sensors.
    val temps = row(g, "Temps")
    assertTrue(temps[0], temps[0].matches(Regex("""Modules −?\d+\.\d °C avg · unverified""")))
    assertTrue(temps[1], temps[1].matches(Regex("""Coolant −?\d+\.\d / −?\d+\.\d °C""")))
    // Only module temperatures are unverified in the simulator's profile.
    assertEquals(1, texts(g).count { it.contains("unverified") })
  }

  /** Every state the link can publish gives the same title, the same 4 row titles and 1–2 lines per row. */
  @Test
  fun titleRowCountAndRowTitlesNeverChange() {
    val sim = simState()
    val states = listOf(
      LiveState(),
      LiveState(status = LinkStatus.CONNECTING, message = "Starting the link service…"),
      LiveState(status = LinkStatus.PAIRING),
      LiveState(status = LinkStatus.ADAPTER_UNREACHABLE, canRetry = true, nextAutoRetryAtMs = NOW + 15_000),
      sim,
      sim.copy(status = LinkStatus.CONNECTED, demo = false),
      sim.copy(status = LinkStatus.TRUCK_ASLEEP, demo = false),
      sim.copy(status = LinkStatus.STOPPED_ERROR, demo = false, canRetry = true),
      LiveState(values = mapOf("pack_voltage" to value("pack_voltage", 380.0))),
      LiveState(values = mapOf("coolant_temp_2" to value("coolant_temp_2", 20.0, period = 10.0))),
      LiveState(values = mapOf("soc" to value("soc", 50.0, period = 2.0))),
      LiveState(values = Profile.NOT_DISPLAYED.associateWith { value(it, 60.0, status = "unverified") }),
    )
    for (live in states) {
      for (now in listOf(NOW, NOW + 600_000)) {
        for (t in TempUnit.entries) {
          val g = build(live, t, DistUnit.KM, now)
          assertEquals(GlanceModel.TITLE, g.title)
          assertEquals(listOf("Power", "Battery", "Cells", "Temps"), g.rows.map { it.title })
          assertEquals(GlanceModel.ROW_TITLES, g.rows.map { it.title })
          for (r in g.rows) {
            assertTrue("${r.title}: ${r.lines}", r.lines.size in 1..GlanceModel.MAX_LINES)
            assertTrue("${r.title}: ${r.lines}", r.lines.all { it.isNotBlank() })
          }
        }
      }
    }
  }

  @Test
  fun emptyStateShowsDashesAndWhatIsNeeded() {
    val g = build(LiveState(message = "Choose your OBDLink MX+ …"))
    assertEquals("No adapter", g.status)
    assertEquals(listOf("— kW", "Needs the OBDLink MX+"), row(g, "Power"))
    assertEquals(listOf("— % · — kWh", "Needs the OBDLink MX+"), row(g, "Battery"))
    assertEquals(listOf("— mV spread", "Needs cell data from the MX+"), row(g, "Cells"))
    assertEquals(listOf("Modules —", "Coolant —"), row(g, "Temps"))
  }

  @Test
  fun headerLabelFollowsTheLinkState() {
    fun label(s: LinkStatus, demo: Boolean = false, retry: Boolean = false) =
      GlanceModel.status(LiveState(status = s, demo = demo, canRetry = retry))
    assertEquals("No adapter", label(LinkStatus.NO_ADAPTER))
    assertEquals("Pairing…", label(LinkStatus.PAIRING))
    assertEquals("Connecting…", label(LinkStatus.CONNECTING))
    assertEquals("Starting demo…", label(LinkStatus.CONNECTING, demo = true))
    assertEquals("MX+ connected", label(LinkStatus.CONNECTED))
    assertEquals("MX+ unreachable · Retry", label(LinkStatus.ADAPTER_UNREACHABLE, retry = true))
    assertEquals("Truck asleep", label(LinkStatus.TRUCK_ASLEEP))
    assertEquals("Truck asleep · Retry", label(LinkStatus.TRUCK_ASLEEP, retry = true))
    assertEquals("Stopped · Retry", label(LinkStatus.STOPPED_ERROR, retry = true))
    // A lost link with an automatic reconnect scheduled.
    assertEquals("Link lost · Retry", GlanceModel.status(LiveState(status = LinkStatus.STOPPED_ERROR, canRetry = true, nextAutoRetryAtMs = 5L)))
    assertEquals("DEMO DATA", label(LinkStatus.DEMO, demo = true))
    // Demo mode in any other state still says so.
    assertEquals("Demo: Stopped · Retry", label(LinkStatus.STOPPED_ERROR, demo = true, retry = true))
    for (s in LinkStatus.entries) for (d in listOf(false, true)) for (r in listOf(false, true)) {
      assertTrue("$s: header label too long for the action", label(s, d, r).length <= 30)
      assertEquals(r, build(LiveState(status = s, demo = d, canRetry = r)).canRetry)
    }
  }

  @Test
  fun neverShowsTheWrongBcmTemperatures() {
    val only = LiveState(values = Profile.NOT_DISPLAYED.associateWith { value(it, 61.5, status = "unverified", period = 10.0) })
    val g = build(only)
    assertFalse(texts(g).any { it.contains("61") || it.contains("unverified") })
    assertEquals(listOf("Modules —", "Coolant —"), row(g, "Temps"))
  }

  @Test
  fun unverifiedValuesAreMarked() {
    val v = mapOf(
      "pack_voltage" to value("pack_voltage", 380.0),
      "pack_current" to value("pack_current", -12.5, status = "unverified"),
      "soc" to value("soc", 50.0, status = "unverified", period = 2.0),
      "coolant_temp_1" to value("coolant_temp_1", 21.0, status = "unverified", period = 10.0),
      "module_temps" to value("module_temps", listOf(20.0, 22.0), status = "unverified", period = 10.0),
    )
    val g = build(LiveState(values = v, derived = mapOf("power_kw" to -4.75)))
    assertEquals(listOf("−4.8 kW · Charging / regen", "380.0 V · −12.5 A · unverified"), row(g, "Power"))
    assertEquals(listOf("50.0 % · — kWh remaining", "unverified"), row(g, "Battery"))
    assertEquals(listOf("Modules 21.0 °C avg · unverified", "Coolant 21.0 / — °C · unverified"), row(g, "Temps"))
    // Confirmed values carry no marker.
    val ok = v.mapValues { (_, x) -> x.copy(status = "confirmed") }
    val g2 = build(LiveState(values = ok, derived = mapOf("power_kw" to -4.75)))
    assertFalse(texts(g2).any { it.contains("unverified") })
  }

  @Test
  fun staleValuesShowTheirAge() {
    val fresh = simState(atMs = NOW)
    val g = build(fresh, now = NOW + 2_000)
    assertFalse(texts(g).any { it.endsWith(" old") })
    // 25 s later: power (1 s period), SoC (2 s) and cells (5 s) are stale, the temperatures (10 s) not yet.
    val later = build(fresh, now = NOW + 25_000)
    assertTrue(row(later, "Power")[1], row(later, "Power")[1].endsWith(" A · 25 s old"))
    assertTrue(row(later, "Battery")[1], row(later, "Battery")[1].endsWith("Range 317 mi · 25 s old"))
    assertTrue(row(later, "Cells")[1], row(later, "Cells")[1].endsWith(" V · under load · 25 s old"))
    assertFalse(row(later, "Temps").toString(), row(later, "Temps").any { it.endsWith(" old") })
    // After 3 periods (30 s) the temperatures are stale too.
    val much = build(fresh, now = NOW + 40_000)
    assertTrue(row(much, "Temps")[0], row(much, "Temps")[0].endsWith(" avg · unverified · 40 s old"))
    assertTrue(row(much, "Temps")[1], row(much, "Temps")[1].endsWith(" °C · 40 s old"))
  }

  /** Fresh data must not tick: the same values give the same view a moment later (no needless refresh). */
  @Test
  fun freshDataGivesAStableView() {
    val live = simState()
    assertEquals(build(live, now = NOW), build(live, now = NOW + 900))
    assertEquals(build(live, now = NOW + 100), build(live, now = NOW + 4_900))
  }

  @Test
  fun unitsFollowTheSettings() {
    val live = simState()
    val c = build(live, TempUnit.C, DistUnit.MI)
    val f = build(live, TempUnit.F, DistUnit.KM)
    assertTrue(row(f, "Temps")[0], row(f, "Temps")[0].contains(" °F avg"))
    assertTrue(row(f, "Temps")[1], row(f, "Temps")[1].matches(Regex("""Coolant \d+\.\d / \d+\.\d °F""")))
    val miles = (live.values.getValue("range_estimate").v as Number).toDouble()
    assertEquals("Range ${Units.distText(miles, DistUnit.KM)}", row(f, "Battery")[1])
    assertTrue(row(f, "Battery")[1].endsWith(" km"))
    assertEquals(row(c, "Power"), row(f, "Power"))
    val v = mapOf("coolant_temp_1" to value("coolant_temp_1", 20.0, period = 10.0), "coolant_temp_2" to value("coolant_temp_2", 25.0, period = 10.0))
    assertEquals("Coolant 68.0 / 77.0 °F", row(build(LiveState(values = v), TempUnit.F), "Temps")[1])
  }

  @Test
  fun cellsFallBackToTheHalfSummaries() {
    val v = mapOf(
      "cell_min_a" to value("cell_min_a", 3.6692, period = 2.0), "cell_max_a" to value("cell_max_a", 3.6769, period = 2.0),
      "cell_min_b" to value("cell_min_b", 3.6709, period = 2.0), "cell_max_b" to value("cell_max_b", 3.6769, period = 2.0),
    )
    val derived = mapOf("cell_min_v" to 3.6692, "cell_max_v" to 3.6769, "cell_spread_mv" to 7.7)
    assertEquals(listOf("7.7 mV spread", "3.6692–3.6769 V"), row(build(LiveState(values = v, derived = derived)), "Cells"))
  }

  @Test
  fun detailIsTheLinkMessageWithVinsMasked() {
    assertEquals("Simulated truck", GlanceModel.detail(LiveState(message = "Simulated truck")))
    assertEquals("No adapter", GlanceModel.detail(LiveState(message = "")))
    val leaked = GlanceModel.detail(LiveState(status = LinkStatus.STOPPED_ERROR, message = "Stopped: unexpected output '1GCUDEED5RZ123456'"))
    assertFalse(leaked, leaked.contains("1GCUDEED5RZ123456"))
    assertTrue(leaked, leaked.startsWith("Stopped: unexpected output '"))
  }

  @Test
  fun noteJoinsMarkersAndAge() {
    assertEquals("x", GlanceModel.note("x", unverified = false, stale = false, age = "3 s"))
    assertEquals("x · unverified", GlanceModel.note("x", unverified = true, stale = false, age = "3 s"))
    assertEquals("x · unverified · 3 s old", GlanceModel.note("x", unverified = true, stale = true, age = "3 s"))
    assertEquals("x · 3 min old", GlanceModel.note("x", unverified = false, stale = true, age = "3 min"))
  }

  // --- Built-in data (no adapter / OBD not delivering) ------------------------------------------

  /** A session like the truck's photo: 85.25 of 205.08 kWh, 304 km range, 2.32 mi/kWh over 9.8 mi. */
  private fun session(
    eff: Efficiency = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(2.32), 1.0, 15_000.0, 4_000.0),
    power: PowerNow? = PowerNow(23.4, PowerDirection.DISCHARGING, PowerSource.CHARGE_RATE),
    feed: Feed = Feed(FeedKind.VEHICLE, sampling = true),
  ) = SessionState(
    activeMs = 24 * 60_000L,
    samples = 2_880,
    distanceM = 9.8 * EffUnits.METERS_PER_MILE,
    usedWh = 4_210.0,
    efficiency = eff,
    power = power,
    speedMps = 45 / 2.2369362920544,
    levelWh = 85_250.0,
    capacityWh = 205_080.0,
    rangeM = 304_000.0,
    outsideC = 12.5,
    feed = feed,
  )

  @Test
  fun builtInDataFillsTheRowsWithoutAnAdapter() {
    val g = GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, session())
    assertEquals("Built-in data", g.status)
    assertEquals(GlanceModel.ROW_TITLES, g.rows.map { it.title })
    assertEquals(listOf("+23.4 kW · Discharging", "45 mph · truck's charge rate"), row(g, "Power"))
    assertEquals(listOf("41.6 % · 85.3 kWh · 189 mi range", "2.32 mi/kWh since opened · 9.8 mi"), row(g, "Battery"))
    assertEquals(listOf("— mV spread", "Needs the OBDLink MX+"), row(g, "Cells"))
    assertEquals(listOf("Outside 12.5 °C", "Battery temps need the OBDLink MX+"), row(g, "Temps"))
    assertFalse(g.demo)
    // Metric and °F.
    val m = GlanceModel.build(LiveState(), TempUnit.F, DistUnit.KM, NOW, session())
    assertEquals(listOf("41.6 % · 85.3 kWh · 304 km range", "3.73 km/kWh since opened · 15.8 km"), row(m, "Battery"))
    assertEquals("72 km/h · truck's charge rate", row(m, "Power")[1])
    assertEquals("Outside 54.5 °F", row(m, "Temps")[0])
  }

  @Test
  fun builtInPowerSaysWhenItIsEstimated() {
    val est = session(power = PowerNow(31.0, PowerDirection.DISCHARGING, PowerSource.ESTIMATED))
    assertEquals(listOf("+31.0 kW · Discharging · est.", "45 mph · est. from battery level"), row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, est), "Power"))
    val regen = session(power = PowerNow(-18.25, PowerDirection.REGEN, PowerSource.CHARGE_RATE))
    assertEquals("−18.3 kW · Regen", row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, regen), "Power")[0])
  }

  @Test
  fun builtInWarmUpAndMissingAccess() {
    val warm = session(eff = Efficiency(EffStatus.WARMING_UP, null, 0.4, 640.0, 120.0)).copy(distanceM = 0.4 * EffUnits.METERS_PER_MILE)
    assertEquals("Efficiency warming up (40 %) · 0.4 mi", row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, warm), "Battery")[1])
    val early = session(eff = Efficiency(EffStatus.OK, EffUnits.fromMiPerKwh(1.43), 1.0, 1_640.0, 710.0, early = true))
    assertEquals("1.43 mi/kWh so far (early) · 9.8 mi", row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, early), "Battery")[1])
    val regen = session(eff = Efficiency(EffStatus.NET_REGEN, null, 1.0, 10_460.0, -120.0))
    assertEquals("Net regen so far · 9.8 mi", row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, regen), "Battery")[1])
    val noAccess = session(eff = Efficiency.NONE, feed = Feed(FeedKind.VEHICLE, missingPermissions = listOf(CarPerm.CAR_SPEED)))
    assertEquals("Efficiency needs access to car data", row(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, noAccess), "Battery")[1])
    assertEquals("Measuring stopped", GlanceModel.builtInHint(SessionState(feed = Feed(FeedKind.FAILED))))
    val denied = SessionState(feed = Feed(FeedKind.VEHICLE, missingPermissions = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED)))
    val g = GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, denied)
    assertEquals(listOf("— kW", "Allow car data in Silverado Stats"), row(g, "Power"))
    assertEquals(listOf("— % · — kWh", "Allow car data in Silverado Stats"), row(g, "Battery"))
    assertEquals(listOf("Outside —", "Battery temps need the OBDLink MX+"), row(g, "Temps"))
    val phone = GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, SessionState(feed = Feed(FeedKind.NOT_AUTOMOTIVE)))
    assertEquals("No car data on this device", row(phone, "Power")[1])
  }

  @Test
  fun obdRowsWhileTheLinkDeliversElseBuiltIn() {
    val sim = simState()
    // OBD delivering: the OBD rows, with the measured efficiency added to the range line.
    val obd = GlanceModel.build(sim, TempUnit.C, DistUnit.MI, NOW, session())
    assertEquals(listOf("72.0 % · 147.9 kWh remaining", "Range 317 mi · 2.32 mi/kWh since opened · 9.8 mi"), row(obd, "Battery"))
    assertTrue(row(obd, "Power")[1], row(obd, "Power")[1].endsWith(" A"))
    // The adapter chosen but unreachable / the truck asleep / connecting: built-in rows, link state kept.
    for (st in listOf(LinkStatus.ADAPTER_UNREACHABLE, LinkStatus.TRUCK_ASLEEP, LinkStatus.CONNECTING, LinkStatus.STOPPED_ERROR)) {
      val live = sim.copy(status = st, demo = false, canRetry = st != LinkStatus.CONNECTING)
      val g = GlanceModel.build(live, TempUnit.C, DistUnit.MI, NOW, session())
      assertEquals(st.toString(), "+23.4 kW · Discharging", row(g, "Power")[0])
      assertEquals(GlanceModel.status(live), g.status)
      assertEquals(live.canRetry, g.canRetry)
    }
    // Row titles never change between the two sources (template refresh quota).
    for (live in listOf(LiveState(), sim, sim.copy(status = LinkStatus.TRUCK_ASLEEP))) {
      for (ss in listOf(null, session(), SessionState())) {
        assertEquals(GlanceModel.ROW_TITLES, GlanceModel.build(live, TempUnit.C, DistUnit.KM, NOW, ss).rows.map { it.title })
        GlanceModel.build(live, TempUnit.C, DistUnit.KM, NOW, ss).rows.forEach { r ->
          assertTrue(r.lines.size in 1..GlanceModel.MAX_LINES && r.lines.all { it.isNotBlank() })
        }
      }
    }
    // A demo session says demo.
    assertTrue(GlanceModel.build(LiveState(), TempUnit.C, DistUnit.MI, NOW, session(feed = Feed(FeedKind.DEMO))).demo)
  }

  private companion object {
    const val NOW = 1_000_000L
  }
}
