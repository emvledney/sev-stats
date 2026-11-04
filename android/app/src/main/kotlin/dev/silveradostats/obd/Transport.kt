package dev.silveradostats.obd

import java.io.Closeable

/**
 * Request/response path to one module (physical) or to all (functional); port of transports/base.py.
 *
 * The CAN IDs are checked when the channel is created, and [request] is the only way to transmit:
 * it always runs the payload safety check and the shared rate limiter first. Subclasses implement
 * [transmit] / [receive] and never expose an unchecked send.
 */
abstract class Channel(
  val txId: Long,
  val rxId: Long,
  val functional: Boolean = false,
  val limiter: RateLimiter = RateLimiter(),
) : Closeable {
  init {
    Safety.checkAddress(txId, rxId, functional)
  }

  /**
   * Sends one guarded request and returns every complete response payload seen. Physical channels
   * normally return one payload; functional channels may return one per responding module.
   */
  fun request(payload: ByteArray, timeoutS: Double): List<ByteArray> {
    if (functional) Safety.checkFunctional(payload) else Safety.checkPhysical(payload)
    limiter.await()
    transmit(payload.copyOf())
    return receive(timeoutS)
  }

  /** Waits for further responses (used after NRC 0x78 'response pending'). */
  fun receiveMore(timeoutS: Double): List<ByteArray> = receive(timeoutS)

  protected abstract fun transmit(payload: ByteArray)

  protected abstract fun receive(timeoutS: Double): List<ByteArray>

  override fun close() {}
}

/** Opens [Channel]s, one per module (request/response CAN ID pair). */
interface Transport : Closeable {
  val name: String

  fun open(txId: Long, rxId: Long, functional: Boolean = false): Channel

  fun describe(): String = name

  /** 12 V at the OBD port as measured by the adapter itself (adapter-local, no bus traffic). */
  fun adapterVoltage(): Double? = null

  override fun close() {}
}

/** The functional (broadcast) channel for OBD modes 01 / 09. */
fun Transport.functionalChannel(): Channel = open(Addressing.FUNCTIONAL_29, Addressing.FUNCTIONAL_RESP_29, functional = true)
