package dev.silveradostats.link

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import dev.silveradostats.bt.BtController
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
 * only this app's own screens can reach it. Polling runs only while a visible screen is attached
 * (LinkConnection attaches on onStart and detaches on onStop). Without a visible screen nothing is
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

  override fun onCreate() {
    super.onCreate()
    settings = AppSettings.get(this)
    val profile = assets.open(PROFILE_ASSET).use { Profile.fromJson(it.readBytes().decodeToString(), PROFILE_ASSET) }
    engine = LinkEngine(profile)
    bt = BtController(this, engine, settings)
    vehicle = SessionHub.get(this)
    scope.launch {
      settings.state.map { Triple(it.demo, it.mxAddress, it.mxName) }.distinctUntilChanged().collect { (demo, address, name) ->
        engine.setSource(
          when {
            demo -> LinkSource.Demo
            address != null -> bt.sourceFor(address, name)
            else -> null
          },
        )
      }
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
  }
}
