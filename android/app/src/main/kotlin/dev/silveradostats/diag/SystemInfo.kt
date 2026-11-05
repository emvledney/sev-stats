package dev.silveradostats.diag

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.view.WindowInsets
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** Build, display and network facts. Read-only; no identifiers (serial, MAC, VIN) are collected. */
object SystemInfo {

  fun build(appId: String, versionName: String, versionCode: Int): List<KV> = listOf(
    KV("App", "$appId $versionName ($versionCode)"),
    KV("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"),
    KV("Security patch", Build.VERSION.SECURITY_PATCH ?: "?"),
    KV("Build.DISPLAY", Build.DISPLAY ?: "?"),
    KV("Build.ID", Build.ID ?: "?"),
    KV("Build.VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL ?: "?"),
    KV("Build type / tags", "${Build.TYPE} / ${Build.TAGS}"),
    KV("Fingerprint", Build.FINGERPRINT ?: "?"),
    KV("Manufacturer / brand", "${Build.MANUFACTURER} / ${Build.BRAND}"),
    KV("Model / device / product", "${Build.MODEL} / ${Build.DEVICE} / ${Build.PRODUCT}"),
    KV("Hardware / board", "${Build.HARDWARE} / ${Build.BOARD}"),
    KV("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"),
    KV("Supported ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
  )

  /** Window metrics and insets, measured from the Activity. */
  fun display(activity: Activity): List<KV> {
    val out = mutableListOf<KV>()
    try {
      val wm = activity.windowManager
      val cur = wm.currentWindowMetrics
      val max = wm.maximumWindowMetrics
      val dm = activity.resources.displayMetrics
      val cfg = activity.resources.configuration
      val density = dm.density
      val b = cur.bounds
      out += KV("Window bounds (px)", "${b.width()} x ${b.height()}  ${rect(b)}")
      out += KV("Window size (dp)", f("%.0f x %.0f dp", b.width() / density, b.height() / density))
      out += KV("Max window bounds (px)", "${max.bounds.width()} x ${max.bounds.height()}")
      out += KV("Density", f("%d dpi (scale %.2f)", dm.densityDpi, density))
      out += KV("Configuration (dp)", "screen ${cfg.screenWidthDp} x ${cfg.screenHeightDp}, smallest width ${cfg.smallestScreenWidthDp}")
      out += KV("Font scale", f("%.2f", cfg.fontScale))
      out += KV("Orientation", if (cfg.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape")
      val uiType = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK
      val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
      out += KV("UI mode", "${if (uiType == Configuration.UI_MODE_TYPE_CAR) "car" else "type $uiType"}, night=$night")
      val wi = cur.windowInsets
      out += KV("Insets: system bars (px)", insets(wi.getInsets(WindowInsets.Type.systemBars())))
      out += KV("Insets: status bars (px)", insets(wi.getInsets(WindowInsets.Type.statusBars())))
      out += KV("Insets: navigation bars (px)", insets(wi.getInsets(WindowInsets.Type.navigationBars())))
      out += KV("Insets: display cutout (px)", insets(wi.getInsets(WindowInsets.Type.displayCutout())))
      val cutout = wi.displayCutout
      out += KV(
        "Display cutout",
        if (cutout == null) "none" else "safe insets ${cutout.safeInsetLeft},${cutout.safeInsetTop},${cutout.safeInsetRight},${cutout.safeInsetBottom}; rects ${cutout.boundingRects.joinToString { rect(it) }}",
      )
      val d = activity.display
      if (d != null) {
        val mode = d.mode
        out += KV("Display", "#${d.displayId} \"${d.name}\"")
        out += KV("Display mode (physical px)", f("%d x %d @ %.1f Hz", mode.physicalWidth, mode.physicalHeight, mode.refreshRate))
      }
    } catch (e: Exception) {
      out += KV("Display error", "${e.javaClass.simpleName}: ${e.message}")
    }
    return out
  }

  fun network(context: Context): List<KV> {
    val out = mutableListOf<KV>()
    // Interfaces and addresses (java.net; MAC addresses are not read).
    try {
      val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      if (ifaces.isEmpty()) out += KV("Interfaces", "none visible")
      for (ni in ifaces.sortedBy { it.name }) {
        val addrs = ni.interfaceAddresses.mapNotNull { ia ->
          val a = ia.address ?: return@mapNotNull null
          val host = a.hostAddress ?: return@mapNotNull null
          val fam = if (a is Inet4Address) "v4" else "v6"
          "$fam $host/${ia.networkPrefixLength}"
        }
        val flags = buildList {
          add(if (ni.isUp) "up" else "down")
          if (ni.isLoopback) add("loopback")
          if (ni.isPointToPoint) add("p2p")
          if (ni.isVirtual) add("virtual")
        }
        out += KV("if ${ni.name}", "${flags.joinToString(",")}; ${if (addrs.isEmpty()) "no addresses" else addrs.joinToString("; ")}")
      }
    } catch (e: Exception) {
      out += KV("Interfaces error", "${e.javaClass.simpleName}: ${e.message}")
    }
    // ConnectivityManager view.
    try {
      val cm = context.getSystemService(ConnectivityManager::class.java)
      val active = cm.activeNetwork
      out += KV("Active network", if (active == null) "none" else describe(cm, active))
      @Suppress("DEPRECATION")
      val all = cm.allNetworks
      all.forEachIndexed { i, n ->
        if (n != active) out += KV("Network #${i + 1}", describe(cm, n))
      }
    } catch (e: Exception) {
      out += KV("Connectivity error", "${e.javaClass.simpleName}: ${e.message}")
    }
    try {
      val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
      out += KV("Wi-Fi enabled", wifi?.isWifiEnabled?.toString() ?: "no Wi-Fi service")
    } catch (e: Exception) {
      out += KV("Wi-Fi error", "${e.javaClass.simpleName}: ${e.message}")
    }
    return out
  }

  private fun describe(cm: ConnectivityManager, n: Network): String {
    val caps: NetworkCapabilities? = cm.getNetworkCapabilities(n)
    val lp: LinkProperties? = cm.getLinkProperties(n)
    val transports = buildList {
      if (caps == null) return@buildList
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("WIFI_AWARE")
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("USB")
    }
    val capsText = buildList {
      if (caps == null) return@buildList
      if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
      if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
      if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("not-metered")
    }
    val iface = lp?.interfaceName ?: "?"
    val addrs = lp?.linkAddresses?.joinToString(", ") { it.toString() } ?: ""
    val gw = lp?.routes?.filter { it.isDefaultRoute }?.mapNotNull { it.gateway?.hostAddress }?.joinToString(", ").orEmpty()
    val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ").orEmpty()
    return "transports ${transports.ifEmpty { listOf("?") }.joinToString("+")}; caps ${capsText.joinToString(",")}; " +
      "iface $iface; addrs $addrs; gateway ${gw.ifEmpty { "-" }}; dns ${dns.ifEmpty { "-" }}"
  }

  private fun f(pattern: String, vararg args: Any?) = String.format(Locale.US, pattern, *args)

  private fun rect(r: Rect) = "[${r.left},${r.top},${r.right},${r.bottom}]"

  private fun insets(i: Insets) = "left ${i.left}, top ${i.top}, right ${i.right}, bottom ${i.bottom}"
}
