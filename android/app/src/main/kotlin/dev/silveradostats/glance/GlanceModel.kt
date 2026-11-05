package dev.silveradostats.glance

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.link.PowerPoint
import dev.silveradostats.obd.Profile
import dev.silveradostats.obd.Vin
import dev.silveradostats.ui.live.DashboardModel
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.ui.live.VhalFigures
import dev.silveradostats.ui.simple.SimpleModel
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.Signal

/*
 * Pure Kotlin (no android.* imports): what Battery Glance, the templated glance screen, shows. Unit
 * tested on the JVM; GlanceScreen only puts it into a PaneTemplate.
 *
 * Template quota: the host lets a task send at most 5 templates, and counts a new PaneTemplate as a
 * free refresh only while the template title, the number of rows and every row title stay the same
 * (PaneTemplate docs). So the title and the 4 row titles are constants, and everything that
 * changes (values, ages, link state) goes into the row texts and the header action's label.
 *
 * Same rules as the Live dashboard (the values come from DashboardModel): Profile.NOT_DISPLAYED is
 * never shown, and every value whose profile status is not "confirmed" is marked "unverified".
 *
 * Two sources, same 4 row titles: the OBD link while it is connected and delivering (MX+ or the
 * demo simulator), else the truck's built-in data from the session (no adapter needed): Capacity from
 * the car's API, Battery with SoC · kWh · truck range and the efficiency measured since the app was
 * opened, Temps with the outside temperature.
 */

/** One pane row: a fixed [title] and 1 or 2 lines of text (the most a pane row may have). */
data class GlanceRow(val title: String, val lines: List<String>)

data class GlanceView(
  /** Always [GlanceModel.TITLE]. */
  val title: String,
  /** Short link state for the header action: "MX+ connected", "DEMO DATA", "Stopped · Retry", ... */
  val status: String,
  /** Tapping the header action re-runs the connect and adapter setup (LinkEngine.retry). */
  val canRetry: Boolean,
  val demo: Boolean,
  /** Always 4 rows, titled [GlanceModel.ROW_TITLES] in that order. */
  val rows: List<GlanceRow>,
)

object GlanceModel {
  const val TITLE = "Battery Glance"
  const val CAPACITY = "Capacity"
  const val BATTERY = "Battery"
  const val CELLS = "Cells"
  const val TEMPS = "Temps"
  val ROW_TITLES = listOf(CAPACITY, BATTERY, CELLS, TEMPS)

  /** Marker for any value whose profile status is not "confirmed" (2AF1 module temperatures). */
  const val UNVERIFIED = "unverified"

  /** Most text lines a PaneTemplate row may carry. */
  const val MAX_LINES = 2

  private val DASH = Units.DASH

  /** [session]: the built-in session (null = OBD rows only, as before the Simple tab). */
  fun build(live: LiveState, temp: TempUnit, dist: DistUnit, nowMs: Long, session: SessionState? = null): GlanceView {
    if (session != null && !obdActive(live)) return builtIn(live, session, temp, dist)
    val v = live.values.filterKeys { it !in Profile.NOT_DISPLAYED }
    return GlanceView(
      title = TITLE,
      status = status(live),
      canRetry = live.canRetry,
      demo = live.demo,
      rows = listOf(
        capacity(v, live.derived, nowMs),
        battery(v, dist, nowMs, session),
        cells(v, live.derived, nowMs, live.power),
        temps(v, temp, nowMs),
      ),
    )
  }

  /** OBD data is live: the MX+ (or the demo simulator) is connected and has delivered values. */
  fun obdActive(live: LiveState): Boolean = live.status in OBD_LIVE && live.values.isNotEmpty()

  private val OBD_LIVE = setOf(LinkStatus.CONNECTED, LinkStatus.DEMO)

  /** Header label: the link state in a few words, "· Retry" when tapping it reconnects. */
  fun status(live: LiveState): String {
    val base = when (live.status) {
      LinkStatus.NO_ADAPTER -> "No adapter"
      LinkStatus.DISCONNECTED -> "MX+ disconnected"
      LinkStatus.PAIRING -> "Pairing…"
      LinkStatus.CONNECTING -> if (live.demo) "Starting demo…" else "Connecting…"
      LinkStatus.CONNECTED -> "MX+ connected"
      LinkStatus.ADAPTER_UNREACHABLE -> "MX+ unreachable"
      LinkStatus.TRUCK_ASLEEP -> "Truck asleep"
      // A lost link or timeout with an automatic reconnect scheduled (LinkEngine) vs a stop for good.
      LinkStatus.STOPPED_ERROR -> if (live.nextAutoRetryAtMs != null) "Link lost" else "Stopped"
      LinkStatus.DEMO -> "DEMO DATA"
    }
    // Demo mode in any other state (an error, ...) still says so.
    val label = if (live.demo && live.status != LinkStatus.DEMO && live.status != LinkStatus.CONNECTING) "Demo: $base" else base
    return if (live.canRetry) "$label · Retry" else label
  }

  /** The full link message, shown as a toast when the header action is tapped (VIN-shaped text masked). */
  fun detail(live: LiveState): String = Vin.maskText(live.message.ifEmpty { status(live) })

  // --- Rows -------------------------------------------------------------------------------------

  /** "208.44 kWh · 602.2 Ah" / "Battery capability, from the BECM · unverified". */
  fun capacity(v: Map<String, LiveValue>, derived: Map<String, Any>, nowMs: Long): GlanceRow {
    val kwh = DashboardModel.capacity(v, derived, VhalFigures(), nowMs)
    val ah = DashboardModel.capacityAh(v, derived, nowMs)
    if (!kwh.hasValue) return GlanceRow(CAPACITY, listOf("$DASH kWh · $DASH Ah", NEEDS_MX))
    val first = "${kwh.value} kWh · ${if (ah.marker == "est.") "≈" else ""}${ah.value} Ah"
    return GlanceRow(CAPACITY, listOf(first, note(kwh.lines.first(), kwh.marker == UNVERIFIED || ah.marker == UNVERIFIED, kwh.stale, kwh.age)))
  }

  /** "72.0 % · 147.9 kWh remaining" / "Range 322 mi · truck 317 mi" (+ " · 2.32 mi/kWh since opened"). */
  fun battery(v: Map<String, LiveValue>, dist: DistUnit, nowMs: Long, session: SessionState? = null): GlanceRow {
    val soc = v["soc"]
    val energy = v["energy_remaining"]
    val socV = num(soc)
    val energyV = num(energy)
    if (socV == null && energyV == null) return GlanceRow(BATTERY, listOf("$DASH % · $DASH kWh", NEEDS_MX))
    val first = "${socV?.let { Units.num(it, 1) } ?: DASH} % · ${energyV?.let { Units.num(it, 1) } ?: DASH} kWh remaining"
    val eff = session?.let { sessionEfficiency(it, dist) }
    val own = session?.rangeEstimate?.let { "Range ${SimpleModel.distText(it.m, dist, 0)}" }
    val truck = num(v["range_estimate"])?.let { "${if (own == null) "Range" else "truck"} ${Units.distText(it, dist)}" }
    val range = listOfNotNull(own, truck, eff)
      .takeIf { it.isNotEmpty() }?.joinToString(" · ")
    val used = listOfNotNull(soc, energy)
    val stale = used.filter { isStale(it, nowMs) }
    return GlanceRow(
      BATTERY,
      lines(first, range, unverified = used.any { !it.confirmed }, stale = stale.isNotEmpty(), age = oldestAge(stale, nowMs)),
    )
  }

  /**
   * "7.2 mV spread" / "3.9412–3.9484 V": from the full cell-group array read at rest, else (while
   * driving: under load) the BMS half summaries, "half B 3.9412–3.9484 V · under load".
   */
  fun cells(v: Map<String, LiveValue>, derived: Map<String, Any>, nowMs: Long, power: List<PowerPoint> = emptyList()): GlanceRow {
    val heat = DashboardModel.heatmap(v, nowMs, power)
    val k = DashboardModel.spread(v, derived, heat, nowMs, power)
    if (!k.hasValue) return GlanceRow(CELLS, listOf("$DASH mV spread", k.lines.first()))
    val used = if (DashboardModel.spreadFromArray(v, heat)) {
      v.values.filter { it.name.startsWith("cells_") }
    } else {
      listOfNotNull(v["cell_min_a"], v["cell_min_b"], v["cell_max_a"], v["cell_max_b"])
    }
    val unverified = k.marker != null || used.any { !it.confirmed }
    return GlanceRow(CELLS, listOf("${k.value} mV spread", note(k.lines.first(), unverified, k.stale, k.age)))
  }

  /** "Modules 24.5 °C avg · unverified" / "Coolant 21.5 / 22.0 °C". */
  fun temps(v: Map<String, LiveValue>, u: TempUnit, nowMs: Long): GlanceRow {
    val mt = DashboardModel.moduleTemps(v, nowMs)
    val real = mt?.celsius?.filterNotNull().orEmpty()
    val modules = v["module_temps"]
    val first = if (real.isNotEmpty()) {
      note(
        "Modules ${Units.tempText(real.sum() / real.size, u)} avg",
        unverified = modules?.confirmed == false,
        stale = mt?.stale == true,
        age = modules?.let { Units.age(nowMs - it.atMs) },
      )
    } else {
      "Modules $DASH"
    }
    val c1 = v["coolant_temp_1"]
    val c2 = v["coolant_temp_2"]
    val second = if (num(c1) == null && num(c2) == null) {
      "Coolant $DASH"
    } else {
      val used = listOfNotNull(c1, c2).filter { num(it) != null }
      val stale = used.filter { isStale(it, nowMs) }
      note(
        "Coolant ${coolant(num(c1), u)} / ${coolant(num(c2), u)} ${u.symbol}",
        unverified = used.any { !it.confirmed },
        stale = stale.isNotEmpty(),
        age = oldestAge(stale, nowMs),
      )
    }
    return GlanceRow(TEMPS, listOf(first, second))
  }

  // --- Built-in data (no adapter, or the OBD link not delivering) -----------------------------

  /** Header label: the link state, except that "No adapter" is simply the built-in data. */
  fun builtInStatus(live: LiveState): String = if (live.status == LinkStatus.NO_ADAPTER && !live.canRetry) BUILT_IN else status(live)

  const val BUILT_IN = "Built-in data"

  fun builtIn(live: LiveState, s: SessionState, temp: TempUnit, dist: DistUnit): GlanceView = GlanceView(
    title = TITLE,
    status = builtInStatus(live),
    canRetry = live.canRetry,
    demo = live.demo || s.feed.kind == FeedKind.DEMO,
    rows = listOf(builtInCapacity(s), builtInBattery(s, dist), GlanceRow(CELLS, listOf("$DASH mV spread", NEEDS_MX)), builtInTemps(s, temp)),
  )

  /** Why the built-in data has no value yet (permissions, device, car service). */
  fun builtInHint(s: SessionState): String = when {
    s.feed.kind == FeedKind.NOT_AUTOMOTIVE -> "No car data on this device"
    s.feed.missingPermissions.isNotEmpty() -> "Allow car data in Silverado Stats"
    s.feed.kind == FeedKind.NO_CAR_SERVICE -> "Car service unavailable"
    s.feed.kind == FeedKind.FAILED -> "Measuring stopped"
    else -> "Waiting for the truck's data"
  }

  /** "205.1 kWh" / "Usable now, from the car's API". */
  fun builtInCapacity(s: SessionState): GlanceRow {
    val k = SimpleModel.capacity(s)
    if (!k.hasValue) return GlanceRow(CAPACITY, listOf("$DASH kWh", builtInHint(s)))
    return GlanceRow(CAPACITY, listOf("${k.value} kWh", k.lines.first()))
  }

  /** "41.6 % · 85.3 kWh · 198 mi range · truck 189 mi" / "2.32 mi/kWh since opened · 9.8 mi". */
  fun builtInBattery(s: SessionState, dist: DistUnit): GlanceRow {
    val level = s.levelWh ?: return GlanceRow(BATTERY, listOf("$DASH % · $DASH kWh", builtInHint(s)))
    val own = s.rangeEstimate?.let { "${SimpleModel.distText(it.m, dist, 0)} range" }
    val first = listOfNotNull(
      "${s.socPct?.let { Units.num(it, 1) } ?: DASH} %",
      "${Units.num(level / 1000.0, 1)} kWh",
      own,
      s.rangeM?.let { if (own == null) "${SimpleModel.distText(it, dist, 0)} range" else "truck ${SimpleModel.distText(it, dist, 0)}" },
    ).joinToString(" · ")
    return GlanceRow(BATTERY, listOf(first, sessionEfficiency(s, dist)))
  }

  /** "2.32 mi/kWh since opened · 9.8 mi" ("early" over the first miles), or why there is no figure. */
  fun sessionEfficiency(s: SessionState, dist: DistUnit): String {
    val m = s.efficiency.mPerWh
    val d = SimpleModel.distText(s.distanceM, dist)
    return when {
      s.efficiency.status == EffStatus.OK && m != null ->
        "${SimpleModel.effText(m, dist)} ${SimpleModel.effUnit(dist)} ${if (s.efficiency.early) "so far (early)" else "since opened"} · $d"
      s.efficiency.status == EffStatus.NET_REGEN -> "Net regen so far · $d"
      s.feed.missingPermissions.isNotEmpty() -> "Efficiency needs access to car data"
      Signal.SPEED in s.feed.missing -> "Efficiency needs vehicle speed"
      else -> "Efficiency warming up (${Units.num(s.efficiency.progress * 100, 0)} %) · $d"
    }
  }

  /** "Outside 12.5 °C" / "Battery temps need the OBDLink MX+". */
  fun builtInTemps(s: SessionState, u: TempUnit): GlanceRow =
    GlanceRow(TEMPS, listOf("Outside ${Units.tempText(s.outsideC, u)}", "Battery temps need the OBDLink MX+"))

  // --- Helpers ----------------------------------------------------------------------------------

  const val NEEDS_MX = "Needs the OBDLink MX+"

  private fun num(x: LiveValue?): Double? = (x?.v as? Number)?.toDouble()?.takeIf { it.isFinite() }

  private fun coolant(c: Double?, u: TempUnit): String = if (c == null) DASH else Units.num(Units.temp(c, u), 1)

  private fun isStale(x: LiveValue, nowMs: Long): Boolean = nowMs - x.atMs > DashboardModel.staleAfterMs(x.periodS)

  private fun oldestAge(xs: List<LiveValue>, nowMs: Long): String? = xs.minOfOrNull { it.atMs }?.let { Units.age(nowMs - it) }

  /** [text] plus " · unverified" and " · 25 s old" as they apply. */
  fun note(text: String, unverified: Boolean, stale: Boolean, age: String?): String {
    val parts = mutableListOf(text)
    if (unverified) parts += UNVERIFIED
    if (stale && age != null) parts += "$age old"
    return parts.joinToString(" · ")
  }

  /** [first] and an optional [second] line; the notes go on the last line (a line of their own if needed). */
  private fun lines(first: String, second: String?, unverified: Boolean, stale: Boolean, age: String?): List<String> {
    val notes = note("", unverified, stale, age).removePrefix(" · ")
    return when {
      second != null -> listOf(first, if (notes.isEmpty()) second else "$second · $notes")
      notes.isNotEmpty() -> listOf(first, notes)
      else -> listOf(first)
    }
  }
}
