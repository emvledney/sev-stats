package dev.silveradostats.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** The main screen's tabs, in order. Only Live and Diagnostics need the OBD link (the MX+). Map uses neither the link nor the car API. */
enum class AppTab(val label: String, val needsObd: Boolean) {
  SIMPLE("Simple", false),
  LIVE("Live", true),
  MAP("Map", false),
  DIAGNOSTICS("Diagnostics", true),
  ;

  /** Whether the OBD link is wanted. While restricted Diagnostics is a text panel with no OBD data. */
  fun wantsObd(restricted: Boolean): Boolean = needsObd && !(this == DIAGNOSTICS && restricted)
}

/** The Simple tab's layout: A "Efficiency" (trip efficiency first) or B "Battery" (battery first). */
enum class SimpleLayout(val label: String) {
  EFFICIENCY("Efficiency"),
  BATTERY("Battery"),
}

/** The persisted user choices the dashboard and the link use. */
data class UiSettings(
  /** Use the simulator instead of the MX+ (a DEMO DATA banner is shown). */
  val demo: Boolean,
  val tempUnit: TempUnit,
  val distUnit: DistUnit,
  /** The chosen OBDLink MX+ (Bluetooth address and name), or null. */
  val mxAddress: String?,
  val mxName: String?,
  /** The last selected tab (the app reopens on it). */
  val tab: AppTab = AppTab.SIMPLE,
  val simpleLayout: SimpleLayout = SimpleLayout.EFFICIENCY,
)

/** SharedPreferences-backed settings, one instance per process. Call from the main thread. */
class AppSettings private constructor(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _state = MutableStateFlow(load(context.applicationContext))
  val state: StateFlow<UiSettings> = _state.asStateFlow()

  private fun load(app: Context): UiSettings {
    val locale = Locale.getDefault()
    return UiSettings(
      demo = if (prefs.contains(KEY_DEMO)) prefs.getBoolean(KEY_DEMO, false) else defaultDemo(app),
      tempUnit = prefs.getString(KEY_TEMP, null)?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() } ?: Units.defaultTemp(locale),
      distUnit = prefs.getString(KEY_DIST, null)?.let { runCatching { DistUnit.valueOf(it) }.getOrNull() } ?: Units.defaultDist(locale),
      mxAddress = prefs.getString(KEY_MX_ADDRESS, null),
      mxName = prefs.getString(KEY_MX_NAME, null),
      tab = prefs.getString(KEY_TAB, null)?.let { runCatching { AppTab.valueOf(it) }.getOrNull() } ?: AppTab.SIMPLE,
      simpleLayout = prefs.getString(KEY_SIMPLE_LAYOUT, null)?.let { runCatching { SimpleLayout.valueOf(it) }.getOrNull() }
        ?: SimpleLayout.EFFICIENCY,
    )
  }

  fun setTab(t: AppTab) {
    prefs.edit { putString(KEY_TAB, t.name) }
    _state.update { it.copy(tab = t) }
  }

  fun setSimpleLayout(l: SimpleLayout) {
    prefs.edit { putString(KEY_SIMPLE_LAYOUT, l.name) }
    _state.update { it.copy(simpleLayout = l) }
  }

  fun setDemo(v: Boolean) {
    prefs.edit { putBoolean(KEY_DEMO, v) }
    _state.update { it.copy(demo = v) }
  }

  fun setTempUnit(u: TempUnit) {
    prefs.edit { putString(KEY_TEMP, u.name) }
    _state.update { it.copy(tempUnit = u) }
  }

  fun setDistUnit(u: DistUnit) {
    prefs.edit { putString(KEY_DIST, u.name) }
    _state.update { it.copy(distUnit = u) }
  }

  /** Remembers the chosen adapter (null forgets it). */
  fun setAdapter(address: String?, name: String?) {
    prefs.edit {
      if (address == null) remove(KEY_MX_ADDRESS) else putString(KEY_MX_ADDRESS, address)
      if (name == null) remove(KEY_MX_NAME) else putString(KEY_MX_NAME, name)
    }
    _state.update { it.copy(mxAddress = address, mxName = name) }
  }

  companion object {
    private const val KEY_DEMO = "demo_mode"
    private const val KEY_TEMP = "temp_unit"
    private const val KEY_DIST = "distance_unit"
    private const val KEY_MX_ADDRESS = "mx_address"
    private const val KEY_MX_NAME = "mx_name"
    private const val KEY_TAB = "last_tab"
    private const val KEY_SIMPLE_LAYOUT = "simple_layout"

    @Volatile private var instance: AppSettings? = null

    fun get(context: Context): AppSettings =
      instance ?: synchronized(this) { instance ?: AppSettings(context).also { instance = it } }

    /** Demo data defaults to ON on non-car devices and emulators, OFF on a real head unit. */
    fun defaultDemo(context: Context): Boolean {
      val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
      return !automotive || isEmulator()
    }

    fun isEmulator(): Boolean {
      val hw = Build.HARDWARE.orEmpty()
      val product = Build.PRODUCT.orEmpty()
      val fingerprint = Build.FINGERPRINT.orEmpty()
      return hw == "ranchu" || hw == "goldfish" || hw.startsWith("vbox") ||
        fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
        product.startsWith("sdk") || product.contains("_sdk") || product.contains("emulator") ||
        Build.MODEL.orEmpty().contains("Emulator")
    }
  }
}
