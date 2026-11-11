package dev.silveradostats.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import dev.silveradostats.BuildConfig
import dev.silveradostats.R
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.link.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the session of the truck's built-in data running with no screen of the app up, by the owner's
 * choice (2026-11-06): a drive with Maps in front is measured whole, and a charge is seen as soon as the
 * head unit is up. It has to be a foreground service, or Android freezes the process within minutes.
 *
 * Read-only, and the truck's API only: all it does is attach to the session (VehicleSession.setWatching).
 * It never touches the OBD link, so nothing goes out to the MX+ because of it. It holds no wake lock, so it
 * runs while the head unit is up and not at all while it sleeps. It has no screen, and without the
 * notification permission, which the app does not ask for, Android 13 and later show no notification for it.
 * Not with demo data: a simulated drive has nothing to measure.
 */
class VehicleWatchService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val token = Any()
  private var session: VehicleSession? = null

  override fun onCreate() {
    super.onCreate()
    // No logcat on the truck, and with the app closed no activity has done this.
    CrashLog.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    try {
      startForeground(NOTIFICATION_ID, notification())
    } catch (_: IllegalStateException) {
      // Android restarting it from the background may refuse: the next boot or app start brings it back.
      stopSelf()
      return
    }
    val s = SessionHub.get(this)
    session = s
    s.attach(token)
    s.setWatching(true)
    scope.launch {
      AppSettings.get(this@VehicleWatchService).state.map { it.demo }.distinctUntilChanged().collect { demo -> if (demo) stopSelf() }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    scope.cancel()
    session?.let {
      it.setWatching(false)
      it.detach(token)
    }
    super.onDestroy()
  }

  private fun notification(): Notification {
    val channel = NotificationChannel(CHANNEL_ID, getString(R.string.watch_channel), NotificationManager.IMPORTANCE_MIN)
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    return Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(getString(R.string.watch_text))
      .setCategory(Notification.CATEGORY_SERVICE)
      .setOngoing(true)
      .build()
  }

  private companion object {
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "vehicle_watch"
  }
}

/** Starts [VehicleWatchService] where it has something to read. This file is the app's only service start. */
object VehicleWatch {
  /** Main thread. Android allows it from a visible screen and at boot; anywhere else a refusal just means not now. */
  fun start(context: Context) {
    val app = context.applicationContext
    if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) || AppSettings.get(app).state.value.demo) return
    try {
      app.startForegroundService(Intent(app, VehicleWatchService::class.java))
    } catch (_: IllegalStateException) {
      // Refused: the next boot or app start tries again.
    }
  }
}

/** Boot, and an update from Play (which ends the process): the service is back without opening the app. */
class VehicleWatchStarter : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) VehicleWatch.start(context)
  }
}
