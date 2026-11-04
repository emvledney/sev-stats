package dev.silveradostats.adapter

import dev.silveradostats.link.ConnectFailure
import dev.silveradostats.link.LinkCheck
import dev.silveradostats.link.LinkCheckResult
import dev.silveradostats.obd.ElmLink
import dev.silveradostats.obd.StreamByteLink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Adapter link test over TCP (Wi-Fi ELM327 adapters; not used with the OBDLink MX+).
 *
 * Connect (5 s timeout), then the OBD core's adapter setup only, through [LinkCheck]: settle before
 * the first write, ATZ (banner check), ATE0 / ATH1 / ATSP7 (must answer OK), the cosmetic settings,
 * ATI / STI and ATRV. All adapter-local: no vehicle request is ever sent. The socket's streams go
 * straight into the core's [StreamByteLink]; only ElmLink writes to them (link discipline and
 * allowlist included). The socket is always closed afterwards.
 *
 * Blocking: run it off the main thread.
 */
class AdapterLinkTest(
  val host: String,
  val port: Int,
  private val socketFactory: SocketFactory = SocketFactory.getDefault(),
  private val connectTimeoutMs: Int = 5_000,
  private val elm: (dev.silveradostats.obd.ByteLink) -> ElmLink = { ElmLink(it) },
) {
  @Volatile private var socket: Socket? = null

  fun run(): LinkCheckResult = LinkCheck.run("tcp $host:$port", ::open, elm)

  /** Ends a running test at once: its connect or its next read fails. Any thread. */
  fun cancel() {
    try {
      socket?.close()
    } catch (_: IOException) {
    }
  }

  private fun open(): StreamByteLink {
    val s: Socket = socketFactory.createSocket()
    socket = s
    try {
      s.tcpNoDelay = true
      s.connect(InetSocketAddress(host, port), connectTimeoutMs)
      return StreamByteLink(s.getInputStream(), s.getOutputStream(), s, "tcp-adapter-reader")
    } catch (e: IOException) {
      try {
        s.close()
      } catch (_: IOException) {
      }
      throw ConnectFailure("connect to $host:$port failed: ${e.javaClass.simpleName}: ${e.message}", retryable = false, e)
    } catch (e: IllegalArgumentException) {
      try {
        s.close()
      } catch (_: IOException) {
      }
      throw ConnectFailure("connect to $host:$port failed: ${e.message}", retryable = false, e)
    }
  }

  companion object {
    const val DEFAULT_HOST = "192.168.0.10"
    const val DEFAULT_PORT = 35000
  }
}
