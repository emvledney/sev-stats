package dev.silveradostats.obd

/**
 * ELM327 / OBDLink (STN) transport over an [ElmLink] (port of ElmTransport in transports/elm.py).
 *
 * Construction talks to the adapter (blocking; run it off the main thread):
 *  1. [ElmLink.settle]: never write to an adapter that is still busy;
 *  2. ATZ; the reply must carry an ELM327 / OBDLink / STN / WiCAN banner;
 *  3. ATE0, ATH1, ATSP7 must each answer exactly OK (protocol pinned to 29-bit CAN 500k);
 *  4. ATL0, ATS0, ATCAF1, ATAT1, ATST64 best-effort; ATI / STI identity and ATRV voltage.
 * Any failure closes the link and throws; nothing else is sent.
 */
class ElmTransport(
  val link: ElmLink,
  val responseTimeoutS: Double = 2.0,
  minIntervalS: Double = Safety.DEFAULT_INTERVAL_S,
  limiterClock: () -> Double = Monotonic::seconds,
  limiterSleeper: (Double) -> Unit = ::sleepSeconds,
) : Transport {
  override val name: String = "elm"

  val limiter = RateLimiter(minIntervalS, limiterClock, limiterSleeper)

  /** The (tx, rx, functional) the adapter is currently set up for; null = unknown. */
  internal var selected: Selection? = null

  internal data class Selection(val tx: Long, val rx: Long, val functional: Boolean)

  /** The ATZ reply without its echo (an ELM327 / OBDLink / STN / WiCAN banner). */
  val banner: String
  val ident: String
  val voltage: String

  init {
    try {
      link.settle() // never write to an adapter that is still busy
      val reply = link.command("ATZ", 5.0) // reset; the reply is the banner
      val text = reply.joinToString(" ")
      if (Elm.fatalIn(text, Elm.BANNER_MARKERS) == null) {
        throw FatalTransportError("no adapter banner after ATZ (got '$text'); replug it and retry")
      }
      banner = reply.filter { it.uppercase() != "ATZ" }.joinToString(" ")
      for (cmd in listOf("ATE0", "ATH1", "ATSP7")) at(cmd)
      link.echoOff = true
      for (cmd in listOf("ATL0", "ATS0", "ATCAF1", "ATAT1", "ATST64")) tryAt(cmd) // nice to have
      ident = tryAt("ATI").joinToString(" ") + " / " + tryAt("STI").joinToString(" ")
      voltage = tryAt("ATRV").joinToString(" ")
    } catch (e: Throwable) {
      link.close()
      throw e
    }
  }

  private fun tryAt(cmd: String): List<String> = try {
    at(cmd).filter { it != "?" }
  } catch (e: FatalTransportError) {
    throw e
  } catch (e: TransportError) {
    emptyList()
  }

  /** One allowlisted adapter-local command; required settings must answer exactly OK. */
  fun at(cmd: String, timeoutS: Double = 2.0): List<String> {
    val up = cmd.uppercase()
    if (!Elm.isAllowedAt(up)) throw UnsafeRequestError("adapter command '$cmd' is not on the allowlist")
    val lines = link.command(cmd, timeoutS)
    val markers = if (Elm.IDENTITY_COMMANDS.any { up.startsWith(it) }) Elm.ERROR_MARKERS else Elm.FATAL_MARKERS
    val bad = lines.firstNotNullOfOrNull { Elm.fatalIn(it, markers) }
    if (bad != null) {
      selected = null
      throw FatalTransportError("adapter reported '$bad' on '$cmd'; stopping")
    }
    if (Elm.REQUIRED_OK.any { up.startsWith(it) } &&
      lines.map { it.uppercase() }.filter { it != up } != listOf("OK")
    ) {
      selected = null
      throw FatalTransportError("adapter rejected '$cmd' (replied $lines); stopping")
    }
    return lines
  }

  override fun open(txId: Long, rxId: Long, functional: Boolean): Channel {
    if (txId <= 0x7FF) throw TransportError("the ELM transport is pinned to 29-bit CAN (ATSP7)")
    return ElmChannel(this, txId, rxId, functional, limiter)
  }

  override fun describe(): String = "elm ($ident; adapter sees $voltage)"

  /** ATRV (adapter-local). null when the adapter can't tell; fatal adapter states still throw. */
  override fun adapterVoltage(): Double? = try {
    parseVolts(at("ATRV").joinToString(" "))
  } catch (e: FatalTransportError) {
    throw e
  } catch (e: TransportError) {
    null
  }

  override fun close() = link.close()

  companion object {
    private val FLOAT = Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")

    /** float(text.strip().rstrip("Vv")) with Python's float syntax; null instead of ValueError. */
    fun parseVolts(text: String): Double? {
      val s = text.trim().trimEnd('V', 'v').trim()
      if (!FLOAT.matches(s)) return null
      return s.toDouble()
    }
  }
}

/** One module (or the broadcast) through the ELM: selects headers / filters / flow control as needed. */
internal class ElmChannel(
  private val t: ElmTransport,
  txId: Long,
  rxId: Long,
  functional: Boolean,
  limiter: RateLimiter,
) : Channel(txId, rxId, functional, limiter) {
  private var pending: List<ByteArray> = emptyList()

  private fun select() {
    val want = ElmTransport.Selection(txId, rxId, functional)
    if (t.selected == want) return
    t.selected = null
    t.at("ATCP" + hex((txId shr 24) and 0x1F, 2))
    t.at("ATSH" + hex(txId and 0xFFFFFF, 6))
    if (functional) {
      t.at("ATCRA") // bare ATCRA clears the receive filter: hear every module
      t.at("ATFCSM0")
    } else {
      t.at("ATCRA" + hex(rxId, 8))
      // Flow control for multi-frame answers goes back to this module.
      t.at("ATFCSH" + hex(txId, 8))
      t.at("ATFCSD300000")
      t.at("ATFCSM1")
    }
    t.selected = want
  }

  override fun transmit(payload: ByteArray) {
    select()
    val lines = t.link.command(hexUpper(payload), t.responseTimeoutS)
    val upper = lines.map { it.uppercase() }
    if (upper.any { it == "?" } || upper.any { Elm.fatalIn(it) != null }) {
      t.selected = null
      throw FatalTransportError("adapter reported $lines; stopping")
    }
    val frames = Elm.reassemble(lines, 8)
    pending = frames.filter { (arb, _) -> functional || arb == rxId }.map { it.second }
  }

  override fun receive(timeoutS: Double): List<ByteArray> {
    // The adapter returns every frame it saw before its prompt, so all responses were already
    // collected in transmit. (Never send a bare CR to wait for more: an ELM repeats the last request.)
    val out = pending
    pending = emptyList()
    return out
  }
}
