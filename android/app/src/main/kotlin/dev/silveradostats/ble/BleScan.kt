package dev.silveradostats.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Passive-listing BLE scan: low-latency scan for a fixed time, then stop.
 * Scan only. The app never connects, pairs or bonds to anything it finds.
 */
class BleScan(context: Context) {
  private val app = context.applicationContext

  data class Device(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val connectable: Boolean,
    val seen: Int,
  )

  data class Result(
    val ok: Boolean,
    val error: String?,
    val devices: List<Device>,
    val durationMs: Long,
  )

  /** Scans for [durationMs]; [onUpdate] gets the device list about twice a second. */
  @SuppressLint("MissingPermission") // BLUETOOTH_SCAN is checked explicitly below.
  suspend fun scan(durationMs: Long = 10_000, onUpdate: (List<Device>) -> Unit = {}): Result = withContext(Dispatchers.Main) {
    val t0 = SystemClock.elapsedRealtime()
    fun fail(msg: String) = Result(false, msg, emptyList(), SystemClock.elapsedRealtime() - t0)

    if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      return@withContext fail("This device reports no Bluetooth LE feature")
    }
    if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
      return@withContext fail("BLUETOOTH_SCAN permission not granted (use Request permissions above)")
    }
    val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
      ?: return@withContext fail("No Bluetooth adapter")
    if (!adapter.isEnabled) return@withContext fail("Bluetooth is off")
    val scanner = adapter.bluetoothLeScanner ?: return@withContext fail("BLE scanner unavailable (Bluetooth off?)")

    val found = LinkedHashMap<String, Device>()
    var failedCode: Int? = null
    fun add(r: ScanResult) {
      val addr = r.device?.address ?: return
      val prev = found[addr]
      val rec = r.scanRecord
      found[addr] = Device(
        address = addr,
        name = rec?.deviceName ?: prev?.name,
        rssi = r.rssi,
        serviceUuids = rec?.serviceUuids?.map { it.toString() } ?: prev?.serviceUuids.orEmpty(),
        connectable = r.isConnectable,
        seen = (prev?.seen ?: 0) + 1,
      )
    }
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
      override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
      override fun onScanFailed(errorCode: Int) {
        failedCode = errorCode
      }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try {
      scanner.startScan(null, settings, callback)
    } catch (e: SecurityException) {
      return@withContext fail("startScan refused: ${e.message}")
    } catch (e: IllegalStateException) {
      return@withContext fail("startScan failed: ${e.message}")
    }
    try {
      val end = SystemClock.elapsedRealtime() + durationMs
      while (SystemClock.elapsedRealtime() < end && failedCode == null) {
        delay(500)
        onUpdate(found.values.sortedByDescending { it.rssi })
      }
    } finally {
      try {
        scanner.stopScan(callback)
      } catch (_: Exception) {
      }
    }
    val devices = found.values.sortedByDescending { it.rssi }
    val err = failedCode?.let { "scan failed, error code $it" }
    Result(err == null, err, devices, SystemClock.elapsedRealtime() - t0)
  }
}
