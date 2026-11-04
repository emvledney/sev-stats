package dev.silveradostats.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The ELM transport end to end against [FakeElm] over the stream abstraction ([StreamByteLink] on
 * a loopback socket, the same shape as a BluetoothSocket). Port of ElmTcpEndToEnd plus the link
 * discipline cases (settle, resync, stale output, STOPPED, echo, banner, rejected settings).
 */
class ElmEndToEndTest {

  private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
      block()
    } catch (e: Throwable) {
      if (e is T) return e
      throw AssertionError("expected ${T::class.simpleName}, got $e", e)
    }
    fail("expected ${T::class.simpleName}, nothing thrown")
    throw IllegalStateException()
  }

  private fun transport(
    fake: FakeElm,
    responseTimeoutS: Double = 2.0,
    resyncBudgetS: Double = 10.0,
    settleBudgetS: Double = 10.0,
  ) = ElmTransport(
    ElmLink(fake.connect(), resyncBudgetS = resyncBudgetS, settleBudgetS = settleBudgetS),
    responseTimeoutS = responseTimeoutS,
    minIntervalS = 0.05,
  )

  private fun cb14(t: ElmTransport, timeoutS: Double = 0.5) = UdsClient(t.open(0x14DACBF1, 0x142AF1CB), timeoutS = timeoutS)

  private fun waitFor(cond: () -> Boolean) {
    val end = System.nanoTime() + 3_000_000_000L
    while (!cond() && System.nanoTime() < end) Thread.sleep(10)
  }

  @Test
  fun readOverTheStreamLink() {
    FakeElm().use { fake ->
      val t = transport(fake)
      val client = cb14(t)
      assertArrayEquals(TestSupport.hex("7123"), client.readDid(0x27C6).data)
      assertArrayEquals(TestSupport.hex("9D759D629D91B5179113"), client.readDid(0x2AF5).data)
      val (tx18, rx18) = Addressing.ids(0xCB, 0x18)
      assertThrows<NoResponse> { UdsClient(t.open(tx18, rx18), timeoutS = 0.2).readDid(0x27C6) }
      assertEquals(12.6, t.adapterVoltage()!!, 1e-9)
      assertTrue(t.describe(), t.describe().contains("ELM327 v1.5") && t.describe().contains("12.6V"))
      t.close()
      // Everything the adapter was sent is an allowlisted AT command or a read.
      for (cmd in fake.received) assertTrue(cmd, FakeElm.allowed(cmd))
      assertEquals(listOf("ATZ", "ATE0", "ATH1", "ATSP7"), fake.received.take(4))
      assertTrue("ATSP7" in fake.received)
      assertTrue("ATCRA142AF1CB" in fake.received)
      assertTrue("ATFCSH14DACBF1" in fake.received)
      assertTrue(fake.raw.split('\r').dropLast(1).none { it.isEmpty() })
      assertEquals("protocol pinned before anything else", 3, fake.received.indexOf("ATSP7"))
    }
  }

  @Test
  fun channelSelectionIsCachedAndRedoneOnSwitch() {
    FakeElm().use { fake ->
      val t = transport(fake)
      val a = cb14(t)
      a.readDid(0x27C6)
      a.readDid(0x27C6)
      assertEquals(1, fake.received.count { it == "ATCP14" })
      val (tx18, rx18) = Addressing.ids(0xCB, 0x18)
      runCatching { UdsClient(t.open(tx18, rx18)).readDid(0x27C6) }
      a.readDid(0x27C6)
      assertEquals(2, fake.received.count { it == "ATCP14" })
      assertEquals(1, fake.received.count { it == "ATCP18" })
      assertEquals(
        listOf("ATCP14", "ATSHDACBF1", "ATCRA142AF1CB", "ATFCSH14DACBF1", "ATFCSD300000", "ATFCSM1", "2227C6"),
        fake.received.dropWhile { it != "ATCP14" }.take(7),
      )
      t.close()
    }
  }

  @Test
  fun functionalChannelHearsEveryModule() {
    val replies = "18DAF1CB064100BE3EA813\r18DAF11706410098180001\r18DAF145064100800000001"
    FakeElm(custom = { if (it == "0100") replies else null }).use { fake ->
      val t = transport(fake)
      val answers = obdQuery(t.functionalChannel(), 0x01, 0x00)
      assertEquals(3, answers.size)
      assertArrayEquals(TestSupport.hex("BE3EA813"), answers[0])
      val sel = fake.received.dropWhile { !it.startsWith("ATCP") }
      assertEquals(listOf("ATCP18", "ATSHDB33F1", "ATCRA", "ATFCSM0", "0100"), sel)
      t.close()
    }
  }

  @Test
  fun physicalChannelIgnoresOtherModulesFrames() {
    FakeElm(custom = { if (it == "2227C6") "18DAF117056227C61111\r142AF1CB056227C67123" else null }).use { fake ->
      val t = transport(fake)
      assertArrayEquals(TestSupport.hex("7123"), cb14(t).readDid(0x27C6).data)
      t.close()
    }
  }

  @Test
  fun neverWritesToBusyAdapter() {
    FakeElm(stallFirst = "2227C6" to 800L).use { fake ->
      val t = transport(fake, responseTimeoutS = 0.3)
      val client = UdsClient(t.open(0x14DACBF1, 0x142AF1CB))
      assertThrows<TransportError> { client.readDid(0x27C6) } // times out; link marked dirty
      assertTrue(t.link.dirty)
      try {
        client.readDid(0x27C6) // must wait for the late prompt before writing
      } catch (_: FatalTransportError) {
      }
      t.close()
      assertEquals("wrote to the adapter while it was busy", emptyList<String>(), fake.interrupted.toList())
      assertEquals(2, fake.received.count { it == "2227C6" })
    }
  }

  @Test
  fun resyncIsBoundedAndNeverWrites() {
    FakeElm(stallFirst = "2227C6" to 0L, lateReply = null).use { fake ->
      val t = transport(fake, responseTimeoutS = 0.3, resyncBudgetS = 1.0)
      val client = cb14(t)
      assertThrows<TransportError> { client.readDid(0x27C6) }
      val e = assertThrows<FatalTransportError> { client.readDid(0x27C6) }
      assertTrue(e.message, e.message!!.contains("did not return to its prompt"))
      assertTrue(t.link.closed)
      assertEquals("2227C6", fake.received.last())
      assertTrue("no bytes after the stalled request", fake.raw.endsWith("2227C6\r"))
      assertThrows<FatalTransportError> { client.readDid(0x27C6) }
      assertTrue(fake.raw.endsWith("2227C6\r"))
    }
  }

  @Test
  fun lateErrorWhileRecoveringIsFatal() {
    FakeElm(stallFirst = "2227C6" to 500L, lateReply = "CAN ERROR\r\r>").use { fake ->
      val t = transport(fake, responseTimeoutS = 0.3)
      val client = cb14(t)
      assertThrows<TransportError> { client.readDid(0x27C6) }
      val e = assertThrows<FatalTransportError> { client.readDid(0x27C6) }
      assertTrue(e.message, e.message!!.contains("while recovering"))
      assertEquals(1, fake.received.count { it == "2227C6" })
    }
  }

  @Test
  fun rejectedSettingIsFatalAndRequestNotSent() {
    FakeElm(reject = "ATCRA142AF1CB").use { fake ->
      val t = transport(fake)
      assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
      assertNull(t.selected)
      t.close()
      assertFalse("request sent with unconfirmed addressing", "2227C6" in fake.received)
    }
  }

  @Test
  fun rejectedRequiredSettingAtConnectIsFatal() {
    FakeElm(reject = "ATSP7").use { fake ->
      assertThrows<FatalTransportError> { transport(fake) }
      assertEquals(listOf("ATZ", "ATE0", "ATH1", "ATSP7"), fake.received.toList())
    }
  }

  @Test
  fun rejectedBestEffortSettingIsFine() {
    FakeElm(reject = "ATL0").use { fake ->
      val t = transport(fake)
      assertArrayEquals(TestSupport.hex("7123"), cb14(t).readDid(0x27C6).data)
      t.close()
    }
  }

  @Test
  fun adapterResetIsFatal() {
    FakeElm(resetOn = "2227C6").use { fake ->
      val t = transport(fake)
      assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
      assertNull(t.selected)
      t.close()
    }
  }

  @Test
  fun stoppedIsFatal() {
    FakeElm(custom = { if (it == "2227C6") "STOPPED" else null }).use { fake ->
      val t = transport(fake)
      val e = assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
      assertTrue(e.message!!.contains("STOPPED"))
      t.close()
    }
  }

  @Test
  fun echoComingBackIsFatal() {
    FakeElm(custom = { if (it == "2227C6") "2227C6\r142AF1CB056227C67123" else null }).use { fake ->
      val t = transport(fake)
      val e = assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
      assertTrue(e.message!!.contains("echo"))
      t.close()
    }
  }

  @Test
  fun bannerOnNonIdentityCommandIsFatal() {
    FakeElm(custom = { if (it == "ATRV") "ELM327 v1.5" else null }).use { fake ->
      assertThrows<FatalTransportError> { transport(fake) }
      assertEquals("ATRV", fake.received.last())
    }
  }

  @Test
  fun unexpectedOutputBetweenCommandsIsFatal() {
    FakeElm().use { fake ->
      val t = transport(fake)
      val before = fake.received.size
      fake.inject("\r\r>") // a stray prompt is harmless
      Thread.sleep(100)
      assertArrayEquals(TestSupport.hex("7123"), cb14(t).readDid(0x27C6).data)
      val count = fake.received.size
      assertTrue(count > before)
      fake.inject("LV RESET\r\r>")
      Thread.sleep(100)
      val e = assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
      assertTrue(e.message, e.message!!.contains("unexpected adapter output"))
      assertEquals("nothing written after unexpected output", count, fake.received.size)
      t.close()
    }
  }

  @Test
  fun missingBannerIsFatalAndNothingElseSent() {
    FakeElm(custom = { if (it == "ATZ") "OK" else null }).use { fake ->
      val e = assertThrows<FatalTransportError> { transport(fake) }
      assertTrue(e.message!!.contains("banner"))
      waitFor { fake.received.isNotEmpty() }
      assertEquals(listOf("ATZ"), fake.received.toList())
    }
  }

  @Test
  fun otherBannersAccepted() {
    for (banner in listOf("OBDLink MX+ r5.6.19", "STN2255 v5.8.1", "WiCAN Pro")) {
      FakeElm(custom = { if (it == "ATZ") "\r$banner" else null }).use { fake ->
        transport(fake).close()
      }
    }
  }

  @Test
  fun busyAtConnectWaitsForPromptAndSilence() {
    val fake = FakeElm(onConnect = { out, f ->
      repeat(12) {
        out.write("SEARCHING...\r".toByteArray())
        out.flush()
        Thread.sleep(50)
      }
      out.write("\r>".toByteArray())
      out.flush()
      f.promptSentAtNanos = System.nanoTime()
    })
    fake.use {
      val t = transport(fake)
      assertTrue(fake.promptSentAtNanos > 0)
      val quietMs = (fake.firstByteAtNanos - fake.promptSentAtNanos) / 1_000_000
      assertTrue("first write came only $quietMs ms after the prompt", quietMs >= 280)
      assertEquals("ATZ", fake.received.first())
      t.close()
    }
  }

  @Test
  fun busyForeverNeverWrites() {
    val fake = FakeElm(onConnect = { out, f ->
      while (!f.stopWriter) {
        out.write("BUS BUSY\r>".toByteArray())
        out.flush()
        Thread.sleep(100)
      }
    })
    fake.use {
      val e = assertThrows<FatalTransportError> { transport(fake, settleBudgetS = 1.5) }
      assertTrue(e.message!!.contains("kept talking"))
      assertEquals("", fake.raw)
      assertTrue(fake.received.isEmpty())
    }
  }

  @Test
  fun refusesElevenBitAndUnlistedCommands() {
    FakeElm().use { fake ->
      val t = transport(fake)
      val e = assertThrows<TransportError> { t.open(0x7E4, 0x7EC) }
      assertTrue(e.message!!.contains("29-bit"))
      assertThrows<TransportError> { t.open(0x7DF, 0x7E8, functional = true) }
      assertThrows<UnsafeRequestError> { t.open(0x18DA33F1, 0x18DAF133) }
      t.close()
    }
  }

  @Test
  fun refusesUnlistedAdapterCommandsAndBusLines() {
    FakeElm().use { fake ->
      val t = transport(fake)
      val before = fake.received.toList()
      assertThrows<UnsafeRequestError> { t.at("ATMA") }
      assertThrows<UnsafeRequestError> { t.at("ATSP0") }
      assertThrows<UnsafeRequestError> { t.link.command("1101", 1.0) }
      assertThrows<UnsafeRequestError> { t.link.command("2E402800", 1.0) }
      assertThrows<UnsafeRequestError> { t.link.command("ATZ\r1101", 1.0) }
      assertThrows<TransportError> { t.link.command("", 1.0) }
      Thread.sleep(100)
      assertEquals(before, fake.received.toList())
      t.close()
    }
  }

  @Test
  fun closedConnectionIsFatal() {
    val fake = FakeElm()
    val t = transport(fake)
    fake.close()
    Thread.sleep(100)
    assertThrows<FatalTransportError> { cb14(t).readDid(0x27C6) }
    t.close()
  }

  @Test
  fun voltageParsing() {
    assertEquals(12.6, ElmTransport.parseVolts("12.6V")!!, 1e-12)
    assertEquals(12.6, ElmTransport.parseVolts(" 12.6 V ")!!, 1e-12)
    assertEquals(13.0, ElmTransport.parseVolts("13.v")!!, 1e-12)
    assertEquals(9.0, ElmTransport.parseVolts("9")!!, 1e-12)
    for (bad in listOf("?", "", "V", "NO DATA", "12.6f", "12,6V", "nan", "ELM327 v1.5")) {
      assertNull(bad, ElmTransport.parseVolts(bad))
    }
    assertNotNull(ElmTransport.parseVolts("1e1"))
  }
}
