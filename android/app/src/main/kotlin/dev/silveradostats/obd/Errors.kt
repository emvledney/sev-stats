package dev.silveradostats.obd

/**
 * Errors of the OBD core, mirroring the Python probe (tools/probe/src/ultium_probe).
 *
 *  - [UnsafeRequestError]: something tried to send a frame or adapter command outside the read-only
 *    allowlist. Raised before anything is written. Never caught by the transport.
 *  - [TransportError]: the adapter or bus did not do what was asked (timeout, rejected optional
 *    setting, unknown transport). The link is still in a known state.
 *  - [FatalTransportError]: the link is in an unknown state (adapter reset, interrupted, late or
 *    unexpected output, rejected required setting, closed connection). Stop the run; send nothing else.
 */
class UnsafeRequestError(message: String) : Exception(message)

open class TransportError(message: String, cause: Throwable? = null) : Exception(message, cause)

open class FatalTransportError(message: String, cause: Throwable? = null) : TransportError(message, cause)

/*
 * App-only refinements (the probe raises the plain classes above; these are subclasses, so every
 * `except` / catch of the parent behaves exactly as in the probe). LinkEngine uses them to tell a
 * lost connection or a slow reply, which a fresh connect + adapter setup recovers from, apart from
 * an adapter that reset, errored or rejected a setting, which always waits for the user.
 */

/** The connection itself failed or closed (Bluetooth / TCP dropped). Fatal for this link. */
class LinkLostError(message: String, cause: Throwable? = null) : FatalTransportError(message, cause)

/** No prompt within the timeout; the link is dirty until it resyncs. */
class AdapterTimeoutError(message: String) : TransportError(message)
