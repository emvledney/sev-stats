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
import dev.silveradostats.link.MxAction
import dev.silveradostats.link.UiSettings
import dev.silveradostats.log.LogHub
import dev.silveradostats.report.MxReport
import dev.silveradostats.vehicle.DemoDrive
import dev.silveradostats.vehicle.DrivingInputs
import dev.silveradostats.vehicle.DrivingMonitor
import dev.silveradostats.vehicle.LastCharge
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
 * ViewModel's lifetime (the built-in session stays attached with another app in front; VehicleWatchService
 * is what keeps it sampling then); OBD polling runs only while [setVisible] is true (MainActivity: onStart … onStop)
 * and the selected tab uses OBD data (UiSettings.wantsObd: not Diagnostics while it is a text panel).
 *
 * [restricted] is the one driving state of the main screen. Simple's header works the same in every
 * gear, by the owner's choice: its tabs, Reset session and the MX+ button. Beyond that,
 * while it is true Live hides its controls and Trends and Diagnostics are one text panel, and every
 * other action here is refused as well ([parkedOnly]), so a stale tap cannot start anything from
 * behind the driving state.
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

  /** The last charge the Simple tab shows: the demo's in demo mode, which is never saved. */
  val lastCharge: StateFlow<LastCharge?> =
    combine(LogHub.get(app).lastCharge, store.state, shownSession) { c, s, session -> if (s.demo) DemoDrive.lastCharge(session.distanceM) else c }
      .stateIn(viewModelScope, SharingStarted.Eagerly, if (store.state.value.demo) DemoDrive.LAST_CHARGE else LogHub.get(app).lastCharge.value)

  /** What the MX+ button offers now, from the same figures the screens draw. */
  val mxAction: StateFlow<MxAction> =
    combine(store.state, shownLive) { s, l -> MxAction.of(s.mxOff, l) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, MxAction.of(store.state.value.mxOff, shownLive.value))

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
    store.setTab(t)
  }

  /** Nothing of this screen is visible: a search or link test it started has nobody to show it to. */
  fun onLeftScreen() {
    conn.service.value?.bt?.onLeftScreen()
  }

  /** "Reset session": distance, energy and charts start again from now. */
  fun resetSession() {
    conn.service.value?.vehicle?.reset()
  }

  fun retry() = parkedOnly {
    conn.service.value?.engine?.retry()
  }

  /**
   * The MX+ button, given the action it showed so a tap does what its label said. Cancel and Disconnect
   * leave the MX+ free for other devices until Connect, app restarts included. Allowed while driving:
   * the phone may need Bluetooth back on the road, and a dropped link can be brought back.
   */
  fun onMxAction(action: MxAction) {
    when (action) {
      MxAction.CONNECT -> if (store.state.value.mxOff) store.setMxOff(false) else conn.service.value?.engine?.retry()
      MxAction.CANCEL, MxAction.DISCONNECT -> store.setMxOff(true)
    }
  }

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
