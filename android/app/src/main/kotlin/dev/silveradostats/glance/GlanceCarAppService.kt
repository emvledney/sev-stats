package dev.silveradostats.glance

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import dev.silveradostats.BuildConfig
import dev.silveradostats.R
import dev.silveradostats.diag.CrashLog

/**
 * Battery Glance as a Car App Library app (category IOT): the template-only screen, which also stays
 * if GM ever blocks the main one while driving. The system's Templates Host connects to this service
 * (it is exported for that, see the manifest), and CarAppActivity shows what the host renders: a
 * PaneTemplate with 4 fixed rows ([GlanceScreen]).
 *
 * The app talks to nothing here but its own LinkService (through LinkConnection, while the screen
 * is visible). The Car App Library's car-hardware and climate APIs are never used (SourceSafetyTest
 * forbids them).
 */
class GlanceCarAppService : CarAppService() {
  override fun onCreate() {
    // The glance can start the process without MainActivity: record crashes here too.
    CrashLog.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    super.onCreate()
  }

  /**
   * Which hosts may connect. Debug builds: any host (the library's advice, for emulators and
   * testing). Release builds: hosts holding the system's template-renderer permission (the
   * Builder's default) plus the Android Automotive Templates Host by package name and signing
   * certificate (res/values/car_app_hosts.xml, taken from the library's sample list as the
   * CarAppService docs say). Nothing else can bind.
   */
  override fun createHostValidator(): HostValidator =
    if (BuildConfig.DEBUG) {
      HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
      HostValidator.Builder(applicationContext)
        .addAllowedHosts(R.array.car_app_hosts_allowlist)
        .build()
    }

  override fun onCreateSession(sessionInfo: SessionInfo): Session = GlanceSession()
}

/** One connection to the host: always the single glance screen (no screen stack, no deep links). */
class GlanceSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen = GlanceScreen(carContext)
}
