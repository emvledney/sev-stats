package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review findings on the adapter link: output after the prompt in the same read (a reset announced
 * right after a reply) and the exact-form link allowlist.
 */
class ElmLinkHardeningTest {

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

  /** Answers each written line with one scripted chunk, delivered in a single read. */
  private class ScriptLink(private val replies: Map<String, String>) : ByteLink {
    val writes = mutableListOf<String>()
    private val pending = ArrayDeque<ByteArray>()
    var closed = false

    override fun write(bytes: ByteArray) {
      if (closed) throw LinkLostError("adapter link is closed")
      val line = String(bytes, Charsets.US_ASCII).trimEnd('\r')
      writes += line
      pending.addLast((replies[line] ?: "OK\r\r>").toByteArray(Charsets.ISO_8859_1))
    }

    override fun readAvailable(timeoutMs: Long): ByteArray {
      if (closed) throw LinkLostError("adapter link is closed")
      return pending.removeFirstOrNull() ?: ByteArray(0)
    }

    override fun close() {
      closed = true
    }
  }

  @Test
  fun outputAfterThePromptInTheSameReadIsFatal() {
    val io = ScriptLink(mapOf("ATRV" to "12.6V\r\r>\r\rLV RESET\r\rELM327 v1.5\r\r>"))
    val link = ElmLink(io)
    val e = assertThrows<FatalTransportError> { link.command("ATRV", 1.0) }
    assertTrue(e.message, e.message!!.contains("after the prompt"))
    assertTrue("link closed: nothing more can be written", io.closed && link.closed)
    assertThrows<FatalTransportError> { link.command("2227C6", 1.0) }
    assertEquals(listOf("ATRV"), io.writes)
  }

  @Test
  fun whitespaceOrASecondPromptAfterThePromptIsHarmless() {
    val io = ScriptLink(mapOf("ATE0" to "OK\r\r>\r\n ", "ATH1" to "OK\r\r>>", "ATSP7" to "OK\r\r>"))
    val link = ElmLink(io)
    for (c in listOf("ATE0", "ATH1", "ATSP7")) assertEquals(listOf("OK"), link.command(c, 1.0))
    assertFalse(io.closed)
  }

  /** The review's reproduction, end to end over a socket: nothing reaches the bus after the reset. */
  @Test
  fun resetAnnouncedWithTheReplyStopsBeforeTheNextBusRequest() {
    val reset = AtomicBoolean(false)
    val atrv = AtomicInteger()
    FakeElm(custom = { cmd ->
      when {
        cmd == "ATRV" && atrv.incrementAndGet() == 2 -> {
          reset.set(true)
          "12.6V\r\r>\r\rLV RESET\r\rELM327 v1.5" // FakeElm appends "\r\r>"; one write, one read
        }
        reset.get() -> "$cmd\rNO DATA" // after the reset: echo on, default (functional) headers
        else -> null
      }
    }).use { fake ->
      val t = ElmTransport(ElmLink(fake.connect()), responseTimeoutS = 2.0, minIntervalS = 0.05)
      val client = UdsClient(t.open(0x14DACBF1, 0x142AF1CB), timeoutS = 0.5)
      client.readDid(0x27C6) // selects CB@14 (cached from now on)
      // Usually the reset arrives in the same read and the ATRV itself fails; if the socket split
      // it, the stale-output check before the next write catches it. Either way: fatal, no write.
      val first = runCatching { t.adapterVoltage() }.exceptionOrNull()
      val second = runCatching { client.readDid(0x27C6) }.exceptionOrNull()
      assertTrue("$first / $second", first is FatalTransportError || second is FatalTransportError)
      Thread.sleep(100)
      val after = fake.received.drop(fake.received.lastIndexOf("ATRV") + 1)
      assertEquals("nothing written after the adapter announced a reset", emptyList<String>(), after)
    }
  }

  @Test
  fun timeoutAndLostLinkHaveTheirOwnTypes() {
    FakeElm(stallFirst = "2227C6" to 5_000L).use { fake ->
      val t = ElmTransport(ElmLink(fake.connect()), responseTimeoutS = 0.3, minIntervalS = 0.05)
      val e = assertThrows<TransportError> { UdsClient(t.open(0x14DACBF1, 0x142AF1CB), timeoutS = 0.5).readDid(0x27C6) }
      assertTrue("$e", e is AdapterTimeoutError)
      t.close()
    }
    val fake = FakeElm()
    val t = ElmTransport(ElmLink(fake.connect()), minIntervalS = 0.05)
    fake.close()
    Thread.sleep(100)
    val e = assertThrows<FatalTransportError> { UdsClient(t.open(0x14DACBF1, 0x142AF1CB)).readDid(0x27C6) }
    assertTrue("$e", e is LinkLostError)
    t.close()
  }

  @Test
  fun linkAllowlistIsExact() {
    val ok = listOf(
      "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP7", "ATCAF1", "ATAT1", "ATST64", "ATI", "AT@1", "ATRV", "ATDPN", "STI",
      "ATCP14", "ATCP18", "ATSHDACBF1", "ATSHDA17F1", "ATSHDB33F1", "ATCRA", "ATCRA142AF1CB", "ATCRA18DAF140",
      "ATFCSH14DACBF1", "ATFCSH18DA40F1", "ATFCSD300000", "ATFCSM0", "ATFCSM1",
    )
    for (c in ok) assertTrue(c, Elm.isAllowedLine(c))
    val bad = listOf(
      // Accepted by the probe's prefix rule, refused by the exact forms:
      "ATSH7DF", "ATSHDB33F2", "ATSHDA33F1", "ATSHDAF1F1", "ATSHDBCBF1", "ATSH", "ATSHDACBF1X",
      "ATCRA00000000", "ATCRA18DAF133", "ATCRA142AF1F1", "ATCRA7E8", "ATCRA18DB33F1",
      "ATFCSH18DB33F1", "ATFCSH14DA33F1", "ATFCSH000007E0", "ATFCSD300001", "ATFCSD", "ATFCSM2",
      "ATCP00", "ATCP1F", "ATCP", "ATST", "ATST1234", "ATIB10", "ATIIA13", "ATIGN", "ATIFR0", "STIX", "STIP",
      "ATZ1", "ATRVX", "AT@2", "AT@3ABC", "ATE1", "ATH0", "ATCAF0",
    )
    for (c in bad) assertFalse(c, Elm.isAllowedLine(c))
    // The probe's transport-level prefix check still accepts some of them; the link refuses them
    // before anything is written.
    FakeElm().use { fake ->
      val t = ElmTransport(ElmLink(fake.connect()), minIntervalS = 0.05)
      val before = fake.received.toList()
      for (c in listOf("ATSH7DF", "ATCRA00000000", "ATIB10", "ATFCSH18DB33F1")) {
        assertTrue(c, Elm.isAllowedAt(c))
        assertThrows<UnsafeRequestError> { t.at(c) }
      }
      Thread.sleep(100)
      assertEquals(before, fake.received.toList())
      t.close()
    }
  }

  /** Every line the app's own setup and channel selection produce passes the exact forms. */
  @Test
  fun everyModuleOfTheProfileCanBeSelected() {
    FakeElm().use { fake ->
      val t = ElmTransport(ElmLink(fake.connect()), minIntervalS = 0.05)
      for ((key, m) in TestSupport.profile.modules) {
        try {
          UdsClient(t.open(m.tx, m.rx), timeoutS = 0.3).readDid(0x27C6)
        } catch (_: NoResponse) {
          // NO DATA from modules the fake doesn't emulate: fine, the selection went through.
        } catch (e: UnsafeRequestError) {
          fail("$key: ${e.message}")
        }
      }
      obdQuery(t.functionalChannel(), 0x01, 0x00, timeoutS = 0.3)
      Thread.sleep(100)
      val sent = fake.received.toList()
      assertTrue(sent.toString(), sent.all { Elm.isAllowedLine(it) })
      assertTrue(sent.toString(), "ATSHDB33F1" in sent && "ATCRA" in sent && "ATCP18" in sent && "ATCP14" in sent)
      t.close()
    }
  }
}
