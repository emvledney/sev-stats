package dev.silveradostats

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
  private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val reader = VehicleReader(app) { refreshVhal() }

  private val _state = MutableStateFlow(
    DiagState(
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

  /** The main screen's driving state, once [followRestriction] has it. */
  private var restriction: StateFlow<Boolean> = MutableStateFlow(false)
  private val restricted: Boolean get() = restriction.value

  /**
   * Nothing starts here while [state] is on, even from a tap handled before the screen has redrawn
   * without its button. An activity recreated for a configuration change passes the same state.
   */
  fun followRestriction(state: StateFlow<Boolean>) {
    restriction = state
  }

  fun clearLastCrash() {
    CrashLog.clear(ctx)
    _state.update { it.copy(lastCrash = null) }
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
    const val KEY_URL = "report_url"
  }
}
