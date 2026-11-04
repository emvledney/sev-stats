package dev.silveradostats.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.silveradostats.link.ConnectFailure
import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.StreamByteLink
import java.io.IOException
import java.util.UUID

/** A Bluetooth device as the Diagnostics screen lists it. */
data class BtDevice(val address: String, val name: String?, val bonded: Boolean) {
  val obdLike: Boolean get() = BtErrors.isObdName(name)
}

enum class BtAvailability { NO_BLUETOOTH, NO_PERMISSION, OFF, READY }

/**
 * Bluetooth Classic access for the OBDLink MX+ (serial port profile over RFCOMM).
 *
 * This file and the rest of the bt package are the only places allowed to pair (createBond),
 * discover (startDiscovery) and open RFCOMM sockets; SourceSafetyTest enforces it. Nothing here
 * touches BLE GATT, other Bluetooth profiles, server sockets or the adapter's own settings.
 * The socket's streams go straight into the OBD core's [StreamByteLink]: only ElmLink writes to them.
 */
class BtClassic(context: Context) {
  private val app = context.applicationContext
  private val adapter: BluetoothAdapter? = try {
    app.getSystemService(BluetoothManager::class.java)?.adapter
  } catch (_: Exception) {
    null
  }

  val hasBluetooth: Boolean =
    adapter != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

  fun canConnect(): Boolean = granted(Manifest.permission.BLUETOOTH_CONNECT)

  fun canScan(): Boolean = granted(Manifest.permission.BLUETOOTH_SCAN)

  private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  fun availability(): Pair<BtAvailability, String> {
    val a = adapter
    return when {
      a == null || !hasBluetooth -> BtAvailability.NO_BLUETOOTH to BtErrors.NO_BLUETOOTH
      !canConnect() -> BtAvailability.NO_PERMISSION to BtErrors.NO_CONNECT_PERMISSION
      !isEnabled(a) -> BtAvailability.OFF to BtErrors.BLUETOOTH_OFF
      else -> BtAvailability.READY to "Bluetooth ready"
    }
  }

  private fun isEnabled(a: BluetoothAdapter): Boolean = try {
    a.isEnabled
  } catch (_: SecurityException) {
    false
  }

  /** Devices already paired with this head unit (phones too), OBD-looking ones first. */
  @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked explicitly.
  fun bonded(): List<BtDevice> {
    val a = adapter ?: return emptyList()
    if (!canConnect()) return emptyList()
    return try {
      a.bondedDevices.orEmpty()
        .map { BtDevice(it.address, it.name, bonded = true) }
        .sortedWith(compareByDescending<BtDevice> { it.obdLike }.thenBy { it.name ?: "" })
    } catch (_: SecurityException) {
      emptyList()
    }
  }

  @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked explicitly.
  fun nameOf(device: BluetoothDevice): String? = if (!canConnect()) null else try {
    device.name
  } catch (_: SecurityException) {
    null
  }

  /** Starts Classic discovery (the caller stops it after 30 s). Returns an error text or null. */
  @SuppressLint("MissingPermission") // BLUETOOTH_SCAN checked explicitly.
  fun startDiscovery(): String? {
    val a = adapter ?: return BtErrors.NO_BLUETOOTH
    if (!canScan()) return BtErrors.NO_SCAN_PERMISSION
    if (!canConnect()) return BtErrors.NO_CONNECT_PERMISSION
    if (!isEnabled(a)) return BtErrors.BLUETOOTH_OFF
    return try {
      if (a.isDiscovering) a.cancelDiscovery()
      if (a.startDiscovery()) null else "The system refused to start Bluetooth discovery; try again in a moment."
    } catch (e: SecurityException) {
      BtErrors.NO_SCAN_PERMISSION
    }
  }

  @SuppressLint("MissingPermission") // BLUETOOTH_SCAN checked explicitly.
  fun cancelDiscovery() {
    val a = adapter ?: return
    if (!canScan()) return
    try {
      a.cancelDiscovery()
    } catch (_: SecurityException) {
    }
  }

  /** Starts pairing; the system shows its own pairing dialog. Returns an error text or null. */
  @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked explicitly.
  fun createBond(address: String): String? {
    val a = adapter ?: return BtErrors.NO_BLUETOOTH
    if (!canConnect()) return BtErrors.NO_CONNECT_PERMISSION
    if (!BluetoothAdapter.checkBluetoothAddress(address)) return "Not a Bluetooth address: $address"
    return try {
      cancelDiscovery() // pairing while discovering is slow and flaky
      val dev = a.getRemoteDevice(address)
      when {
        dev.bondState == BluetoothDevice.BOND_BONDED -> null
        dev.createBond() -> null
        else -> "The system refused to start pairing; press the MX+ button and try again."
      }
    } catch (e: SecurityException) {
      BtErrors.NO_CONNECT_PERMISSION
    }
  }

  /**
   * Opens the serial channel to a paired adapter and wraps it for the OBD core. Blocking (run it
   * off the main thread). Tries the secure SPP socket first, then the insecure one, within an
   * overall deadline of [timeoutMs]; failures become [ConnectFailure] with a clear message.
   */
  @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT / BLUETOOTH_SCAN checked explicitly.
  fun connect(address: String, timeoutMs: Long = BtErrors.CONNECT_TIMEOUT_MS): ByteLink {
    val a = adapter ?: throw ConnectFailure(BtErrors.NO_BLUETOOTH, retryable = false)
    if (!canConnect()) throw ConnectFailure(BtErrors.NO_CONNECT_PERMISSION, retryable = false)
    if (!isEnabled(a)) throw ConnectFailure(BtErrors.BLUETOOTH_OFF, retryable = true)
    if (!BluetoothAdapter.checkBluetoothAddress(address)) throw ConnectFailure("Not a Bluetooth address: $address", retryable = false)
    val dev = try {
      a.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
      throw ConnectFailure("Not a Bluetooth address: $address", retryable = false, e)
    }
    val bond = try {
      dev.bondState
    } catch (e: SecurityException) {
      throw BtErrors.connectFailure(e, timedOut = false)
    }
    cancelDiscovery() // discovery slows RFCOMM connects down a lot
    if (bond != BluetoothDevice.BOND_BONDED) {
      // Not paired (some head units refuse to pair non-phone devices): try an unauthenticated
      // serial link only. A secure socket would start pairing, which is exactly what fails there.
      val only = attempt(dev, secure = false, timeoutMs)
      only.socket?.let { return wrap(it) }
      throw BtErrors.unpairedConnectFailure(only.error, only.timedOut)
    }

    val t0 = SystemClock.elapsedRealtime()
    val first = attempt(dev, secure = true, timeoutMs)
    first.socket?.let { return wrap(it) }
    val remaining = timeoutMs - (SystemClock.elapsedRealtime() - t0)
    if (!first.timedOut && first.error !is SecurityException && remaining >= MIN_FALLBACK_MS) {
      // Some adapters only accept an unauthenticated RFCOMM link.
      val second = attempt(dev, secure = false, remaining)
      second.socket?.let { return wrap(it) }
      throw BtErrors.connectFailure(second.error ?: first.error, second.timedOut)
    }
    throw BtErrors.connectFailure(first.error, first.timedOut)
  }

  private class Attempt(val socket: BluetoothSocket?, val error: Throwable?, val timedOut: Boolean)

  @SuppressLint("MissingPermission") // only called from connect(), after the permission check.
  private fun attempt(dev: BluetoothDevice, secure: Boolean, timeoutMs: Long): Attempt {
    val socket = try {
      if (secure) dev.createRfcommSocketToServiceRecord(SPP_UUID) else dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
    } catch (e: IOException) {
      return Attempt(null, e, false)
    } catch (e: SecurityException) {
      return Attempt(null, e, false)
    }
    return try {
      TimedConnect.run(timeoutMs, "mxplus-connect", connect = { socket.connect() }, abort = { socket.close() })
      Attempt(socket, null, false)
    } catch (e: TimedConnect.Timeout) {
      closeQuietly(socket)
      Attempt(null, e, true)
    } catch (e: IOException) {
      closeQuietly(socket)
      Attempt(null, e, false)
    } catch (e: SecurityException) {
      closeQuietly(socket)
      Attempt(null, e, false)
    }
  }

  private fun wrap(s: BluetoothSocket): ByteLink = try {
    StreamByteLink(s.inputStream, s.outputStream, s, "mxplus-reader")
  } catch (e: IOException) {
    closeQuietly(s)
    throw BtErrors.connectFailure(e, timedOut = false)
  }

  private fun closeQuietly(s: BluetoothSocket) {
    try {
      s.close()
    } catch (_: IOException) {
    }
  }

  companion object {
    /** Serial Port Profile. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** The insecure fallback runs only if at least this much of the deadline is left. */
    private const val MIN_FALLBACK_MS = 2_000L
  }
}
