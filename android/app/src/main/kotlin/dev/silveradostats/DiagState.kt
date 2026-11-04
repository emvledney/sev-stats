package dev.silveradostats

import dev.silveradostats.adapter.AdapterLinkTest
import dev.silveradostats.ble.BleScan
import dev.silveradostats.diag.KV
import dev.silveradostats.diag.PermissionState
import dev.silveradostats.link.LinkCheckResult
import dev.silveradostats.vehicle.CarConnState
import dev.silveradostats.vehicle.PropertyRow
import dev.silveradostats.vehicle.UxInfo
import dev.silveradostats.vehicle.VhalSnapshot

/** Everything the Diagnostics screen shows (and the report contains). */
data class DiagState(
  val permissions: List<PermissionState> = emptyList(),
  val car: CarConnState = CarConnState(CarConnState.Phase.CONNECTING, "Starting"),
  val carApi: List<KV> = emptyList(),
  val ux: UxInfo? = null,
  val live: List<PropertyRow> = emptyList(),
  val liveUpdatedAtMs: Long = 0,
  val vhal: VhalSnapshot? = null,
  val vhalLoading: Boolean = false,
  val build: List<KV> = emptyList(),
  val display: List<KV> = emptyList(),
  val composeDisplay: List<KV> = emptyList(),
  val network: List<KV> = emptyList(),
  val adapterHost: String = AdapterLinkTest.DEFAULT_HOST,
  val adapterPort: String = AdapterLinkTest.DEFAULT_PORT.toString(),
  val adapterBindWifi: Boolean = false,
  val adapterRunning: Boolean = false,
  val adapterResult: LinkCheckResult? = null,
  val adapterError: String? = null,
  val bleRunning: Boolean = false,
  val bleLive: List<BleScan.Device> = emptyList(),
  val bleResult: BleScan.Result? = null,
  val reportUrl: String = DEFAULT_REPORT_URL,
  val reportSending: Boolean = false,
  val reportStatus: String? = null,
  /** filesDir/last_crash.txt from a previous run (see CrashLog), or null. */
  val lastCrash: String? = null,
) {
  companion object {
    /** Placeholder: the user edits it to the laptop's address on the truck hotspot. */
    const val DEFAULT_REPORT_URL = "http://10.0.0.2:8766/report"
  }
}
