package dev.silveradostats

import dev.silveradostats.diag.KV
import dev.silveradostats.diag.PermissionState
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
  val reportUrl: String = DEFAULT_REPORT_URL,
  val reportSending: Boolean = false,
  val reportStatus: String? = null,
  /** filesDir/last_crash.txt from a previous run (see CrashLog), or null. */
  val lastCrash: String? = null,
) {
  companion object {
    /** Placeholder: the user edits it to the laptop's address on a hotspot both the head unit and the laptop are on. */
    const val DEFAULT_REPORT_URL = "http://10.0.0.2:8766/report"
  }
}
