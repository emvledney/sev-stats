package dev.silveradostats.link

/*
 * Pure Kotlin (no android.* imports): the link state the Live dashboard and Diagnostics show.
 * Unit tested on the JVM.
 */

/** Monotonic milliseconds (never jumps with the wall clock). Value ages are measured with it. */
object MonoClock {
  fun ms(): Long = System.nanoTime() / 1_000_000
}

/** What the OBD link is doing, as shown in the status chip. */
enum class LinkStatus {
  /** No adapter chosen yet (and demo mode off). */
  NO_ADAPTER,

  /** The user disconnected the MX+: nothing connects to it until they tap Connect. */
  DISCONNECTED,

  /** Bluetooth discovery or pairing in progress (Diagnostics › Pair new). */
  PAIRING,

  /** Opening the Bluetooth connection and running the adapter setup. */
  CONNECTING,

  /** Polling the truck through the MX+. */
  CONNECTED,

  /** The Bluetooth connection could not be opened (MX+ asleep, out of range, or held by a phone). */
  ADAPTER_UNREACHABLE,

  /** The truck stopped answering: the poller is bus-silent (or has stopped). */
  TRUCK_ASLEEP,

  /** A transport error stopped polling; Retry re-runs the full connect / setup. */
  STOPPED_ERROR,

  /** Simulated truck (demo mode). */
  DEMO,
}

/** One decoded OBD value and when it was read ([atMs] on [MonoClock]). */
data class LiveValue(
  val name: String,
  val label: String,
  val v: Any?,
  val unit: String,
  /** Profile status: "confirmed", "unverified", ... */
  val status: String,
  /** Profile poll period of this value, seconds. */
  val periodS: Double,
  val atMs: Long,
) {
  val confirmed: Boolean get() = status == "confirmed"
}

/** One point of the power history: kW, + discharge / - charge or regen. */
data class PowerPoint(val atMs: Long, val kw: Double)

/** The last capacity the MX+ delivered, kept for when it is not connected. */
data class SavedCapacity(val kwh: Double?, val ah: Double?, val atWallMs: Long)

/** Everything the LinkService publishes. Times are [MonoClock] milliseconds. */
data class LiveState(
  val status: LinkStatus = LinkStatus.NO_ADAPTER,
  val message: String = "",
  val demo: Boolean = false,
  /** "OBDLink MX+ (00:04:3E:…)" or the simulator; plus the adapter's identity once known. */
  val adapter: String? = null,
  val adapterVolts: Double? = null,
  val adapterVoltsAtMs: Long? = null,
  /** Latest value of every field read so far in this source's sessions. */
  val values: Map<String, LiveValue> = emptyMap(),
  /** Derive.derive() of the latest values (power_kw, cell spread, BMS energy at 100 %, ...). */
  val derived: Map<String, Any> = emptyMap(),
  /** When the last sample with at least one value arrived. */
  val lastDataAtMs: Long? = null,
  /** Power history, oldest first, at most [PowerHistory.WINDOW_MS] long. */
  val power: List<PowerPoint> = emptyList(),
  /** Per-DID errors of the last cycle that had any (NRC / no answer). */
  val errors: Map<String, String> = emptyMap(),
  /** A Retry action is offered (re-runs the full connect / setup). */
  val canRetry: Boolean = false,
  /** When the next automatic reconnect attempt is due, if one is scheduled. */
  val nextAutoRetryAtMs: Long? = null,
  /** A session is open and the poller is running. */
  val polling: Boolean = false,
  /** Number of visible UI clients (polling runs only while > 0). */
  val clients: Int = 0,
) {
  /** Age of the newest data, or null before the first sample. */
  fun dataAgeMs(nowMs: Long): Long? = lastDataAtMs?.let { nowMs - it }
}
