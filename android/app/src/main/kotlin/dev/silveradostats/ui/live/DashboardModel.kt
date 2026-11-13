package dev.silveradostats.ui.live

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.link.PowerPoint
import dev.silveradostats.link.SavedCapacity
import dev.silveradostats.obd.Profile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): turns a LiveState (OBD) plus the VHAL figures into exactly
 * what the Live dashboard draws. Unit tested on the JVM; the Compose code only lays it out.
 *
 * Never shows Profile.NOT_DISPLAYED (40:434F, 4127, 4124: wrong on this truck); every value whose
 * profile status is not "confirmed" carries a marker ("likely" for the 2AF1 module temperatures).
 *
 * Cell groups under load: the 16 cell-group DIDs (2AE1…2AF0) are read one after another, about
 * 1.5–2.5 s from group 1 to group 192, and every group sags with pack current (IR drop). While
 * current flows or changes, differences between DIDs therefore include load changes, which can be
 * larger than the real ~7 mV module-to-module spread. Then the spread comes from the BMS half
 * summaries (2AF6 = half A, 2AF5 = half B; each one read, so internally simultaneous) and the
 * heatmap is marked "read under load".
 */

/** Colour role of a text or mark. */
enum class Tone { NORMAL, GOOD, WARN, ERROR, DIM }

/** Values from Android's vehicle API (no adapter needed). Wh, metres, °C. */
data class VhalFigures(
  val energyWh: Double? = null,
  val usableWh: Double? = null,
  val nominalWh: Double? = null,
  val rangeM: Double? = null,
  val outsideC: Double? = null,
) {
  /** Energy ÷ usable capacity (else nominal capacity). */
  val socPct: Double?
    get() {
      val e = energyWh ?: return null
      val c = usableWh?.takeIf { it > 0 } ?: nominalWh?.takeIf { it > 0 } ?: return null
      return e / c * 100.0
    }
}

data class StatusView(
  val chip: String,
  val tone: Tone,
  val message: String,
  val dataAge: String?,
  val dataStale: Boolean,
  val adapterVolts: String?,
  val canRetry: Boolean,
  val retryIn: String?,
  val demo: Boolean,
)

data class Kpi(
  val title: String,
  val value: String,
  val unit: String,
  val lines: List<String>,
  /** "unverified", "likely", "VHAL", "est." … shown as a small marker beside the title. */
  val marker: String? = null,
  val age: String? = null,
  val stale: Boolean = false,
  val hasValue: Boolean = true,
  /** The title on a narrow tile and while driving. */
  val shortTitle: String = title,
  /** All of [lines] that stays while driving: figures only, in the fewest words; never a sentence. */
  val drivingLines: List<String> = emptyList(),
  /** The first line where the tile is narrow. */
  val shortLine: String? = null,
)

data class TempLine(
  val label: String,
  val value: String,
  val marker: String? = null,
  val stale: Boolean = false,
  val shortLabel: String = label,
  val shortValue: String = value,
)

data class HeatCell(
  /** 1-based position in the cell-group array (the probe's group numbering). */
  val group: Int,
  val module: Int,
  /** 0-based row inside the module column. */
  val row: Int,
  /** Null for padding / missing positions. */
  val volts: Double?,
  /** Deviation from the pack average, mV; null when [volts] is. */
  val devMv: Double?,
)

data class HeatmapView(
  val modules: Int,
  val groupsPerModule: Int,
  /** Index = group - 1; covers modules × groupsPerModule positions. */
  val cells: List<HeatCell>,
  val avgV: Double,
  /** Colour scale: ± this many mV map to the two poles. */
  val scaleMv: Double,
  val read: Int,
  val min: HeatCell,
  val max: HeatCell,
  val age: String?,
  val stale: Boolean,
  /** Current flowed or changed while the array was read: the shading includes load changes. */
  val underLoad: Boolean = false,
) {
  fun cell(module: Int, row: Int): HeatCell? = cells.getOrNull((module - 1) * groupsPerModule + row)

  /** Column after which the second 400 V half starts (modules 13-24 on a 24-module pack). */
  val halfAfter: Int? get() = if (modules >= 4 && modules % 2 == 0) modules / 2 else null
}

/** [celsius] is one figure per module; [sensors] every sensor reading behind them (two per module). */
data class ModuleTemps(val celsius: List<Double?>, val marker: String?, val stale: Boolean, val sensors: List<Double> = emptyList(), val pairs: List<List<Double?>> = emptyList())

/**
 * One tile of the parked OBD page's grid: a label over a value, or, with [parts], a wide tile of small
 * captioned figures (a battery half's module temperatures).
 */
data class PackTile(
  val label: String,
  val value: String,
  val marker: String? = null,
  val stale: Boolean = false,
  val parts: List<Pair<String, String>> = emptyList(),
)

data class Dashboard(
  val status: StatusView,
  val soc: Kpi,
  /** Capacity in kWh and in Ah, and its deviation: on the Live tab whenever it is on screen, parked or driving. */
  val capacity: Kpi,
  val capacityAh: Kpi,
  val capacityDeviation: Kpi,
  val spread: Kpi,
  /** Pack voltage, pack current, cell-group spread. */
  val pack: List<TempLine>,
  val temps: List<TempLine>,
  /** Fewer, shorter lines: what the Live tab shows of [temps] while driving. */
  val tempSummary: List<TempLine>,
  val heatmap: HeatmapView?,
  val moduleTemps: ModuleTemps?,
  /** Shown when OBD data is missing: what is needed to get it. */
  val obdHint: String?,
  /** The OBD page's grid of tiles, row by row: up to three small tiles, or one with parts. */
  val packTiles: List<List<PackTile>> = emptyList(),
  /** The energy left, a tile of its own beside the charge. */
  val remaining: Kpi = Kpi("Remaining", Units.DASH, "kWh", emptyList(), hasValue = false),
)

object DashboardModel {
  const val GROUPS_PER_MODULE = 8

  /** Below this a module temperature byte is padding (raw 0 → −80 °C on smaller packs). */
  const val MIN_VALID_MODULE_TEMP_C = -60.0

  /** Cell-group array entries at or below this are padding (as in Derive). */
  const val MIN_VALID_CELL_V = 0.5

  const val OBD_HINT = "Pack voltage and current, cell groups and module temperatures come from the OBDLink MX+ " +
    "(Settings › OBDLink MX+ link). SoC, energy, range and outside temperature below are from the car's own API."

  /** [saved]: the last capacity read, shown while the MX+ is not delivering; [wallNowMs]: for its age. */
  fun build(live: LiveState, vhal: VhalFigures, temp: TempUnit, dist: DistUnit, nowMs: Long, saved: SavedCapacity? = null, wallNowMs: Long = 0L, ownRangeM: Double? = null): Dashboard {
    val v = live.values.filterKeys { it !in Profile.NOT_DISPLAYED }
    val heat = heatmap(v, nowMs, live.power)
    val hasObd = v.isNotEmpty()
    val spread = spread(v, live.derived, heat, nowMs, live.power)
    return Dashboard(
      status = status(live, nowMs),
      soc = soc(v, vhal, dist, nowMs, ownRangeM),
      capacity = capacity(v, live.derived, vhal, nowMs, saved, wallNowMs),
      capacityAh = capacityAh(v, live.derived, nowMs, saved, wallNowMs),
      capacityDeviation = capacityDeviation(v, nowMs),
      spread = spread,
      pack = pack(v, nowMs) + TempLine("Cell spread", if (spread.hasValue) "${spread.value} ${spread.unit}" else Units.DASH, spread.marker, spread.stale),
      temps = temps(v, vhal, live, temp, nowMs),
      tempSummary = tempSummary(temps(v, vhal, live, temp, nowMs)),
      heatmap = heat,
      moduleTemps = moduleTemps(v, nowMs),
      obdHint = if (hasObd) null else OBD_HINT,
      packTiles = packTiles(v, vhal, temp, nowMs, spread, heat),
      remaining = remaining(v, vhal, nowMs),
    )
  }

  /** The energy left: the BMS's figure, else the car API's (marked), else a dash. */
  fun remaining(v: Map<String, LiveValue>, vhal: VhalFigures, nowMs: Long): Kpi {
    val src = v["energy_remaining"]
    val kwh = num(v, "energy_remaining")
    return when {
      kwh != null -> Kpi("Remaining", Units.num(kwh, 1), "kWh", emptyList(), marker = marker(src), age = ageOf(src, nowMs), stale = isStale(src, nowMs))
      vhal.energyWh != null -> Kpi("Remaining", Units.num(vhal.energyWh / 1000.0, 1), "kWh", emptyList(), marker = "VHAL")
      else -> Kpi("Remaining", Units.DASH, "kWh", emptyList(), hasValue = false)
    }
  }

  /** Min, mean, median and max of [readings] (gaps skipped), or null with none. */
  fun minMeanMedianMax(readings: List<Double?>): List<Double>? {
    val t = readings.filterNotNull().sorted()
    if (t.isEmpty()) return null
    val median = if (t.size % 2 == 1) t[t.size / 2] else (t[t.size / 2 - 1] + t[t.size / 2]) / 2
    return listOf(t.first(), t.sum() / t.size, median, t.last())
  }

  private val TEMP_PARTS = listOf("min", "mean", "median", "max")
  private val BATTERY_TEMP_PARTS = listOf("min", "mean", "max")

  /**
   * The OBD page's grid, as the owner asked for it (2026-11-11): each battery half's voltage, the cell groups'
   * voltages as min, mean, median and max, the cell spread, each half's module temperatures as min, mean,
   * median and max, the two coolant temperatures in one tile, and the outside temperature. No current, power,
   * range or 12 V figure; the energy left is a tile of the top row ([remaining]).
   *
   * Owner's request (2026-11-13): each battery's state of charge on top (2B43 bytes 0 and 1), and up to three
   * small tiles to a row, so the grid stays six rows and its figures grow into the room the cell grid gave up.
   */
  fun packTiles(v: Map<String, LiveValue>, vhal: VhalFigures, temp: TempUnit, nowMs: Long, spread: Kpi, heat: HeatmapView? = null): List<List<PackTile>> {
    val rows = mutableListOf<List<PackTile>>()
    rows += listOf("bms_soc_layer", "soc_pack_2").mapIndexed { i, f ->
      val src = v[f]
      PackTile("Batt ${i + 1} charge", num(v, f)?.let { "${Units.num(it, 1)} %" } ?: Units.DASH, marker(src), isStale(src, nowMs))
    }
    val out = mutableListOf<PackTile>()
    // A half's voltage is the sum of its cell groups: the BECM reports the pack's, not each half's. Only a
    // complete half is summed; without the cell groups the pack's own figure stands in.
    val halfVolts = heat?.halfAfter?.let { half ->
      val split = half * heat.groupsPerModule
      listOf(heat.cells.take(split), heat.cells.drop(split)).map { cells -> cells.mapNotNull { it.volts }.takeIf { it.isNotEmpty() && it.size == cells.size }?.sum() }
    }
    if (heat != null && halfVolts != null && halfVolts.all { it != null }) {
      halfVolts.forEachIndexed { i, sum -> out += PackTile("Batt ${i + 1} voltage", "${Units.num(sum ?: 0.0, 1)} V", stale = heat.stale) }
    } else {
      pack(v, nowMs).first().let { out += PackTile(it.shortLabel, it.value, it.marker, it.stale) }
    }
    out += PackTile("Cell spread", if (spread.hasValue) "${spread.value} ${spread.unit}" else Units.DASH, spread.marker, spread.stale)
    rows += out.toList()
    minMeanMedianMax(heat?.cells.orEmpty().map { it.volts })?.let { s ->
      rows += listOf(PackTile("Cells", "V", stale = heat?.stale ?: false, parts = TEMP_PARTS.zip(s.map { Units.num(it, 3) })))
    }
    // Each battery's own temperature summary, as the BECM reports it (2AF3, 2AF4: average, min, max). Until
    // 2026-11-11 these tiles split 2AF1 in two, but all of 2AF1 is battery 1 (two sensors per module).
    for ((i, field) in listOf("battery_temps_a", "battery_temps_b").withIndex()) {
      val src = v[field]
      val t = (src?.v as? List<*>)?.map { (it as? Number)?.toDouble()?.takeIf { c -> c > MIN_VALID_MODULE_TEMP_C } }?.takeIf { it.size >= 3 && it.take(3).all { c -> c != null } }
      val figures = if (t == null) List(3) { Units.DASH } else listOf(t[1], t[0], t[2]).map { Units.num(Units.temp(it!!, temp), 0) }
      // For a tile with parts, the value is the unit its figures are in. "Batt" is short: the name shares a row with the figures.
      rows += listOf(PackTile("Batt ${i + 1}", temp.symbol, marker(src), isStale(src, nowMs), BATTERY_TEMP_PARTS.zip(figures)))
    }
    // The two coolant circuits in tiles of their own (one shared tile until 2026-11-13): short values, bigger figures.
    rows += listOf("coolant_temp_1", "coolant_temp_2").mapIndexed { i, f ->
      PackTile("Coolant ${i + 1}", Units.tempText(num(v, f), temp, 0), marker(v[f]), isStale(v[f], nowMs))
    } + PackTile("Outside", Units.tempText(vhal.outsideC, temp, 0))
    return rows
  }

  // --- Status -----------------------------------------------------------------------------------

  fun chip(status: LinkStatus, demo: Boolean): Pair<String, Tone> = when (status) {
    LinkStatus.NO_ADAPTER -> "No adapter" to Tone.DIM
    LinkStatus.DISCONNECTED -> "MX+ disconnected" to Tone.DIM
    LinkStatus.PAIRING -> "Pairing…" to Tone.WARN
    LinkStatus.CONNECTING -> (if (demo) "Starting demo…" else "Connecting to MX+…") to Tone.WARN
    LinkStatus.CONNECTED -> "MX+ connected" to Tone.GOOD
    LinkStatus.ADAPTER_UNREACHABLE -> "MX+ unreachable" to Tone.WARN
    LinkStatus.TRUCK_ASLEEP -> "Truck asleep" to Tone.DIM
    LinkStatus.STOPPED_ERROR -> "Stopped: error" to Tone.ERROR
    LinkStatus.DEMO -> "Demo: simulated truck" to Tone.WARN
  }

  fun status(live: LiveState, nowMs: Long): StatusView {
    val (chip, tone) = chip(live.status, live.demo)
    val age = live.dataAgeMs(nowMs)
    return StatusView(
      chip = chip,
      tone = tone,
      message = live.message,
      dataAge = age?.let { "data ${Units.age(it)} old" },
      dataStale = age != null && age > DATA_STALE_MS,
      adapterVolts = live.adapterVolts?.let { "${Units.num(it, 1)} V at the adapter" },
      canRetry = live.canRetry,
      retryIn = live.nextAutoRetryAtMs?.let { at -> "auto retry in ${Units.age(max(0, at - nowMs))}" },
      demo = live.demo,
    )
  }

  const val DATA_STALE_MS = 10_000L

  // --- Helpers ----------------------------------------------------------------------------------

  private fun num(v: Map<String, LiveValue>, name: String): Double? = (v[name]?.v as? Number)?.toDouble()?.takeIf { it.isFinite() }

  private fun dnum(d: Map<String, Any>, name: String): Double? = (d[name] as? Number)?.toDouble()?.takeIf { it.isFinite() }

  /** A value is stale after 3 poll periods (at least 5 s). */
  fun staleAfterMs(periodS: Double): Long = max(5_000L, (periodS * 3_000).toLong())

  private fun ageOf(x: LiveValue?, nowMs: Long): String? = x?.let { Units.age(nowMs - it.atMs) }

  private fun isStale(x: LiveValue?, nowMs: Long): Boolean = x != null && nowMs - x.atMs > staleAfterMs(x.periodS)

  /** "likely" for the 2AF1 module temperatures, "unverified" for any other non-confirmed value. */
  fun marker(x: LiveValue?): String? = when {
    x == null || x.confirmed -> null
    x.name == "module_temps" -> "likely"
    else -> "unverified"
  }

  fun moduleOf(group: Int): Int = (group - 1) / GROUPS_PER_MODULE + 1

  // --- KPI tiles --------------------------------------------------------------------------------

  /** [ownRangeM]: the session's range, ahead of the truck's on one line because a tile has few lines. */
  fun soc(v: Map<String, LiveValue>, vhal: VhalFigures, dist: DistUnit, nowMs: Long, ownRangeM: Double? = null): Kpi {
    val soc = num(v, "soc")
    val energy = num(v, "energy_remaining")
    val range = num(v, "range_estimate")
    val lines = mutableListOf<String>()
    val driving = mutableListOf<String>()
    when {
      energy != null -> {
        lines += "${Units.num(energy, 1)} kWh remaining"
        driving += "${Units.num(energy, 1)} kWh"
      }
      vhal.energyWh != null -> {
        lines += "${Units.num(vhal.energyWh / 1000.0, 1)} kWh remaining (VHAL)"
        driving += "${Units.num(vhal.energyWh / 1000.0, 1)} kWh"
      }
    }
    when {
      ownRangeM != null -> {
        val own = "Range ${Units.distText(ownRangeM / Units.METERS_PER_MILE, dist)}"
        val truck = range ?: vhal.rangeM?.let { it / Units.METERS_PER_MILE }
        val both = if (truck != null) "$own · truck ${Units.distText(truck, dist)}" else own
        lines += both
        driving += both
      }
      range != null -> {
        lines += "Range ${Units.distText(range, dist)} (BMS)"
        driving += "Range ${Units.distText(range, dist)}"
      }
      vhal.rangeM != null -> {
        lines += "Range ${Units.distText(vhal.rangeM / Units.METERS_PER_MILE, dist)} (VHAL)"
        driving += "Range ${Units.distText(vhal.rangeM / Units.METERS_PER_MILE, dist)}"
      }
    }
    if (soc != null) {
      val x = v["soc"]
      return Kpi("State of charge", Units.num(soc, 1), "%", lines, marker = marker(x), age = ageOf(x, nowMs), stale = isStale(x, nowMs), shortTitle = SOC_SHORT, drivingLines = driving)
    }
    val vs = vhal.socPct
    if (vs != null) return Kpi("State of charge", Units.num(vs, 1), "%", lines, marker = "VHAL", shortTitle = SOC_SHORT, drivingLines = driving)
    return Kpi("State of charge", Units.DASH, "%", lines.ifEmpty { listOf("Waiting for the MX+ or the car service") }, hasValue = false, shortTitle = SOC_SHORT, drivingLines = driving)
  }

  private const val SOC_SHORT = "Charge"
  private const val SPREAD_SHORT = "Cell spread"

  /**
   * The pack's capacity in kWh: read from the BECM (27AE), else energy / SoC. With no MX+ data, the
   * last one read and how long ago; never the car API's figure as if it were this one.
   */
  fun capacity(v: Map<String, LiveValue>, derived: Map<String, Any>, vhal: VhalFigures, nowMs: Long, saved: SavedCapacity? = null, wallNowMs: Long = 0L): Kpi {
    val read = dnum(derived, "capacity_kwh")
    val implied = dnum(derived, "bms_energy_at_100_kwh")
    val kwh = read ?: implied
    if (kwh != null) {
      val src = if (read != null) v["battery_capability"] else v["energy_remaining"]
      val lines = mutableListOf(if (read != null) "Battery capability, from the BECM" else "Calculated: energy ÷ SoC")
      if (read != null && implied != null) lines += "Energy ÷ SoC: ${Units.num(implied, 1)} kWh"
      val groups = dnum(derived, "series_groups")
      val modules = dnum(derived, "modules")
      listOfNotNull(
        modules?.let { "${Units.num(it, 0)} modules" },
        groups?.let { "${Units.num(it, 0)} series groups" },
      ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
      return Kpi(CAPACITY_TITLE, Units.num(kwh, if (read != null) 2 else 1), "kWh", lines, marker = if (read != null) marker(src) else EST, age = ageOf(src, nowMs), stale = isStale(src, nowMs), shortTitle = CAPACITY_SHORT)
    }
    saved?.kwh?.let { return lastRead(CAPACITY_TITLE, CAPACITY_SHORT, Units.num(it, 2), "kWh", saved, wallNowMs) }
    val api = (vhal.usableWh ?: vhal.nominalWh)?.let { "The car's API says ${Units.num(it / 1000.0, 1)} kWh" }
    return Kpi(CAPACITY_TITLE, Units.DASH, "kWh", listOfNotNull(NEEDS_MX, api), hasValue = false, shortTitle = CAPACITY_SHORT)
  }

  /**
   * The pack's capacity in Ah: read from the BECM (2B44, the PID Car Scanner uses), else a dash. Never worked
   * out from the kWh figure: that read 585-587 and wandered next to Car Scanner's 599 (owner, 2026-11-11).
   */
  fun capacityAh(v: Map<String, LiveValue>, derived: Map<String, Any>, nowMs: Long, saved: SavedCapacity? = null, wallNowMs: Long = 0L): Kpi {
    val read = dnum(derived, "capacity_ah")
    if (read != null) {
      val src = v["pack_capacity_ah"]
      return Kpi(AH_TITLE, Units.num(read, 1), "Ah", listOf("Total energy storage, from the BECM"), marker = marker(src), age = ageOf(src, nowMs), stale = isStale(src, nowMs), shortTitle = AH_SHORT)
    }
    saved?.ah?.let { return lastRead(AH_TITLE, AH_SHORT, Units.num(it, 1), "Ah", saved, wallNowMs) }
    return Kpi(AH_TITLE, Units.DASH, "Ah", listOf(if (v.isEmpty()) NEEDS_MX else PID_UNKNOWN), hasValue = false, shortTitle = AH_SHORT)
  }

  /**
   * "Battery total energy storage capacity deviation", an integer Ah: the profile's capacity_deviation,
   * the byte after the capacity in 2B44 (where Car Scanner reads it).
   */
  fun capacityDeviation(v: Map<String, LiveValue>, nowMs: Long): Kpi {
    val src = v["capacity_deviation"]
    val ah = num(v, "capacity_deviation") ?: return Kpi(DEVIATION_TITLE, Units.DASH, "Ah", listOf(PID_UNKNOWN), hasValue = false, shortTitle = DEVIATION_SHORT)
    return Kpi(DEVIATION_TITLE, Units.num(ah, 0), "Ah", listOf("Total energy storage capacity deviation"), marker = marker(src), age = ageOf(src, nowMs), stale = isStale(src, nowMs), shortTitle = DEVIATION_SHORT)
  }

  private fun lastRead(title: String, short: String, value: String, unit: String, saved: SavedCapacity, wallNowMs: Long): Kpi {
    val line = "Read ${Units.age(max(0L, wallNowMs - saved.atWallMs))} ago"
    return Kpi(title, value, unit, listOf(line), marker = LAST_READ, shortTitle = short, drivingLines = listOf(line))
  }

  const val LAST_READ = "last read"
  private const val EST = "est."
  private const val NEEDS_MX = "Needs the OBDLink MX+"
  private const val PID_UNKNOWN = "Not reported by the BECM"
  private const val CAPACITY_TITLE = "Capacity"
  private const val CAPACITY_SHORT = "Capacity"
  private const val AH_TITLE = "Capacity (Ah)"
  private const val AH_SHORT = "Capacity"
  private const val DEVIATION_TITLE = "Capacity deviation"
  private const val DEVIATION_SHORT = "Deviation"

  /** Pack voltage, current and the power they make, + discharge, each with its own age and marker. */
  fun pack(v: Map<String, LiveValue>, nowMs: Long): List<TempLine> {
    val volts = v["pack_voltage"]
    val amps = v["pack_current"]
    val kw = num(v, "pack_voltage")?.let { u -> num(v, "pack_current")?.let { i -> u * i / 1000.0 } }
    return listOf(
      TempLine("Pack voltage", num(v, "pack_voltage")?.let { "${Units.num(it, 1)} V" } ?: Units.DASH, marker(volts), isStale(volts, nowMs), shortLabel = "Voltage"),
      TempLine("Pack current", num(v, "pack_current")?.let { "${Units.signed(it, 1)} A" } ?: Units.DASH, marker(amps), isStale(amps, nowMs), shortLabel = "Current"),
      // Volts x amps: as old as the older of the two.
      TempLine("Pack power", kw?.let { "${Units.signed(it, 1)} kW" } ?: Units.DASH, marker(amps), isStale(volts, nowMs) || isStale(amps, nowMs), shortLabel = "Power"),
    )
  }

  // --- Cell groups under load -------------------------------------------------------------------

  /**
   * Current samples this long before the oldest value still count as "during the read". Values
   * carry the time their poll cycle ended; the cycle that reads the 16 cell-group DIDs takes about
   * 1.5–2.5 s, and the previous current sample (1 s period) lands about 3–3.5 s before its end.
   */
  const val CELL_READ_WINDOW_MS = 5_000L

  /** At rest: |pack power| at most this (idle draw on the truck is 0.1–0.2 kW; 2 kW ≈ 5 A at 400 V). */
  const val CELL_REST_KW = 2.0

  /** At rest: pack power changing by at most this during the read (≈ 2.5 A at 400 V). */
  const val CELL_REST_SWING_KW = 1.0

  /** At rest by the latest pack current alone (A), when no power sample covers the read. */
  const val CELL_REST_A = 5.0

  const val UNDER_LOAD = "under load"

  /**
   * True when pack current flowed or changed while [read] were read: power samples (pack V × A)
   * from [CELL_READ_WINDOW_MS] before the oldest to the newest of them, plus the latest pack current
   * if it was read in that window. No current read around them counts as at rest.
   */
  fun readUnderLoad(read: List<LiveValue>, v: Map<String, LiveValue>, power: List<PowerPoint>): Boolean {
    if (read.isEmpty()) return false
    val from = read.minOf { it.atMs } - CELL_READ_WINDOW_MS
    val to = read.maxOf { it.atMs }
    val kw = power.filter { it.atMs in from..to }.map { it.kw }.filter { it.isFinite() }
    if (kw.any { abs(it) > CELL_REST_KW }) return true
    if (kw.isNotEmpty() && kw.max() - kw.min() > CELL_REST_SWING_KW) return true
    val amps = num(v, "pack_current") ?: return false
    return v.getValue("pack_current").atMs in from..to && abs(amps) > CELL_REST_A
  }

  /** One 400 V half from its BMS summary (2AF6 = "a", modules 1–12; 2AF5 = "b", modules 13–24). */
  class HalfSummary(val half: String, val lowV: Double, val highV: Double, val low: String?, val high: String?, val values: List<LiveValue>) {
    val spreadMv: Double get() = (highV - lowV) * 1000.0
  }

  fun halfSummaries(v: Map<String, LiveValue>): List<HalfSummary> = listOf("a", "b").mapNotNull { h ->
    val lo = num(v, "cell_min_$h") ?: return@mapNotNull null
    val hi = num(v, "cell_max_$h") ?: return@mapNotNull null
    HalfSummary(h, lo, hi, halfLocation(v, "min", h), halfLocation(v, "max", h), listOfNotNull(v["cell_min_$h"], v["cell_max_$h"]))
  }

  private fun halfLocation(v: Map<String, LiveValue>, kind: String, h: String): String? {
    val g = num(v, "cell_${kind}_index_$h") ?: return null
    val m = num(v, "cell_${kind}_module_$h") ?: return null
    return "group ${Units.num(g, 0)} · module ${Units.num(m, 0)}"
  }

  /** Whether [spread] takes the figure from the full array (else from the BMS summaries). */
  fun spreadFromArray(v: Map<String, LiveValue>, heat: HeatmapView?): Boolean =
    heat != null && (!heat.underLoad || halfSummaries(v).isEmpty())

  /**
   * Cell-group spread: from the full array (with locations) when it was read with the pack at
   * rest. Under load, the larger of the two per-half spreads from the BMS summaries (each half is
   * one read, so no load change can creep in). Without the array, the half summaries: combined at
   * rest, per half under load.
   */
  fun spread(
    v: Map<String, LiveValue>,
    derived: Map<String, Any>,
    heat: HeatmapView?,
    nowMs: Long,
    power: List<PowerPoint> = emptyList(),
  ): Kpi {
    if (heat != null && spreadFromArray(v, heat)) return arraySpread(heat)
    val halves = halfSummaries(v)
    if (halves.isNotEmpty() && (heat != null || readUnderLoad(halves.flatMap { it.values }, v, power))) {
      return halfSpread(halves, v, nowMs)
    }
    val mv = dnum(derived, "cell_spread_mv")
    val lo = dnum(derived, "cell_min_v")
    val hi = dnum(derived, "cell_max_v")
    if (mv != null && lo != null && hi != null) {
      val lines = mutableListOf("${Units.num(lo, 4)}–${Units.num(hi, 4)} V")
      summaryLocation(v, low = true)?.let { lines += "low: $it" }
      summaryLocation(v, low = false)?.let { lines += "high: $it" }
      val src = v["cell_min_a"] ?: v["cell_min_b"]
      val line = spreadLine(lo, hi, underLoad = false)
      return Kpi("Cell-group spread", Units.num(mv, 1), "mV", lines, age = ageOf(src, nowMs), stale = isStale(src, nowMs), shortTitle = SPREAD_SHORT, shortLine = line, drivingLines = listOf(line))
    }
    return Kpi("Cell-group spread", Units.DASH, "mV", listOf("Needs cell data from the MX+"), hasValue = false, shortTitle = SPREAD_SHORT)
  }

  private fun arraySpread(heat: HeatmapView): Kpi {
    val spreadMv = (heat.max.volts!! - heat.min.volts!!) * 1000.0
    val range = "${Units.num(heat.min.volts, 4)}–${Units.num(heat.max.volts, 4)} V"
    return Kpi(
      title = "Cell-group spread",
      value = Units.num(spreadMv, 1),
      unit = "mV",
      lines = listOf(
        // Under load only when no half summary could replace the array.
        if (heat.underLoad) "$range · $UNDER_LOAD" else range,
        "low: group ${heat.min.group} · module ${heat.min.module}",
        "high: group ${heat.max.group} · module ${heat.max.module}",
      ),
      age = heat.age,
      stale = heat.stale,
      shortTitle = SPREAD_SHORT,
      shortLine = spreadLine(heat.min.volts, heat.max.volts, heat.underLoad),
      drivingLines = listOf(spreadLine(heat.min.volts, heat.max.volts, heat.underLoad)),
    )
  }

  /** "3.9181–3.9298 V · load": cell min / max, still saying the figures were read under load. */
  private fun spreadLine(lowV: Double, highV: Double, underLoad: Boolean): String =
    "${Units.num(lowV, 4)}–${Units.num(highV, 4)} V" + if (underLoad) " · load" else ""

  /** The half with the larger spread (half A on a tie), from its own BMS summary. */
  private fun halfSpread(halves: List<HalfSummary>, v: Map<String, LiveValue>, nowMs: Long): Kpi {
    val pick = halves.maxBy { it.spreadMv }
    val lines = mutableListOf("half ${pick.half.uppercase()} ${Units.num(pick.lowV, 4)}–${Units.num(pick.highV, 4)} V · $UNDER_LOAD")
    pick.low?.let { lines += "low: $it" }
    pick.high?.let { lines += "high: $it" }
    val src = v["cell_min_${pick.half}"]
    return Kpi(
      "Cell-group spread",
      Units.num(pick.spreadMv, 1),
      "mV",
      lines,
      age = ageOf(src, nowMs),
      stale = isStale(src, nowMs),
      shortTitle = SPREAD_SHORT,
      shortLine = spreadLine(pick.lowV, pick.highV, underLoad = true),
      drivingLines = listOf(spreadLine(pick.lowV, pick.highV, underLoad = true)),
    )
  }

  /** "group G · module M" of the lowest / highest group from the half summaries (2AF6 / 2AF5). */
  private fun summaryLocation(v: Map<String, LiveValue>, low: Boolean): String? {
    val kind = if (low) "min" else "max"
    val halves = listOf("a", "b").mapNotNull { h ->
      val volts = num(v, "cell_${kind}_$h") ?: return@mapNotNull null
      Triple(volts, num(v, "cell_${kind}_index_$h"), num(v, "cell_${kind}_module_$h"))
    }
    if (halves.isEmpty()) return null
    val pick = if (low) halves.minBy { it.first } else halves.maxBy { it.first }
    val g = pick.second ?: return null
    val m = pick.third ?: return null
    return "group ${Units.num(g, 0)} · module ${Units.num(m, 0)}"
  }

  // --- Cell-group heatmap -----------------------------------------------------------------------

  /** The cell-group array (cells_001_012 … in key order, as Derive numbers it); null = not a number. */
  fun cellArray(v: Map<String, LiveValue>): List<Double?> =
    v.keys.sorted().filter { it.startsWith("cells_") && v[it]?.v is List<*> }
      .flatMap { k -> (v.getValue(k).v as List<*>).map { (it as? Number)?.toDouble() } }

  /**
   * 8 groups per module column, as many columns as the array reaches (24 on this truck; 20 or 14 on
   * smaller packs). Padding / missing positions stay empty. Colour = deviation from the pack average.
   */
  fun heatmap(v: Map<String, LiveValue>, nowMs: Long, power: List<PowerPoint> = emptyList()): HeatmapView? {
    val arr = cellArray(v)
    val real = arr.withIndex().filter { (_, x) -> x != null && x > MIN_VALID_CELL_V }
    if (real.isEmpty()) return null
    val avg = real.sumOf { it.value!! } / real.size
    val last = real.last().index + 1
    val modules = ceil(last / GROUPS_PER_MODULE.toDouble()).toInt()
    val cells = (1..modules * GROUPS_PER_MODULE).map { g ->
      val x = arr.getOrNull(g - 1)?.takeIf { it > MIN_VALID_CELL_V }
      HeatCell(g, moduleOf(g), (g - 1) % GROUPS_PER_MODULE, x, x?.let { (it - avg) * 1000.0 })
    }
    val valid = cells.filter { it.volts != null }
    var lo = valid.first()
    var hi = valid.first()
    for (c in valid) { // first lowest / first highest, like Derive
      if (c.volts!! < lo.volts!!) lo = c
      if (c.volts > hi.volts!!) hi = c
    }
    val maxDev = valid.maxOf { abs(it.devMv!!) }
    val cellFields = v.values.filter { it.name.startsWith("cells_") }
    val oldest = cellFields.minByOrNull { it.atMs }
    return HeatmapView(
      modules = modules,
      groupsPerModule = GROUPS_PER_MODULE,
      cells = cells,
      avgV = avg,
      scaleMv = colourScaleMv(maxDev),
      read = valid.size,
      min = lo,
      max = hi,
      age = ageOf(oldest, nowMs),
      stale = isStale(oldest, nowMs),
      underLoad = readUnderLoad(cellFields, v, power),
    )
  }

  /** Symmetric colour scale: the largest deviation rounded up to 0.5 mV, at least 1 mV. */
  fun colourScaleMv(maxDevMv: Double): Double = max(1.0, ceil(maxDevMv * 2.0) / 2.0)

  /** -1 … +1 position of a deviation on the colour scale. */
  fun colourPosition(devMv: Double, scaleMv: Double): Double = (devMv / scaleMv).coerceIn(-1.0, 1.0)

  fun cellDetail(c: HeatCell): String =
    if (c.volts == null) {
      "group ${c.group} · module ${c.module} · no data"
    } else {
      "group ${c.group} · module ${c.module} · ${Units.num(c.volts, 4)} V · ${Units.signed(c.devMv!!, 1)} mV vs avg"
    }

  // --- Temperatures -----------------------------------------------------------------------------

  /**
   * One temperature per module. 2AF1 holds two sensors for each of battery 1's modules (Car Scanner's
   * mapping, 2026-11-11), and 2AF2 is taken to hold battery 2's the same way: a module's figure is the mean
   * of its two sensors. Battery 2's modules follow battery 1's.
   */
  fun moduleTemps(v: Map<String, LiveValue>, nowMs: Long): ModuleTemps? {
    val x = v["module_temps"] ?: return null
    val temps = (sensorPairs(x) ?: return null) + sensorPairs(v["module_temps_2"]).orEmpty()
    if (temps.all { it == null }) return null
    val trimmed = temps.dropLastWhile { it == null }
    val sensors = listOfNotNull(x, v["module_temps_2"]).flatMap { (it.v as? List<*>).orEmpty() }.mapNotNull { (it as? Number)?.toDouble()?.takeIf { t -> t > MIN_VALID_MODULE_TEMP_C } }
    val pairs = listOfNotNull(x, v["module_temps_2"]).flatMap { s ->
      (s.v as? List<*>).orEmpty().chunked(2) { p -> p.map { (it as? Number)?.toDouble()?.takeIf { t -> t > MIN_VALID_MODULE_TEMP_C } } }
    }.take(trimmed.size)
    return ModuleTemps(trimmed, marker(x), isStale(x, nowMs), sensors, pairs)
  }

  /** The mean of each pair of sensors; null for a pair that is all padding. */
  private fun sensorPairs(x: LiveValue?): List<Double?>? = (x?.v as? List<*>)?.chunked(2) { pair ->
    pair.mapNotNull { (it as? Number)?.toDouble()?.takeIf { t -> t > MIN_VALID_MODULE_TEMP_C } }.takeIf { it.isNotEmpty() }?.average()
  }

  fun temps(v: Map<String, LiveValue>, vhal: VhalFigures, live: LiveState, u: TempUnit, nowMs: Long): List<TempLine> {
    val out = mutableListOf<TempLine>()
    val mt = moduleTemps(v, nowMs)
    val real = mt?.celsius?.filterNotNull().orEmpty()
    out += if (real.isNotEmpty()) {
      val avg = real.sum() / real.size
      TempLine(
        "Modules min · avg · max",
        "${Units.num(Units.temp(real.min(), u), 0)} · ${Units.num(Units.temp(avg, u), 1)} · ${Units.num(Units.temp(real.max(), u), 0)} ${u.symbol}",
        mt?.marker,
        mt?.stale ?: false,
        shortLabel = MODULES_SHORT,
        shortValue = "${Units.num(Units.temp(real.min(), u), 0)}–${Units.num(Units.temp(real.max(), u), 0)} ${u.symbol}",
      )
    } else {
      TempLine("Modules min · avg · max", Units.DASH, marker(v["module_temps"]), shortLabel = MODULES_SHORT)
    }
    for ((name, label, short) in listOf(Triple("coolant_temp_1", "Battery coolant 1", "Coolant 1"), Triple("coolant_temp_2", "Battery coolant 2", "Coolant 2"))) {
      val x = v[name]
      out += TempLine(label, Units.tempText(num(v, name), u), marker(x), isStale(x, nowMs), shortLabel = short, shortValue = Units.tempText(num(v, name), u, 0))
    }
    out += TempLine("Outside (car API)", Units.tempText(vhal.outsideC, u), shortLabel = OUTSIDE_SHORT, shortValue = Units.tempText(vhal.outsideC, u, 0))
    val aux = v["aux_12v"]
    val auxV = num(v, "aux_12v")
    out += when {
      auxV != null -> TempLine("12 V system", "${Units.num(auxV, 1)} V", marker(aux), isStale(aux, nowMs), shortLabel = "12 V")
      live.adapterVolts != null -> TempLine("12 V system", "${Units.num(live.adapterVolts, 1)} V at the adapter", shortLabel = "12 V", shortValue = "${Units.num(live.adapterVolts, 1)} V")
      else -> TempLine("12 V system", Units.DASH, shortLabel = "12 V")
    }
    return out
  }

  /**
   * [lines] of [temps] for the driving layout: the two coolant circuits share one line, so there are
   * fewer to read. Whichever coolant is unverified or stale marks the shared line.
   */
  fun tempSummary(lines: List<TempLine>): List<TempLine> {
    val coolant = lines.filter { it.shortLabel.startsWith(COOLANT_SHORT) }
    if (coolant.isEmpty()) return lines
    val first = lines.indexOf(coolant.first())
    val known = coolant.map { it.shortValue }.filter { it != Units.DASH }
    val shared = TempLine(
      label = COOLANT_SHORT,
      value = known.joinToString(" · ").ifEmpty { Units.DASH },
      marker = coolant.firstNotNullOfOrNull { it.marker },
      stale = coolant.any { it.stale },
      shortLabel = COOLANT_SHORT,
    )
    return lines.filterNot { it in coolant }.toMutableList().apply { add(first, shared) }
  }

  private const val MODULES_SHORT = "Modules"
  private const val COOLANT_SHORT = "Coolant"
  private const val OUTSIDE_SHORT = "Outside"
}
