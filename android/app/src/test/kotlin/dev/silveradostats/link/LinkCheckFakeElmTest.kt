package dev.silveradostats.link

import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.Elm
import dev.silveradostats.obd.ElmLink
import dev.silveradostats.obd.ElmTransport
import dev.silveradostats.obd.StreamByteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The init-only adapter check behind the MX+ Test link ([LinkCheck]: StreamByteLink + ElmLink +
 * ElmTransport setup only), on a loopback socket where the app has the Bluetooth one. Adapter-local
 * commands only, nothing written to a busy adapter, nothing written after a stall, a missing banner
 * or a rejected setting stops it, and the result says how far it got and what was sent.
 */
class LinkCheckFakeElmTest {

  private fun check(port: Int, elm: (ByteLink) -> ElmLink = { ElmLink(it) }) =
    LinkCheck.run(
      "fake 127.0.0.1:$port",
      {
        val s = Socket(InetAddress.getLoopbackAddress(), port)
        s.tcpNoDelay = true
        StreamByteLink(s.getInputStream(), s.getOutputStream(), s, "fake-elm-reader")
      },
      elm,
    )

  /** What ElmTransport's setup sends, in order. */
  private val setup = listOf("ATZ", "ATE0", "ATH1", "ATSP7", "ATL0", "ATS0", "ATCAF1", "ATAT1", "ATST64", "ATI", "STI", "ATRV")

  /** Adapter-local: allowlisted AT / ST commands only, never a bus request. */
  private fun assertAdapterLocal(cmds: List<String>) {
    for (c in cmds) {
      assertTrue("not an adapter command: $c", (c.startsWith("AT") || c.startsWith("ST")) && Elm.isAllowedAt(c) && Elm.isAllowedLine(c))
    }
  }

  @Test
  fun happyPath_bannerInfoAndVoltage() {
    FakeElmServer(respond = FakeElmServer::standardReplies).use { srv ->
      val r = check(srv.port)
      assertTrue("result should be ok: ${r.error}", r.ok)
      assertNull(r.error)
      assertEquals("done", r.stage)
      assertEquals(setup, srv.commands.toList())
      assertEquals(setup.joinToString("") { "$it\r" }, srv.rawReceived)
      assertEquals(setup, r.sent)
      assertAdapterLocal(srv.commands.toList())
      assertTrue(r.banner!!.contains("ELM327"))
      assertFalse("echo is not part of the banner", r.banner!!.contains("ATZ"))
      assertTrue(r.ident!!.startsWith("ELM327 v1.5"))
      assertEquals(12.6, r.volts!!, 1e-9)
      assertEquals("fake 127.0.0.1:${srv.port}", r.target)
      assertNotNull(r.connectMs)
      assertTrue("link closed after the check", srv.awaitClientClosed())
    }
  }

  @Test
  fun happyPath_otherBannersAccepted() {
    for (banner in listOf("OBDLink MX+ r5.6.19", "STN1155 v4.2.0", "WiCAN Pro")) {
      FakeElmServer(respond = { c -> if (c == "ATZ") "\r$banner\r\r>" else FakeElmServer.standardReplies(c) }).use { srv ->
        val r = check(srv.port)
        assertTrue("banner $banner should be accepted: ${r.error}", r.ok)
        assertEquals(banner, r.banner)
      }
    }
  }

  @Test
  fun bannerMissing_failsAndStopsAfterAtz() {
    FakeElmServer(respond = { c -> if (c == "ATZ") "ATZ\r\rHELLO WORLD 1.0\r\r>" else FakeElmServer.standardReplies(c) }).use { srv ->
      val r = check(srv.port)
      assertFalse(r.ok)
      assertEquals("setup", r.stage)
      assertTrue(r.error!!.contains("banner"))
      assertNull(r.banner)
      assertTrue(srv.awaitClientClosed())
      assertEquals("nothing after ATZ may be sent", listOf("ATZ"), srv.commands.toList())
    }
  }

  @Test
  fun rejectedRequiredSetting_stopsBeforeAnythingElse() {
    FakeElmServer(respond = { c -> if (c == "ATSP7") "?\r\r>" else FakeElmServer.standardReplies(c) }).use { srv ->
      val r = check(srv.port)
      assertFalse(r.ok)
      assertTrue(r.error!!.contains("rejected"))
      assertTrue(srv.awaitClientClosed())
      assertEquals(listOf("ATZ", "ATE0", "ATH1", "ATSP7"), srv.commands.toList())
    }
  }

  @Test
  fun timeout_noFurtherWrites() {
    // ATI never gets a prompt. The adapter may still be busy: nothing else may be written, and
    // the bounded resync (waiting for '>' then silence, without writing) gives up and closes.
    FakeElmServer(respond = { c -> if (c == "ATI") null else FakeElmServer.standardReplies(c) }).use { srv ->
      val r = check(srv.port) { ElmLink(it, resyncBudgetS = 1.0) }
      assertFalse(r.ok)
      assertTrue(r.error!!, r.error!!.contains("prompt"))
      assertTrue("client must close after a timeout", srv.awaitClientClosed())
      assertEquals("ATI", srv.stalledOn)
      assertEquals("no bytes may arrive after the stalled command", 0, srv.bytesAfterStall)
      assertEquals(setup.subList(0, setup.indexOf("ATI") + 1), srv.commands.toList())
    }
  }

  @Test
  fun busyAtConnect_waitsForPromptAndSilence() {
    val srv = FakeElmServer(
      onConnect = { out, s ->
        // A busy adapter: streaming output for ~600 ms, then a prompt, then silence.
        repeat(12) {
          out.write("SEARCHING...\r".toByteArray())
          out.flush()
          Thread.sleep(50)
        }
        out.write("\r>".toByteArray())
        out.flush()
        s.promptSentAtNanos = System.nanoTime()
      },
      respond = FakeElmServer::standardReplies,
    )
    srv.use {
      val r = check(srv.port)
      assertTrue("should succeed after settling: ${r.error}", r.ok)
      assertTrue(srv.promptSentAtNanos > 0)
      val quietMs = (srv.firstByteAtNanos - srv.promptSentAtNanos) / 1_000_000
      assertTrue("first write came only $quietMs ms after the prompt", quietMs >= 280)
      assertEquals(setup, srv.commands.toList())
    }
  }

  @Test
  fun busyForever_neverWrites() {
    // Output that never ends in prompt + silence: settle must give up without writing anything.
    val srv = FakeElmServer(
      onConnect = { out, s ->
        while (!s.stopWriter) {
          out.write("BUS BUSY\r>".toByteArray())
          out.flush()
          Thread.sleep(100)
        }
      },
      respond = FakeElmServer::standardReplies,
    )
    srv.use {
      val r = check(srv.port) { ElmLink(it, settleBudgetS = 1.5) }
      assertFalse(r.ok)
      assertTrue(r.error!!, r.error!!.contains("kept talking"))
      assertTrue(r.sent.isEmpty())
      assertTrue(srv.awaitClientClosed())
      assertEquals("", srv.rawReceived)
      assertTrue(srv.commands.isEmpty())
    }
  }

  @Test
  fun connectRefused_reportsError() {
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } // closed again: nothing listens
    val r = check(port)
    assertFalse(r.ok)
    assertEquals("connect", r.stage)
    assertTrue(r.error!!.startsWith("connect"))
    assertTrue(r.sent.isEmpty())
  }

  @Test
  fun parsing() {
    assertEquals(12.6, ElmTransport.parseVolts("12.6V")!!, 1e-9)
    assertEquals(13.05, ElmTransport.parseVolts("13.05 V")!!, 1e-9)
    assertEquals(9.0, ElmTransport.parseVolts("9V")!!, 1e-9)
    assertNull(ElmTransport.parseVolts("?"))
    assertNull(ElmTransport.parseVolts("ATRV"))
    assertNull(ElmTransport.parseVolts(""))
  }
}
