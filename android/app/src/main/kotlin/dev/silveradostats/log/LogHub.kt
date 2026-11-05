package dev.silveradostats.log

import android.content.Context
import android.net.Uri
import dev.silveradostats.BuildConfig
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.link.AppSettings
import dev.silveradostats.link.LinkConnection
import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.MonoClock
import dev.silveradostats.vehicle.CarConnState
import dev.silveradostats.vehicle.KnownProperties
import dev.silveradostats.vehicle.RawRead
import dev.silveradostats.vehicle.ReadProblem
import dev.silveradostats.vehicle.VehicleReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/**
 * The process's one battery log: writes rows and hands them to the Trends tab and the export.
 *
 *  - built-in data: [logCar], from the hourly job and every few minutes while a screen is up
 *    ([whileOnScreen]). A short read of the car service, then the connection is released;
 *  - MX+: [onLive] takes a row a minute from whatever the link is reading for a screen, and sends
 *    nothing itself. [visit] is the one thing that connects for the log: only once the truck reports
 *    its ignition on (LogPolicy.visitAllowed), through the same link service and read-only guards as the
 *    screens, for one poll cycle.
 */
class LogHub private constructor(private val app: Context) {
  private val store: LogStore = SqliteLogStore(app)

  @OptIn(ExperimentalCoroutinesApi::class)
  private val io = Dispatchers.IO.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _revision = MutableStateFlow(0L)

  /** Goes up with every row written: Trends reloads on it. */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  /** Main thread. Newest MX+ row written or being written. */
  private var lastMxAtMs: Long? = null
  private var mxLoaded = false

  /** Main thread. A visit is connected right now, so its row says so. */
  private var visiting = false

  /** Why each car property had no value in the newest read; null until this process has read them. */
  @Volatile private var carProblems: Map<Int, ReadProblem>? = null
  private val mxRows = MutableStateFlow(0L)

  /** The app opened: keeps the hourly job scheduled. */
  fun onAppOpened() {
    LogScheduler.ensure(app)
  }

  /**
   * Main thread, for as long as a screen of the app is up (returns only when cancelled): a row of the
   * built-in data every few minutes, so that the log does not depend on when Android runs the job.
   */
  suspend fun whileOnScreen() {
    while (true) {
      val last = withContext(io) { store.lastAt(LogSource.CAR) }
      val now = System.currentTimeMillis()
      when {
        LogPolicy.carRowDue(last, now) -> logCar(LogTrigger.APP_OPEN)
        // Trends says why a property has no value, which only a read in this process can tell.
        carProblems == null || LogPolicy.carScreenRowDue(last, now) -> logCar(LogTrigger.CAR_SCREEN)
      }
      delay(LogPolicy.CAR_SCREEN_CHECK_MS)
    }
  }

  /** Main thread. One read of the built-in data into the log. Returns the ignition state read, if any. */
  suspend fun logCar(trigger: LogTrigger): Int? {
    val reader = VehicleReader(app) {}
    reader.setUxWatching(false)
    reader.connect()
    try {
      val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { reader.connection.first { it.phase != CarConnState.Phase.CONNECTING } }
      if (connected?.phase != CarConnState.Phase.CONNECTED) return null
      val problems = HashMap<Int, ReadProblem>()
      fun read(id: Int): RawRead = reader.readRaw(id).also { r -> r.problem?.let { problems[id] = whyUnread(id, it) } }
      val reading = withContext(Dispatchers.IO) {
        CarReading(
          levelWh = read(KnownProperties.EV_BATTERY_LEVEL).number,
          usableWh = read(KnownProperties.EV_CURRENT_BATTERY_CAPACITY).number,
          nominalWh = read(KnownProperties.INFO_EV_BATTERY_CAPACITY).number,
          outsideC = read(KnownProperties.ENV_OUTSIDE_TEMPERATURE).number,
          rangeM = read(KnownProperties.RANGE_REMAINING).number,
          plugged = read(KnownProperties.EV_CHARGE_PORT_CONNECTED).value as? Boolean,
          chargeRateMw = read(KnownProperties.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE).number,
          batteryTempC = read(KnownProperties.EV_BATTERY_AVERAGE_TEMPERATURE).number,
          chargeState = read(KnownProperties.EV_CHARGE_STATE).number,
          portOpen = read(KnownProperties.EV_CHARGE_PORT_OPEN).value as? Boolean,
          chargeLimitPct = read(KnownProperties.EV_CHARGE_PERCENT_LIMIT).number,
          drawLimitA = read(KnownProperties.EV_CHARGE_CURRENT_DRAW_LIMIT).number,
          chargeTimeS = read(KnownProperties.EV_CHARGE_TIME_REMAINING).number,
          regenState = read(KnownProperties.EV_REGENERATIVE_BRAKING_STATE).number,
          ignition = read(KnownProperties.IGNITION_STATE).number,
          gear = read(KnownProperties.GEAR_SELECTION).number,
          speedMs = read(KnownProperties.PERF_VEHICLE_SPEED).number,
        )
      }
      carProblems = problems
      val now = System.currentTimeMillis()
      if (LogRows.wallClockValid(now)) LogRows.fromCar(reading, now, tzMinutes(now), trigger)?.let { save(it) }
      return reading.ignition?.toInt()
    } finally {
      reader.disconnect()
    }
  }

  /** Without its permission a property looks unsupported: the car service hides its config. */
  private fun whyUnread(id: Int, problem: ReadProblem): ReadProblem {
    val permissions = KnownProperties.readPermissionsOf(id)
    return if (problem == ReadProblem.NOT_SUPPORTED && permissions.isNotEmpty() && permissions.none { AppPermissions.isGranted(app, it) }) ReadProblem.NO_PERMISSION else problem
  }

  /** Main thread: every LiveState the engine publishes. Sends nothing; only logs what was read anyway. */
  fun onLive(s: LiveState) {
    if (s.demo || s.status != LinkStatus.CONNECTED) return
    val now = System.currentTimeMillis()
    if (!LogRows.wallClockValid(now)) return
    if (!mxLoaded) {
      // Claims the slot first, so that a state arriving during the lookup cannot write a second row.
      mxLoaded = true
      lastMxAtMs = now
      scope.launch {
        val last = withContext(io) { store.lastAt(LogSource.MX) }
        lastMxAtMs = last
        onLive(s)
      }
      return
    }
    if (!LogPolicy.mxRowDue(lastMxAtMs, now)) return
    val row = LogRows.fromLive(s, MonoClock.ms(), now, tzMinutes(now), if (visiting) LogTrigger.MX_VISIT else LogTrigger.MX_SCREEN) ?: return
    lastMxAtMs = now
    scope.launch {
      save(row)
      mxRows.value += 1
    }
  }

  /**
   * Main thread. The hourly visit: connects through the app's one link service, waits for [onLive]'s
   * row and lets go. No visit unless [ignition] positively says the truck is on.
   */
  suspend fun visit(ignition: Int?) {
    val settings = AppSettings.get(app).state.value
    val last = withContext(io) { store.lastAt(LogSource.MX) }
    if (!LogPolicy.visitAllowed(ignition, settings.demo, settings.mxAddress != null, settings.mxOff, last, System.currentTimeMillis())) return
    val before = mxRows.value
    visiting = true
    val link = LinkConnection(app, scope, obdWanted = true)
    try {
      link.setVisible(true)
      withTimeoutOrNull(LogPolicy.VISIT_TIMEOUT_MS) { mxRows.first { it > before } }
    } finally {
      link.close()
      visiting = false
    }
  }

  /** What Trends draws of one source. Blocking: call off the main thread. */
  fun trendData(range: TrendRange, demo: Boolean, source: LogSource, pick: ChartPick): TrendData {
    val now = System.currentTimeMillis()
    val from = range.ms?.let { now - it } ?: 0L
    if (demo) {
      val rows = DemoLog.rows(now).filter { it.source == source }
      return TrendData(source, pick, rows.filter { it.atMs >= from }, rows.size.toLong(), rows.lastOrNull()?.atMs, now, demo = true, TrendsModel.latest(rows), DemoLog.PROBLEMS)
    }
    val rows = store.rows(from, source, TrendsModel.chartColumns(source, pick))
    return TrendData(source, pick, rows, store.count(source), store.lastAt(source), now, demo = false, store.latest(source, TrendsModel.loggedColumns(source)), carProblems.orEmpty())
  }

  /** Writes the whole log to [uri] as CSV. The stored log only, never demo data. Says how it went. */
  suspend fun export(uri: Uri): String = withContext(io) {
    try {
      val saved = store.useRows { rows -> LogExport.save(app, uri, rows.asIterable()) }
      "Saved $saved rows"
    } catch (e: Exception) {
      CrashLog.record(app, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Thread.currentThread().name, e)
      "Export failed: ${e.javaClass.simpleName}"
    }
  }

  private suspend fun save(row: LogRow) {
    withContext(io) { store.insert(row) }
    _revision.value += 1
  }

  private fun tzMinutes(atMs: Long): Int = TimeZone.getDefault().getOffset(atMs) / 60_000

  companion object {
    private const val CONNECT_TIMEOUT_MS = 15_000L

    @Volatile private var instance: LogHub? = null

    fun get(context: Context): LogHub =
      instance ?: synchronized(this) { instance ?: LogHub(context.applicationContext).also { instance = it } }
  }
}
