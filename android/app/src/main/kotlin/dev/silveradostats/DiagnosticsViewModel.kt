package dev.silveradostats

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silveradostats.adapter.AdapterLinkTest
import dev.silveradostats.ble.BleScan
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.diag.KV
import dev.silveradostats.diag.SystemInfo
import dev.silveradostats.report.MxReport
import dev.silveradostats.report.Report
import dev.silveradostats.report.ReportSender
import dev.silveradostats.vehicle.CarPerm
import dev.silveradostats.vehicle.VehicleReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.net.SocketFactory

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
  private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val reader = VehicleReader(app) { refreshVhal() }
  private val ble = BleScan(app)

  private val _state = MutableStateFlow(
    DiagState(
      adapterHost = prefs.getString(KEY_HOST, null) ?: AdapterLinkTest.DEFAULT_HOST,
      adapterPort = prefs.getString(KEY_PORT, null) ?: AdapterLinkTest.DEFAULT_PORT.toString(),
      adapterBindWifi = prefs.getBoolean(KEY_BIND_WIFI, false),
      reportUrl = prefs.getString(KEY_URL, null) ?: DiagState.DEFAULT_REPORT_URL,
    ),
  )
  val state: StateFlow<DiagState> = _state.asStateFlow()

  /** android.car permissions granted at the last check (null before the first check). */
  private var lastCarGrants: Set<String>? = null

  init {
    viewModelScope.launch { reader.connection.collect { c -> _state.update { it.copy(car = c, carApi = reader.carApiInfo()) } } }
    viewModelScope.launch { reader.ux.collect { u -> _state.update { it.copy(ux = u) } } }
    viewModelScope.launch {
      val crash = withContext(Dispatchers.IO) { CrashLog.read(ctx) }
      _state.update { it.copy(lastCrash = crash) }
    }
    refreshStatic()
    reader.connect()
  }

  private val ctx: Context get() = getApplication()

  /** Called from onResume too, so it also notices grants made in Settings while the app was away. */
  fun refreshStatic() {
    refreshStaticAndReconnect()
  }

  /** Returns true when a new car permission grant started a reconnect (which re-reads everything). */
  private fun refreshStaticAndReconnect(): Boolean {
    val newCarGrant = updatePermissions()
    _state.update {
      it.copy(
        build = SystemInfo.build(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        carApi = reader.carApiInfo(),
      )
    }
    viewModelScope.launch {
      val net = withContext(Dispatchers.IO) { SystemInfo.network(ctx) }
      _state.update { it.copy(network = net) }
    }
    // A fresh CarPropertyManager is needed to see the new grant: Android 12L caches its config list.
    if (newCarGrant) reader.reconnect()
    return newCarGrant
  }

  /** Updates the permission list; true when an android.car permission was granted since the last check. */
  private fun updatePermissions(): Boolean {
    val snap = AppPermissions.snapshot(ctx)
    _state.update { it.copy(permissions = snap) }
    val carGrants = snap.filter { it.granted && it.permission.startsWith(CarPerm.PREFIX) }.map { it.permission }.toSet()
    val previous = lastCarGrants
    lastCarGrants = carGrants
    return previous != null && !previous.containsAll(carGrants)
  }

  fun refreshAll() {
    if (refreshStaticAndReconnect()) return // the reconnect re-reads UX restrictions and properties when ready
    reader.refreshUx()
    refreshVhal()
  }

  private var vhalPending = false

  fun refreshVhal() {
    if (_state.value.vhalLoading) {
      vhalPending = true // e.g. a reconnect after a permission grant while a read is running
      return
    }
    _state.update { it.copy(vhalLoading = true) }
    viewModelScope.launch {
      val snap = withContext(Dispatchers.IO) { reader.readAll() }
      _state.update { it.copy(vhal = snap, vhalLoading = false, permissions = AppPermissions.snapshot(ctx)) }
      refreshLive()
      if (vhalPending) {
        vhalPending = false
        refreshVhal()
      }
    }
  }

  /** 1 s refresh of the energy panel; runs only while the screen is started (see MainActivity). */
  suspend fun liveLoop() {
    while (true) {
      refreshLive()
      delay(1_000)
    }
  }

  private suspend fun refreshLive() {
    val rows = withContext(Dispatchers.IO) { reader.readLive() }
    _state.update { it.copy(live = rows, liveUpdatedAtMs = System.currentTimeMillis()) }
  }

  fun setDisplay(kvs: List<KV>) = _state.update { it.copy(display = kvs) }

  fun setComposeDisplay(kvs: List<KV>) {
    if (_state.value.composeDisplay != kvs) _state.update { it.copy(composeDisplay = kvs) }
  }

  /** After the permission dialog: re-read, and reconnect so the car service sees new grants. */
  fun onPermissionsResult(anyNewlyGranted: Boolean) {
    val newCarGrant = updatePermissions()
    if (anyNewlyGranted || newCarGrant) reader.reconnect() else refreshVhal()
  }

  private var adapterTest: AdapterLinkTest? = null
  private var bleJob: Job? = null

  /** The main screen's driving state, once [followRestriction] has it. */
  private var restriction: StateFlow<Boolean> = MutableStateFlow(false)
  private val restricted: Boolean get() = restriction.value

  /**
   * Whatever runs here ends when [state] turns on, and nothing new starts under it, even from a tap
   * handled before the screen has redrawn without its button. Once per view model; an activity
   * recreated for a configuration change passes the same state.
   */
  fun followRestriction(state: StateFlow<Boolean>) {
    if (restriction === state) return
    restriction = state
    viewModelScope.launch { state.collect { if (it) stopRunningWork() } }
  }

  /** The screen that shows them is gone (restricted, or another app in front). */
  fun stopRunningWork() {
    adapterTest?.cancel()
    bleJob?.cancel()
    if (_state.value.bleRunning) _state.update { it.copy(bleRunning = false, bleLive = emptyList()) }
  }

  fun clearLastCrash() {
    CrashLog.clear(ctx)
    _state.update { it.copy(lastCrash = null) }
  }

  // Adapter link test ------------------------------------------------------------------------

  fun setAdapterHost(v: String) {
    _state.update { it.copy(adapterHost = v.trim()) }
    prefs.edit { putString(KEY_HOST, v.trim()) }
  }

  fun setAdapterPort(v: String) {
    val digits = v.filter { it.isDigit() }.take(5)
    _state.update { it.copy(adapterPort = digits) }
    prefs.edit { putString(KEY_PORT, digits) }
  }

  fun setAdapterBindWifi(v: Boolean) {
    _state.update { it.copy(adapterBindWifi = v) }
    prefs.edit { putBoolean(KEY_BIND_WIFI, v) }
  }

  fun runAdapterTest() {
    if (restricted) return
    val s = _state.value
    if (s.adapterRunning) return
    val port = s.adapterPort.toIntOrNull()
    if (s.adapterHost.isBlank() || port == null || port !in 1..65535) {
      _state.update { it.copy(adapterError = "Enter a host and a port between 1 and 65535") }
      return
    }
    val factory: SocketFactory = if (s.adapterBindWifi) {
      val net = SystemInfo.wifiNetwork(ctx)
      if (net == null) {
        _state.update { it.copy(adapterError = "No Wi-Fi network to bind to (turn off \"Bind to Wi-Fi\" to use default routing)") }
        return
      }
      net.socketFactory
    } else {
      SocketFactory.getDefault()
    }
    _state.update { it.copy(adapterRunning = true, adapterError = null, adapterResult = null) }
    val test = AdapterLinkTest(s.adapterHost, port, factory)
    adapterTest = test
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { test.run() }
      _state.update { it.copy(adapterRunning = false, adapterResult = result) }
    }
  }

  // BLE --------------------------------------------------------------------------------------

  fun runBleScan() {
    if (restricted) return
    if (_state.value.bleRunning) return
    _state.update { it.copy(bleRunning = true, bleLive = emptyList(), bleResult = null) }
    bleJob = viewModelScope.launch {
      val result = ble.scan(10_000) { live -> _state.update { it.copy(bleLive = live) } }
      _state.update { it.copy(bleRunning = false, bleResult = result, permissions = AppPermissions.snapshot(ctx)) }
    }
  }

  // Report -----------------------------------------------------------------------------------

  fun setReportUrl(v: String) {
    _state.update { it.copy(reportUrl = v.trim()) }
    prefs.edit { putString(KEY_URL, v.trim()) }
  }

  fun buildReport(mx: MxReport? = null): String =
    Report.build(_state.value, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, mx)

  /** [mx]: the OBDLink MX+ link part (from LiveViewModel), included when given. */
  fun sendReport(mx: MxReport? = null) {
    if (restricted) return
    if (_state.value.reportSending) return
    _state.update { it.copy(reportSending = true, reportStatus = "Sending…") }
    viewModelScope.launch {
      val (json, res) = withContext(Dispatchers.IO) {
        val j = buildReport(mx)
        j to ReportSender.post(_state.value.reportUrl, j)
      }
      val msg = "${if (res.ok) "Sent" else "Failed"}: ${res.message} (${json.length / 1024} KiB, ${res.durationMs} ms)"
      _state.update { it.copy(reportSending = false, reportStatus = msg) }
    }
  }

  override fun onCleared() {
    reader.disconnect()
  }

  private companion object {
    const val KEY_HOST = "adapter_host"
    const val KEY_PORT = "adapter_port"
    const val KEY_BIND_WIFI = "adapter_bind_wifi"
    const val KEY_URL = "report_url"
  }
}
