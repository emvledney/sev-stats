package dev.silveradostats

import dev.silveradostats.diag.PermissionState
import dev.silveradostats.vehicle.CarConnState
import dev.silveradostats.vehicle.PropertyRow

/** Everything the Settings screen shows. */
data class DiagState(
  val permissions: List<PermissionState> = emptyList(),
  val car: CarConnState = CarConnState(CarConnState.Phase.CONNECTING, "Starting"),
  /** The car's energy properties, read every second while the screen is up: the Expert page's API figures. */
  val live: List<PropertyRow> = emptyList(),
  /** filesDir/last_crash.txt from a previous run (see CrashLog), or null. */
  val lastCrash: String? = null,
)
