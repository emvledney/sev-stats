package dev.silveradostats.obd

/** Minimal read-only diagnostic client (port of uds.py): UDS 0x22, OBD 01/09 and DTC reads. */

val NRC_NAMES: Map<Int, String> = mapOf(
  0x10 to "generalReject",
  0x11 to "serviceNotSupported",
  0x12 to "subFunctionNotSupported",
  0x13 to "incorrectMessageLengthOrInvalidFormat",
  0x14 to "responseTooLong",
  0x21 to "busyRepeatRequest",
  0x22 to "conditionsNotCorrect",
  0x31 to "requestOutOfRange",
  0x33 to "securityAccessDenied",
  0x34 to "authenticationRequired",
  0x78 to "requestCorrectlyReceivedResponsePending",
  0x7E to "subFunctionNotSupportedInActiveSession",
  0x7F to "serviceNotSupportedInActiveSession",
)

/** The module answered, but with a negative response (7F sid nrc). */
class NegativeResponse(val sid: Int, val nrc: Int) :
  Exception("NRC 0x${hex(nrc, 2)} ${NRC_NAMES[nrc] ?: ""} for service 0x${hex(sid, 2)}")

/** Nothing answered at all. */
class NoResponse(message: String) : Exception(message)

class DidResult(val did: Int, val data: ByteArray, val elapsedMs: Double)

/** Anything that can read one DID from one module (the Poller's view of a [UdsClient]). */
fun interface DidReader {
  fun readDid(did: Int): DidResult
}

/**
 * ReadDataByIdentifier against one module.
 *
 * NRC 0x78 (response pending): keep listening until [pendingTimeoutS]. NRC 0x21 (busy): one retry
 * after [busyRetryDelayS], which is routine on Ultium while driving. Everything else is reported to
 * the caller; nothing ever escalates to another service.
 */
class UdsClient(
  val ch: Channel,
  val timeoutS: Double = 1.0,
  val pendingTimeoutS: Double = 5.0,
  val busyRetryDelayS: Double = 0.15,
  private val clock: () -> Double = Monotonic::seconds,
  private val sleeper: (Double) -> Unit = ::sleepSeconds,
) : DidReader {

  override fun readDid(did: Int): DidResult {
    try {
      return readOnce(did)
    } catch (e: NegativeResponse) {
      if (e.nrc != 0x21) throw e
    }
    sleeper(busyRetryDelayS)
    return readOnce(did)
  }

  private fun readOnce(did: Int): DidResult {
    require(did in 0..0xFFFF) { "DID must be 16-bit" }
    val req = bytesOf(0x22, (did shr 8) and 0xFF, did and 0xFF)
    val start = clock()
    var responses = ch.request(req, timeoutS)
    val deadline = start + pendingTimeoutS
    while (true) {
      for (r in responses) {
        if (r.size >= 3 && u8(r[0]) == 0x62 && r[1] == req[1] && r[2] == req[2]) {
          return DidResult(did, r.sliceFrom(3), (clock() - start) * 1000)
        }
        if (r.size >= 3 && u8(r[0]) == 0x7F && u8(r[1]) == 0x22 && u8(r[2]) != 0x78) {
          throw NegativeResponse(0x22, u8(r[2]))
        }
      }
      val pending = responses.any { it.size >= 3 && u8(it[0]) == 0x7F && u8(it[2]) == 0x78 }
      if (!pending || clock() >= deadline) break
      responses = ch.receiveMore(maxOf(0.05, deadline - clock()))
    }
    throw NoResponse("no answer from 0x${hex(ch.rxId, 1)} for DID 0x${hex(did, 4)}")
  }

  /** OBD mode 03/07/0A against this module; returns codes like "P0AA6". */
  fun readDtcs(mode: Int): List<String> {
    val responses = ch.request(bytesOf(mode), timeoutS)
    for (r in responses) {
      if (r.isNotEmpty() && u8(r[0]) == mode + 0x40) {
        val body = if (r.size % 2 == 0) r.sliceFrom(2) else r.sliceFrom(1)
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.size - 1) {
          if (body[i].toInt() != 0 || body[i + 1].toInt() != 0) out += dtc(u8(body[i]), u8(body[i + 1]))
          i += 2
        }
        return out
      }
      if (r.size >= 3 && u8(r[0]) == 0x7F) throw NegativeResponse(mode, u8(r[2]))
    }
    throw NoResponse("no DTC answer from 0x${hex(ch.rxId, 1)}")
  }

  companion object {
    fun dtc(b0: Int, b1: Int): String = "${"PCBU"[b0 shr 6]}${(b0 shr 4) and 3}" + hex(b0 and 0xF, 1) + hex(b1, 2)
  }
}

/**
 * Functional OBD request; returns the data bytes from every positive answer.
 * An empty list means nobody answered. Adapter or bus errors throw [TransportError].
 */
fun obdQuery(channel: Channel, mode: Int, pid: Int, timeoutS: Double = 0.5): List<ByteArray> {
  val responses = channel.request(bytesOf(mode, pid), timeoutS)
  return responses.filter { it.size >= 2 && u8(it[0]) == mode + 0x40 && u8(it[1]) == pid }.map { it.sliceFrom(2) }
}

/** OBD mode 01 PID 0D. null if nothing answered. */
fun vehicleSpeedKmh(channel: Channel): Int? {
  val answers = obdQuery(channel, 0x01, 0x0D)
  return if (answers.isNotEmpty() && answers[0].isNotEmpty()) u8(answers[0][0]) else null
}
