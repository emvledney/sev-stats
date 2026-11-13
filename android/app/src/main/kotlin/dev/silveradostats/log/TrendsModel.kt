package dev.silveradostats.log

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.CarEnums
import dev.silveradostats.vehicle.KnownProperties
import dev.silveradostats.vehicle.ReadProblem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/*
 * Pure Kotlin (no android.* imports): turns log rows into exactly what the Trends tab draws: one source
 * at a time, its capacity tiles, its chart and its list of PIDs. The Compose code only lays it out. A
 * chart has its own left and right scale, because the measures it pairs differ in unit; a crossing of
 * the two lines therefore means nothing, and the axes say so by carrying the colour of their series.
 */

/** The window a Trends chart shows. */
enum class TrendRange(val label: String, val ms: Long?) {
  DAY("24 h", 24 * 3_600_000L),
  WEEK("7 d", 7 * 24 * 3_600_000L),
  MONTH("30 d", 30 * 24 * 3_600_000L),
  YEAR("1 y", 365 * 24 * 3_600_000L),
  ALL("All", null),
}

/** The PID list's groups. No group of a source may hold more than [DashLayout.TREND_MAX_ROWS] measures. */
enum class TrendGroup(val label: String) {
  CAPACITY("Capacity"),
  ENERGY("Energy"),
  CELLS("Cells"),
  TEMPS("Temps"),
  POWER("Power"),
  CHARGING("Charging"),
  TRUCK("Truck"),
  UNKNOWN("Unknown"),
}

/** How far to trust a value, next to it wherever it is shown. */
enum class TrendBadge(val label: String) {
  UNVERIFIED("unverified"),
  LIKELY("likely"),
  CALCULATED("calculated"),
  FIXED("spec figure"),
  SIGN("sign unverified"),
  UNKNOWN("unknown"),
  NO_PID("no PID"),
}

/** Which of the user's units a measure follows. Logged in °C, km and km/h. */
enum class TrendScale { PLAIN, TEMPERATURE, DISTANCE, SPEED }

private val YES_NO = mapOf(1 to "Yes", 0 to "No")

/**
 * What the log holds, one source each, in the order the PID list shows it. A measure with [high] is a
 * min–max band; one with [texts] is a state, listed but not charted. Sources never mix: the BMS,
 * calculated and car figures differ by several kWh, which would look like a change.
 */
enum class TrendMeasure(
  val source: LogSource,
  val group: TrendGroup,
  val label: String,
  /** For the tap readout and a narrow window. */
  val shortLabel: String,
  val unit: String,
  val col: LogCol,
  /** Where the value comes from: a PID, a car property's name, or how it is calculated. */
  val pid: String,
  val note: String = "",
  val badge: TrendBadge? = null,
  val high: LogCol? = null,
  val decimals: Int = 1,
  val scale: TrendScale = TrendScale.PLAIN,
  val signed: Boolean = false,
  val texts: Map<Int, String>? = null,
  val plottable: Boolean = texts == null,
  /** The unit shown is the value in hex: for comparing an unidentified PID with Car Scanner. */
  val hex: Boolean = false,
  /** Value is a cell group's number, so the list also says which module it is in. */
  val cellGroup: Boolean = false,
  /** The car property read, so that the list can say why there is no value. */
  val property: Int? = null,
  /** A scale never spans less than this: rounding noise must not look like a trend. */
  val minSpan: Double = 5.0,
) {
  // Truck API.
  CAR_NOMINAL(LogSource.CAR, TrendGroup.CAPACITY, "Total capacity, nominal", "Nominal", "kWh", LogCol.CAR_NOMINAL_KWH, "INFO_EV_BATTERY_CAPACITY", badge = TrendBadge.FIXED, property = KnownProperties.INFO_EV_BATTERY_CAPACITY, minSpan = 2.0),
  CAR_USABLE(LogSource.CAR, TrendGroup.CAPACITY, "Total capacity, usable", "Usable", "kWh", LogCol.CAR_USABLE_KWH, "EV_CURRENT_BATTERY_CAPACITY", property = KnownProperties.EV_CURRENT_BATTERY_CAPACITY, minSpan = 2.0),
  CAR_ENERGY(LogSource.CAR, TrendGroup.ENERGY, "Energy remaining", "Energy", "kWh", LogCol.CAR_ENERGY_KWH, "EV_BATTERY_LEVEL", property = KnownProperties.EV_BATTERY_LEVEL),
  CAR_SOC(LogSource.CAR, TrendGroup.ENERGY, "State of charge", "SoC", "%", LogCol.CAR_SOC_PCT, "Level ÷ capacity", badge = TrendBadge.CALCULATED),
  CAR_RANGE(LogSource.CAR, TrendGroup.ENERGY, "Range remaining", "Range", "", LogCol.CAR_RANGE_KM, "RANGE_REMAINING", decimals = 0, scale = TrendScale.DISTANCE, property = KnownProperties.RANGE_REMAINING, minSpan = 10.0),
  CAR_CHARGE_RATE(LogSource.CAR, TrendGroup.CHARGING, "Charge rate, + charging", "Charge rate", "kW", LogCol.CAR_CHARGE_KW, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", badge = TrendBadge.SIGN, signed = true, property = KnownProperties.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE),
  CAR_CHARGE_STATE(LogSource.CAR, TrendGroup.CHARGING, "Charge state", "Charge state", "", LogCol.CAR_CHARGE_STATE, "EV_CHARGE_STATE", texts = CarEnums.CHARGE_STATE, property = KnownProperties.EV_CHARGE_STATE),
  CAR_PLUGGED(LogSource.CAR, TrendGroup.CHARGING, "Charge plug connected", "Plug", "", LogCol.CAR_PLUGGED, "EV_CHARGE_PORT_CONNECTED", texts = YES_NO, property = KnownProperties.EV_CHARGE_PORT_CONNECTED),
  CAR_PORT_OPEN(LogSource.CAR, TrendGroup.CHARGING, "Charge port open", "Port", "", LogCol.CAR_PORT_OPEN, "EV_CHARGE_PORT_OPEN", texts = YES_NO, property = KnownProperties.EV_CHARGE_PORT_OPEN),
  CAR_CHARGE_LIMIT(LogSource.CAR, TrendGroup.CHARGING, "Charge limit", "Limit", "%", LogCol.CAR_CHARGE_LIMIT_PCT, "EV_CHARGE_PERCENT_LIMIT", decimals = 0, property = KnownProperties.EV_CHARGE_PERCENT_LIMIT),
  CAR_DRAW_LIMIT(LogSource.CAR, TrendGroup.CHARGING, "Current draw limit", "Draw limit", "A", LogCol.CAR_DRAW_LIMIT_A, "EV_CHARGE_CURRENT_DRAW_LIMIT", decimals = 0, property = KnownProperties.EV_CHARGE_CURRENT_DRAW_LIMIT),
  CAR_CHARGE_TIME(LogSource.CAR, TrendGroup.CHARGING, "Charge time remaining", "Time left", "min", LogCol.CAR_CHARGE_MINUTES, "EV_CHARGE_TIME_REMAINING", decimals = 0, property = KnownProperties.EV_CHARGE_TIME_REMAINING),
  CAR_BATTERY_TEMP(LogSource.CAR, TrendGroup.TEMPS, "Battery average temp", "Battery", "", LogCol.CAR_BATTERY_TEMP_C, "EV_BATTERY_AVERAGE_TEMPERATURE", scale = TrendScale.TEMPERATURE, property = KnownProperties.EV_BATTERY_AVERAGE_TEMPERATURE),
  OUTSIDE(LogSource.CAR, TrendGroup.TEMPS, "Outside temperature", "Outside", "", LogCol.OUTSIDE_C, "ENV_OUTSIDE_TEMPERATURE", scale = TrendScale.TEMPERATURE, property = KnownProperties.ENV_OUTSIDE_TEMPERATURE),
  CAR_IGNITION(LogSource.CAR, TrendGroup.TRUCK, "Ignition", "Ignition", "", LogCol.CAR_IGNITION, "IGNITION_STATE", texts = CarEnums.IGNITION, property = KnownProperties.IGNITION_STATE),
  CAR_GEAR(LogSource.CAR, TrendGroup.TRUCK, "Gear", "Gear", "", LogCol.CAR_GEAR, "GEAR_SELECTION", texts = CarEnums.GEAR, property = KnownProperties.GEAR_SELECTION),
  CAR_SPEED(LogSource.CAR, TrendGroup.TRUCK, "Speed", "Speed", "", LogCol.CAR_SPEED_KMH, "PERF_VEHICLE_SPEED", decimals = 0, scale = TrendScale.SPEED, property = KnownProperties.PERF_VEHICLE_SPEED, minSpan = 10.0),
  CAR_REGEN(LogSource.CAR, TrendGroup.TRUCK, "Regenerative braking", "Regen", "", LogCol.CAR_REGEN_STATE, "EV_REGENERATIVE_BRAKING_STATE", texts = CarEnums.REGEN_STATE, property = KnownProperties.EV_REGENERATIVE_BRAKING_STATE),

  // MX+.
  CAPACITY_BMS(LogSource.MX, TrendGroup.CAPACITY, "Total capacity, checked", "Capacity", "kWh", LogCol.CAPACITY_KWH, "BECM 27AE", note = "must fit energy ÷ SoC", badge = TrendBadge.UNVERIFIED, decimals = 2, minSpan = 2.0),
  CAPACITY_27AE(LogSource.MX, TrendGroup.CAPACITY, "27AE as read", "27AE", "kWh", LogCol.BECM_27AE_KWH, "BECM 27AE", note = "no check", badge = TrendBadge.UNVERIFIED, decimals = 2, minSpan = 2.0),
  AH_BMS(LogSource.MX, TrendGroup.CAPACITY, "Total capacity, checked", "Capacity", "Ah", LogCol.CAPACITY_AH, "BECM 2B44", note = "must fit pack voltage", badge = TrendBadge.UNVERIFIED),
  AH_4676(LogSource.MX, TrendGroup.CAPACITY, "2B44 as read", "2B44", "Ah", LogCol.BECM_4676_AH, "BECM 2B44", note = "no check", badge = TrendBadge.UNVERIFIED),
  CAPACITY_CALC(LogSource.MX, TrendGroup.CAPACITY, "Energy ÷ SoC", "Energy ÷ SoC", "kWh", LogCol.IMPLIED_KWH, "27AF ÷ 27C6", badge = TrendBadge.CALCULATED, minSpan = 2.0),
  AH_CALC(LogSource.MX, TrendGroup.CAPACITY, "Ah at 3.6 V per cell group", "Nominal", "Ah", LogCol.NOMINAL_AH, "Energy ÷ SoC rescaled", badge = TrendBadge.CALCULATED),
  AH_DEVIATION(LogSource.MX, TrendGroup.CAPACITY, "Capacity deviation", "Deviation", "Ah", LogCol.AH_DEVIATION, "Profile field ${LogRows.AH_DEVIATION_FIELD}", decimals = 0),
  SOC(LogSource.MX, TrendGroup.ENERGY, "State of charge", "SoC", "%", LogCol.SOC_PCT, "BECM 27C6"),
  BMS_SOC(LogSource.MX, TrendGroup.ENERGY, "Battery 1 charge", "Batt 1", "%", LogCol.BMS_SOC_PCT, "BECM 2B43"),
  BMS_SOC_2(LogSource.MX, TrendGroup.ENERGY, "Battery 2 charge", "Batt 2", "%", LogCol.BMS_SOC_2_PCT, "BECM 2B43"),
  ENERGY(LogSource.MX, TrendGroup.ENERGY, "Energy remaining", "Energy", "kWh", LogCol.ENERGY_KWH, "BECM 27AF"),
  BMS_RANGE(LogSource.MX, TrendGroup.ENERGY, "Range, BMS", "Range", "", LogCol.BMS_RANGE_KM, "BECM 27C7", decimals = 0, scale = TrendScale.DISTANCE, minSpan = 10.0),
  SINCE_FULL(LogSource.MX, TrendGroup.ENERGY, "Distance since full", "Since full", "", LogCol.SINCE_FULL_KM, "BECM 27C0", decimals = 0, scale = TrendScale.DISTANCE, minSpan = 10.0),
  CELL_LOW(LogSource.MX, TrendGroup.CELLS, "Lowest cell group", "Lowest", "V", LogCol.CELL_MIN_V, "2AF5, 2AF6", decimals = 3, minSpan = 0.02),
  CELL_HIGH(LogSource.MX, TrendGroup.CELLS, "Highest cell group", "Highest", "V", LogCol.CELL_MAX_V, "2AF5, 2AF6", decimals = 3, minSpan = 0.02),
  CELL_SPREAD(LogSource.MX, TrendGroup.CELLS, "Cell spread", "Spread", "mV", LogCol.CELL_SPREAD_MV, "Highest − lowest", badge = TrendBadge.CALCULATED, minSpan = 2.0),
  CELL_AVG_A(LogSource.MX, TrendGroup.CELLS, "Average, modules 1–12", "Avg 1–12", "V", LogCol.CELL_AVG_A_V, "BECM 2AF6", decimals = 3, minSpan = 0.02),
  CELL_AVG_B(LogSource.MX, TrendGroup.CELLS, "Average, modules 13–24", "Avg 13–24", "V", LogCol.CELL_AVG_B_V, "BECM 2AF5", decimals = 3, minSpan = 0.02),
  CELL_LOW_GROUP(LogSource.MX, TrendGroup.CELLS, "Lowest group number", "Lowest #", "", LogCol.CELL_MIN_GROUP, "2AE1–2AF0", decimals = 0, cellGroup = true, minSpan = 10.0),
  CELL_HIGH_GROUP(LogSource.MX, TrendGroup.CELLS, "Highest group number", "Highest #", "", LogCol.CELL_MAX_GROUP, "2AE1–2AF0", decimals = 0, cellGroup = true, minSpan = 10.0),
  MODULE_TEMPS(LogSource.MX, TrendGroup.TEMPS, "Module temps, min–max", "Modules", "", LogCol.MODULE_TEMP_MIN_C, "BECM 2AF1", badge = TrendBadge.LIKELY, high = LogCol.MODULE_TEMP_MAX_C, decimals = 0, scale = TrendScale.TEMPERATURE),
  COOLANT_1(LogSource.MX, TrendGroup.TEMPS, "Battery coolant 1", "Coolant 1", "", LogCol.COOLANT_1_C, "BCM 40E5", scale = TrendScale.TEMPERATURE),
  COOLANT_2(LogSource.MX, TrendGroup.TEMPS, "Battery coolant 2", "Coolant 2", "", LogCol.COOLANT_2_C, "BCM 40E6", scale = TrendScale.TEMPERATURE),
  PACK_TEMP(LogSource.MX, TrendGroup.TEMPS, "Ambient air (battery module)", "Ambient", "", LogCol.PACK_TEMP_C, "BECM 0046", note = "Car Scanner's name; logged as pack temp before 0.21.0", badge = TrendBadge.UNVERIFIED, decimals = 0, scale = TrendScale.TEMPERATURE),
  BATTERY_TEMP_A(LogSource.MX, TrendGroup.TEMPS, "Battery sensor A", "Sensor A", "", LogCol.BATTERY_TEMP_A_C, "BCM 4127", note = "meaning unclear", badge = TrendBadge.UNVERIFIED, scale = TrendScale.TEMPERATURE),
  BATTERY_TEMP_B(LogSource.MX, TrendGroup.TEMPS, "Battery sensor B", "Sensor B", "", LogCol.BATTERY_TEMP_B_C, "BCM 4124", note = "meaning unclear", badge = TrendBadge.UNVERIFIED, scale = TrendScale.TEMPERATURE),
  HV_BATTERY_TEMP(LogSource.MX, TrendGroup.TEMPS, "HV battery temp", "HV temp", "", LogCol.HV_BATTERY_TEMP_C, "BCM 434F", note = "reads wrong on this truck", badge = TrendBadge.UNVERIFIED, decimals = 0, scale = TrendScale.TEMPERATURE),
  PACK_VOLTAGE(LogSource.MX, TrendGroup.POWER, "Pack voltage", "Pack V", "V", LogCol.PACK_V, "Drive unit 2885", minSpan = 10.0),
  PACK_CURRENT(LogSource.MX, TrendGroup.POWER, "Pack current, + discharge", "Pack A", "A", LogCol.PACK_A, "Drive unit 2414", signed = true, minSpan = 10.0),
  PACK_POWER(LogSource.MX, TrendGroup.POWER, "Pack power", "Pack kW", "kW", LogCol.PACK_KW, "V × A", badge = TrendBadge.CALCULATED, signed = true),
  PACK_VOLTAGE_DU2(LogSource.MX, TrendGroup.POWER, "Pack voltage, drive unit 2", "DU2 V", "V", LogCol.PACK_V_DU2, "Drive unit 2 2885", minSpan = 10.0),
  AUX_12V(LogSource.MX, TrendGroup.POWER, "12 V system", "12 V", "V", LogCol.AUX_12V_V, "BECM 33E5", minSpan = 1.0),
  AUX_12V_DU(LogSource.MX, TrendGroup.POWER, "12 V system, drive unit", "12 V DU", "V", LogCol.AUX_12V_DU_V, "Drive unit 33E5", minSpan = 1.0),
  CHARGE_CODE(LogSource.MX, TrendGroup.CHARGING, "Charge code (raw)", "Code", "", LogCol.CHARGE_STATE_CODE, "BECM 5401", note = "0 = idle; Car Scanner reads it as charger DC kW = raw × 15 ÷ 255", decimals = 0, plottable = false),
  EVSE_PILOT(LogSource.MX, TrendGroup.CHARGING, "Charger pilot current", "Pilot", "A", LogCol.EVSE_PILOT_A, "BCM 4149"),
  RAW_27B9(LogSource.MX, TrendGroup.UNKNOWN, "27B9", "27B9", "", LogCol.RAW_27B9, "BECM 27B9", note = "meaning unknown", badge = TrendBadge.UNKNOWN, decimals = 0, hex = true, minSpan = 10.0),
  RAW_2984(LogSource.MX, TrendGroup.UNKNOWN, "2984", "2984", "", LogCol.RAW_2984, "BECM 2984", note = "meaning unknown", badge = TrendBadge.UNKNOWN, decimals = 0, hex = true, minSpan = 10.0),
  RAW_270D(LogSource.MX, TrendGroup.UNKNOWN, "270D", "270D", "", LogCol.RAW_270D, "BECM 270D", note = "meaning unknown", badge = TrendBadge.UNKNOWN, decimals = 0, hex = true, minSpan = 10.0),
  RAW_28AF(LogSource.MX, TrendGroup.UNKNOWN, "28AF", "28AF", "", LogCol.RAW_28AF, "BECM 28AF", note = "meaning unknown", badge = TrendBadge.UNKNOWN, decimals = 0, hex = true, minSpan = 10.0),
  RAW_2770(LogSource.MX, TrendGroup.UNKNOWN, "2770", "2770", "", LogCol.RAW_2770, "BECM 2770", note = "meaning unknown", badge = TrendBadge.UNKNOWN, decimals = 0, hex = true, minSpan = 10.0),
  ;

  fun unitIn(temp: TempUnit, dist: DistUnit): String = when (scale) {
    TrendScale.PLAIN -> unit
    TrendScale.TEMPERATURE -> temp.symbol
    TrendScale.DISTANCE -> dist.symbol
    TrendScale.SPEED -> if (dist == DistUnit.MI) "mph" else "km/h"
  }

  /** A logged value in the user's units. */
  fun convert(x: Double, temp: TempUnit, dist: DistUnit): Double = when (scale) {
    TrendScale.PLAIN -> x
    TrendScale.TEMPERATURE -> Units.temp(x, temp)
    TrendScale.DISTANCE, TrendScale.SPEED -> if (dist == DistUnit.MI) x / Units.KM_PER_MILE else x
  }

  /** The same pack figure as read, for when this one's check turned it down. */
  val asRead: TrendMeasure?
    get() = when (this) {
      CAPACITY_BMS -> CAPACITY_27AE
      AH_BMS -> AH_4676
      else -> null
    }

  /** A figure nothing reads yet: its PID has not been identified. */
  val unidentified: Boolean get() = this == AH_DEVIATION

  companion object {
    fun of(source: LogSource): List<TrendMeasure> = entries.filter { it.source == source }

    fun groups(source: LogSource): List<TrendGroup> = of(source).map { it.group }.distinct()
  }
}

/** The two measures on a source's chart. */
data class ChartPick(val left: TrendMeasure, val right: TrendMeasure) {
  /** A measure on one side only: asking for the one on the other side swaps them. */
  fun withLeft(m: TrendMeasure): ChartPick = if (m == right) ChartPick(m, left) else copy(left = m)

  fun withRight(m: TrendMeasure): ChartPick = if (m == left) ChartPick(right, m) else copy(right = m)
}

/** The newest value a column has, whichever row it is in. */
data class LatestValue(val v: Double, val atMs: Long)

/** All Trends draws of one source. [rows] hold only the columns its tiles and chart use. */
data class TrendData(
  val source: LogSource,
  val pick: ChartPick,
  val rows: List<LogRow> = emptyList(),
  val totalRows: Long = 0,
  val lastAtMs: Long? = null,
  val nowMs: Long = 0,
  val demo: Boolean = false,
  val latest: Map<LogCol, LatestValue> = emptyMap(),
  /** Why car properties (by id) had no value in the last read. */
  val problems: Map<Int, ReadProblem> = emptyMap(),
)

/** One source's page, worked out off the main thread. [data] no longer holds its rows: a chart thins them. */
data class TrendPage(val data: TrendData, val chart: TrendChart, val tiles: List<TrendTile>)

/** One capacity tile. [line] is where the value comes from or, with [value] null, why it has none. */
data class TrendTile(val measure: TrendMeasure, val title: String, val badge: TrendBadge?, val value: String?, val unit: String, val line: String, val change: String? = null)

/** One line of the PID list. [value] null: nothing logged, and [missing] says why. */
data class PidRow(val measure: TrendMeasure, val detail: String, val badge: TrendBadge?, val value: String?, val unit: String, val missing: String?) {
  val chartable: Boolean get() = value != null && measure.plottable
}

/** Over the rows in range: its extremes, last minus first, how many rows. In the user's units. */
data class TrendStats(val low: Double, val high: Double, val change: Double, val rows: Int)

/** One plotted time: [low] == [high] for a single value; a bucket's mean is [mean]. */
data class TrendPoint(val atMs: Long, val mean: Double, val low: Double, val high: Double)

data class TrendAxis(val min: Double, val max: Double, val ticks: List<Double>, val decimals: Int)

/** One measure on one side: runs of points, broken wherever the truck was off. Empty = nothing logged. */
data class TrendSeries(
  val measure: TrendMeasure,
  val unit: String,
  val band: Boolean,
  val segments: List<List<TrendPoint>>,
  val axis: TrendAxis,
  val stats: TrendStats?,
) {
  val hasData: Boolean get() = segments.isNotEmpty()
}

data class TimeTick(val atMs: Long, val label: String)

data class TrendChart(
  val startMs: Long,
  val endMs: Long,
  val left: TrendSeries,
  val right: TrendSeries,
  val timeTicks: List<TimeTick>,
)

object TrendsModel {
  /** At most this many plotted times per series: more than the screen's width can tell apart. */
  const val MAX_POINTS = 480

  /** Rows further apart than this are not joined by a line: the truck was off. */
  const val GAP_MS = 3 * 3_600_000L

  /** "All" with nothing logged, or a single row, still shows a day. */
  private const val MIN_SPAN_MS = 24 * 3_600_000L

  /** The capacity tiles at the top of each source's page, and what its chart shows at first. */
  val TILES: Map<LogSource, List<TrendMeasure>> = mapOf(
    LogSource.CAR to listOf(TrendMeasure.CAR_NOMINAL, TrendMeasure.CAR_USABLE, TrendMeasure.CAR_ENERGY, TrendMeasure.CAR_SOC),
    LogSource.MX to listOf(TrendMeasure.CAPACITY_BMS, TrendMeasure.AH_BMS, TrendMeasure.CAPACITY_CALC, TrendMeasure.AH_DEVIATION),
  )

  fun defaultPick(source: LogSource): ChartPick = when (source) {
    LogSource.CAR -> ChartPick(TrendMeasure.CAR_NOMINAL, TrendMeasure.CAR_ENERGY)
    LogSource.MX -> ChartPick(TrendMeasure.CAPACITY_BMS, TrendMeasure.AH_BMS)
  }

  /** The columns the tiles and the chart need in every row. A year of whole rows would not fit in memory. */
  fun chartColumns(source: LogSource, pick: ChartPick): Set<LogCol> =
    (TILES.getValue(source) + pick.left + pick.right).flatMap { listOfNotNull(it.col, it.high) }.toSet()

  fun loggedColumns(source: LogSource): Set<LogCol> = TrendMeasure.of(source).flatMap { listOfNotNull(it.col, it.high) }.toSet()

  private fun startOf(rows: List<LogRow>, range: TrendRange, nowMs: Long): Long =
    range.ms?.let { nowMs - it } ?: rows.firstOrNull()?.atMs?.let { minOf(it, nowMs - MIN_SPAN_MS) } ?: (nowMs - MIN_SPAN_MS)

  fun chart(rows: List<LogRow>, range: TrendRange, left: TrendMeasure, right: TrendMeasure, temp: TempUnit, dist: DistUnit, nowMs: Long): TrendChart {
    val start = startOf(rows, range, nowMs)
    val shown = rows.filter { it.atMs in start..nowMs }
    return TrendChart(start, nowMs, series(shown, left, temp, dist, start, nowMs), series(shown, right, temp, dist, start, nowMs), timeTicks(start, nowMs))
  }

  fun series(rows: List<LogRow>, m: TrendMeasure, temp: TempUnit, dist: DistUnit, startMs: Long, endMs: Long): TrendSeries {
    val raw = rows.mapNotNull { r -> pointOf(r, m, temp, dist) }
    val bucketMs = max(1L, (endMs - startMs) / MAX_POINTS)
    val points = if (raw.size > MAX_POINTS) bucketed(raw, bucketMs) else raw
    val gapMs = max(GAP_MS, if (raw.size > MAX_POINTS) bucketMs * 3 else 0L)
    val segments = ArrayList<MutableList<TrendPoint>>()
    for (p in points) {
      val cur = segments.lastOrNull()
      if (cur == null || p.atMs - cur.last().atMs > gapMs) segments += mutableListOf(p) else cur += p
    }
    val stats = if (raw.isEmpty()) null else TrendStats(raw.minOf { it.low }, raw.maxOf { it.high }, raw.last().mean - raw.first().mean, raw.size)
    return TrendSeries(m, m.unitIn(temp, dist), m.high != null, segments, axis(points, m), stats)
  }

  private fun pointOf(r: LogRow, m: TrendMeasure, temp: TempUnit, dist: DistUnit): TrendPoint? {
    val lowRaw = r[m.col] ?: return null
    val highRaw = if (m.high != null) r[m.high] ?: return null else lowRaw
    val low = m.convert(lowRaw, temp, dist)
    val high = m.convert(highRaw, temp, dist)
    return TrendPoint(r.atMs, (low + high) / 2.0, low, high)
  }

  /** Each bucket's mean for a line, and its full min and max for a band and the scale. */
  private fun bucketed(points: List<TrendPoint>, bucketMs: Long): List<TrendPoint> =
    points.groupBy { it.atMs / bucketMs }.values.map { b ->
      TrendPoint(b.sumOf { it.atMs } / b.size, b.sumOf { it.mean } / b.size, b.minOf { it.low }, b.maxOf { it.high })
    }.sortedBy { it.atMs }

  private fun axis(points: List<TrendPoint>, m: TrendMeasure): TrendAxis {
    if (points.isEmpty()) return TrendAxis(0.0, 1.0, emptyList(), m.decimals)
    val lo = points.minOf { it.low }
    val hi = points.maxOf { it.high }
    val span = max(hi - lo, m.minSpan)
    val mid = (hi + lo) / 2.0
    return niceAxis(mid - span * 0.6, mid + span * 0.6)
  }

  /** A scale on round numbers (1, 2 or 5 × 10^n), about four ticks, that contains [lo]…[hi]. */
  fun niceAxis(lo: Double, hi: Double, wanted: Int = 4): TrendAxis {
    val rough = (hi - lo) / wanted
    val mag = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * mag >= rough } * mag
    val first = floor(lo / step) * step
    val last = ceil(hi / step) * step
    val n = ((last - first) / step).toInt()
    val decimals = max(0, -floor(log10(step)).toInt())
    return TrendAxis(first, last, (0..n).map { first + it * step }, decimals)
  }

  private fun timeTicks(startMs: Long, endMs: Long, n: Int = 6): List<TimeTick> = (0..n).map { i ->
    val at = startMs + (endMs - startMs) * i / n
    TimeTick(at, ago(endMs - at))
  }

  fun ago(ms: Long): String {
    val hours = ms / 3_600_000.0
    return when {
      ms < 60_000 -> "now"
      hours < 1 -> "${ms / 60_000} min ago"
      hours < 48 -> "${Math.round(hours)} h ago"
      else -> "${Math.round(hours / 24)} d ago"
    }
  }

  /** The point of [s] nearest [atMs], within [withinMs]; for the tap readout. */
  fun nearest(s: TrendSeries, atMs: Long, withinMs: Long): TrendPoint? =
    s.segments.asSequence().flatten().minByOrNull { abs(it.atMs - atMs) }?.takeIf { abs(it.atMs - atMs) <= withinMs }

  fun valueText(s: TrendSeries, p: TrendPoint): String {
    val d = s.measure.decimals
    val v = if (s.band) "${Units.num(p.low, d)}–${Units.num(p.high, d)}" else Units.num(p.mean, d)
    return "${s.measure.shortLabel} $v ${s.unit}".trimEnd()
  }

  /** "412 rows · last row 12 min ago" under the header. */
  fun summary(rowCount: Long, lastAtMs: Long?, nowMs: Long): String = when {
    rowCount == 0L || lastAtMs == null -> "Nothing logged yet"
    else -> "${rowsText(rowCount)} · newest ${ago(max(0L, nowMs - lastAtMs))}"
  }

  private fun rowsText(n: Long): String = if (n == 1L) "1 row" else "$n rows"

  /** What the header says after [summary]: whether more rows are coming, and if not, why. */
  fun sourceText(source: LogSource, mx: LinkStatus): String = when (source) {
    LogSource.CAR -> "no adapter needed"
    LogSource.MX -> when (mx) {
      LinkStatus.CONNECTED -> "reading now"
      LinkStatus.CONNECTING -> "connecting"
      LinkStatus.PAIRING -> "pairing"
      LinkStatus.DISCONNECTED -> "MX+ disconnected"
      LinkStatus.NO_ADAPTER -> "no MX+ chosen"
      LinkStatus.ADAPTER_UNREACHABLE -> "MX+ not reachable"
      LinkStatus.TRUCK_ASLEEP -> "truck asleep"
      LinkStatus.STOPPED_ERROR -> "link stopped"
      LinkStatus.DEMO -> "demo"
    }
  }

  /** The newest value of each column of [rows], oldest first: what the store's query gives for real rows. */
  fun latest(rows: List<LogRow>): Map<LogCol, LatestValue> {
    val out = LinkedHashMap<LogCol, LatestValue>()
    for (r in rows) for ((c, v) in r.values) out[c] = LatestValue(v, r.atMs)
    return out
  }

  fun page(d: TrendData, range: TrendRange, temp: TempUnit, dist: DistUnit): TrendPage =
    TrendPage(d.copy(rows = emptyList()), chart(d.rows, range, d.pick.left, d.pick.right, temp, dist, d.nowMs), tiles(d, range, temp, dist))

  fun tiles(d: TrendData, range: TrendRange, temp: TempUnit, dist: DistUnit): List<TrendTile> {
    val start = startOf(d.rows, range, d.nowMs)
    val shown = d.rows.filter { it.atMs in start..d.nowMs }
    return TILES.getValue(d.source).map { m ->
      val value = valueText(m, d.latest, temp, dist)
      val unit = m.unitIn(temp, dist)
      val line = when {
        value != null -> m.pid
        m.unidentified -> "PID not identified yet"
        else -> {
          val rejected = m.asRead?.let { valueText(it, d.latest, temp, dist) }
          if (rejected != null) "${m.pid} failed its check: $rejected $unit as read" else "${m.pid}: ${missingText(m, d.problems).replaceFirstChar { it.lowercase() }}"
        }
      }
      val change = if (value == null) null else changeText(series(shown, m, temp, dist, start, d.nowMs), range)
      // The fixed figure says so in words: its title is long enough without a marker beside it.
      val fixed = m.badge == TrendBadge.FIXED
      TrendTile(m, m.label.removeSuffix(", checked"), m.badge.takeUnless { fixed }, value, unit, line, if (fixed) change?.let { "$it, this figure is fixed" } else change)
    }
  }

  /** "−0.38 kWh in 30 d", "no change", or null with too few rows to tell. */
  fun changeText(s: TrendSeries, range: TrendRange): String? {
    val stats = s.stats?.takeIf { it.rows >= 2 } ?: return null
    val change = Units.signed(stats.change, s.measure.decimals)
    if (change.none { it in '1'..'9' }) return "no change"
    val over = if (range == TrendRange.ALL) "overall" else "in ${range.label}"
    return "$change ${s.unit} $over".replace("  ", " ")
  }

  /** A chart's key: its measure, or with [point] the value tapped. */
  fun legendText(s: TrendSeries, point: TrendPoint?, tapped: Boolean, short: Boolean): String = when {
    point != null -> valueText(s, point)
    tapped -> "${s.measure.shortLabel}: no row here"
    else -> (if (short) s.measure.shortLabel else s.measure.label) + if (s.unit.isEmpty()) "" else " (${s.unit})"
  }

  /** How far from a tap a row still counts as what was tapped. */
  fun tapWindowMs(c: TrendChart): Long = max((c.endMs - c.startMs) / 40, GAP_MS)

  /** The line under a chart: "low 208.28 · high 208.92 · change −0.49 kWh · 170 rows". */
  fun statsText(s: TrendSeries): String {
    val stats = s.stats ?: return "no rows in this range"
    val d = s.measure.decimals
    val change = Units.signed(stats.change, d)
    return "low ${Units.num(stats.low, d)} · high ${Units.num(stats.high, d)} · change $change ${s.unit}".trimEnd() + " · ${rowsText(stats.rows.toLong())}"
  }

  /** The PID list for one group of the source, in catalog order. */
  fun pidRows(d: TrendData, group: TrendGroup, temp: TempUnit, dist: DistUnit): List<PidRow> =
    TrendMeasure.of(d.source).filter { it.group == group }.map { m ->
      val value = valueText(m, d.latest, temp, dist)
      val at = d.latest[m.col]
      val extra = listOfNotNull(
        at?.let { ago(max(0L, d.nowMs - it.atMs)) },
        at?.takeIf { m.cellGroup }?.let { "module ${moduleOfGroup(it.v)}" },
        m.note.ifEmpty { null },
      )
      val detail = if (value == null && m.unidentified) "DID not identified" else (listOf(m.pid) + extra).joinToString(" · ")
      val unit = if (m.hex && at != null) "0x" + at.v.toLong().toString(16).uppercase() else m.unitIn(temp, dist)
      PidRow(m, detail, m.badge ?: TrendBadge.NO_PID.takeIf { value == null && m.unidentified }, value, unit, if (value == null) missingText(m, d.problems) else null)
    }

  /** A measure's newest value as the list shows it; null when it has none. */
  fun valueText(m: TrendMeasure, latest: Map<LogCol, LatestValue>, temp: TempUnit, dist: DistUnit): String? {
    val low = latest[m.col]?.v ?: return null
    m.texts?.let { names -> return names[low.toInt()]?.let(::stateText) ?: low.toInt().toString() }
    fun text(x: Double): String = m.convert(x, temp, dist).let { if (m.signed) Units.signed(it, m.decimals) else Units.num(it, m.decimals) }
    val highCol = m.high ?: return text(low)
    val high = latest[highCol]?.v ?: return null
    return "${text(low)}–${text(high)}"
  }

  /** CarEnums' names read as words: "NOT_CHARGING" to "Not charging". */
  private fun stateText(name: String): String =
    if (name.any { it.isLowerCase() }) name else name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

  /** Eight cell groups a module, numbered from 1: checked against the array in session 1. */
  private fun moduleOfGroup(group: Double): Int = (group.toInt() - 1) / GROUPS_PER_MODULE + 1

  private const val GROUPS_PER_MODULE = 8

  fun missingText(m: TrendMeasure, problems: Map<Int, ReadProblem>): String = when (m.property?.let { problems[it] }) {
    ReadProblem.NOT_SUPPORTED -> "Not offered by this truck"
    ReadProblem.NO_PERMISSION -> "Permission not granted"
    ReadProblem.UNAVAILABLE -> "Unavailable right now"
    ReadProblem.NOT_CONNECTED, ReadProblem.ERROR -> "Could not be read"
    null -> "No reading logged"
  }
}

/** Simulated log for demo mode, so Trends can be judged without a truck. Never stored or exported. */
object DemoLog {
  /** The truck offers neither of these. */
  val PROBLEMS: Map<Int, ReadProblem> = mapOf(
    KnownProperties.EV_CURRENT_BATTERY_CAPACITY to ReadProblem.NOT_SUPPORTED,
    KnownProperties.EV_BATTERY_AVERAGE_TEMPERATURE to ReadProblem.NOT_SUPPORTED,
  )

  fun rows(nowMs: Long, days: Int = 60): List<LogRow> {
    val rnd = java.util.Random(7)
    val out = ArrayList<LogRow>()
    var soc = 78.0
    val hour = 3_600_000L
    val start = nowMs - days * 24 * hour
    for (d in 0 until days) {
      val mxDay = rnd.nextDouble() < 0.45
      for (h in listOf(7, 8, 9, 17, 18, 19)) {
        if (rnd.nextDouble() < 0.12) continue
        soc -= 3 + rnd.nextDouble() * 3
        val out_c = 10 + 7 * Math.sin(d / 5.0) + 4 * Math.sin((h - 9) / 24.0 * 6.283) + rnd.nextDouble()
        val at = start + d * 24 * hour + h * hour
        val energy = soc * 2.035
        val rangeKm = soc * 6.4 + rnd.nextDouble() * 4
        out += LogRow(
          at,
          0,
          LogSource.CAR,
          LogTrigger.JOB,
          mapOf(
            LogCol.OUTSIDE_C to out_c,
            LogCol.CAR_SOC_PCT to soc,
            LogCol.CAR_ENERGY_KWH to energy,
            LogCol.CAR_CAPACITY_KWH to 205.0,
            LogCol.CAR_CAPACITY_NOMINAL to 1.0,
            LogCol.CAR_NOMINAL_KWH to 205.0,
            LogCol.CAR_RANGE_KM to rangeKm,
            LogCol.CAR_PLUGGED to 0.0,
            LogCol.CAR_PORT_OPEN to 0.0,
            LogCol.CAR_CHARGE_KW to 0.0,
            LogCol.CAR_CHARGE_STATE to 3.0,
            LogCol.CAR_CHARGE_LIMIT_PCT to 80.0,
            LogCol.CAR_DRAW_LIMIT_A to 48.0,
            LogCol.CAR_REGEN_STATE to 3.0,
            LogCol.CAR_IGNITION to 4.0,
            LogCol.CAR_GEAR to 4.0,
            LogCol.CAR_SPEED_KMH to 0.0,
          ),
        )
        if (mxDay && h > 12) {
          val capacity = 203.8 - 0.02 * d + (rnd.nextDouble() - 0.5) * 0.5
          val tempLow = out_c + 5 + rnd.nextDouble()
          val cell = 3.42 + soc * 0.0072
          val low = cell - 0.004 - rnd.nextDouble() * 0.003
          val high = cell + 0.004 + rnd.nextDouble() * 0.003
          val kw = rnd.nextDouble() * 30
          val volts = cell * 96
          val ah = capacity * 1000 / (96 * 3.6)
          out += LogRow(
            at + 60_000,
            0,
            LogSource.MX,
            LogTrigger.MX_VISIT,
            mapOf(
              LogCol.SOC_PCT to soc,
              LogCol.BMS_SOC_PCT to soc + 1.2,
              LogCol.BMS_SOC_2_PCT to soc + 0.8,
              LogCol.ENERGY_KWH to energy,
              LogCol.BMS_RANGE_KM to rangeKm + 3,
              LogCol.SINCE_FULL_KM to (80 - soc) * 6.0,
              LogCol.IMPLIED_KWH to capacity,
              LogCol.CAPACITY_KWH to capacity,
              LogCol.BECM_27AE_KWH to capacity,
              LogCol.NOMINAL_AH to ah,
              LogCol.CAPACITY_AH to ah,
              LogCol.BECM_4676_AH to ah,
              LogCol.MODULE_TEMP_MIN_C to tempLow,
              LogCol.MODULE_TEMP_MAX_C to tempLow + 2 + rnd.nextDouble(),
              LogCol.COOLANT_1_C to tempLow - 1,
              LogCol.COOLANT_2_C to tempLow + 1,
              LogCol.PACK_TEMP_C to tempLow,
              LogCol.BATTERY_TEMP_A_C to tempLow + 11,
              LogCol.BATTERY_TEMP_B_C to tempLow + 10,
              LogCol.HV_BATTERY_TEMP_C to 60.0,
              LogCol.CELL_MIN_V to low,
              LogCol.CELL_MAX_V to high,
              LogCol.CELL_SPREAD_MV to (high - low) * 1000,
              LogCol.CELL_AVG_A_V to cell - 0.001,
              LogCol.CELL_AVG_B_V to cell + 0.001,
              LogCol.CELL_MIN_GROUP to 41.0,
              LogCol.CELL_MAX_GROUP to 81.0,
              LogCol.PACK_V to volts,
              LogCol.PACK_A to kw * 1000 / volts,
              LogCol.PACK_KW to kw,
              LogCol.PACK_V_DU2 to volts - 0.1,
              LogCol.AUX_12V_V to 12.5,
              LogCol.AUX_12V_DU_V to 12.5,
              LogCol.CHARGE_STATE_CODE to 0.0,
              LogCol.EVSE_PILOT_A to 0.0,
              LogCol.RAW_27B9 to 0x1566.toDouble(),
              LogCol.RAW_2984 to 0x2041.toDouble(),
              LogCol.RAW_270D to 0x0FA0.toDouble(),
              LogCol.RAW_28AF to 0x2710.toDouble(),
              LogCol.RAW_2770 to 0x32.toDouble(),
            ),
          )
        }
      }
      if (soc < 38) soc = 80.0
    }
    return out
  }
}
