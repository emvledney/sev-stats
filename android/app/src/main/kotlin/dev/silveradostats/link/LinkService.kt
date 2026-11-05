package dev.silveradostats.link

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import dev.silveradostats.bt.BtController
import dev.silveradostats.log.LogHub
import dev.silveradostats.obd.Profile
import dev.silveradostats.vehicle.SessionHub
import dev.silveradostats.vehicle.VehicleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the OBD link: the transport (OBDLink MX+ over Bluetooth, or the simulator in demo mode),
 * the poller on one background thread ([LinkEngine]) and the Bluetooth pairing flow ([BtController]).
 *
 * Bound only, never started, and not exported (the manifest declares android:exported="false"), so
 * only this app's own code can reach it. Polling runs only while a visible screen is attached
 * (LinkConnection attaches on onStart and detaches on onStop) or during the log's hourly visit with the
 * truck on (LogHub.visit, through the same LinkConnection), which keeps one row of what is read. Without a visible screen nothing is
 * sent; the socket stays open, silent, for at most [LinkEngine.GRACE_MS] in case a screen comes
 * back (glance <-> dashboard), then closes and releases the MX+. When the last client
 * unbinds, the service is destroyed and the socket is closed at once.
 *
 * It also hands the screens the session of the truck's built-in data ([vehicle], the process's
 * SessionHub instance): sampled while any screen is bound, independent of the OBD link. Bound is not
 * enough on its own: Android freezes the process once none of its screens is visible (the
 * cached-app freezer, within 10 s to about 6 min on API 34), so a drive is measured while the main
 * screen or Battery Glance is on screen, and a frozen stretch becomes a gap the session leaves out.
 */
class LinkService : Service() {
  inner class LocalBinder : Binder() {
    val service: LinkService get() = this@LinkService
  }

  private val binder = LocalBinder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  lateinit var settings: AppSettings
    private set
  lateinit var engine: LinkEngine
    private set
  lateinit var bt: BtController
    private set
  lateinit var vehicle: VehicleSession
    private set
  private lateinit var logs: LogHub

  override fun onCreate() {
    super.onCreate()
    settings = AppSettings.get(this)
    val profile = assets.open(PROFILE_ASSET).use { Profile.fromJson(it.readBytes().decodeToString(), PROFILE_ASSET) }
    engine = LinkEngine(profile)
    bt = BtController(this, engine, settings)
    vehicle = SessionHub.get(this)
    logs = LogHub.get(this)
    scope.launch {
      settings.state.map { LinkChoice(it.demo, it.mxAddress, it.mxName, it.mxOff) }.distinctUntilChanged().collect { c ->
        // First: a source must never start connecting before it knows it is disconnected.
        engine.setDisconnected(c.mxOff)
        engine.setSource(
          when {
            c.demo -> LinkSource.Demo
            c.address != null -> bt.sourceFor(c.address, c.name)
            else -> null
          },
        )
      }
    }
    scope.launch { engine.state.collect { passOn(it) } }
    scope.launch { engine.state.collect { logs.onLive(it) } }
  }

  private data class LinkChoice(val demo: Boolean, val address: String?, val name: String?, val mxOff: Boolean)

  private var packReadAtMs = 0L

  /** What a real, connected MX+ delivers beyond its own screens: pack power for the session, capacity to keep. */
  private fun passOn(s: LiveState) {
    if (s.status != LinkStatus.CONNECTED || s.demo) return
    val current = s.values["pack_current"]
    val kw = (s.derived["power_kw"] as? Number)?.toDouble()
    if (current != null && kw != null && current.atMs != packReadAtMs) {
      packReadAtMs = current.atMs
      vehicle.addPackPower(kw)
    }
    val kwh = (s.derived["capacity_kwh"] as? Number ?: s.derived["bms_energy_at_100_kwh"] as? Number)?.toDouble()
    val ah = (s.derived["capacity_ah"] as? Number ?: s.derived["ah_nominal_est"] as? Number)?.toDouble()
    val saved = settings.state.value.savedCapacity
    if (kwh != null && (saved == null || saved.kwh != kwh || saved.ah != ah) && (saved == null || System.currentTimeMillis() - saved.atWallMs >= SAVE_CAPACITY_MS)) {
      settings.setSavedCapacity(SavedCapacity(kwh, ah, System.currentTimeMillis()))
    }
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onUnbind(intent: Intent?): Boolean {
    // Last client gone: stop polling and close the socket (the MX+ is free for another host).
    engine.detachAll()
    bt.stopDiscovery()
    return false
  }

  override fun onDestroy() {
    scope.cancel()
    bt.close()
    engine.close()
    super.onDestroy()
  }

  private companion object {
    const val PROFILE_ASSET = "profile.json"

    /** The last capacity read is written at most this often. */
    const val SAVE_CAPACITY_MS = 60_000L
  }
}
