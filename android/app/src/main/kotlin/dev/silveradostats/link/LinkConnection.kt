package dev.silveradostats.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.silveradostats.bt.BtUiState
import dev.silveradostats.vehicle.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.io.Closeable

/**
 * The app's only service binding: this app's own, non-exported [LinkService], by explicit class.
 * (SourceSafetyTest allows binding in this file only, and only in this exact form.)
 *
 * Binds for its whole lifetime (a ViewModel's, or Battery Glance's screen) so the engine keeps its
 * history while the app is in the background. Two attachments, independent of each other:
 *  - the OBD engine: only while [setVisible] is true AND the screen wants OBD ([setObdWanted]), so the
 *    MX+ is polled only while a screen that uses its data is on (UiSettings.wantsObd);
 *  - the built-in vehicle session (LinkService.vehicle): for the whole binding, visible or not, so
 *    sampling goes on behind another app for as long as Android lets the process run (it froze an
 *    app with no visible screen after 10 s to about 6 min on API 34: the main screen or Battery Glance
 *    on screen keeps it measuring). With no visible screen and the truck not moving, VehicleSession
 *    stops sampling by itself.
 * Main thread only.
 */
class LinkConnection(context: Context, scope: CoroutineScope, obdWanted: Boolean = true) : Closeable {
  private val app = context.applicationContext
  private val token = Any()
  private var visible = false
  private var obd = obdWanted
  private var engineAttached = false
  private var closed = false
  private val _service = MutableStateFlow<LinkService?>(null)
  val service: StateFlow<LinkService?> = _service.asStateFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  val live: StateFlow<LiveState> = _service
    .flatMapLatest { it?.engine?.state ?: flowOf(STARTING) }
    .stateIn(scope, SharingStarted.Eagerly, STARTING)

  @OptIn(ExperimentalCoroutinesApi::class)
  val session: StateFlow<SessionState> = _service
    .flatMapLatest { it?.vehicle?.state ?: flowOf(SessionState()) }
    .stateIn(scope, SharingStarted.Eagerly, SessionState())

  @OptIn(ExperimentalCoroutinesApi::class)
  val bt: StateFlow<BtUiState> = _service
    .flatMapLatest { it?.bt?.state ?: flowOf(BtUiState()) }
    .stateIn(scope, SharingStarted.Eagerly, BtUiState())

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      val s = (binder as? LinkService.LocalBinder)?.service ?: return
      if (closed) return
      _service.value = s
      engineAttached = false
      s.vehicle.attach(token)
      s.vehicle.setVisible(token, visible)
      syncEngine()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      _service.value = null
      engineAttached = false
    }
  }

  private val bound: Boolean = app.bindService(Intent(app, LinkService::class.java), connection, Context.BIND_AUTO_CREATE)

  /** True while a screen of the app is visible (onStart … onStop). */
  fun setVisible(v: Boolean) {
    visible = v
    _service.value?.vehicle?.setVisible(token, v)
    syncEngine()
  }

  /** Whether the visible screen uses OBD data (UiSettings.wantsObd; Battery Glance does). */
  fun setObdWanted(w: Boolean) {
    obd = w
    syncEngine()
  }

  /** Attaches to the OBD engine exactly while visible and wanted; never re-sends an attach. */
  private fun syncEngine() {
    val s = _service.value ?: return
    val want = visible && obd && !closed
    if (want == engineAttached) return
    engineAttached = want
    if (want) s.engine.attach(token) else s.engine.detach(token)
  }

  override fun close() {
    if (closed) return
    closed = true
    _service.value?.let {
      if (engineAttached) it.engine.detach(token)
      it.vehicle.detach(token)
    }
    engineAttached = false
    _service.value = null
    if (bound) app.unbindService(connection)
  }

  companion object {
    val STARTING = LiveState(status = LinkStatus.CONNECTING, message = "Starting the link service…")
  }
}
