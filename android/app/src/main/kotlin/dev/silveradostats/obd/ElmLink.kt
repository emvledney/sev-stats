package dev.silveradostats.obd

import kotlin.math.ceil

/**
 * ELM327 / OBDLink (STN) command set constants (port of transports/elm.py).
 *
 * Only a fixed allowlist of adapter-configuration commands is ever sent; the only thing that
 * reaches the vehicle bus is the hex payload of a request, which goes through the same safety
 * checks as every other transport. The protocol is pinned (ATSP7 = ISO 15765-4 CAN 29-bit/500k,
 * what Ultium trucks use) so the adapter never runs its protocol auto-search.
 */
object Elm {
  /** Adapter-local configuration commands that may be sent (prefix match). */
  val ALLOWED_AT: List<String> = listOf(
    "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP7", "ATCAF1", "ATAT1", "ATST", "ATSH",
    "ATCRA", "ATCP", "ATFCSH", "ATFCSD", "ATFCSM", "ATI", "AT@1", "ATRV", "ATDPN", "STI",
  )

  /** Settings whose reply must be exactly OK (anything else = unknown adapter state). */
  val REQUIRED_OK: List<String> = listOf("ATE0", "ATH1", "ATSP7", "ATSH", "ATCRA", "ATCP", "ATFCSH", "ATFCSD", "ATFCSM")

  /** Replies that mean the adapter reset, was interrupted or lost the bus. */
  val ERROR_MARKERS: List<String> = listOf(
    "LV RESET", "STOPPED", "ERR", "CAN ERROR", "BUS ERROR", "BUFFER FULL",
    "BUS BUSY", "FB ERROR", "DATA ERROR", "<RX ERROR", "ACT ALERT",
  )

  /** A power-on banner where none was asked for also means the adapter reset. */
  val BANNER_MARKERS: List<String> = listOf("ELM327", "OBDLINK", "STN", "WICAN")
  val FATAL_MARKERS: List<String> = ERROR_MARKERS + BANNER_MARKERS

  /** Commands whose normal reply is a banner / identity string. */
  val IDENTITY_COMMANDS: List<String> = listOf("ATZ", "ATI", "AT@1", "STI")

  /** Bus payload lines the link may carry (checked again here, after Safety): 22xxxx, 03/07/0A, 01xx/09xx. */
  private val BUS_LINE = Regex("^(22[0-9A-F]{4}|0[37A]|0[19][0-9A-F]{2})$")
  private val HEX_LINE = Regex("^[0-9A-F]+$")

  /** A diagnostic ECU byte: anything but 0x33 (functional) and 0xF1 (the tester). */
  private const val ECU = "(?!33|F1)[0-9A-F]{2}"

  /**
   * Adapter lines the link may carry, in their exact forms (an addition to the probe, whose
   * allowlist is the prefix list [ALLOWED_AT]). Addressing commands may only name the diagnostic
   * IDs Safety permits: ATCP 14 / 18; ATSH DA<ecu>F1 (physical) or DB33F1 (functional 18DB33F1);
   * ATCRA bare (functional) or 142AF1<ecu> / 18DAF1<ecu>; ATFCSH 14DA<ecu>F1 / 18DA<ecu>F1.
   */
  private val AT_EXACT = Regex(
    "^(ATZ|ATE0|ATL0|ATS0|ATH1|ATSP7|ATCAF1|ATAT1|ATST[0-9A-F]{2}|ATI|AT@1|ATRV|ATDPN|STI|" +
      "ATCP(14|18)|ATSH(DA${ECU}F1|DB33F1)|ATCRA|ATCRA(142AF1|18DAF1)$ECU|ATFCSH(14|18)DA${ECU}F1|" +
      "ATFCSD300000|ATFCSM[01])$",
  )

  fun isAllowedAt(upper: String): Boolean = ALLOWED_AT.any { upper.startsWith(it) }

  /** First marker contained in [text] (case-insensitive), like `_fatal_in` in the probe. */
  fun fatalIn(text: String, markers: List<String> = FATAL_MARKERS): String? {
    val up = text.uppercase()
    return markers.firstOrNull { it in up }
  }

  /**
   * Link-level allowlist (an addition to the probe, defence in depth): a line is either one of the
   * exact adapter commands in [AT_EXACT] or one of the bus reads Safety permits. No spaces, CR, LF
   * or other control characters can ever be written.
   */
  fun isAllowedLine(line: String): Boolean = AT_EXACT.matches(line) || BUS_LINE.matches(line)

  /**
   * Turns ELM 'headers on, spaces off' frame lines into ISO-TP payloads.
   * Returns (response id, payload) for every complete message, in order of completion.
   */
  fun reassemble(lines: List<String>, headerLen: Int): List<Pair<Long, ByteArray>> {
    val done = mutableListOf<Pair<Long, ByteArray>>()
    val partial = LinkedHashMap<Long, Pair<Int, ArrayList<Byte>>>()
    for (raw in lines) {
      val ln = raw.replace(" ", "")
      if (!HEX_LINE.matches(ln) || ln.length <= headerLen) continue
      val arb = ln.substring(0, headerLen).toLong(16)
      val body = ln.substring(headerLen)
      val data = bytesFromHex(if (body.length % 2 == 0) body else body.dropLast(1))
      if (data.isEmpty()) continue
      when (u8(data[0]) shr 4) {
        0 -> {
          val n = u8(data[0]) and 0x0F
          done += arb to data.pySlice(1, 1 + n)
        }
        1 -> if (data.size >= 2) {
          val total = ((u8(data[0]) and 0x0F) shl 8) or u8(data[1])
          partial[arb] = total to data.sliceFrom(2).toCollection(ArrayList())
        }
        2 -> {
          val p = partial[arb] ?: continue
          val (total, buf) = p
          for (i in 1 until data.size) buf += data[i]
          if (buf.size >= total) {
            done += arb to buf.subList(0, total).toByteArray()
            partial.remove(arb)
          }
        }
      }
    }
    return done
  }
}

/**
 * Line-oriented connection to the adapter (prompt is '>'), port of ElmLink in transports/elm.py.
 *
 * Link discipline (an ELM drops the first character it receives while busy, which can turn
 * '22xxxx' into a different service):
 *  - [settle] before the first write: if the adapter is still talking, wait for '>' and silence;
 *  - a command marks the link dirty until its prompt arrives, so after a timeout or an interrupted
 *    thread nothing is written until [resync] has seen '>' followed by [quietS] of silence
 *    (bounded by [resyncBudgetS]; otherwise fatal and closed);
 *  - output that arrives between commands or after a prompt (in the same read as the reply),
 *    STOPPED, or an echo coming back after ATE0 is fatal;
 *  - empty lines are refused (a bare CR makes an ELM repeat its last bus request), and so is any
 *    line outside [Elm.isAllowedLine].
 *
 * Timeouts are in seconds, like the probe. Not thread-safe: one poller thread owns the link.
 */
class ElmLink(
  private val io: ByteLink,
  private val clock: () -> Double = Monotonic::seconds,
  private val quietS: Double = 0.3,
  private val resyncBudgetS: Double = 10.0,
  private val settleBudgetS: Double = 10.0,
) {
  /** True while the adapter may be busy (a command is outstanding or timed out). */
  @Volatile var dirty: Boolean = false
    private set

  /** Set once ATE0 is confirmed; an echo afterwards means the adapter reset. */
  @Volatile var echoOff: Boolean = false

  @Volatile var closed: Boolean = false
    private set

  private val sentLines = ArrayList<String>()

  /** The most recent lines written to the adapter (at most [SENT_HISTORY]), for diagnostics. */
  val sent: List<String> get() = synchronized(sentLines) { sentLines.toList() }

  private fun readAvailable(timeoutS: Double): ByteArray =
    io.readAvailable(if (timeoutS <= 0) 0 else ceil(timeoutS * 1000).toLong())

  /** Without writing anything, waits until the adapter shows '>' and then stays quiet. */
  private fun resync() {
    val deadline = clock() + resyncBudgetS
    var seen = ByteArray(0)
    while (clock() < deadline) {
      val chunk = readAvailable(quietS)
      if (chunk.isNotEmpty()) {
        seen += chunk
        val marker = Elm.fatalIn(utf8Decode(seen))
        if (marker != null) {
          close()
          throw FatalTransportError("adapter reported '$marker' while recovering; stopping")
        }
        continue
      }
      if (endsWithPrompt(seen)) {
        dirty = false
        return
      }
    }
    close()
    throw FatalTransportError("adapter did not return to its prompt; link closed")
  }

  /** At connect: if the adapter is still talking (busy from an earlier session), waits it out. */
  fun settle() {
    val first = readAvailable(quietS)
    if (first.isEmpty()) return
    dirty = true
    val deadline = clock() + settleBudgetS
    var seen = first
    while (clock() < deadline) {
      val chunk = readAvailable(quietS)
      if (chunk.isEmpty()) {
        if (endsWithPrompt(seen)) {
          dirty = false
          return
        }
        continue
      }
      seen += chunk
    }
    close()
    throw FatalTransportError("adapter kept talking at connect; replug it and retry")
  }

  /** Sends one line and returns the non-empty reply lines before the '>' prompt. */
  fun command(line: String, timeoutS: Double): List<String> {
    if (line.isBlank()) {
      // A bare CR makes an ELM repeat its previous command on the bus.
      throw TransportError("refusing to send an empty line to the adapter")
    }
    if (!Elm.isAllowedLine(line)) throw UnsafeRequestError("adapter line '$line' is not on the allowlist")
    if (closed) throw FatalTransportError("adapter link is closed")
    if (dirty) resync()
    var stale = ByteArray(0)
    while (true) {
      val chunk = readAvailable(0.0)
      if (chunk.isEmpty()) break
      stale += chunk
    }
    if (stale.pyStrip("\r\n >").isNotEmpty()) {
      // Nothing should arrive between commands; output here means a reset or a late reply.
      dirty = true
      throw FatalTransportError("unexpected adapter output ${pyBytesRepr(stale)}; stopping")
    }
    dirty = true // until the prompt comes back (also covers an interrupted thread)
    synchronized(sentLines) {
      if (sentLines.size >= SENT_HISTORY) sentLines.removeAt(0)
      sentLines += line
    }
    io.write((line + "\r").toByteArray(Charsets.US_ASCII))
    var buf = ByteArray(0)
    val deadline = clock() + timeoutS
    while (buf.indexOfByte(PROMPT) < 0) {
      val remaining = deadline - clock()
      if (remaining <= 0) throw AdapterTimeoutError("adapter timed out on '$line' (got ${pyBytesRepr(buf)})")
      buf += readAvailable(remaining)
    }
    val prompt = buf.indexOfByte(PROMPT)
    val tail = buf.copyOfRange(prompt + 1, buf.size)
    if (tail.pyStrip("\r\n >").isNotEmpty()) {
      // Nothing follows a prompt until the next command. Output here (a reset announced right after
      // the reply, in the same read) is the between-commands case: the adapter's state is unknown,
      // so nothing more may be written. (The probe keeps only buf.split(">")[0] and misses this.)
      dirty = true
      close()
      throw FatalTransportError("unexpected adapter output after the prompt ${pyBytesRepr(tail)} on '$line'; stopping")
    }
    dirty = false
    val text = utf8Decode(buf.copyOfRange(0, prompt))
    val lines = text.split(LINE_BREAKS).map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.any { "STOPPED" in it.uppercase() }) {
      throw FatalTransportError("adapter was interrupted (STOPPED) on '$line'; stopping")
    }
    if (echoOff && lines.any { it.uppercase() == line.uppercase() }) {
      throw FatalTransportError("adapter echo came back on: it reset; stopping")
    }
    return lines
  }

  fun close() {
    closed = true
    io.close()
  }

  private fun endsWithPrompt(seen: ByteArray): Boolean {
    val s = seen.pyRstrip()
    return s.isNotEmpty() && s[s.size - 1] == PROMPT
  }

  companion object {
    /** How many written lines [sent] keeps. */
    const val SENT_HISTORY = 64
    private const val PROMPT: Byte = '>'.code.toByte()
    private val LINE_BREAKS = Regex("[\r\n]+")
  }
}
