package dev.silveradostats.link

import dev.silveradostats.obd.StreamByteLink
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Minimal ELM327 on a loopback socket that accepts any number of connections (the engine's
 * reconnects), answering the battery module 0xCB at priority 0x14 like FakeElm.
 *
 * @param atzDelayMs the ATZ reply comes this late (a slow adapter setup).
 * @param dropAfterReads on the first [dropConnections] connections, the socket is closed after this
 *   many bus reads (a Bluetooth dropout); null = never.
 * @param stallRead on the first connection, this bus read (1-based) is never answered (a timeout).
 */
class MultiFakeElm(
  private val atzDelayMs: Long = 0,
  private val dropAfterReads: Int? = null,
  private val dropConnections: Int = 1,
  private val stallRead: Int? = null,
) : AutoCloseable {
  private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())

  /** Commands received over all connections, in order. */
  val received = CopyOnWriteArrayList<String>()
  val accepts = AtomicInteger()

  /** System.nanoTime() when each served connection ended (closed by either side). */
  val closedAt = CopyOnWriteArrayList<Long>()

  @Volatile private var stop = false
  private val sockets = CopyOnWriteArrayList<Socket>()

  private val acceptThread = thread(isDaemon = true, name = "multi-fake-elm") {
    while (!stop) {
      val s = runCatching { server.accept() }.getOrNull() ?: break
      val n = accepts.incrementAndGet()
      sockets += s
      thread(isDaemon = true) { serve(s, n) }
    }
  }

  val busReads: Int get() = received.count { it.startsWith("22") }

  private fun serve(s: Socket, connection: Int) {
    val input = s.getInputStream()
    val out = s.getOutputStream()
    val line = StringBuilder()
    var hdr = ""
    var cp = ""
    var reads = 0
    try {
      while (true) {
        val b = input.read()
        if (b < 0) break
        if (b != '\r'.code) {
          line.append(b.toChar())
          continue
        }
        val cmd = line.toString().trim().uppercase()
        line.setLength(0)
        received += cmd
        if (cmd.startsWith("ATSH")) hdr = cmd.substring(4)
        if (cmd.startsWith("ATCP")) cp = cmd.substring(4)
        if (cmd.startsWith("22")) {
          reads += 1
          if (connection == 1 && reads == stallRead) continue // never answered
        }
        val reply = when {
          cmd == "ATZ" -> {
            if (atzDelayMs > 0) Thread.sleep(atzDelayMs)
            "ATZ\r\rELM327 v1.5"
          }
          cmd == "ATI" -> "ELM327 v1.5"
          cmd == "ATRV" -> "12.6V"
          cmd.startsWith("AT") || cmd.startsWith("ST") -> if (cmd.startsWith("STI")) "?" else "OK"
          cmd == "2227C6" && cp + hdr == "14DACBF1" -> "142AF1CB056227C67123"
          else -> "NO DATA"
        }
        out.write("$reply\r\r>".toByteArray())
        out.flush()
        val drop = dropAfterReads
        if (drop != null && connection <= dropConnections && cmd.startsWith("22") && reads >= drop) break
      }
    } catch (_: Exception) {
    } finally {
      closedAt += System.nanoTime()
      runCatching { s.close() }
    }
  }

  fun connect(): StreamByteLink {
    val s = Socket()
    s.tcpNoDelay = true
    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2_000)
    return StreamByteLink(s.getInputStream(), s.getOutputStream(), s)
  }

  override fun close() {
    stop = true
    runCatching { server.close() }
    sockets.forEach { runCatching { it.close() } }
    acceptThread.join(2_000)
  }
}
