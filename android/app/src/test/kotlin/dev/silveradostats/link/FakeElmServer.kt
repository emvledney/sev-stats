package dev.silveradostats.link

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * In-process fake ELM327 on a loopback socket, for one client.
 *
 * @param onConnect runs on its own thread right after accept (e.g. to stream "busy" output).
 * @param respond maps a received command (without '\r') to the reply bytes; null = stall forever.
 */
class FakeElmServer(
  private val onConnect: (OutputStream, FakeElmServer) -> Unit = { _, _ -> },
  private val respond: (String) -> String?,
) : Closeable {
  private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
  val port: Int get() = server.localPort

  /** Commands received, in order (split on '\r'). */
  val commands = CopyOnWriteArrayList<String>()

  /** Every byte received, in order. */
  private val raw = StringBuffer()
  val rawReceived: String get() = raw.toString()

  @Volatile var firstByteAtNanos: Long = -1
  @Volatile var promptSentAtNanos: Long = -1
  @Volatile var stalledOn: String? = null
  @Volatile var bytesAfterStall: Int = 0
  @Volatile var clientClosed: Boolean = false
  @Volatile var stopWriter: Boolean = false

  private val done = CountDownLatch(1)
  @Volatile private var client: Socket? = null

  private val acceptThread = thread(name = "fake-elm", isDaemon = true) {
    try {
      val s = server.accept()
      client = s
      val out = s.getOutputStream()
      thread(name = "fake-elm-writer", isDaemon = true) {
        try {
          onConnect(out, this)
        } catch (_: Exception) {
        }
      }
      val input = s.getInputStream()
      val line = StringBuilder()
      while (true) {
        val b = input.read()
        if (b < 0) {
          clientClosed = true
          break
        }
        if (firstByteAtNanos < 0) firstByteAtNanos = System.nanoTime()
        raw.append(b.toChar())
        if (stalledOn != null) {
          bytesAfterStall++
          continue
        }
        if (b == '\r'.code) {
          val cmd = line.toString()
          line.setLength(0)
          commands += cmd
          val reply = respond(cmd)
          if (reply == null) {
            stalledOn = cmd
          } else {
            synchronized(out) {
              out.write(reply.toByteArray(Charsets.ISO_8859_1))
              out.flush()
            }
          }
        } else {
          line.append(b.toChar())
        }
      }
    } catch (_: Exception) {
      clientClosed = true
    } finally {
      stopWriter = true
      done.countDown()
    }
  }

  /** Waits until the client has disconnected (or the timeout passes). */
  fun awaitClientClosed(timeoutMs: Long = 5_000): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

  override fun close() {
    stopWriter = true
    try {
      client?.close()
    } catch (_: Exception) {
    }
    server.close()
    acceptThread.join(2_000)
  }

  companion object {
    /** Typical ELM327 v1.5 replies: echo on after ATZ until ATE0 turns it off. */
    fun standardReplies(cmd: String): String? = when {
      cmd == "ATZ" -> "ATZ\r\r\rELM327 v1.5\r\r>"
      cmd == "ATE0" -> "ATE0\rOK\r\r>"
      cmd == "ATI" -> "ELM327 v1.5\r\r>"
      cmd == "STI" -> "?\r\r>"
      cmd == "ATRV" -> "12.6V\r\r>"
      cmd.startsWith("AT") -> "OK\r\r>"
      else -> "?\r\r>"
    }
  }
}
