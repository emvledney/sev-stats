package dev.silveradostats.obd

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal ELM327 stand-in on a loopback socket (port of FakeElm in the probe's tests), answering
 * the Ultium battery module 0xCB at priority 0x14. The client side is a [StreamByteLink] over the
 * socket's blocking streams, the same shape as a BluetoothSocket.
 *
 * @param stallFirst (command, ms): answer that command late, once, with [lateReply]
 *   (null lateReply = never answer it).
 * @param reject command answered with '?'.
 * @param resetOn command answered with 'LV RESET'.
 * @param custom per-command reply override (reply text without the trailing "\r\r>"); null = default.
 * @param onConnect runs on its own thread right after accept (e.g. to stream "busy" output).
 */
class FakeElm(
  private var stallFirst: Pair<String, Long>? = null,
  private val reject: String? = null,
  private val resetOn: String? = null,
  private val lateReply: String? = "142AF1CB056227C67123\r\r>",
  private val custom: (String) -> String? = { null },
  private val onConnect: ((OutputStream, FakeElm) -> Unit)? = null,
) : Closeable {
  private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
  val port: Int get() = server.localPort

  /** Commands received, in order (split on '\r', trimmed, uppercased). */
  val received = CopyOnWriteArrayList<String>()

  /** Commands that arrived while the fake was still busy (a real ELM would drop a character). */
  val interrupted = CopyOnWriteArrayList<String>()

  private val rawBytes = StringBuffer()
  val raw: String get() = rawBytes.toString()

  @Volatile var firstByteAtNanos: Long = -1
  @Volatile var promptSentAtNanos: Long = -1
  @Volatile var stopWriter = false
  @Volatile private var busyUntilNanos = 0L
  @Volatile private var out: OutputStream? = null
  @Volatile private var client: Socket? = null

  private val acceptThread = thread(name = "fake-elm", isDaemon = true) {
    try {
      val s = server.accept()
      client = s
      val o = s.getOutputStream()
      out = o
      onConnect?.let { hook -> thread(name = "fake-elm-writer", isDaemon = true) { runCatching { hook(o, this) } } }
      serve(s)
    } catch (_: Exception) {
    } finally {
      stopWriter = true
    }
  }

  /** The client side: what the app would build from a BluetoothSocket. */
  fun connect(): StreamByteLink {
    val s = Socket()
    s.tcpNoDelay = true
    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
    return StreamByteLink(s.getInputStream(), s.getOutputStream(), s)
  }

  /** Writes unsolicited output, like an adapter that reset or answered late. */
  fun inject(text: String) = send(text)

  private fun send(text: String) {
    val o = out ?: return
    synchronized(o) {
      o.write(text.toByteArray(Charsets.ISO_8859_1))
      o.flush()
    }
  }

  private fun serve(s: Socket) {
    val input = s.getInputStream()
    var hdr = ""
    var cp = ""
    val line = StringBuilder()
    while (true) {
      val b = input.read()
      if (b < 0) return
      if (firstByteAtNanos < 0) firstByteAtNanos = System.nanoTime()
      rawBytes.append(b.toChar())
      if (b != '\r'.code) {
        line.append(b.toChar())
        continue
      }
      val cmd = line.toString().trim().uppercase()
      line.setLength(0)
      if (System.nanoTime() < busyUntilNanos) interrupted += cmd
      received += cmd
      val stall = stallFirst
      if (stall != null && cmd == stall.first) {
        stallFirst = null
        busyUntilNanos = if (lateReply == null) Long.MAX_VALUE else System.nanoTime() + stall.second * 1_000_000
        val late = lateReply
        if (late != null) {
          thread(isDaemon = true) {
            Thread.sleep(stall.second)
            runCatching { send(late) }
          }
        }
        continue
      }
      if (cmd == reject) {
        send("?\r\r>")
        continue
      }
      if (cmd == resetOn) {
        send("LV RESET\r\r>")
        continue
      }
      if (cmd.startsWith("ATSH")) hdr = cmd.substring(4)
      if (cmd.startsWith("ATCP")) cp = cmd.substring(4)
      val reply = custom(cmd) ?: when {
        cmd == "ATZ" -> "ATZ\r\rELM327 v1.5"
        cmd == "ATI" -> "ELM327 v1.5"
        cmd == "ATRV" -> "12.6V"
        cmd.startsWith("AT") || cmd.startsWith("ST") -> if (cmd.startsWith("STI")) "?" else "OK"
        cmd == "2227C6" && cp + hdr == "14DACBF1" -> "142AF1CB056227C67123"
        cmd == "222AF5" && cp + hdr == "14DACBF1" -> "142AF1CB100D622AF59D759D62\r142AF1CB219D91B5179113AAAA"
        else -> "NO DATA"
      }
      send("$reply\r\r>")
    }
  }

  override fun close() {
    stopWriter = true
    runCatching { client?.close() }
    runCatching { server.close() }
    acceptThread.join(2_000)
  }

  companion object {
    private val READ = Regex("22[0-9A-F]{4}")

    /** Every command is an allowlisted adapter command or a single-DID read. */
    fun allowed(cmd: String): Boolean = Elm.isAllowedAt(cmd) || READ.matches(cmd)
  }
}
