package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * StreamByteLink closing from another thread. Found while fixing the review's "can't interrupt a
 * setup" finding: close() interrupted the reader thread while it was queueing the socket-closed
 * error with put(), which then threw InterruptedException out of the thread. On Android an uncaught
 * exception on any thread kills the app, so closing the MX+ link could crash it.
 */
class StreamByteLinkTest {
  private fun pair(server: ServerSocket): Pair<StreamByteLink, Socket> {
    val c = Socket()
    c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2_000)
    val peer = server.accept()
    return StreamByteLink(c.getInputStream(), c.getOutputStream(), c) to peer
  }

  @Test
  fun closingNeverLeaksAnExceptionFromTheReaderThread() {
    val uncaught = CopyOnWriteArrayList<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    try {
      ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
        repeat(200) {
          val (link, peer) = pair(server)
          link.close()
          peer.close()
        }
      }
      Thread.sleep(300)
      assertEquals("uncaught on reader threads: $uncaught", emptyList<Throwable>(), uncaught.toList())
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
  }

  @Test
  fun closeFromAnotherThreadWakesAWaitingRead() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      val (link, peer) = pair(server)
      thread(isDaemon = true) {
        Thread.sleep(200)
        link.close()
      }
      val t0 = System.nanoTime()
      try {
        link.readAvailable(5_000)
        fail("a closed link must not return data")
      } catch (e: LinkLostError) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("woke $ms ms after the read started (close at 200 ms)", ms < 1_000)
      }
      peer.close()
    }
  }

  @Test
  fun dataBeforeTheEndOfStreamIsDeliveredFirst() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      val (link, peer) = pair(server)
      peer.getOutputStream().apply {
        write("OK\r\r>".toByteArray())
        flush()
      }
      peer.close()
      Thread.sleep(200)
      assertEquals("OK\r\r>", String(link.readAvailable(1_000)))
      try {
        link.readAvailable(1_000)
        fail("end of stream must be reported")
      } catch (e: LinkLostError) {
        assertEquals("adapter closed the connection", e.message)
      }
      link.close()
    }
  }
}
