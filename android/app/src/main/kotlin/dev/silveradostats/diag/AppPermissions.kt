package dev.silveradostats.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.silveradostats.vehicle.CarPerm

data class PermissionState(
  val permission: String,
  val shortName: String,
  val runtime: Boolean,
  val purpose: String,
  val granted: Boolean,
)

/** Every permission the app declares, and which ones need a runtime grant. */
object AppPermissions {
  private data class Def(val name: String, val runtime: Boolean, val purpose: String)

  private val defs = listOf(
    Def(CarPerm.CAR_INFO, false, "make, model, year, nominal capacity, connector"),
    Def(CarPerm.CAR_ENERGY, true, "battery level, range, charge rate and state"),
    Def(CarPerm.CAR_ENERGY_PORTS, false, "charge port open / plug connected"),
    Def(CarPerm.CAR_EXTERIOR_ENVIRONMENT, false, "outside temperature, night mode"),
    Def(CarPerm.CAR_POWERTRAIN, false, "gear, ignition, parking brake, regen"),
    Def(CarPerm.CAR_SPEED, true, "vehicle speed"),
    Def(CarPerm.READ_CAR_DISPLAY_UNITS, false, "display units"),
    Def(Manifest.permission.BLUETOOTH_SCAN, true, "find the OBDLink MX+ (Classic discovery)"),
    Def(Manifest.permission.BLUETOOTH_CONNECT, true, "pair with and connect to the OBDLink MX+ only"),
    Def(Manifest.permission.ACCESS_FINE_LOCATION, true, "the truck's position on the Map tab"),
    Def(Manifest.permission.ACCESS_COARSE_LOCATION, true, "asked together with the precise one, as Android requires"),
    Def(Manifest.permission.INTERNET, false, "chargers, place search and road distances (Map); Send to laptop report"),
    Def(Manifest.permission.ACCESS_NETWORK_STATE, false, "network diagnostics"),
    Def(Manifest.permission.ACCESS_WIFI_STATE, false, "the Wi-Fi enabled row under Network"),
  )

  /** The runtime permissions the Request button asks for. */
  val RUNTIME_REQUESTABLE: List<String> = listOf(
    CarPerm.CAR_ENERGY,
    CarPerm.CAR_SPEED,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
  )

  /**
   * [missing], one list per system dialog: the two Bluetooth ones share "Nearby devices", the two
   * location ones share one dialog (Android ignores a request for the precise one alone), and each
   * car permission gets a dialog of its own (Android groups them under car information and location).
   */
  fun dialogGroups(missing: List<String>): List<List<String>> =
    missing.groupBy {
      when (it) {
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT -> Manifest.permission.BLUETOOTH_CONNECT
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> it
      }
    }.values.toList()

  fun isGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  fun snapshot(context: Context): List<PermissionState> = defs.map {
    PermissionState(it.name, it.name.substringAfterLast('.'), it.runtime, it.purpose, isGranted(context, it.name))
  }

  fun missingRuntime(context: Context): List<String> = RUNTIME_REQUESTABLE.filterNot { isGranted(context, it) }
}
