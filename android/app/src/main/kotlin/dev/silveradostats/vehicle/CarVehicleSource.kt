package dev.silveradostats.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.silveradostats.BuildConfig
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.link.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The session's samples from the truck's built-in data: read-only property reads through
 * [VehicleReader.readRaw] (its own car-service connection), polled by VehicleSession (speed at 2 Hz,
 * the rest at 1 Hz, capacity and outside temperature every 10 s). No OBD adapter, no subscription,
 * no write of any kind.
 *
 * Polling rather than event subscriptions: a plain read is what the Diagnostics live panel already
 * does on the truck, and a polled value is always the VHAL's current one. Called from
 * VehicleSession's single sampling thread only.
 */
class CarVehicleSource(context: Context) : VehicleSource {
  private val app = context.applicationContext
  private val reader = VehicleReader(app) {}
  private var started = false
  private var granted: Set<String> = emptySet()
  private var permissionsCheckedAtMs: Long? = null
  private val problems = HashMap<Signal, ReadProblem?>()
  private var fullReads = 0L
  private var capacityWh: Double? = null
  private var capacityNominal = false
  private var ratedWh: Double? = null
  private var outsideC: Double? = null

  override fun read(atMs: Long, full: Boolean): SourceRead {
    if (!reader.isAutomotive) return SourceRead(null, Feed(FeedKind.NOT_AUTOMOTIVE, NOT_A_CAR))
    if (!started) {
      started = true
      reader.connect()
    }
    val conn = reader.connection.value
    when (conn.phase) {
      CarConnState.Phase.CONNECTED -> {}
      CarConnState.Phase.CONNECTING -> return SourceRead(null, Feed(FeedKind.STARTING, "Connecting to the car service…"))
      CarConnState.Phase.NOT_AUTOMOTIVE -> return SourceRead(null, Feed(FeedKind.NOT_AUTOMOTIVE, NOT_A_CAR))
      CarConnState.Phase.DISCONNECTED, CarConnState.Phase.FAILED -> return SourceRead(null, Feed(FeedKind.NO_CAR_SERVICE, conn.message))
    }
    val checked = permissionsCheckedAtMs
    if (checked == null || atMs - checked >= PERMISSION_RECHECK_MS) checkPermissions(atMs)

    var sample = VehicleSample(atMs, speedMps = get(KnownProperties.PERF_VEHICLE_SPEED, Signal.SPEED, CarPerm.CAR_SPEED)?.number)
    if (full) {
      if (fullReads % SLOW_EVERY == 0L) readSlow()
      fullReads += 1
      val rate = get(KnownProperties.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, Signal.CHARGE_RATE, CarPerm.CAR_ENERGY)
      sample = sample.copy(
        levelWh = get(KnownProperties.EV_BATTERY_LEVEL, Signal.LEVEL, CarPerm.CAR_ENERGY)?.number,
        chargeRateMw = rate?.number,
        chargeRateAgeMs = rate?.ageMs,
        rangeM = get(KnownProperties.RANGE_REMAINING, Signal.RANGE, CarPerm.CAR_ENERGY)?.number,
        gear = get(KnownProperties.GEAR_SELECTION, Signal.GEAR, CarPerm.CAR_POWERTRAIN)?.number?.toInt(),
        plugConnected = get(KnownProperties.EV_CHARGE_PORT_CONNECTED, Signal.PLUG, CarPerm.CAR_ENERGY_PORTS)?.value as? Boolean,
        chargeState = ChargeState.fromAosp(get(KnownProperties.EV_CHARGE_STATE, Signal.CHARGE_STATE, CarPerm.CAR_ENERGY)?.number?.toInt()),
        capacityWh = capacityWh,
        outsideC = outsideC,
      )
    }
    val missing = problems.filterValues { it == ReadProblem.NOT_SUPPORTED || it == ReadProblem.NO_PERMISSION }.keys
    return SourceRead(
      sample,
      Feed(
        kind = FeedKind.VEHICLE,
        message = "The truck's built-in data",
        missingPermissions = RUNTIME_NEEDED.filterNot { it in granted },
        missing = missing,
        capacityNominal = capacityNominal,
        ratedWh = ratedWh,
      ),
    )
  }

  /** Capacity (usable now if offered, else nominal) and outside temperature: they change slowly. */
  private fun readSlow() {
    val usable = get(KnownProperties.EV_CURRENT_BATTERY_CAPACITY, null, CarPerm.CAR_ENERGY)?.number?.takeIf { it > 0 }
    val nominal = get(KnownProperties.INFO_EV_BATTERY_CAPACITY, if (usable == null) Signal.CAPACITY else null, CarPerm.CAR_INFO)?.number?.takeIf { it > 0 }
    ratedWh = nominal ?: ratedWh
    if (usable != null) {
      capacityWh = usable
      capacityNominal = false
      problems[Signal.CAPACITY] = null
    } else if (nominal != null) {
      capacityWh = nominal
      capacityNominal = true
    }
    outsideC = get(KnownProperties.ENV_OUTSIDE_TEMPERATURE, Signal.OUTSIDE_TEMP, CarPerm.CAR_EXTERIOR_ENVIRONMENT)?.number
  }

  /** One read of [id] when [permission] is held; records in [problems] why [signal] has no value. */
  private fun get(id: Int, signal: Signal?, permission: String): RawRead? {
    if (permission !in granted) {
      if (signal != null) problems[signal] = ReadProblem.NO_PERMISSION
      return null
    }
    val r = reader.readRaw(id)
    if (signal != null) problems[signal] = r.problem
    return r.takeIf { it.problem == null }
  }

  private fun checkPermissions(atMs: Long) {
    val first = permissionsCheckedAtMs == null
    permissionsCheckedAtMs = atMs
    val now = ALL_NEEDED.filter { AppPermissions.isGranted(app, it) }.toSet()
    val newGrant = !granted.containsAll(now)
    granted = now
    // A fresh car-service connection sees the new grant (Android 12L caches its config list).
    if (newGrant && !first && started) reader.reconnect()
  }

  override fun release() {
    if (started) reader.disconnect()
    started = false
  }

  override fun onPermissionsChanged() {
    checkPermissions(permissionsCheckedAtMs ?: 0L)
  }

  companion object {
    const val NOT_A_CAR = "This device has no car data (not Android Automotive)."

    /** Re-check permissions this often (a grant in Settings while the app runs). */
    const val PERMISSION_RECHECK_MS = 10_000L

    /** Capacity and outside temperature every this many 1 Hz reads. */
    const val SLOW_EVERY = 10L

    /** The runtime permissions the session needs (energy and speed). */
    val RUNTIME_NEEDED = listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED)
    private val ALL_NEEDED = RUNTIME_NEEDED + listOf(CarPerm.CAR_ENERGY_PORTS, CarPerm.CAR_POWERTRAIN, CarPerm.CAR_INFO, CarPerm.CAR_EXTERIOR_ENVIRONMENT)
  }
}

/**
 * The process's one VehicleSession: it outlives LinkService (which is destroyed when the last
 * screen unbinds), so a session survives the dashboard closing and reopening, and only ends with
 * a reset, a demo switch or the process. LinkService hands it to the screens.
 */
object SessionHub {
  @Volatile private var instance: VehicleSession? = null

  fun get(context: Context): VehicleSession =
    instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun create(app: Context): VehicleSession {
    // No logcat on the truck: what stopped the measuring goes where the report can show it.
    val record: (Throwable) -> Unit = { e ->
      CrashLog.record(app, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Thread.currentThread().name, e)
    }
    // One thread: the session, its tracker and the car source are only touched from it.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> record(e) })
    val automotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    val settings = AppSettings.get(app)
    // elapsedRealtime, not System.nanoTime: nanoTime stops while the device sleeps, and a sleep must be a gap.
    val session = VehicleSession(scope, SystemClock::elapsedRealtime, { demo, fresh ->
      when {
        demo -> DemoSource(if (fresh) 0L else VehicleSession.DEMO_HEAD_START_MS)
        automotive -> CarVehicleSource(app)
        else -> UnavailableSource(Feed(FeedKind.NOT_AUTOMOTIVE, CarVehicleSource.NOT_A_CAR))
      }
    }, onFailure = record, savedEfficiency = settings.lastEfficiency(), onEfficiency = settings::setLastEfficiency)
    scope.launch { settings.state.map { it.demo }.distinctUntilChanged().collect { session.setDemo(it) } }
    return session
  }
}
