package dev.silveradostats

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.silveradostats.diag.AppPermissions
import dev.silveradostats.diag.CrashLog
import dev.silveradostats.diag.SystemInfo
import dev.silveradostats.ui.AppShell
import dev.silveradostats.ui.theme.SilveradoStatsTheme
import dev.silveradostats.vehicle.CarPerm
import kotlinx.coroutines.launch

/**
 * Simple, Live dashboard and Diagnostics tabs. Marked distraction-optimized (see the manifest for the
 * trade-off), so it stays on screen out of Park and switches itself to its restricted driving
 * layouts (LiveViewModel.restricted). Measuring continues while this activity or Battery Glance is on
 * screen; with another app in front Android freezes the process within seconds to minutes.
 *
 * No permission dialog and no Settings screen is ever opened while restricted. A request asks one
 * dialog at a time and stops when the restriction begins. Known limit: a dialog already open then
 * stays up (Android gives an app no way to close the permission controller's activity); see the
 * manifest.
 */
class MainActivity : ComponentActivity() {
  private val vm: DiagnosticsViewModel by viewModels()
  private val liveVm: LiveViewModel by viewModels()

  /** The running request came from the Simple tab, whose only way to a grant is that button. */
  private var carOnlyRequest = false

  /** The dialogs the running request has yet to open (AppPermissions.dialogGroups). */
  private var pendingDialogs: List<List<String>> = emptyList()

  /** Something in the running request is denied for good. */
  private var deniedForGood = false

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      vm.onPermissionsResult(anyNewlyGranted = result.values.any { it })
      // The dialog only pauses this activity (no onStop / onStart): refresh the MX+ side here.
      liveVm.onPermissionsChanged()
      // Once denied twice Android answers "denied" without asking: only Settings can grant it now.
      if (result.any { (permission, granted) -> !granted && !shouldShowRequestPermissionRationale(permission) }) deniedForGood = true
      // An empty result: the dialog was dismissed, or could not be shown. Nothing more to ask then.
      if (result.isEmpty()) pendingDialogs = emptyList()
      if (!askNextDialog() && carOnlyRequest && deniedForGood) openAppSettings()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    // No logcat on the truck: keep the last crash in a file the next launch shows and reports.
    CrashLog.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      SilveradoStatsTheme {
        AppShell(
          liveVm = liveVm,
          diagVm = vm,
          onRequestPermissions = { requestRuntimePermissions(carOnly = false) },
          onRequestCarPermissions = { requestRuntimePermissions(carOnly = true) },
          onOpenAppSettings = ::openAppSettings,
        )
      }
    }
    // Whatever Diagnostics had running ends with the restriction, seen from any tab.
    vm.followRestriction(liveVm.restricted)
    // Live energy values: 1 s refresh only while the screen is visible.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) { vm.liveLoop() }
    }
  }

  override fun onStart() {
    super.onStart()
    // OBD polling runs only while a screen of the app is visible (no background polling).
    liveVm.setVisible(true)
  }

  override fun onStop() {
    // A configuration change (rotation, day/night) recreates the activity at once: keep the link
    // instead of closing and re-initialising the adapter for it.
    if (!isChangingConfigurations) {
      liveVm.setVisible(false)
      // The driving state is not watched behind another app, and nothing here would show the result.
      vm.stopRunningWork()
      liveVm.onLeftScreen()
    }
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    vm.refreshStatic()
    // Also notices a "Nearby devices" grant made in Settings while the app was away.
    liveVm.onPermissionsChanged()
    vm.setDisplay(SystemInfo.display(this))
    // Measure again after the first layout pass, when insets are final.
    window.decorView.post { vm.setDisplay(SystemInfo.display(this)) }
  }

  /** [carOnly]: just car energy and speed (the Simple tab), without the Bluetooth ones. */
  private fun requestRuntimePermissions(carOnly: Boolean) {
    if (liveVm.restricted.value) return
    val missing = AppPermissions.missingRuntime(this).filter { !carOnly || it.startsWith(CarPerm.PREFIX) }
    if (missing.isEmpty()) {
      // Everything is already granted (perhaps in Settings): reconnect so the car service reflects it.
      vm.onPermissionsResult(anyNewlyGranted = true)
      liveVm.onPermissionsChanged()
    } else {
      carOnlyRequest = carOnly
      deniedForGood = false
      pendingDialogs = AppPermissions.dialogGroups(missing)
      askNextDialog()
    }
  }

  /**
   * Opens the next dialog of the running request; false when none opened. Each waits for the answer
   * to the one before, so a request does not run on into the restriction: what is left is dropped, and
   * the same button asks again once parked.
   */
  private fun askNextDialog(): Boolean {
    val next = pendingDialogs.firstOrNull()
    if (next == null || liveVm.restricted.value) {
      pendingDialogs = emptyList()
      return false
    }
    pendingDialogs = pendingDialogs.drop(1)
    permissionLauncher.launch(next.toTypedArray())
    return true
  }

  private fun openAppSettings() {
    if (liveVm.restricted.value) return
    try {
      startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    } catch (_: ActivityNotFoundException) {
      // Not every head unit exposes this screen; the on-screen text explains the manual path.
    }
  }
}
