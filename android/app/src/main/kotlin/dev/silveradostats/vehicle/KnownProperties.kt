package dev.silveradostats.vehicle

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Pure Kotlin (no android.* imports) so it can be unit tested on the JVM.
 *
 * Property IDs are integer literals on purpose: GM strips the VehiclePropertyIds
 * field-name constants at runtime, and compile-time constants are inlined anyway.
 */

/** Read permissions, as plain strings (the app checks them with checkSelfPermission). */
object CarPerm {
  private const val P = "android.car.permission."
  const val PREFIX = P
  const val CAR_INFO = P + "CAR_INFO"
  const val CAR_ENERGY = P + "CAR_ENERGY"
  const val CAR_ENERGY_PORTS = P + "CAR_ENERGY_PORTS"
  const val CAR_EXTERIOR_ENVIRONMENT = P + "CAR_EXTERIOR_ENVIRONMENT"
  const val CAR_POWERTRAIN = P + "CAR_POWERTRAIN"
  const val CAR_SPEED = P + "CAR_SPEED"
  const val READ_CAR_DISPLAY_UNITS = P + "READ_CAR_DISPLAY_UNITS"
  // Not declared by the app (signature|privileged, or Android 16+ only). Listed so the
  // diagnostics table can say why a property is unreadable.
  const val CAR_MILEAGE = P + "CAR_MILEAGE"
  const val CAR_MILEAGE_3P = P + "CAR_MILEAGE_3P"
  const val CAR_TIRES = P + "CAR_TIRES"
  const val CAR_TIRES_3P = P + "CAR_TIRES_3P"
  const val CONTROL_CAR_CLIMATE = P + "CONTROL_CAR_CLIMATE"
  const val CAR_VENDOR_EXTENSION = P + "CAR_VENDOR_EXTENSION"

  fun short(permission: String): String = permission.substringAfterLast('.')
}

/** Bit layout of a VHAL property id: group | area type | value type | index. */
object PropertyIdBits {
  private const val GROUP_MASK = 0xF0000000.toInt()
  private const val AREA_MASK = 0x0F000000
  private const val TYPE_MASK = 0x00FF0000

  fun hex(id: Int): String = String.format(Locale.US, "0x%08X", id)

  fun group(id: Int): String = when (id and GROUP_MASK) {
    0x10000000 -> "system"
    0x20000000 -> "vendor"
    0x30000000 -> "backported"
    else -> String.format(Locale.US, "group 0x%X", (id ushr 28) and 0xF)
  }

  fun isVendor(id: Int): Boolean = (id and GROUP_MASK) == 0x20000000

  fun areaType(id: Int): String = when (id and AREA_MASK) {
    0x01000000 -> "global"
    0x03000000 -> "window"
    0x04000000 -> "mirror"
    0x05000000 -> "seat"
    0x06000000 -> "door"
    0x07000000 -> "wheel"
    0x09000000 -> "vendor-area"
    else -> String.format(Locale.US, "area 0x%X", (id ushr 24) and 0xF)
  }

  fun valueType(id: Int): String = when (id and TYPE_MASK) {
    0x00100000 -> "string"
    0x00200000 -> "boolean"
    0x00400000 -> "int32"
    0x00410000 -> "int32[]"
    0x00500000 -> "int64"
    0x00510000 -> "int64[]"
    0x00600000 -> "float"
    0x00610000 -> "float[]"
    0x00700000 -> "bytes"
    0x00E00000 -> "mixed"
    else -> String.format(Locale.US, "type 0x%X", (id ushr 16) and 0xFF)
  }
}

/** Value formatters. Every function accepts Any? and never throws for a wrong type. */
object Fmt {
  private val VIN_SHAPE = Regex("(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")

  /** Masks anything shaped like a VIN (17 chars, no I/O/Q) so it never reaches the screen or report. */
  fun maskVin(s: String): String = VIN_SHAPE.replace(s, "[VIN-shaped value masked]")

  fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is List<*> -> (v.singleOrNull() as? Number)?.toDouble()
    else -> null
  }

  private fun f(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)

  /** Generic formatting for any normalized value (lists joined, bytes as hex). */
  fun raw(v: Any?): String = when (v) {
    null -> "null"
    is Float -> trimFloat(v.toDouble())
    is Double -> trimFloat(v)
    is ByteArray -> v.joinToString(" ", prefix = "bytes[${v.size}]: ") { f("%02X", it.toInt() and 0xFF) }
    is List<*> -> v.joinToString(", ", prefix = "[", postfix = "]") { raw(it) }
    is String -> maskVin(v)
    else -> maskVin(v.toString())
  }

  private fun trimFloat(d: Double): String =
    if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else f("%.3f", d).trimEnd('0').trimEnd('.')

  fun whToKwh(v: Any?): String = num(v)?.let { f("%.2f kWh", it / 1000.0) } ?: raw(v)

  /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE: mW, + charging / - discharging (AOSP convention). */
  fun kwFromMw(v: Any?): Double? = num(v)?.let { it / 1_000_000.0 }

  fun mwToKw(v: Any?): String {
    val kw = kwFromMw(v) ?: return raw(v)
    return when {
      abs(kw) < 0.005 -> "0.00 kW (idle)"
      kw > 0 -> f("+%.2f kW (charging)", kw)
      else -> f("%.2f kW (discharging)", kw)
    }
  }

  fun meters(v: Any?): String = num(v)?.let { f("%.1f km / %.1f mi", it / 1000.0, it / 1609.344) } ?: raw(v)

  fun kilometers(v: Any?): String = num(v)?.let { f("%.1f km / %.1f mi", it, it / 1.609344) } ?: raw(v)

  fun celsius(v: Any?): String = num(v)?.let { f("%.1f °C / %.1f °F", it, it * 9.0 / 5.0 + 32.0) } ?: raw(v)

  fun percent(v: Any?): String = num(v)?.let { f("%.1f %%", it) } ?: raw(v)

  fun amps(v: Any?): String = num(v)?.let { f("%.1f A", it) } ?: raw(v)

  fun speedMs(v: Any?): String = num(v)?.let { f("%.1f km/h / %.1f mph", it * 3.6, it * 2.2369363) } ?: raw(v)

  fun kpa(v: Any?): String = num(v)?.let { f("%.0f kPa / %.1f psi", it, it * 0.14503774) } ?: raw(v)

  fun bool(v: Any?): String = (v as? Boolean)?.toString() ?: raw(v)

  fun seconds(v: Any?): String {
    val s = num(v)?.roundToLong() ?: return raw(v)
    if (s < 0) return "$s s"
    return f("%d h %02d min (%d s)", s / 3600, (s % 3600) / 60, s)
  }

  fun enumOf(names: Map<Int, String>): (Any?) -> String = { v ->
    val n = num(v)?.toInt()
    if (n == null) raw(v) else "${names[n] ?: "UNKNOWN_VALUE"} ($n)"
  }

  fun enumListOf(names: Map<Int, String>): (Any?) -> String = { v ->
    when (v) {
      is List<*> -> v.joinToString(", ") { e -> enumOf(names)(e) }
      else -> enumOf(names)(v)
    }
  }

  /** SoC % from energy remaining and a capacity (both Wh). */
  fun socPercent(levelWh: Any?, capacityWh: Any?): Double? {
    val l = num(levelWh) ?: return null
    val c = num(capacityWh) ?: return null
    return if (c > 0) l / c * 100.0 else null
  }
}

/** Value enums from android.car (values copied as plain ints). */
object CarEnums {
  val GEAR = mapOf(
    0 to "UNKNOWN", 1 to "NEUTRAL", 2 to "REVERSE", 4 to "PARK", 8 to "DRIVE",
    16 to "1ST", 32 to "2ND", 64 to "3RD", 128 to "4TH", 256 to "5TH", 512 to "6TH",
    1024 to "7TH", 2048 to "8TH", 4096 to "9TH",
  )
  val IGNITION = mapOf(0 to "UNDEFINED", 1 to "LOCK", 2 to "OFF", 3 to "ACC", 4 to "ON", 5 to "START")
  val CHARGE_STATE = mapOf(0 to "UNKNOWN", 1 to "CHARGING", 2 to "FULLY_CHARGED", 3 to "NOT_CHARGING", 4 to "ERROR")
  val REGEN_STATE = mapOf(0 to "UNKNOWN", 1 to "DISABLED", 2 to "PARTIALLY_ENABLED", 3 to "FULLY_ENABLED")
  val STOPPING_MODE = mapOf(0 to "OTHER", 1 to "CREEP", 2 to "ROLL", 3 to "HOLD")
  val FUEL_TYPE = mapOf(
    0 to "UNKNOWN", 1 to "UNLEADED", 2 to "LEADED", 3 to "DIESEL_1", 4 to "DIESEL_2", 5 to "BIODIESEL",
    6 to "E85", 7 to "LPG", 8 to "CNG", 9 to "LNG", 10 to "ELECTRIC", 11 to "HYDROGEN", 12 to "OTHER",
  )
  val CONNECTOR = mapOf(
    0 to "UNKNOWN", 1 to "J1772", 2 to "MENNEKES", 3 to "CHADEMO", 4 to "COMBO_1", 5 to "COMBO_2",
    6 to "TESLA_ROADSTER", 7 to "TESLA_HPWC", 8 to "TESLA_SUPERCHARGER", 9 to "GBT", 10 to "GBT_DC",
    11 to "SCAME", 101 to "OTHER",
  )
  val PORT_LOCATION = mapOf(
    0 to "UNKNOWN", 1 to "FRONT_LEFT", 2 to "FRONT_RIGHT", 3 to "REAR_RIGHT", 4 to "REAR_LEFT",
    5 to "FRONT", 6 to "REAR",
  )
  val UNIT = mapOf(
    0x01 to "METER_PER_SEC", 0x20 to "MILLIMETER", 0x21 to "METER", 0x23 to "KILOMETER", 0x24 to "MILE",
    0x30 to "CELSIUS", 0x31 to "FAHRENHEIT", 0x32 to "KELVIN", 0x60 to "WATT_HOUR", 0x64 to "AMPERE_HOURS",
    0x65 to "KILOWATT_HOUR", 0x70 to "KILOPASCAL", 0x71 to "PSI", 0x72 to "BAR",
    0x90 to "MILES_PER_HOUR", 0x91 to "KILOMETERS_PER_HOUR",
  )
}

/**
 * One vehicle property the app knows how to label and format.
 *
 * @param readPermissions the property is readable if the app holds any of these.
 * @param live polled every second on the Energy panel while the screen is visible.
 */
class KnownProperty(
  val id: Int,
  val name: String,
  val label: String,
  val unit: String,
  val readPermissions: List<String>,
  val live: Boolean = false,
  val note: String = "",
  private val formatter: (Any?) -> String = Fmt::raw,
) {
  val idHex: String get() = PropertyIdBits.hex(id)

  fun format(value: Any?): String = try {
    Fmt.maskVin(formatter(value))
  } catch (e: RuntimeException) {
    Fmt.raw(value)
  }
}

object KnownProperties {
  /**
   * Properties that are never read even if the platform lists them: the VIN
   * (INFO_VIN, 0x11100100) is personal data and the app has no use for it.
   */
  val EXCLUDED_IDS: Set<Int> = setOf(0x11100100)

  // IDs (spelled out for readability in the rest of the app).
  const val EV_BATTERY_LEVEL = 0x11600309
  const val INFO_EV_BATTERY_CAPACITY = 0x11600106
  const val EV_CURRENT_BATTERY_CAPACITY = 0x1160030D
  const val EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 0x1160030C
  const val EV_BATTERY_AVERAGE_TEMPERATURE = 0x1160030E
  const val EV_CHARGE_STATE = 0x11400F41
  const val EV_CHARGE_PORT_OPEN = 0x1120030A
  const val EV_CHARGE_PORT_CONNECTED = 0x1120030B
  const val EV_CHARGE_CURRENT_DRAW_LIMIT = 0x11600F3F
  const val EV_CHARGE_PERCENT_LIMIT = 0x11600F40
  const val EV_CHARGE_SWITCH = 0x11200F42
  const val EV_CHARGE_TIME_REMAINING = 0x11400F43
  const val EV_REGENERATIVE_BRAKING_STATE = 0x11400F44
  const val EV_BRAKE_REGENERATION_LEVEL = 0x1140040C
  const val EV_STOPPING_MODE = 0x1140040D
  const val EV_BATTERY_DISPLAY_UNITS = 0x11400603
  const val RANGE_REMAINING = 0x11600308
  const val ENV_OUTSIDE_TEMPERATURE = 0x11600703
  const val PERF_VEHICLE_SPEED = 0x11600207
  const val PERF_VEHICLE_SPEED_DISPLAY = 0x11600208
  const val PERF_ODOMETER = 0x11600204
  const val GEAR_SELECTION = 0x11400400
  const val CURRENT_GEAR = 0x11400401
  const val IGNITION_STATE = 0x11400409
  const val PARKING_BRAKE_ON = 0x11200402
  const val NIGHT_MODE = 0x11200407
  const val INFO_MAKE = 0x11100101
  const val INFO_MODEL = 0x11100102
  const val INFO_MODEL_YEAR = 0x11400103
  const val INFO_MODEL_TRIM = 0x1110010D
  const val INFO_FUEL_TYPE = 0x11410105
  const val INFO_EV_CONNECTOR_TYPE = 0x11410107
  const val INFO_EV_PORT_LOCATION = 0x11400109
  const val INFO_MULTI_EV_PORT_LOCATIONS = 0x1141010C
  const val INFO_EXTERIOR_DIMENSIONS = 0x1141010B
  const val DISTANCE_DISPLAY_UNITS = 0x11400600
  const val TIRE_PRESSURE_DISPLAY_UNITS = 0x11400602
  const val VEHICLE_SPEED_DISPLAY_UNITS = 0x11400604
  const val HVAC_TEMPERATURE_DISPLAY_UNITS = 0x1140050E
  const val TIRE_PRESSURE = 0x17600309
  const val HVAC_TEMPERATURE_CURRENT = 0x15600502

  private val energy = listOf(CarPerm.CAR_ENERGY)
  private val ports = listOf(CarPerm.CAR_ENERGY_PORTS)
  private val info = listOf(CarPerm.CAR_INFO)
  private val powertrain = listOf(CarPerm.CAR_POWERTRAIN)
  private val units = listOf(CarPerm.READ_CAR_DISPLAY_UNITS)

  val all: List<KnownProperty> = listOf(
    // Energy (CAR_ENERGY is a runtime permission).
    KnownProperty(EV_BATTERY_LEVEL, "EV_BATTERY_LEVEL", "Battery energy remaining", "Wh", energy, live = true, formatter = Fmt::whToKwh),
    KnownProperty(INFO_EV_BATTERY_CAPACITY, "INFO_EV_BATTERY_CAPACITY", "Nominal battery capacity", "Wh", info, live = true, formatter = Fmt::whToKwh),
    KnownProperty(
      EV_CURRENT_BATTERY_CAPACITY, "EV_CURRENT_BATTERY_CAPACITY", "Usable battery capacity now", "Wh", energy, live = true,
      note = "Android 14+ car service", formatter = Fmt::whToKwh,
    ),
    KnownProperty(
      EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "Pack power (charge rate)", "mW", energy, live = true,
      note = "+ charging / - discharging per AOSP; GM sign unverified", formatter = Fmt::mwToKw,
    ),
    KnownProperty(
      EV_BATTERY_AVERAGE_TEMPERATURE, "EV_BATTERY_AVERAGE_TEMPERATURE", "Battery average temperature", "°C", energy, live = true,
      note = "Android 15+ car service only", formatter = Fmt::celsius,
    ),
    KnownProperty(RANGE_REMAINING, "RANGE_REMAINING", "Range remaining", "m", energy, live = true, formatter = Fmt::meters),
    KnownProperty(
      EV_CHARGE_STATE, "EV_CHARGE_STATE", "Charge state", "enum", energy, live = true,
      note = "Android 13+ car service", formatter = Fmt.enumOf(CarEnums.CHARGE_STATE),
    ),
    KnownProperty(EV_CHARGE_PORT_OPEN, "EV_CHARGE_PORT_OPEN", "Charge port open", "bool", ports, live = true, formatter = Fmt::bool),
    KnownProperty(EV_CHARGE_PORT_CONNECTED, "EV_CHARGE_PORT_CONNECTED", "Charge plug connected", "bool", ports, live = true, formatter = Fmt::bool),
    KnownProperty(
      EV_CHARGE_CURRENT_DRAW_LIMIT, "EV_CHARGE_CURRENT_DRAW_LIMIT", "Charge current draw limit", "A", energy, live = true,
      note = "Android 13+ car service", formatter = Fmt::amps,
    ),
    KnownProperty(
      EV_CHARGE_PERCENT_LIMIT, "EV_CHARGE_PERCENT_LIMIT", "Charge limit", "%", energy, live = true,
      note = "Android 13+ car service", formatter = Fmt::percent,
    ),
    KnownProperty(
      EV_CHARGE_SWITCH, "EV_CHARGE_SWITCH", "Charging enabled (switch)", "bool", listOf(CarPerm.CAR_ENERGY, "android.car.permission.CONTROL_CAR_ENERGY"),
      note = "Android 13+ car service; read only here", formatter = Fmt::bool,
    ),
    KnownProperty(
      EV_CHARGE_TIME_REMAINING, "EV_CHARGE_TIME_REMAINING", "Charge time remaining", "s", energy, live = true,
      note = "Android 13+ car service", formatter = Fmt::seconds,
    ),
    KnownProperty(
      EV_REGENERATIVE_BRAKING_STATE, "EV_REGENERATIVE_BRAKING_STATE", "Regenerative braking", "enum", energy, live = true,
      note = "Android 13+ car service", formatter = Fmt.enumOf(CarEnums.REGEN_STATE),
    ),
    // Powertrain (normal permission).
    KnownProperty(EV_BRAKE_REGENERATION_LEVEL, "EV_BRAKE_REGENERATION_LEVEL", "Regen level", "level", powertrain, note = "Android 13+ car service"),
    KnownProperty(
      EV_STOPPING_MODE, "EV_STOPPING_MODE", "Stopping mode", "enum", powertrain,
      note = "Android 14+ car service", formatter = Fmt.enumOf(CarEnums.STOPPING_MODE),
    ),
    KnownProperty(GEAR_SELECTION, "GEAR_SELECTION", "Gear selected", "enum", powertrain, live = true, formatter = Fmt.enumOf(CarEnums.GEAR)),
    KnownProperty(CURRENT_GEAR, "CURRENT_GEAR", "Current gear", "enum", powertrain, formatter = Fmt.enumOf(CarEnums.GEAR)),
    KnownProperty(IGNITION_STATE, "IGNITION_STATE", "Ignition", "enum", powertrain, live = true, formatter = Fmt.enumOf(CarEnums.IGNITION)),
    KnownProperty(PARKING_BRAKE_ON, "PARKING_BRAKE_ON", "Parking brake on", "bool", powertrain, formatter = Fmt::bool),
    // Speed (CAR_SPEED is a runtime permission).
    KnownProperty(PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED", "Speed", "m/s", listOf(CarPerm.CAR_SPEED), live = true, formatter = Fmt::speedMs),
    KnownProperty(PERF_VEHICLE_SPEED_DISPLAY, "PERF_VEHICLE_SPEED_DISPLAY", "Speed (as displayed)", "m/s", listOf(CarPerm.CAR_SPEED), formatter = Fmt::speedMs),
    // Exterior environment (normal permission).
    KnownProperty(ENV_OUTSIDE_TEMPERATURE, "ENV_OUTSIDE_TEMPERATURE", "Outside temperature", "°C", listOf(CarPerm.CAR_EXTERIOR_ENVIRONMENT), live = true, formatter = Fmt::celsius),
    KnownProperty(NIGHT_MODE, "NIGHT_MODE", "Night mode", "bool", listOf(CarPerm.CAR_EXTERIOR_ENVIRONMENT), formatter = Fmt::bool),
    // Vehicle info (normal permission). INFO_VIN is deliberately absent.
    KnownProperty(INFO_MAKE, "INFO_MAKE", "Make", "string", info),
    KnownProperty(INFO_MODEL, "INFO_MODEL", "Model", "string", info),
    KnownProperty(INFO_MODEL_YEAR, "INFO_MODEL_YEAR", "Model year", "year", info),
    KnownProperty(INFO_MODEL_TRIM, "INFO_MODEL_TRIM", "Trim", "string", info, note = "Android 15+ car service"),
    KnownProperty(INFO_FUEL_TYPE, "INFO_FUEL_TYPE", "Fuel type", "enum[]", info, formatter = Fmt.enumListOf(CarEnums.FUEL_TYPE)),
    KnownProperty(INFO_EV_CONNECTOR_TYPE, "INFO_EV_CONNECTOR_TYPE", "EV connector type", "enum[]", info, formatter = Fmt.enumListOf(CarEnums.CONNECTOR)),
    KnownProperty(INFO_EV_PORT_LOCATION, "INFO_EV_PORT_LOCATION", "EV port location", "enum", info, formatter = Fmt.enumOf(CarEnums.PORT_LOCATION)),
    KnownProperty(
      INFO_MULTI_EV_PORT_LOCATIONS, "INFO_MULTI_EV_PORT_LOCATIONS", "EV port locations", "enum[]", info,
      formatter = Fmt.enumListOf(CarEnums.PORT_LOCATION),
    ),
    KnownProperty(INFO_EXTERIOR_DIMENSIONS, "INFO_EXTERIOR_DIMENSIONS", "Exterior dimensions", "mm[]", info, note = "Android 13+ car service"),
    // Display units (normal permission).
    KnownProperty(DISTANCE_DISPLAY_UNITS, "DISTANCE_DISPLAY_UNITS", "Distance units", "unit", units, formatter = Fmt.enumOf(CarEnums.UNIT)),
    KnownProperty(EV_BATTERY_DISPLAY_UNITS, "EV_BATTERY_DISPLAY_UNITS", "Battery units", "unit", units, formatter = Fmt.enumOf(CarEnums.UNIT)),
    KnownProperty(TIRE_PRESSURE_DISPLAY_UNITS, "TIRE_PRESSURE_DISPLAY_UNITS", "Tire pressure units", "unit", units, formatter = Fmt.enumOf(CarEnums.UNIT)),
    KnownProperty(VEHICLE_SPEED_DISPLAY_UNITS, "VEHICLE_SPEED_DISPLAY_UNITS", "Speed units", "unit", units, formatter = Fmt.enumOf(CarEnums.UNIT)),
    KnownProperty(
      HVAC_TEMPERATURE_DISPLAY_UNITS, "HVAC_TEMPERATURE_DISPLAY_UNITS", "Temperature units", "unit",
      listOf(CarPerm.READ_CAR_DISPLAY_UNITS, CarPerm.CONTROL_CAR_CLIMATE), formatter = Fmt.enumOf(CarEnums.UNIT),
    ),
    // Expected to be unreadable for a Play app on Android 12L/14 (shown for completeness).
    KnownProperty(
      PERF_ODOMETER, "PERF_ODOMETER", "Odometer", "km", listOf(CarPerm.CAR_MILEAGE_3P, CarPerm.CAR_MILEAGE),
      note = "signature permission before Android 16", formatter = Fmt::kilometers,
    ),
    KnownProperty(
      TIRE_PRESSURE, "TIRE_PRESSURE", "Tire pressure", "kPa", listOf(CarPerm.CAR_TIRES_3P, CarPerm.CAR_TIRES),
      note = "signature permission before Android 16", formatter = Fmt::kpa,
    ),
    KnownProperty(
      HVAC_TEMPERATURE_CURRENT, "HVAC_TEMPERATURE_CURRENT", "Cabin temperature", "°C", listOf(CarPerm.CONTROL_CAR_CLIMATE),
      note = "signature permission", formatter = Fmt::celsius,
    ),
  )

  val byId: Map<Int, KnownProperty> = all.associateBy { it.id }

  val live: List<KnownProperty> = all.filter { it.live }

  /** Name for any property id: known table, then the AOSP catalog, else vendor/unknown. */
  fun nameOf(id: Int): String =
    byId[id]?.name ?: AospPropertyCatalog.entries[id]?.first ?: "vendor/unknown ${PropertyIdBits.hex(id)}"

  /**
   * Read permissions (any of) for any property id: the full AOSP list when catalogued (it may add
   * write permissions that also allow reading), else the known table; vendor properties need
   * CAR_VENDOR_EXTENSION.
   */
  fun readPermissionsOf(id: Int): List<String> = AospPropertyCatalog.entries[id]?.second
    ?: byId[id]?.readPermissions
    ?: if (PropertyIdBits.isVendor(id)) listOf(CarPerm.CAR_VENDOR_EXTENSION) else emptyList()

  fun format(id: Int, value: Any?): String = byId[id]?.format(value) ?: Fmt.raw(value)
}
