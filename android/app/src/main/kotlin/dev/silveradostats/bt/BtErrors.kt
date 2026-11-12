package dev.silveradostats.bt

import dev.silveradostats.link.ConnectFailure
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * Pure Kotlin parts of the Bluetooth transport (no android.* imports): user-facing error texts,
 * name filters, address masking and the bounded connect. Unit tested on the JVM.
 */

/** User-facing texts for Bluetooth problems with the OBDLink MX+. */
object BtErrors {
  const val UNREACHABLE = "MX+ not reachable: is your phone connected to it? Turn the phone's Bluetooth off."
  const val UNREACHABLE_HINT = "Also check that the MX+ is plugged in and awake (press its button)."
  const val NO_BLUETOOTH = "This device has no Bluetooth, so the MX+ can't be used here (demo data still works)."
  const val BLUETOOTH_OFF = "Bluetooth is off on this screen: turn it on in the system settings."
  const val NO_CONNECT_PERMISSION =
    "Bluetooth permission (\"Nearby devices\") not granted: Settings › Request runtime permissions."
  const val NO_SCAN_PERMISSION =
    "Bluetooth scan permission (\"Nearby devices\") not granted: Settings › Request runtime permissions."
  const val PAIRING_STALE = "Pairing failed or is out of date: remove the MX+ under Bluetooth settings and pair it again."
  const val SERVICE_NOT_FOUND = "The MX+ serial service was not found: is this really the OBDLink MX+? Pair it again."

  /**
   * Maps a failed RFCOMM connect to a [ConnectFailure] with a clear message.
   * [timedOut] = our own connect deadline passed (the socket was closed to abort it).
   */
  fun connectFailure(e: Throwable?, timedOut: Boolean): ConnectFailure {
    if (timedOut) return ConnectFailure("$UNREACHABLE (no answer within ${CONNECT_TIMEOUT_MS / 1000} s) $UNREACHABLE_HINT", true, e)
    if (e is SecurityException) return ConnectFailure(NO_CONNECT_PERMISSION, false, e)
    val m = (e?.message ?: "").lowercase(Locale.ROOT)
    return when {
      "permission" in m -> ConnectFailure(NO_CONNECT_PERMISSION, false, e)
      "auth" in m || "pin or key" in m || "key missing" in m || "pairing" in m -> ConnectFailure(PAIRING_STALE, false, e)
      "service discovery" in m || "sdp" in m || "no service" in m -> ConnectFailure(SERVICE_NOT_FOUND, true, e)
      "bluetooth is off" in m || "adapter is off" in m || "not enabled" in m -> ConnectFailure(BLUETOOTH_OFF, true, e)
      // Android's generic RFCOMM failures: page timeout, host down, refused, busy, our own close().
      "timeout" in m || "timed out" in m || "host is down" in m || "read failed" in m || "refused" in m ||
        "busy" in m || "socket closed" in m || "reset" in m || "unreachable" in m || "no route" in m || m.isEmpty() ->
        ConnectFailure("$UNREACHABLE $UNREACHABLE_HINT", true, e)
      else -> ConnectFailure("$UNREACHABLE (${e?.message}) $UNREACHABLE_HINT", true, e)
    }
  }

  /** How long one RFCOMM connect attempt may take before it is aborted. */
  const val CONNECT_TIMEOUT_MS = 10_000L

  /** Names discovery keeps (case-insensitive): "OBDLink MX+", "OBDII", ... */
  fun isObdName(name: String?): Boolean {
    if (name == null) return false
    val n = name.lowercase(Locale.ROOT)
    return "obdlink" in n || "obd" in n
  }

  /** "00:04:3E:12:34:56" -> "00:04:3E:XX:XX:XX" (vendor part kept) for reports. */
  fun maskAddress(address: String?): String? {
    if (address == null) return null
    val parts = address.split(':')
    if (parts.size != 6) return "XX:XX:XX:XX:XX:XX"
    return parts.take(3).joinToString(":") + ":XX:XX:XX"
  }

  /** Masks every Bluetooth address in [text] (used for text going into the report). */
  fun maskAddressesIn(text: String): String =
    ADDRESS.replace(text) { maskAddress(it.value) ?: it.value }

  private val ADDRESS = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

  /** Connect to an adapter that is not paired failed (only an unauthenticated link was tried). */
  fun unpairedConnectFailure(e: Throwable?, timedOut: Boolean): ConnectFailure {
    val base = connectFailure(e, timedOut)
    return ConnectFailure(
      "Couldn't connect without pairing: ${base.message} " +
        "The adapter may insist on pairing; try pairing it in the truck's Bluetooth settings.",
      retryable = base.retryable,
      e,
    )
  }

  /** android.bluetooth.device.extra.REASON values sent with ACTION_BOND_STATE_CHANGED (AOSP). */
  fun bondFailReason(code: Int): String = when (code) {
    1 -> "authentication failed"
    2 -> "rejected by the other side"
    3 -> "cancelled"
    4 -> "adapter didn't answer"
    5 -> "discovery was still running"
    6 -> "timed out waiting for confirmation"
    7 -> "too many attempts"
    8 -> "cancelled by the adapter"
    9 -> "pairing removed"
    else -> "no reason given"
  }

}

/**
 * Runs a blocking connect with a deadline (BluetoothSocket.connect has none of its own).
 *
 * [connect] runs on its own daemon thread; if it hasn't finished after [timeoutMs], [abort] is
 * called (closing the socket makes connect() fail) and [Timeout] is thrown. An exception thrown by
 * [connect] is rethrown as-is.
 */
object TimedConnect {
  class Timeout(val timeoutMs: Long) : IOException("connect timed out after $timeoutMs ms")

  fun run(timeoutMs: Long, threadName: String = "bt-connect", connect: () -> Unit, abort: () -> Unit) {
    val done = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>(null)
    val t = Thread({
      try {
        connect()
      } catch (e: Throwable) {
        failure.set(e)
      } finally {
        done.countDown()
      }
    }, threadName)
    t.isDaemon = true
    t.start()
    val finished = try {
      done.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      runCatching(abort)
      throw e
    }
    if (!finished) {
      runCatching(abort)
      throw Timeout(timeoutMs)
    }
    failure.get()?.let { throw it }
  }
}
