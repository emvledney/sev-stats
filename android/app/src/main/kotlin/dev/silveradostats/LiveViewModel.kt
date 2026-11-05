package dev.silveradostats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silveradostats.bt.BtDevice
import dev.silveradostats.bt.BtUiState
import dev.silveradostats.link.AppSettings
import dev.silveradostats.link.AppTab
import dev.silveradostats.link.LinkConnection
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.UiSettings
import dev.silveradostats.report.MxReport
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.vehicle.DrivingInputs
import dev.silveradostats.vehicle.DrivingMonitor
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.heldWhile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Simple tab's, the Live dashboard's and the MX+ section's view of LinkService. Binds for the
 * ViewModel's lifetime (the built-in session stays attached with another app in front, for as long
 * as Android does not freeze the process, and stops by itself when nothing is visible and the truck
 * is not moving); OBD polling runs only while [setVisible] is true (MainActivity: onStart … onStop)
 * and the selected tab uses OBD data (UiSettings.wantsObd: not Diagnostics while it is a text panel).
 *
 * [restricted] is the one driving state of the main screen. While it is true the screens offer only
 * the Simple / Live tabs, the layout selector and the MX+ button, and every other action here is refused as well
 * ([parkedOnly]), so a stale tap cannot change anything from behind the driving layout.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {
  private val store = AppSettings.get(app)

  private val driving = DrivingMonitor(app, viewModelScope)

  // The tab the app opens on decides before the first onStart whether the MX+ is wanted at all.
  private val conn = LinkConnection(app, viewModelScope, obdWanted = store.state.value.wantsObd(driving.restricted.value))

  /** True while the truck is out of Park, or that cannot be ruled out (fail safe, DrivingPolicy). */
  val restricted: StateFlow<Boolean> = driving.restricted

  /** False while [restricted] is only the fail-safe value: the car service gives no answer. */
  val drivingStateKnown: StateFlow<Boolean> = driving.stateKnown

  /** What the driving state is going by, as last read. */
  val drivingInputs: StateFlow<DrivingInputs> = driving.inputs

  val live: StateFlow<LiveState> = conn.live
  val bt: StateFlow<BtUiState> = conn.bt
  val session: StateFlow<SessionState> = conn.session
  val settings: StateFlow<UiSettings> = store.state

  /** What the screens draw: at most one new set of figures a second while driving. */
  val shownSession: StateFlow<SessionState> =
    conn.session.heldWhile(restricted).stateIn(viewModelScope, SharingStarted.Eagerly, conn.session.value)
  val shownLive: StateFlow<LiveState> =
    conn.live.heldWhile(restricted).stateIn(viewModelScope, SharingStarted.Eagerly, conn.live.value)

  init {
    // Also covers a service that binds after the restriction began.
    viewModelScope.launch {
      combine(conn.service, restricted) { s, r -> s to r }.collect { (s, r) -> s?.bt?.setRestricted(r) }
    }
    // Only a visible tab that uses OBD data attaches the engine: the settings or the restriction can change it.
    viewModelScope.launch {
      combine(store.state, restricted) { s, r -> s.wantsObd(r) }.distinctUntilChanged().collect { conn.setObdWanted(it) }
    }
  }

  private inline fun parkedOnly(action: () -> Unit) {
    if (!restricted.value) action()
  }

  fun setVisible(visible: Boolean) {
    conn.setVisible(visible)
    if (visible) driving.start() else driving.stop()
  }

  /** Selects (and remembers) a tab. */
  fun selectTab(t: AppTab) {
    if (t == AppTab.DIAGNOSTICS && restricted.value) return
    store.setTab(t)
  }

  /** Nothing of this screen is visible: a search or link test it started has nobody to show it to. */
  fun onLeftScreen() {
    conn.service.value?.bt?.onLeftScreen()
  }

  /** "Reset session": distance, energy and charts start again from now. */
  fun resetSession() = parkedOnly {
    conn.service.value?.vehicle?.reset()
  }

  fun retry() = parkedOnly {
    conn.service.value?.engine?.retry()
  }

  /**
   * Disconnect (true) / Connect (false): the MX+ stays free for other devices until Connect, app
   * restarts included. Allowed while driving: the phone may need Bluetooth back on the road.
   */
  fun setMxOff(off: Boolean) = store.setMxOff(off)

  fun setTempUnit(u: TempUnit) = parkedOnly { store.setTempUnit(u) }

  fun setDistUnit(u: DistUnit) = parkedOnly { store.setDistUnit(u) }

  fun setDemo(on: Boolean) = parkedOnly { store.setDemo(on) }

  fun refreshBluetooth() = parkedOnly {
    conn.service.value?.bt?.refresh()
  }

  /**
   * After a permission request or on resume: Bluetooth state, a link stopped for the missing
   * permission, and the built-in session (car energy / speed grants).
   */
  fun onPermissionsChanged() {
    conn.service.value?.bt?.onPermissionsChanged()
    conn.service.value?.vehicle?.onPermissionsChanged()
  }

  fun choose(d: BtDevice) = parkedOnly {
    conn.service.value?.bt?.choose(d.address, d.name)
  }

  fun forgetAdapter() = parkedOnly {
    conn.service.value?.bt?.forget()
  }

  fun startPairing() = parkedOnly {
    conn.service.value?.bt?.startPairing()
  }

  fun stopDiscovery() {
    conn.service.value?.bt?.stopDiscovery()
  }

  fun pair(d: BtDevice) = parkedOnly {
    conn.service.value?.bt?.pair(d.address)
  }

  fun useUnpaired(d: BtDevice) = parkedOnly {
    conn.service.value?.bt?.useUnpaired(d.address, d.name)
  }

  fun testLink() = parkedOnly {
    conn.service.value?.bt?.testLink()
  }

  fun reportData(): MxReport = MxReport(settings.value, bt.value, live.value, session.value)

  override fun onCleared() {
    driving.close()
    conn.close()
  }
}
