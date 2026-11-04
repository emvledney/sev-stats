package dev.silveradostats.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.KV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

enum class RowStatus { AVAILABLE, UNAVAILABLE, ERROR }

/** One (property, area) reading. Everything is plain data so it can go straight into the report. */
data class PropertyRow(
  val id: Int,
  val idHex: String,
  val name: String,
  val label: String?,
  val known: Boolean,
  val listed: Boolean,
  val group: String,
  val areaType: String,
  val areaId: Int,
  val areaIds: List<Int>,
  val access: String,
  val changeMode: String,
  val sampleRates: String,
  val valueType: String,
  val value: String,
  val rawValue: Any?,
  val status: RowStatus,
  val detail: String,
  val timestampAgeMs: Long?,
  val permissions: String,
  val permissionGranted: Boolean?,
  val note: String,
)

data class VhalSnapshot(
  val takenAtMs: Long,
  val listedCount: Int,
  val listError: String?,
  val rows: List<PropertyRow>,
  val readMs: Long,
)

data class UxInfo(
  val requiresDistractionOptimization: Boolean,
  val activeRestrictions: Int,
  val decoded: List<String>,
  val maxStringLength: Int?,
  val updatedAtMs: Long,
)

/** Why a raw read returned no value. */
enum class ReadProblem { NOT_CONNECTED, NOT_SUPPORTED, NO_PERMISSION, UNAVAILABLE, ERROR }

/** One raw reading for the session sampler: the normalized value, or why there is none. */
data class RawRead(
  val value: Any?,
  val problem: ReadProblem?,
  /** Age of the value by the property's own timestamp, ms (null = no usable timestamp). */
  val ageMs: Long? = null,
) {
  val number: Double? get() = Fmt.num(value)?.takeIf { it.isFinite() }
}

data class CarConnState(val phase: Phase, val message: String) {
  enum class Phase { NOT_AUTOMOTIVE, CONNECTING, CONNECTED, DISCONNECTED, FAILED }
}

/**
 * Read-only access to the car service.
 *
 * Only these car calls are used: Car.createCar / disconnect, getCarManager,
 * CarPropertyManager.getPropertyList / getCarPropertyConfig / getProperty (also through [readRaw],
 * the session sampler's 1-2 Hz read), and
 * CarUxRestrictionsManager.getCurrentCarUxRestrictions / registerListener /
 * unregisterListener. Nothing here can change vehicle state. [ux] is null whenever the restrictions
 * are not known, so that a failure can never look like "not restricted" (see DrivingPolicy).
 */
class VehicleReader(context: Context, private val onReady: () -> Unit) {
  private val app = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var car: Car? = null
  @Volatile private var propertyManager: CarPropertyManager? = null
  @Volatile private var uxManager: CarUxRestrictionsManager? = null
  @Volatile private var configCache: Map<Int, CarPropertyConfig<*>> = emptyMap()

  /** Main thread. Whether the UX restrictions listener should be registered ([setUxWatching]). */
  private var uxWatching = true

  /** [readRaw]'s configs for the current connection (absent key = not looked up yet). */
  private val rawConfigs = ConcurrentHashMap<Int, Optional<CarPropertyConfig<*>>>()

  /** Permission states, cached per read pass (checkSelfPermission is a binder call). */
  @Volatile private var permCache: Map<String, Boolean> = emptyMap()

  private fun granted(p: String): Boolean = permCache[p] ?: AppPermissions.isGranted(app, p).also { permCache = permCache + (p to it) }

  private val _connection = MutableStateFlow(CarConnState(CarConnState.Phase.CONNECTING, "Not started"))
  val connection: StateFlow<CarConnState> = _connection.asStateFlow()

  private val _ux = MutableStateFlow<UxInfo?>(null)

  /** The current restrictions; null = not known (not connected, service gone, a read or the listener failed). */
  val ux: StateFlow<UxInfo?> = _ux.asStateFlow()

  val isAutomotive: Boolean = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

  fun connect() {
    if (!isAutomotive) {
      _connection.value = CarConnState(
        CarConnState.Phase.NOT_AUTOMOTIVE,
        "Not an Android Automotive device (no FEATURE_AUTOMOTIVE): the car service is not available here.",
      )
      return
    }
    if (car != null) return
    _connection.value = CarConnState(CarConnState.Phase.CONNECTING, "Connecting to the car service…")
    try {
      car = Car.createCar(app, mainHandler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> onLifecycle(c, ready) }
      if (car == null) {
        _connection.value = CarConnState(CarConnState.Phase.FAILED, "Car.createCar returned null")
      }
    } catch (e: LinkageError) {
      // android.car library missing at runtime (for example on a phone).
      _connection.value = CarConnState(CarConnState.Phase.FAILED, "android.car library unavailable: ${e.javaClass.simpleName}: ${e.message}")
    } catch (e: Exception) {
      _connection.value = CarConnState(CarConnState.Phase.FAILED, "Car.createCar failed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  private fun onLifecycle(c: Car, ready: Boolean) {
    rawConfigs.clear()
    if (!ready) {
      propertyManager = null
      uxManager = null
      _ux.value = null
      _connection.value = CarConnState(CarConnState.Phase.DISCONNECTED, "Car service disconnected; waiting for it to come back")
      return
    }
    try {
      propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
      uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
      if (uxWatching) registerUx() else _ux.value = null
      val msg = buildString {
        append("Connected")
        if (propertyManager == null) append("; CarPropertyManager unavailable")
        if (uxManager == null) append("; CarUxRestrictionsManager unavailable")
      }
      _connection.value = CarConnState(CarConnState.Phase.CONNECTED, msg)
      onReady()
    } catch (e: Exception) {
      _ux.value = null
      _connection.value = CarConnState(CarConnState.Phase.FAILED, "getCarManager failed: ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /** Registers the listener and takes the current value; on any failure the state is unknown. */
  private fun registerUx() {
    val ux = uxManager
    if (ux == null) {
      _ux.value = null
      return
    }
    try {
      ux.registerListener { r ->
        _ux.value = try {
          r?.toInfo()
        } catch (e: Exception) {
          null
        }
      }
      _ux.value = ux.currentCarUxRestrictions?.toInfo()
    } catch (e: Exception) {
      _ux.value = null
    } catch (e: LinkageError) {
      _ux.value = null
    }
  }

  /**
   * Keeps the UX restrictions listener registered only while [on] (the main screen's onStart …
   * onStop). Registering takes the current value at once, so a screen that returns never shows the
   * value from before it went away. Main thread.
   */
  fun setUxWatching(on: Boolean) {
    if (uxWatching == on) return
    uxWatching = on
    if (on) {
      registerUx()
    } else {
      try {
        uxManager?.unregisterListener()
      } catch (_: Exception) {
      }
    }
  }

  fun disconnect() {
    try {
      uxManager?.unregisterListener()
    } catch (_: Exception) {
    }
    _ux.value = null
    try {
      car?.disconnect()
    } catch (_: Exception) {
    }
    car = null
    propertyManager = null
    uxManager = null
    rawConfigs.clear()
  }

  /** Reconnects so a fresh CarPropertyManager sees newly granted permissions (Android 12L caches configs). */
  fun reconnect() {
    disconnect()
    connect()
  }

  /** Reads the current restrictions again; a read that fails leaves the state unknown, not stale. */
  fun refreshUx(): UxInfo? {
    val info = try {
      uxManager?.currentCarUxRestrictions?.toInfo()
    } catch (e: Exception) {
      null
    }
    _ux.value = info
    return info
  }

  /**
   * Car API / platform info. Methods newer than the device are caught (NoSuchMethodError).
   * The version getters are deprecated in newer car libs but are the only source on Android 13/14.
   */
  @Suppress("DEPRECATION")
  fun carApiInfo(): List<KV> {
    val out = mutableListOf<KV>()
    out += KV("FEATURE_AUTOMOTIVE", isAutomotive.toString())
    if (!isAutomotive) return out
    out += KV(
      "Car API version (runtime)",
      guard {
        val v = Car.getCarVersion()
        "${v.majorVersion}.${v.minorVersion}"
      },
    )
    out += KV(
      "Car API major (isApiVersionAtLeast)",
      guard {
        // Works on Android 12L too, where getCarVersion() does not exist yet.
        (29..40).lastOrNull { Car.isApiVersionAtLeast(it) }?.toString() ?: "< 29"
      },
    )
    out += KV(
      "Car platform version (runtime)",
      guard {
        val v = Car.getPlatformVersion()
        "${v.majorVersion}.${v.minorVersion}"
      },
    )
    out += KV(
      "Car connection type",
      guard {
        when (val t = car?.carConnectionType) {
          null -> "not connected"
          Car.CONNECTION_TYPE_EMBEDDED -> "$t (EMBEDDED)"
          else -> t.toString()
        }
      },
    )
    return out
  }

  private inline fun guard(block: () -> String): String = try {
    block()
  } catch (e: LinkageError) {
    "n/a (${e.javaClass.simpleName})"
  } catch (e: Exception) {
    "n/a (${e.javaClass.simpleName}: ${e.message})"
  }

  /**
   * Reads every property the platform lists for this app plus every known property,
   * for every area. Blocking binder calls: call from a background thread.
   */
  fun readAll(): VhalSnapshot {
    val t0 = SystemClock.elapsedRealtime()
    permCache = emptyMap()
    val cpm = propertyManager
      ?: return VhalSnapshot(System.currentTimeMillis(), 0, "Car service not connected: ${_connection.value.message}", emptyList(), 0)
    var listError: String? = null
    val listed: List<CarPropertyConfig<*>> = try {
      cpm.propertyList.filterNotNull()
    } catch (e: Exception) {
      listError = "getPropertyList failed: ${e.javaClass.simpleName}: ${e.message}"
      emptyList()
    }
    val configs = HashMap<Int, CarPropertyConfig<*>>()
    listed.forEach { configs[it.propertyId] = it }
    val listedIds = configs.keys.toSet()
    val ids = (listedIds + KnownProperties.all.map { it.id })
      .filterNot { it in KnownProperties.EXCLUDED_IDS }
      .sorted()
    val rows = ArrayList<PropertyRow>()
    for (id in ids) {
      var configError: String? = null
      val cfg = configs[id] ?: try {
        cpm.getCarPropertyConfig(id)
      } catch (e: Exception) {
        configError = "${e.javaClass.simpleName}: ${e.message}"
        null
      }
      if (cfg != null) configs[id] = cfg
      val areas = cfg?.areaIds?.toList()?.takeIf { it.isNotEmpty() } ?: listOf(0)
      for (area in areas) {
        rows += readOne(cpm, id, area, cfg, id in listedIds, configError)
      }
    }
    configCache = configs
    return VhalSnapshot(System.currentTimeMillis(), listedIds.size, listError, rows, SystemClock.elapsedRealtime() - t0)
  }

  /** One-second refresh of the live energy set (global area). Blocking: call from a background thread. */
  fun readLive(): List<PropertyRow> {
    val cpm = propertyManager ?: return emptyList()
    val cache = configCache
    permCache = emptyMap()
    return KnownProperties.live.map { kp ->
      val cfg = cache[kp.id]
      if (cfg != null) {
        val area = cfg.areaIds?.firstOrNull() ?: 0
        readOne(cpm, kp.id, area, cfg, listed = true, configError = null)
      } else {
        val base = baseRow(kp.id, 0, null, listed = false)
        if (base.permissionGranted == false) {
          base.copy(status = RowStatus.ERROR, detail = "$NO_PERMISSION (${base.permissions})")
        } else {
          // No cached config: either not supported, or the permission was granted after the last
          // full read (for example in Settings). Read anyway; the result says which it is.
          readOne(cpm, kp.id, 0, null, listed = false, configError = null)
        }
      }
    }
  }

  /**
   * One read-only getProperty of [id] (first area of its config) without formatting, for the
   * session sampler. The caller checks permissions first: without them the car service hides the
   * config, which would read as "not supported". Blocking: call from a background thread.
   */
  fun readRaw(id: Int): RawRead {
    val cpm = propertyManager ?: return RawRead(null, ReadProblem.NOT_CONNECTED)
    val cfg = rawConfigs.getOrPut(id) {
      Optional.ofNullable(
        try {
          cpm.getCarPropertyConfig(id)
        } catch (e: Exception) {
          null
        },
      )
    }.orElse(null) ?: return RawRead(null, ReadProblem.NOT_SUPPORTED)
    if (cfg.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE) return RawRead(null, ReadProblem.NOT_SUPPORTED)
    val area = cfg.areaIds?.firstOrNull() ?: 0
    return try {
      val v: CarPropertyValue<Any?>? = cpm.getProperty(id, area)
      @Suppress("DEPRECATION")
      val st = v?.status
      when (st) {
        null -> RawRead(null, ReadProblem.UNAVAILABLE)
        CarPropertyValue.STATUS_AVAILABLE -> RawRead(
          normalize(v.value),
          null,
          v.timestamp.takeIf { it > 0 }?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000 }?.takeIf { it >= 0 },
        )
        CarPropertyValue.STATUS_UNAVAILABLE -> RawRead(null, ReadProblem.UNAVAILABLE)
        else -> RawRead(null, ReadProblem.ERROR)
      }
    } catch (e: SecurityException) {
      RawRead(null, ReadProblem.NO_PERMISSION)
    } catch (e: IllegalArgumentException) {
      RawRead(null, ReadProblem.NOT_SUPPORTED)
    } catch (e: RuntimeException) {
      val n = e.javaClass.simpleName
      RawRead(null, if (n.startsWith("PropertyNotAvailable")) ReadProblem.UNAVAILABLE else ReadProblem.ERROR)
    } catch (e: LinkageError) {
      RawRead(null, ReadProblem.ERROR)
    }
  }

  private fun readOne(
    cpm: CarPropertyManager,
    id: Int,
    area: Int,
    cfg: CarPropertyConfig<*>?,
    listed: Boolean,
    configError: String?,
  ): PropertyRow {
    val base = baseRow(id, area, cfg, listed)
    if (cfg != null && cfg.access == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE) {
      return base.copy(status = RowStatus.ERROR, detail = "Write-only property: not read")
    }
    return try {
      val v: CarPropertyValue<Any?>? = cpm.getProperty(id, area)
      if (v == null) {
        // Android 12/12L's car service returns null for IDs missing from its config list, so
        // without a config null means "not supported", not "no value right now".
        if (cfg == null) {
          base.copy(status = RowStatus.ERROR, detail = "$NOT_SUPPORTED (no config; getProperty returned null)")
        } else {
          base.copy(status = RowStatus.UNAVAILABLE, detail = "getProperty returned null")
        }
      } else {
        @Suppress("DEPRECATION")
        val st = v.status
        val status = when (st) {
          CarPropertyValue.STATUS_AVAILABLE -> RowStatus.AVAILABLE
          CarPropertyValue.STATUS_UNAVAILABLE -> RowStatus.UNAVAILABLE
          else -> RowStatus.ERROR
        }
        val value = normalize(v.value)
        val ts = v.timestamp
        val age = if (ts > 0) (SystemClock.elapsedRealtimeNanos() - ts) / 1_000_000 else null
        base.copy(
          value = if (status == RowStatus.AVAILABLE) KnownProperties.format(id, value) else "",
          rawValue = value,
          status = status,
          detail = if (status == RowStatus.AVAILABLE) "" else "status=$st",
          timestampAgeMs = age,
        )
      }
    } catch (e: SecurityException) {
      base.copy(status = RowStatus.ERROR, detail = "${e.javaClass.simpleName} (no read permission): ${e.message}")
    } catch (e: IllegalArgumentException) {
      val why = if (configError != null) "; config: $configError" else ""
      if (cfg == null) {
        base.copy(status = RowStatus.ERROR, detail = "$NOT_SUPPORTED (no config; ${e.javaClass.simpleName}: ${e.message})$why")
      } else {
        base.copy(status = RowStatus.ERROR, detail = "${e.javaClass.simpleName} (not supported / bad area): ${e.message}$why")
      }
    } catch (e: RuntimeException) {
      // Covers IllegalStateException and the car-lib exceptions (PropertyNotAvailableException,
      // PropertyNotAvailableAndRetryException, CarInternalErrorException). They are classified by
      // class name because those classes do not exist on Android 12L.
      val n = e.javaClass.simpleName
      val status = if (n.startsWith("PropertyNotAvailable")) RowStatus.UNAVAILABLE else RowStatus.ERROR
      base.copy(status = status, detail = "$n: ${e.message}")
    } catch (e: LinkageError) {
      base.copy(status = RowStatus.ERROR, detail = "${e.javaClass.simpleName}: ${e.message}")
    }
  }

  private fun baseRow(id: Int, area: Int, cfg: CarPropertyConfig<*>?, listed: Boolean): PropertyRow {
    val kp = KnownProperties.byId[id]
    val perms = KnownProperties.readPermissionsOf(id)
    val anyGranted = if (perms.isEmpty()) null else perms.any { granted(it) }
    val permText = when {
      perms.isEmpty() -> "none known (system-internal)"
      else -> perms.joinToString(" | ") { p ->
        CarPerm.short(p) + "=" + if (granted(p)) "granted" else "denied"
      }
    }
    return PropertyRow(
      id = id,
      idHex = PropertyIdBits.hex(id),
      name = KnownProperties.nameOf(id),
      label = kp?.label,
      known = kp != null,
      listed = listed,
      group = PropertyIdBits.group(id),
      areaType = PropertyIdBits.areaType(id),
      areaId = area,
      areaIds = cfg?.areaIds?.toList() ?: emptyList(),
      access = cfg?.let { accessName(it.access) } ?: "no config",
      changeMode = cfg?.let { changeModeName(it.changeMode) } ?: "-",
      sampleRates = cfg?.let {
        if (it.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) "${it.minSampleRate}-${it.maxSampleRate} Hz" else ""
      } ?: "",
      valueType = PropertyIdBits.valueType(id),
      value = "",
      rawValue = null,
      status = RowStatus.ERROR,
      detail = "",
      timestampAgeMs = null,
      permissions = permText,
      permissionGranted = anyGranted,
      note = kp?.note ?: "",
    )
  }

  private fun accessName(a: Int) = when (a) {
    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_NONE -> "NONE"
    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ -> "READ"
    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE -> "WRITE"
    CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE -> "READ_WRITE"
    else -> "access $a"
  }

  private fun changeModeName(m: Int) = when (m) {
    CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC -> "STATIC"
    CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE -> "ON_CHANGE"
    CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS -> "CONTINUOUS"
    else -> "mode $m"
  }

  companion object {
    /** Detail prefix of a row the car service does not offer (the UI shows "not offered"). */
    const val NOT_SUPPORTED = "Not supported by this car service"

    /** Detail prefix of a row skipped because its read permission is denied. */
    const val NO_PERMISSION = "No read permission"

    private val UX_BITS = listOf(
      CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD to "NO_DIALPAD",
      CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING to "NO_FILTERING",
      CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH to "LIMIT_STRING_LENGTH",
      CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD to "NO_KEYBOARD",
      CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO to "NO_VIDEO",
      CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT to "LIMIT_CONTENT",
      CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP to "NO_SETUP",
      CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE to "NO_TEXT_MESSAGE",
      CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION to "NO_VOICE_TRANSCRIPTION",
    )

    private fun CarUxRestrictions.toInfo(): UxInfo {
      val bits = activeRestrictions
      return UxInfo(
        requiresDistractionOptimization = isRequiresDistractionOptimization,
        activeRestrictions = bits,
        decoded = if (bits == 0) listOf("BASELINE (none)") else UX_BITS.filter { (b, _) -> bits and b != 0 }.map { it.second },
        maxStringLength = try {
          maxRestrictedStringLength
        } catch (e: Exception) {
          null
        },
        updatedAtMs = System.currentTimeMillis(),
      )
    }

    /** Converts car values to plain Kotlin values (arrays to lists). */
    fun normalize(v: Any?): Any? = when (v) {
      null -> null
      is Array<*> -> v.map { normalize(it) }
      is IntArray -> v.toList()
      is LongArray -> v.toList()
      is FloatArray -> v.toList()
      is BooleanArray -> v.toList()
      is ByteArray -> v
      else -> v
    }
  }
}
