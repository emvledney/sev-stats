package dev.silveradostats.obd

/**
 * GM Global B / Ultium diagnostic addressing (port of addressing.py).
 *
 * Ultium trucks answer at the OBD port only on 29-bit CAN (ISO 15765-4, 500k).
 * A module with address `ecu` is reached at one of two priorities:
 *
 *   priority 0x18 (ISO normal-fixed):  request 0x18DA<ecu>F1 -> response 0x18DAF1<ecu>
 *   priority 0x14 (GM variant):        request 0x14DA<ecu>F1 -> response 0x142AF1<ecu>
 *
 * Modules differ in which priority they answer (the Silverado EV battery module 0xCB answers at
 * both; the body controller 0x40 only at 0x18). Standard OBD requests are broadcast on 0x18DB33F1
 * and answered on 0x18DAF1<ecu>. CAN IDs are Longs so out-of-range values can be checked, not wrapped.
 */
object Addressing {
  const val FUNCTIONAL_29: Long = 0x18DB33F1
  const val FUNCTIONAL_RESP_29: Long = 0x18DAF100
  const val TESTER: Int = 0xF1

  /** (request id, response id) for module [ecu] at priority 0x14 or 0x18. */
  fun ids(ecu: Int, pri: Int): Pair<Long, Long> {
    require(ecu in 0..0xFF) { "ECU address must be one byte, got 0x${ecu.toString(16)}" }
    val tx = (pri.toLong() shl 24) or 0xDA0000L or (ecu.toLong() shl 8) or TESTER.toLong()
    val rx = when (pri) {
      0x18 -> 0x18DAF100L or ecu.toLong()
      0x14 -> 0x142AF100L or ecu.toLong()
      else -> throw IllegalArgumentException("priority must be 0x14 or 0x18")
    }
    return tx to rx
  }

  /** Where to send ISO-TP flow control for a multi-frame answer from [respId]. */
  fun flowControlTarget(respId: Long): Long {
    if (respId <= 0x7FF) return respId - 8
    val ecu = (respId and 0xFF).toInt()
    val pri = (respId shr 24).toInt()
    return ids(ecu, if (pri == 0x14 || pri == 0x18) pri else 0x18).first
  }
}
