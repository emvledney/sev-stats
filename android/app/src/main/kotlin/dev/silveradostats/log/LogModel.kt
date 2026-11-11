package dev.silveradostats.log

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.ui.live.DashboardModel
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.ChargeReading
import dev.silveradostats.vehicle.ChargeState
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Pure Kotlin (no android.* imports): what one row of the long-term log holds, how it is built from
 * the truck's built-in data or from the MX+, and the CSV it exports to. Unit tested on the JVM.
 *
 * A value that is unknown or stale is left out of its row, never carried over: an empty cell is a
 * fact about that moment. Demo data never reaches the log.
 */

/** Where a row's readings came from. */
enum class LogSource(val code: String, val label: String) {
  /** The truck's built-in data (no adapter needed). */
  CAR("car", "Truck API"),

  /** The OBDLink MX+. */
  MX("mx", "MX+"),
  ;

  companion object {
    fun of(code: String?): LogSource = entries.firstOrNull { it.code == code } ?: CAR
  }
}

/** What caused a row: this is how the real logging cadence on the truck gets measured. */
enum class LogTrigger(val code: String) {
  /** The hourly background job. */
  JOB("job"),

  /** The app was opened and the last row was over an hour old. */
  APP_OPEN("app_open"),

  /** A screen of the app was up: a built-in row every few minutes, next to the MX+'s one a minute. */
  CAR_SCREEN("car_screen"),

  /** The MX+ was connected anyway, for a screen. */
  MX_SCREEN("mx_screen"),

  /** The hourly visit: connected for one row, truck on. */
  MX_VISIT("mx_visit"),
  ;

  companion object {
    fun of(code: String?): LogTrigger = entries.firstOrNull { it.code == code } ?: JOB
  }
}

/** One numeric column. [key] is both the database column and the CSV header: it says its unit. */
enum class LogCol(val key: String) {
  // Built-in data.
  OUTSIDE_C("outside_c"),
  CAR_SOC_PCT("car_soc_pct"),
  CAR_ENERGY_KWH("car_energy_kwh"),
  CAR_CAPACITY_KWH("car_capacity_kwh"),

  /** 1 = [CAR_CAPACITY_KWH] is the nominal capacity when new (flat by nature), 0 = usable now. */
  CAR_CAPACITY_NOMINAL("car_capacity_nominal"),
  CAR_RANGE_KM("car_range_km"),
  CAR_PLUGGED("car_plugged"),

  // MX+.
  SOC_PCT("soc_pct"),
  ENERGY_KWH("energy_kwh"),

  /** 27AE, only while it fits energy / SoC (Derive.capacity_kwh). Meaning not yet verified on this truck. */
  CAPACITY_KWH("capacity_kwh"),

  /** 27AE as read, checked or not: history stays separable if the check or the DID turns out wrong. */
  BECM_27AE_KWH("becm_27ae_kwh"),

  /** Calculated: energy / SoC. */
  IMPLIED_KWH("implied_kwh"),

  /** 4676, only while it fits the pack's voltage (Derive.capacity_ah). A best guess at the DID. */
  CAPACITY_AH("capacity_ah"),

  /** 4676 as read, checked or not. */
  BECM_4676_AH("becm_4676_ah"),

  /** Calculated from [IMPLIED_KWH] at a nominal cell voltage: not an independent measurement. */
  NOMINAL_AH("nominal_ah"),

  /** Car Scanner's "battery total energy storage capacity deviation" (22 Ah on this truck). DID not identified yet. */
  AH_DEVIATION("ah_deviation"),
  MODULE_TEMP_MIN_C("module_temp_min_c"),
  MODULE_TEMP_MAX_C("module_temp_max_c"),
  CELL_MIN_V("cell_min_v"),
  CELL_MAX_V("cell_max_v"),

  /** Pack power when the row was taken, + discharge: says whether the cell voltages were under load. */
  PACK_KW("pack_kw"),

  // Built-in data, each property in its own column. Rows from before these columns have no value in them.

  /** EV_CURRENT_BATTERY_CAPACITY. Not offered on this truck so far; fills in by itself if an update adds it. */
  CAR_USABLE_KWH("car_usable_kwh"),

  /** INFO_EV_BATTERY_CAPACITY: a fixed figure for the pack, so a flat line, not a measure of its health. */
  CAR_NOMINAL_KWH("car_nominal_kwh"),

  /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, + charging per AOSP; GM's sign is unverified. */
  CAR_CHARGE_KW("car_charge_kw"),
  CAR_BATTERY_TEMP_C("car_battery_temp_c"),

  /** CarEnums.CHARGE_STATE's number. */
  CAR_CHARGE_STATE("car_charge_state"),
  CAR_PORT_OPEN("car_port_open"),
  CAR_CHARGE_LIMIT_PCT("car_charge_limit_pct"),
  CAR_DRAW_LIMIT_A("car_draw_limit_a"),
  CAR_CHARGE_MINUTES("car_charge_minutes"),

  /** CarEnums.REGEN_STATE's number. */
  CAR_REGEN_STATE("car_regen_state"),

  /** CarEnums.IGNITION's number. */
  CAR_IGNITION("car_ignition"),

  /** CarEnums.GEAR's number. */
  CAR_GEAR("car_gear"),
  CAR_SPEED_KMH("car_speed_kmh"),

  // MX+, every single-value PID of the profile. Rows from before these columns have no value in them.
  BMS_SOC_PCT("bms_soc_pct"),
  BMS_RANGE_KM("bms_range_km"),
  SINCE_FULL_KM("since_full_km"),
  CELL_SPREAD_MV("cell_spread_mv"),
  CELL_AVG_A_V("cell_avg_a_v"),
  CELL_AVG_B_V("cell_avg_b_v"),

  /** Where the lowest and highest groups of the 192 are: a weak group shows as a number that stays put. */
  CELL_MIN_GROUP("cell_min_group"),
  CELL_MAX_GROUP("cell_max_group"),
  COOLANT_1_C("coolant_1_c"),
  COOLANT_2_C("coolant_2_c"),

  /** 0046, unverified. */
  PACK_TEMP_C("pack_temp_c"),

  /** 4127 and 4124, unverified: their meaning is unclear. */
  BATTERY_TEMP_A_C("battery_temp_a_c"),
  BATTERY_TEMP_B_C("battery_temp_b_c"),

  /** 434F: reads wrong on this truck (60 C). Kept so that a corrected decoding can use the history. */
  HV_BATTERY_TEMP_C("hv_battery_temp_c"),
  PACK_V("pack_v"),
  PACK_A("pack_a"),
  PACK_V_DU2("pack_v_du2"),
  AUX_12V_V("aux_12v_v"),
  AUX_12V_DU_V("aux_12v_du_v"),

  /** 5401: 0 is idle, the other codes are not decoded yet. */
  CHARGE_STATE_CODE("charge_state_code"),
  EVSE_PILOT_A("evse_pilot_a"),

  // Short BECM values of unknown meaning, as plain numbers: compared with Car Scanner they can identify a PID.
  RAW_27B9("becm_27b9_raw"),
  RAW_2984("becm_2984_raw"),
  RAW_270D("becm_270d_raw"),
  RAW_28AF("becm_28af_raw"),
  RAW_2770("becm_2770_raw"),
}

/**
 * [atMs]: wall clock. [tzMinutes]: the local offset then, so a row keeps the time of day it was taken.
 */
data class LogRow(
  val atMs: Long,
  val tzMinutes: Int,
  val source: LogSource,
  val trigger: LogTrigger,
  val values: Map<LogCol, Double>,
) {
  operator fun get(c: LogCol): Double? = values[c]
}

/** Where rows are kept: SQLite in the app, a list in the tests. */
interface LogStore {
  fun insert(row: LogRow)

  /** Time of the newest row of [source], else null. */
  fun lastAt(source: LogSource): Long?

  /** How many rows [source] has logged. */
  fun count(source: LogSource): Long

  /** Rows of [source] with [fromMs] <= time, oldest first, holding only [cols]. */
  fun rows(fromMs: Long, source: LogSource, cols: Collection<LogCol>): List<LogRow>

  /** The newest value each of [cols] has in the rows of [source]: a column may be empty in the newest rows. */
  fun latest(source: LogSource, cols: Collection<LogCol>): Map<LogCol, LatestValue>

  /** The whole log, oldest first, one row at a time: it may not fit in memory all at once. */
  fun <T> useRows(block: (Sequence<LogRow>) -> T): T
}

/** One read of the built-in data, in the units the car service reports. Null = no value. */
data class CarReading(
  val levelWh: Double? = null,
  val usableWh: Double? = null,
  val nominalWh: Double? = null,
  val outsideC: Double? = null,
  val rangeM: Double? = null,
  val plugged: Boolean? = null,
  val chargeRateMw: Double? = null,
  val batteryTempC: Double? = null,
  val chargeState: Double? = null,
  val portOpen: Boolean? = null,
  val chargeLimitPct: Double? = null,
  val drawLimitA: Double? = null,
  val chargeTimeS: Double? = null,
  val regenState: Double? = null,
  val ignition: Double? = null,
  val gear: Double? = null,
  val speedMs: Double? = null,
)

object LogRows {
  /** A wall clock before this has not been set yet (a head unit just after boot): no row. */
  const val MIN_VALID_WALL_MS = 1_704_067_200_000L // 2024-01-01

  /** One MX+ row a minute while the link is up: the slowest value that matters is read every 30 s. */
  const val MX_MIN_INTERVAL_MS = 60_000L

  /** The profile field DashboardModel also reads. It exists once a truck scan finds the DID; until then the column stays empty. */
  const val AH_DEVIATION_FIELD = "capacity_deviation"

  /** Profile fields logged exactly as read. */
  val MX_FIELDS: Map<String, LogCol> = mapOf(
    "bms_soc_layer" to LogCol.BMS_SOC_PCT,
    "cell_avg_a" to LogCol.CELL_AVG_A_V,
    "cell_avg_b" to LogCol.CELL_AVG_B_V,
    "coolant_temp_1" to LogCol.COOLANT_1_C,
    "coolant_temp_2" to LogCol.COOLANT_2_C,
    "pack_temp_candidate" to LogCol.PACK_TEMP_C,
    "battery_temp_a" to LogCol.BATTERY_TEMP_A_C,
    "battery_temp_b" to LogCol.BATTERY_TEMP_B_C,
    "hv_battery_temp" to LogCol.HV_BATTERY_TEMP_C,
    "pack_voltage" to LogCol.PACK_V,
    "pack_current" to LogCol.PACK_A,
    "pack_voltage_du2" to LogCol.PACK_V_DU2,
    "aux_12v" to LogCol.AUX_12V_V,
    "aux_12v_dmcm" to LogCol.AUX_12V_DU_V,
    "charge_state_code" to LogCol.CHARGE_STATE_CODE,
    "evse_pilot_current" to LogCol.EVSE_PILOT_A,
  )

  /** Profile fields read as miles, logged as km like the built-in range. */
  val MX_MILE_FIELDS: Map<String, LogCol> = mapOf(
    "range_estimate" to LogCol.BMS_RANGE_KM,
    "distance_since_full" to LogCol.SINCE_FULL_KM,
  )

  /** Profile fields read as hex bytes, logged as the number they spell. */
  val MX_HEX_FIELDS: Map<String, LogCol> = mapOf(
    "unknown_27b9_raw" to LogCol.RAW_27B9,
    "unknown_2984_raw" to LogCol.RAW_2984,
    "unknown_270d_raw" to LogCol.RAW_270D,
    "unknown_28af_raw" to LogCol.RAW_28AF,
    "unknown_2770_raw" to LogCol.RAW_2770,
  )

  /** A hex field longer than this is a record, not one number. */
  private const val MAX_HEX_DIGITS = 8

  fun wallClockValid(wallMs: Long): Boolean = wallMs >= MIN_VALID_WALL_MS

  /** What [chargeReading] needs of a built-in row. */
  val CHARGE_COLS: List<LogCol> = listOf(LogCol.CAR_ENERGY_KWH, LogCol.CAR_CAPACITY_KWH, LogCol.CAR_PLUGGED, LogCol.CAR_CHARGE_STATE, LogCol.CAR_SPEED_KMH)

  /** A built-in row as the last charge reads it: the log can say how far back the last charge was, not what was used with the truck on. */
  fun chargeReading(row: LogRow): ChargeReading = ChargeReading.fromCar(
    levelWh = row[LogCol.CAR_ENERGY_KWH]?.times(1000.0),
    capacityWh = row[LogCol.CAR_CAPACITY_KWH]?.times(1000.0),
    plugged = row[LogCol.CAR_PLUGGED]?.let { it != 0.0 },
    chargeState = ChargeState.fromAosp(row[LogCol.CAR_CHARGE_STATE]?.toInt()),
    speedMps = row[LogCol.CAR_SPEED_KMH]?.div(3.6),
  ).copy(atMs = row.atMs, awake = false) // Each row a moment of its own: no usage is counted across them.

  /** A row of the built-in data; null when the truck gave nothing to log. */
  fun fromCar(r: CarReading, atMs: Long, tzMinutes: Int, trigger: LogTrigger): LogRow? {
    val v = LinkedHashMap<LogCol, Double>()
    fun put(c: LogCol, x: Double?) {
      if (x != null && x.isFinite()) v[c] = x
    }
    val usable = r.usableWh?.takeIf { it > 0 }
    val nominal = r.nominalWh?.takeIf { it > 0 }
    val capacity = usable ?: nominal
    val level = r.levelWh?.takeIf { it >= 0 }
    put(LogCol.OUTSIDE_C, r.outsideC)
    put(LogCol.CAR_ENERGY_KWH, level?.div(1000.0))
    put(LogCol.CAR_CAPACITY_KWH, capacity?.div(1000.0))
    if (capacity != null) v[LogCol.CAR_CAPACITY_NOMINAL] = if (usable != null) 0.0 else 1.0
    if (level != null && capacity != null) put(LogCol.CAR_SOC_PCT, level / capacity * 100.0)
    put(LogCol.CAR_RANGE_KM, r.rangeM?.takeIf { it >= 0 }?.div(1000.0))
    r.plugged?.let { v[LogCol.CAR_PLUGGED] = if (it) 1.0 else 0.0 }
    put(LogCol.CAR_USABLE_KWH, usable?.div(1000.0))
    put(LogCol.CAR_NOMINAL_KWH, nominal?.div(1000.0))
    put(LogCol.CAR_CHARGE_KW, r.chargeRateMw?.div(1_000_000.0))
    put(LogCol.CAR_BATTERY_TEMP_C, r.batteryTempC)
    put(LogCol.CAR_CHARGE_STATE, r.chargeState)
    r.portOpen?.let { v[LogCol.CAR_PORT_OPEN] = if (it) 1.0 else 0.0 }
    put(LogCol.CAR_CHARGE_LIMIT_PCT, r.chargeLimitPct)
    put(LogCol.CAR_DRAW_LIMIT_A, r.drawLimitA)
    put(LogCol.CAR_CHARGE_MINUTES, r.chargeTimeS?.takeIf { it >= 0 }?.div(60.0))
    put(LogCol.CAR_REGEN_STATE, r.regenState)
    put(LogCol.CAR_IGNITION, r.ignition)
    put(LogCol.CAR_GEAR, r.gear)
    put(LogCol.CAR_SPEED_KMH, r.speedMs?.times(3.6))
    if (v.isEmpty()) return null
    return LogRow(atMs, tzMinutes, LogSource.CAR, trigger, v)
  }

  /**
   * A row of what the MX+ read, or null when it is not time for one: only a real, connected MX+ with
   * a fresh SoC and energy reading. [monoMs] is on the clock of the state's timestamps.
   */
  fun fromLive(s: LiveState, monoMs: Long, atMs: Long, tzMinutes: Int, trigger: LogTrigger): LogRow? {
    if (s.demo || s.status != LinkStatus.CONNECTED) return null
    fun fresh(name: String): LiveValue? = s.values[name]?.takeIf { monoMs - it.atMs <= DashboardModel.staleAfterMs(it.periodS) }
    fun num(name: String): Double? = (fresh(name)?.v as? Number)?.toDouble()?.takeIf { it.isFinite() }
    fun derived(name: String): Double? = (s.derived[name] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    val soc = num("soc") ?: return null
    val energy = num("energy_remaining") ?: return null

    val v = LinkedHashMap<LogCol, Double>()
    fun put(c: LogCol, x: Double?) {
      if (x != null) v[c] = x
    }
    put(LogCol.SOC_PCT, soc)
    put(LogCol.ENERGY_KWH, energy)
    put(LogCol.CAPACITY_KWH, derived("capacity_kwh").takeIf { fresh("battery_capability") != null })
    put(LogCol.BECM_27AE_KWH, num("battery_capability"))
    put(LogCol.IMPLIED_KWH, derived("bms_energy_at_100_kwh"))
    put(LogCol.CAPACITY_AH, derived("capacity_ah").takeIf { fresh("pack_capacity_ah") != null })
    put(LogCol.BECM_4676_AH, num("pack_capacity_ah"))
    put(LogCol.NOMINAL_AH, derived("ah_nominal_est"))
    put(LogCol.AH_DEVIATION, num(AH_DEVIATION_FIELD))
    val temps = DashboardModel.moduleTemps(s.values, monoMs)?.takeIf { !it.stale }?.celsius?.filterNotNull().orEmpty()
    if (temps.isNotEmpty()) {
      v[LogCol.MODULE_TEMP_MIN_C] = temps.min()
      v[LogCol.MODULE_TEMP_MAX_C] = temps.max()
    }
    if (listOf("a", "b").any { fresh("cell_min_$it") != null }) {
      put(LogCol.CELL_MIN_V, derived("cell_min_v"))
      put(LogCol.CELL_MAX_V, derived("cell_max_v"))
    }
    put(LogCol.PACK_KW, derived("power_kw").takeIf { fresh("pack_current") != null })
    for ((field, col) in MX_FIELDS) put(col, num(field))
    for ((field, col) in MX_MILE_FIELDS) put(col, num(field)?.times(Units.KM_PER_MILE))
    for ((field, col) in MX_HEX_FIELDS) {
      val hex = (fresh(field)?.v as? String)?.filterNot { it == ' ' }?.takeIf { it.length <= MAX_HEX_DIGITS }
      put(col, hex?.toLongOrNull(16)?.toDouble())
    }
    if (listOf("a", "b").any { fresh("cell_min_$it") != null }) put(LogCol.CELL_SPREAD_MV, derived("cell_spread_mv"))
    if (fresh("cells_001_012") != null) {
      put(LogCol.CELL_MIN_GROUP, derived("array_min_group"))
      put(LogCol.CELL_MAX_GROUP, derived("array_max_group"))
    }
    return LogRow(atMs, tzMinutes, LogSource.MX, trigger, v)
  }
}

/** Decides when the MX+ link is worth a row, and when the hourly visit may connect. Pure. */
object LogPolicy {
  /** The hourly job's period. Android may run it later; it never runs while the head unit is off. */
  const val JOB_PERIOD_MS = 60 * 60_000L

  /** Opening the app adds a row when the last one is older than this. */
  const val APP_OPEN_AFTER_MS = 60 * 60_000L

  /** While a screen is up a built-in row is taken this often. It reads the car service only: no bus traffic. */
  const val CAR_SCREEN_ROW_MS = 5 * 60_000L

  /** How often a screen checks whether that row is due. */
  const val CAR_SCREEN_CHECK_MS = 60_000L

  /** A visit gives up after this: one poll cycle reads everything long before. */
  const val VISIT_TIMEOUT_MS = 90_000L

  /** AOSP VehicleIgnitionState: ON and START are the only states a visit may connect in. */
  private const val IGNITION_ON = 4
  private const val IGNITION_START = 5

  fun mxRowDue(lastMxRowMs: Long?, nowMs: Long): Boolean =
    lastMxRowMs == null || nowMs - lastMxRowMs >= LogRows.MX_MIN_INTERVAL_MS || nowMs < lastMxRowMs

  fun carRowDue(lastCarRowMs: Long?, nowMs: Long): Boolean =
    lastCarRowMs == null || nowMs - lastCarRowMs >= APP_OPEN_AFTER_MS || nowMs < lastCarRowMs

  fun carScreenRowDue(lastCarRowMs: Long?, nowMs: Long): Boolean =
    lastCarRowMs == null || nowMs - lastCarRowMs >= CAR_SCREEN_ROW_MS || nowMs < lastCarRowMs

  /**
   * Whether the hourly visit may talk to the MX+. Fail safe: the truck must positively report that it
   * is on, so that a visit can never keep modules awake or wake a sleeping truck, and a row from the
   * last visit must be old enough. Unknown ignition = no visit.
   */
  fun visitAllowed(ignition: Int?, demo: Boolean, adapterChosen: Boolean, mxOff: Boolean, lastMxRowMs: Long?, nowMs: Long): Boolean {
    if (demo || !adapterChosen || mxOff) return false
    if (ignition != IGNITION_ON && ignition != IGNITION_START) return false
    return lastMxRowMs == null || nowMs - lastMxRowMs >= JOB_PERIOD_MS / 2 || nowMs < lastMxRowMs
  }
}

/** The export format: one header, one line per row, oldest first. Times are UTC plus the local offset. */
object LogCsv {
  private val ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

  val HEADER: String = (listOf("time_utc", "tz_minutes", "source", "trigger") + LogCol.entries.map { it.key }).joinToString(",")

  fun line(r: LogRow): String {
    val cells = ArrayList<String>(LogCol.entries.size + 4)
    cells += ISO.format(Instant.ofEpochMilli(r.atMs))
    cells += r.tzMinutes.toString()
    cells += r.source.code
    cells += r.trigger.code
    for (c in LogCol.entries) cells += r[c]?.let(::number).orEmpty()
    return cells.joinToString(",")
  }

  /** Returns the number of rows written. */
  fun appendTo(rows: Iterable<LogRow>, out: Appendable): Int {
    out.append(HEADER).append('\n')
    var n = 0
    for (r in rows) {
      out.append(line(r)).append('\n')
      n += 1
    }
    return n
  }

  /** At most four decimals (0.1 mV), no trailing zeros, never an exponent. */
  private fun number(x: Double): String = java.math.BigDecimal(x).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
