package dev.silveradostats

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.log.UploadPolicy
import dev.silveradostats.upload.UploadState
import dev.silveradostats.upload.Uploader
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
  private val reader = VehicleReader(app) { viewModelScope.launch { refreshLive() } }

  private val _state = MutableStateFlow(DiagState())
  val state: StateFlow<DiagState> = _state.asStateFlow()

  /** android.car permissions granted at the last check (null before the first check). */
  private var lastCarGrants: Set<String>? = null

  init {
    viewModelScope.launch { reader.connection.collect { c -> _state.update { it.copy(car = c) } } }
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

  /** 1 s refresh of the energy panel; runs only while the screen is started (see MainActivity). */
  suspend fun liveLoop() {
    while (true) {
      refreshLive()
      delay(1_000)
    }
  }

  private suspend fun refreshLive() {
    val rows = withContext(Dispatchers.IO) { reader.readLive() }
    _state.update { it.copy(live = rows) }
  }

  /** After the permission dialog: re-read, and reconnect so the car service sees new grants. */
  fun onPermissionsResult(anyNewlyGranted: Boolean) {
    val newCarGrant = updatePermissions()
    if (anyNewlyGranted || newCarGrant) reader.reconnect()
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

  // Upload -----------------------------------------------------------------------------------

  private val uploader = Uploader.get(app)

  /** The upload of the log to the owner's server, for the Settings page. */
  val upload: StateFlow<UploadState> = uploader.state

  /** Pairs with the code the server's dashboard shows. */
  fun pairUpload(code: String) {
    if (restricted) return
    if (uploader.state.value.busy || !UploadPolicy.codeComplete(code)) return
    viewModelScope.launch { uploader.pair(code) }
  }

  fun uploadNow() {
    if (restricted) return
    if (uploader.state.value.busy) return
    viewModelScope.launch { uploader.run() }
  }

  fun unpairUpload() {
    if (restricted) return
    uploader.unpair()
  }

  override fun onCleared() {
    reader.disconnect()
  }
}
