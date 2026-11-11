package dev.silveradostats.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The main screen's tabs, in order. Only Live and Diagnostics show the OBD link's data (the MX+).
 * Map uses neither the link nor the car API.
 */
enum class AppTab(val label: String, val needsObd: Boolean) {
  SIMPLE("Simple", false),
  LIVE("Live", true),
  TRENDS("Trends", false),
  MAP("Map", false),
  DIAGNOSTICS("Diagnostics", true),
  ;

  /** Whether the OBD link is wanted. While restricted Diagnostics is a text panel with no OBD data. */
  fun wantsObd(restricted: Boolean): Boolean = needsObd && !(this == DIAGNOSTICS && restricted)
}

/** The persisted user choices the dashboard and the link use. */
data class UiSettings(
  /** Use the simulator instead of the MX+. */
  val demo: Boolean,
  /** The chosen OBDLink MX+ (Bluetooth address and name), or null. */
  val mxAddress: String?,
  val mxName: String?,
  /** The last selected tab (the app reopens on it). */
  val tab: AppTab = AppTab.SIMPLE,
  /** The user disconnected the MX+: the app leaves it alone until they tap Connect. */
  val mxOff: Boolean = false,
  val savedCapacity: SavedCapacity? = null,
) {
  /**
   * Whether the OBD link is wanted: on the tabs that show it, and on Simple too with a chosen MX+ that is
   * not disconnected, because its pack power is what the session's energy is best measured from. The same
   * on Trends in Park, so that the MX+ keeps logging while its log is being read.
   */
  fun wantsObd(restricted: Boolean): Boolean {
    val mxUsable = mxAddress != null && !mxOff && !demo
    return tab.wantsObd(restricted) || (tab == AppTab.SIMPLE && mxUsable) || (tab == AppTab.TRENDS && !restricted && mxUsable)
  }
}

/** SharedPreferences-backed settings, one instance per process. Call from the main thread. */
class AppSettings private constructor(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _state = MutableStateFlow(load(context.applicationContext))
  val state: StateFlow<UiSettings> = _state.asStateFlow()

  private fun load(app: Context): UiSettings {
    return UiSettings(
      demo = if (prefs.contains(KEY_DEMO)) prefs.getBoolean(KEY_DEMO, false) else defaultDemo(app),
      mxAddress = prefs.getString(KEY_MX_ADDRESS, null),
      mxName = prefs.getString(KEY_MX_NAME, null),
      tab = prefs.getString(KEY_TAB, null)?.let { runCatching { AppTab.valueOf(it) }.getOrNull() } ?: AppTab.SIMPLE,
      mxOff = prefs.getBoolean(KEY_MX_OFF, false),
      savedCapacity = prefs.getLong(KEY_CAPACITY_AT, 0L).takeIf { it > 0 }?.let { at ->
        SavedCapacity(prefs.getString(KEY_CAPACITY_KWH, null)?.toDoubleOrNull(), prefs.getString(KEY_CAPACITY_AH, null)?.toDoubleOrNull(), at)
      },
    )
  }

  fun setMxOff(off: Boolean) {
    prefs.edit { putBoolean(KEY_MX_OFF, off) }
    _state.update { it.copy(mxOff = off) }
  }

  fun setSavedCapacity(c: SavedCapacity) {
    prefs.edit {
      if (c.kwh == null) remove(KEY_CAPACITY_KWH) else putString(KEY_CAPACITY_KWH, c.kwh.toString())
      if (c.ah == null) remove(KEY_CAPACITY_AH) else putString(KEY_CAPACITY_AH, c.ah.toString())
      putLong(KEY_CAPACITY_AT, c.atWallMs)
    }
    _state.update { it.copy(savedCapacity = c) }
  }

  fun setTab(t: AppTab) {
    prefs.edit { putString(KEY_TAB, t.name) }
    _state.update { it.copy(tab = t) }
  }

  fun setDemo(v: Boolean) {
    prefs.edit { putBoolean(KEY_DEMO, v) }
    _state.update { it.copy(demo = v) }
  }

  /** Remembers the chosen adapter (null forgets it). Choosing one also ends a disconnect. */
  fun setAdapter(address: String?, name: String?) {
    prefs.edit {
      remove(KEY_MX_OFF)
      if (address == null) remove(KEY_MX_ADDRESS) else putString(KEY_MX_ADDRESS, address)
      if (name == null) remove(KEY_MX_NAME) else putString(KEY_MX_NAME, name)
    }
    _state.update { it.copy(mxAddress = address, mxName = name, mxOff = false) }
  }

  companion object {
    private const val KEY_DEMO = "demo_mode"
    private const val KEY_MX_ADDRESS = "mx_address"
    private const val KEY_MX_NAME = "mx_name"
    private const val KEY_TAB = "last_tab"
    private const val KEY_MX_OFF = "mx_off"
    private const val KEY_CAPACITY_KWH = "capacity_kwh"
    private const val KEY_CAPACITY_AH = "capacity_ah"
    private const val KEY_CAPACITY_AT = "capacity_at"

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
