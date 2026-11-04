package dev.silveradostats.obd

/**
 * Hard allowlist for every frame the app can put on the vehicle bus (port of safety.py).
 *
 * Every channel checks its CAN IDs when it is created ([checkAddress]) and every outbound payload
 * before it is transmitted ([checkPhysical] / [checkFunctional]). Anything that is not a pure read
 * to a diagnostic address is rejected here, so a bug elsewhere cannot open a diagnostic session,
 * reset a module, clear codes, write data, run a routine, request security access or talk on a
 * non-diagnostic CAN ID.
 *
 * Allowed:
 *  - physical requests to one module, addressed 0x14DA<ecu>F1 or 0x18DA<ecu>F1:
 *      - UDS 0x22 ReadDataByIdentifier, exactly one 16-bit DID per request;
 *      - SAE J1979 modes 0x03 / 0x07 / 0x0A (read stored / pending / permanent DTCs).
 *  - functional (broadcast 0x18DB33F1) requests: SAE J1979 mode 0x01 (current data) and mode 0x09
 *    (vehicle information), one PID per request.
 *
 * Everything else (0x10, 0x11, 0x14, 0x19, 0x23, 0x27, 0x28, 0x2A, 0x2C, 0x2E, 0x2F, 0x31,
 * 0x34-0x38, 0x3B, 0x3D, 0x3E, 0x83-0x87, OBD 0x04/0x08, 11-bit IDs, any other CAN ID, ...)
 * throws [UnsafeRequestError].
 */
object Safety {
  const val SID_READ_DATA_BY_IDENTIFIER = 0x22
  const val OBD_MODE_CURRENT_DATA = 0x01
  const val OBD_MODE_VEHICLE_INFO = 0x09
  val OBD_DTC_READ_MODES: Set<Int> = setOf(0x03, 0x07, 0x0A)
  val ALLOWED_FUNCTIONAL_MODES: Set<Int> = setOf(OBD_MODE_CURRENT_DATA, OBD_MODE_VEHICLE_INFO)

  /** No request may follow the previous one sooner than this (seconds). */
  const val MIN_INTERVAL_FLOOR_S = 0.05

  /** Default gap between bus requests (seconds). */
  const val DEFAULT_INTERVAL_S = 0.075

  /** Only the Ultium diagnostic addresses: physical 0x14/0x18DA<ecu>F1, functional 0x18DB33F1. */
  fun checkAddress(tx: Long, rx: Long, functional: Boolean) {
    if (functional) {
      if (tx != Addressing.FUNCTIONAL_29 || rx != Addressing.FUNCTIONAL_RESP_29) {
        throw UnsafeRequestError("functional requests only on 0x18DB33F1, not ${hexId(tx)}/${hexId(rx)}")
      }
      return
    }
    val pri = tx shr 24
    val mid = (tx shr 16) and 0xFF
    val ecu = ((tx shr 8) and 0xFF).toInt()
    val tester = (tx and 0xFF).toInt()
    if (tx > 0x1FFFFFFFL || (pri != 0x14L && pri != 0x18L) || mid != 0xDAL || tester != Addressing.TESTER) {
      throw UnsafeRequestError("${hexId(tx)} is not a physical diagnostic request ID (0x14/0x18DA<ecu>F1)")
    }
    if (ecu == 0x33 || ecu == Addressing.TESTER || tx == rx || rx != Addressing.ids(ecu, pri.toInt()).second) {
      throw UnsafeRequestError("response ID ${hexId(rx)} does not pair with request ${hexId(tx)}")
    }
  }

  /** Allow only `22 hi lo` (one DID) or a bare OBD DTC read (03/07/0A). */
  fun checkPhysical(payload: ByteArray) {
    if (payload.size == 1 && u8(payload[0]) in OBD_DTC_READ_MODES) return
    if (payload.size == 3 && u8(payload[0]) == SID_READ_DATA_BY_IDENTIFIER) return
    throw UnsafeRequestError(
      "physical request ${hexSpaced(payload).ifEmpty { "<empty>" }} is not allowed (only '22 <DID>' or OBD 03/07/0A)",
    )
  }

  /** Allow only `01 pid` and `09 pid` broadcast OBD requests. */
  fun checkFunctional(payload: ByteArray) {
    if (payload.size != 2 || u8(payload[0]) !in ALLOWED_FUNCTIONAL_MODES) {
      throw UnsafeRequestError(
        "functional request ${hexSpaced(payload).ifEmpty { "<empty>" }} is not allowed (only OBD mode 01/09 with one PID)",
      )
    }
  }
}

/**
 * Enforces a minimum gap between consecutive bus requests (all modules share one limiter).
 * [clock] and [sleeper] are injectable so tests run on a simulated clock.
 */
class RateLimiter(
  val minIntervalS: Double = Safety.DEFAULT_INTERVAL_S,
  private val clock: () -> Double = Monotonic::seconds,
  private val sleeper: (Double) -> Unit = ::sleepSeconds,
) {
  private var last = Double.NEGATIVE_INFINITY

  init {
    require(minIntervalS >= Safety.MIN_INTERVAL_FLOOR_S) {
      "refusing to run faster than one request per ${(Safety.MIN_INTERVAL_FLOOR_S * 1000).toInt()} ms"
    }
  }

  @Synchronized
  fun await() {
    val delay = last + minIntervalS - clock()
    if (delay > 0) sleeper(delay)
    last = clock()
  }
}
