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
    Def(Manifest.permission.BLUETOOTH_SCAN, true, "find the OBDLink MX+ (Classic discovery); BLE scan"),
    Def(Manifest.permission.BLUETOOTH_CONNECT, true, "pair with and connect to the OBDLink MX+ only"),
    Def(Manifest.permission.INTERNET, false, "Wi-Fi adapter link test (TCP), report upload"),
    Def(Manifest.permission.ACCESS_NETWORK_STATE, false, "network diagnostics"),
    Def(Manifest.permission.ACCESS_WIFI_STATE, false, "Wi-Fi diagnostics"),
    Def(Manifest.permission.CHANGE_WIFI_STATE, false, "reserved for the Wi-Fi transport test"),
    Def(Manifest.permission.CHANGE_NETWORK_STATE, false, "reserved for the Wi-Fi transport test"),
  )

  /** The runtime permissions the Request button asks for. */
  val RUNTIME_REQUESTABLE: List<String> = listOf(
    CarPerm.CAR_ENERGY,
    CarPerm.CAR_SPEED,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
  )

  /**
   * [missing], one list per system dialog: the two Bluetooth ones share "Nearby devices", and each
   * car permission gets a dialog of its own (Android groups them under car information and location).
   */
  fun dialogGroups(missing: List<String>): List<List<String>> =
    missing.groupBy { if (it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT) Manifest.permission.BLUETOOTH_CONNECT else it }.values.toList()

  fun isGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  fun snapshot(context: Context): List<PermissionState> = defs.map {
    PermissionState(it.name, it.name.substringAfterLast('.'), it.runtime, it.purpose, isGranted(context, it.name))
  }

  fun missingRuntime(context: Context): List<String> = RUNTIME_REQUESTABLE.filterNot { isGranted(context, it) }
}
