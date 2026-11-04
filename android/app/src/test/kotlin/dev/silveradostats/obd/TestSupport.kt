package dev.silveradostats.obd

import java.io.File

/** Shared helpers for the OBD core tests. */
object TestSupport {
  /** The app's real profile (assets/profile.json), read from disk. */
  fun profileFile(): File =
    listOf(File("src/main/assets/profile.json"), File("app/src/main/assets/profile.json")).firstOrNull { it.isFile }
      ?: error("cannot find src/main/assets/profile.json from ${File(".").absolutePath}")

  val profile: Profile by lazy { Profile.fromJson(profileFile().readText(), "profile.json") }

  fun hex(s: String): ByteArray = bytesFromHex(s.replace(" ", ""))

  /** A simulator with a settable clock and no real sleeping. */
  class SimRig(start: Double = 1000.0) {
    var now = start
    val transport = SimTransport(clock = { now }, sleeper = {})

    fun client(ecu: Int, pri: Int, timeoutS: Double = 1.0): UdsClient {
      val (tx, rx) = Addressing.ids(ecu, pri)
      return UdsClient(transport.open(tx, rx), timeoutS = timeoutS, clock = { now }, sleeper = {})
    }
  }
}

/** Channel that records what passed the safety checks and answers from a script. */
class ScriptedChannel(
  functional: Boolean = false,
  private val script: ArrayDeque<List<ByteArray>> = ArrayDeque(),
) : Channel(
  if (functional) Addressing.FUNCTIONAL_29 else 0x14DACBF1L,
  if (functional) Addressing.FUNCTIONAL_RESP_29 else 0x142AF1CBL,
  functional,
  RateLimiter(0.05, { 0.0 }, {}),
) {
  val sent = mutableListOf<ByteArray>()
  var receives = 0

  fun then(vararg responses: String): ScriptedChannel {
    script.addLast(responses.map { TestSupport.hex(it) })
    return this
  }

  override fun transmit(payload: ByteArray) {
    sent += payload
  }

  override fun receive(timeoutS: Double): List<ByteArray> {
    receives++
    return script.removeFirstOrNull() ?: emptyList()
  }
}
