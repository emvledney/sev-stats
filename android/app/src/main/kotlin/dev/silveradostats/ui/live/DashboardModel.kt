package dev.silveradostats.ui.live

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.link.PowerHistory
import dev.silveradostats.link.PowerPoint
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
enum class Tone { NORMAL, GOOD, WARN, ERROR, DIM, DISCHARGE, CHARGE }

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
  val pill: String? = null,
  val pillTone: Tone = Tone.NORMAL,
  /** "unverified", "likely", "VHAL", "est." … shown as a small marker beside the title. */
  val marker: String? = null,
  val age: String? = null,
  val stale: Boolean = false,
  val hasValue: Boolean = true,
  /** The title on a narrow tile and while driving. */
  val shortTitle: String = title,
  /** The direction on a narrow tile and while driving. */
  val pillShort: String? = pill,
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

data class ModuleTemps(val celsius: List<Double?>, val marker: String?, val stale: Boolean)

data class PowerView(val points: List<PowerPoint>, val scaleKw: Double, val nowMs: Long, val windowMs: Long, val latestKw: Double?)

data class Dashboard(
  val status: StatusView,
  val soc: Kpi,
  val power: Kpi,
  val capacity: Kpi,
  val spread: Kpi,
  val powerChart: PowerView,
  val temps: List<TempLine>,
  /** Fewer, shorter lines: what the Live tab shows of [temps] while driving. */
  val tempSummary: List<TempLine>,
  val heatmap: HeatmapView?,
  val moduleTemps: ModuleTemps?,
  /** Shown when OBD data is missing: what is needed to get it. */
  val obdHint: String?,
)

object DashboardModel {
  const val GROUPS_PER_MODULE = 8

  /** Below this a module temperature byte is padding (raw 0 → −80 °C on smaller packs). */
  const val MIN_VALID_MODULE_TEMP_C = -60.0

  /** Cell-group array entries at or below this are padding (as in Derive). */
  const val MIN_VALID_CELL_V = 0.5

  const val MIN_POWER_SCALE_KW = 10.0
  private val POWER_STEPS = listOf(10.0, 20.0, 50.0, 100.0, 150.0, 200.0, 300.0, 400.0, 500.0, 600.0, 800.0)

  const val OBD_HINT = "Pack voltage and current, cell groups and module temperatures come from the OBDLink MX+ " +
    "(Diagnostics › OBDLink MX+ link). SoC, energy, range and outside temperature below are from the car's own API."

  fun build(live: LiveState, vhal: VhalFigures, temp: TempUnit, dist: DistUnit, nowMs: Long): Dashboard {
    val v = live.values.filterKeys { it !in Profile.NOT_DISPLAYED }
    val heat = heatmap(v, nowMs, live.power)
    val hasObd = v.isNotEmpty()
    return Dashboard(
      status = status(live, nowMs),
      soc = soc(v, vhal, dist, nowMs),
      power = power(v, live.derived, nowMs),
      capacity = capacity(v, live.derived, vhal, nowMs),
      spread = spread(v, live.derived, heat, nowMs, live.power),
      powerChart = powerView(live.power, nowMs),
      temps = temps(v, vhal, live, temp, nowMs),
      tempSummary = tempSummary(temps(v, vhal, live, temp, nowMs)),
      heatmap = heat,
      moduleTemps = moduleTemps(v, nowMs),
      obdHint = if (hasObd) null else OBD_HINT,
    )
  }

  // --- Status -----------------------------------------------------------------------------------

  fun chip(status: LinkStatus, demo: Boolean): Pair<String, Tone> = when (status) {
    LinkStatus.NO_ADAPTER -> "No adapter" to Tone.DIM
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

  fun soc(v: Map<String, LiveValue>, vhal: VhalFigures, dist: DistUnit, nowMs: Long): Kpi {
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

  /** Power tile: + discharge / − charge or regen; colour by direction, with V and A. */
  fun power(v: Map<String, LiveValue>, derived: Map<String, Any>, nowMs: Long): Kpi {
    val kw = dnum(derived, "power_kw")
    val volts = num(v, "pack_voltage")
    val amps = num(v, "pack_current")
    val cur = v["pack_current"]
    if (kw == null || amps == null) {
      val lines = if (volts != null) listOf("${Units.num(volts, 1)} V · current not read yet") else listOf("Needs the OBDLink MX+")
      return Kpi("Battery power", Units.DASH, "kW", lines, hasValue = false, shortTitle = POWER_SHORT, drivingLines = listOfNotNull(volts?.let { "${Units.num(it, 1)} V" }))
    }
    val (pill, tone) = direction(kw)
    val vText = volts?.let { "${Units.num(it, 1)} V" } ?: "${Units.DASH} V"
    val markers = listOfNotNull(marker(cur), marker(v["pack_voltage"])).distinct()
    return Kpi(
      title = "Battery power",
      value = Units.signed(kw, 1),
      unit = "kW",
      lines = listOf("$vText · ${Units.signed(amps, 1)} A"),
      pill = pill,
      pillTone = tone,
      marker = markers.firstOrNull(),
      age = ageOf(cur, nowMs),
      stale = isStale(cur, nowMs),
      shortTitle = POWER_SHORT,
      pillShort = pill.substringBefore(" /"),
      drivingLines = listOf("$vText · ${Units.signed(amps, 1)} A"),
    )
  }

  /** Direction label and colour: + = discharge (pack current + = discharge, as on the truck). */
  fun direction(kw: Double): Pair<String, Tone> = when {
    kw > IDLE_KW -> "Discharging" to Tone.DISCHARGE
    kw < -IDLE_KW -> "Charging / regen" to Tone.CHARGE
    else -> "Idle" to Tone.DIM
  }

  const val IDLE_KW = 0.05

  private const val SOC_SHORT = "Charge"
  private const val POWER_SHORT = "Power"
  private const val SPREAD_SHORT = "Cell spread"

  fun capacity(v: Map<String, LiveValue>, derived: Map<String, Any>, vhal: VhalFigures, nowMs: Long): Kpi {
    val bms = dnum(derived, "bms_energy_at_100_kwh")
    if (bms != null) {
      val lines = mutableListOf("BMS energy at 100 % SoC")
      dnum(derived, "ah_nominal_est")?.let { lines += "≈ ${Units.num(it, 0)} Ah nominal est." }
      val groups = dnum(derived, "series_groups")
      val modules = dnum(derived, "modules")
      listOfNotNull(
        groups?.let { "${Units.num(it, 0)} series groups" },
        modules?.let { "${Units.num(it, 0)} modules" },
      ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
      val src = v["energy_remaining"]
      return Kpi("Capacity", Units.num(bms, 1), "kWh", lines, marker = "est.", age = ageOf(src, nowMs), stale = isStale(src, nowMs))
    }
    val nominal = vhal.nominalWh
    if (nominal != null) {
      return Kpi("Capacity (nominal)", Units.num(nominal / 1000.0, 1), "kWh", listOf("From the car's API: nominal, not measured"), marker = "VHAL", shortTitle = "Capacity")
    }
    return Kpi("Capacity", Units.DASH, "kWh", listOf("Needs SoC and energy from the MX+"), hasValue = false)
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

  fun moduleTemps(v: Map<String, LiveValue>, nowMs: Long): ModuleTemps? {
    val x = v["module_temps"] ?: return null
    val list = (x.v as? List<*>) ?: return null
    val temps = list.map { (it as? Number)?.toDouble()?.takeIf { t -> t > MIN_VALID_MODULE_TEMP_C } }
    if (temps.all { it == null }) return null
    val trimmed = temps.dropLastWhile { it == null }
    return ModuleTemps(trimmed, marker(x), isStale(x, nowMs))
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

  // --- Power history ----------------------------------------------------------------------------

  fun powerView(points: List<PowerPoint>, nowMs: Long, windowMs: Long = PowerHistory.WINDOW_MS): PowerView {
    val shown = points.filter { nowMs - it.atMs <= windowMs }
    val peak = shown.maxOfOrNull { abs(it.kw) } ?: 0.0
    return PowerView(shown, powerScale(peak), nowMs, windowMs, shown.lastOrNull()?.kw)
  }

  /** Symmetric y scale for the power chart: the next step at or above the peak (at least 10 kW). */
  fun powerScale(peakKw: Double): Double = POWER_STEPS.firstOrNull { it >= peakKw } ?: (ceil(peakKw / 100.0) * 100.0)
}
