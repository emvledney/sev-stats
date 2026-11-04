package dev.silveradostats.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class UdsClientTest {
  private var now = 0.0
  private val slept = mutableListOf<Double>()

  private fun client(ch: Channel) = UdsClient(ch, clock = { now }, sleeper = { slept += it; now += it })

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

  @Test
  fun positiveAnswer() {
    val ch = ScriptedChannel().then("6227C67123")
    val r = client(ch).readDid(0x27C6)
    assertArrayEquals(TestSupport.hex("7123"), r.data)
    assertEquals(0x27C6, r.did)
    assertArrayEquals(TestSupport.hex("2227C6"), ch.sent.single())
  }

  @Test
  fun responsePendingThenAnswer() {
    val ch = ScriptedChannel().then("7F2278").then("6227C60102")
    assertArrayEquals(TestSupport.hex("0102"), client(ch).readDid(0x27C6).data)
    assertEquals("one request only; the rest is listening", 1, ch.sent.size)
    assertEquals(2, ch.receives)
  }

  @Test
  fun responsePendingAndAnswerInOneBatch() {
    val ch = ScriptedChannel().then("7F2278", "6227C60102")
    assertArrayEquals(TestSupport.hex("0102"), client(ch).readDid(0x27C6).data)
  }

  @Test
  fun responsePendingForeverEndsAtTheDeadline() {
    val ch = object : Channel(0x14DACBF1, 0x142AF1CB, false, RateLimiter(0.05, { 0.0 }, {})) {
      var sent = 0
      override fun transmit(payload: ByteArray) {
        sent++
      }

      override fun receive(timeoutS: Double): List<ByteArray> {
        now += 1.0
        return listOf(TestSupport.hex("7F2278"))
      }
    }
    assertThrows<NoResponse> { client(ch).readDid(0x27C6) }
    assertEquals(1, ch.sent)
    assertTrue(now in 5.0..7.0)
  }

  @Test
  fun busyGetsExactlyOneRetry() {
    val ch = ScriptedChannel().then("7F2221").then("6227C67123")
    assertArrayEquals(TestSupport.hex("7123"), client(ch).readDid(0x27C6).data)
    assertEquals(2, ch.sent.size)
    assertEquals(listOf(0.15), slept)

    val busy = ScriptedChannel().then("7F2221").then("7F2221").then("6227C67123")
    val e = assertThrows<NegativeResponse> { client(busy).readDid(0x27C6) }
    assertEquals(0x21, e.nrc)
    assertEquals(2, busy.sent.size)
  }

  @Test
  fun otherNegativeResponsesAreNotRetried() {
    val ch = ScriptedChannel().then("7F2231")
    val e = assertThrows<NegativeResponse> { client(ch).readDid(0xBEEF) }
    assertEquals(0x31, e.nrc)
    assertEquals(0x22, e.sid)
    assertEquals("NRC 0x31 requestOutOfRange for service 0x22", e.message)
    assertEquals(1, ch.sent.size)
  }

  @Test
  fun wrongDidOrSilenceIsNoResponse() {
    assertThrows<NoResponse> { client(ScriptedChannel().then("6227C77123")).readDid(0x27C6) }
    val e = assertThrows<NoResponse> { client(ScriptedChannel()).readDid(0x27C6) }
    assertEquals("no answer from 0x142AF1CB for DID 0x27C6", e.message)
  }

  @Test
  fun dtcReads() {
    assertEquals(listOf("P0AA6"), client(ScriptedChannel().then("43010AA6")).readDtcs(0x03))
    assertEquals(emptyList<String>(), client(ScriptedChannel().then("4300")).readDtcs(0x03))
    assertEquals(listOf("P0AA6", "U0123"), client(ScriptedChannel().then("470AA60000C123")).readDtcs(0x07))
    val e = assertThrows<NegativeResponse> { client(ScriptedChannel().then("7F0A11")).readDtcs(0x0A) }
    assertEquals(0x0A, e.sid)
    assertThrows<NoResponse> { client(ScriptedChannel()).readDtcs(0x03) }
    assertThrows<UnsafeRequestError> { client(ScriptedChannel()).readDtcs(0x04) }
  }

  @Test
  fun functionalQueriesAgainstSim() {
    val rig = TestSupport.SimRig()
    val ch = rig.transport.functionalChannel()
    assertEquals(0, vehicleSpeedKmh(ch))
    val names = obdQuery(ch, 0x09, 0x0A).map { asciiReplace(it.sliceFrom(1)).trim('\u0000', ' ') }
    assertEquals(listOf("GWM-Gateway", "DMCM-DriveMotorCtrl", "BECM-B+EnergyCtrl"), names)
    val vin = asciiReplace(obdQuery(ch, 0x09, 0x02).single().sliceFrom(1))
    assertEquals("1GCSIMULAT*******", Vin.mask(vin))
    rig.transport.silent = true
    assertNull(vehicleSpeedKmh(ch))
  }

  @Test
  fun streamLinkDeliversDataThenReportsEndOfStream() {
    val out = ByteArrayOutputStream()
    val link = StreamByteLink(ByteArrayInputStream("OK\r\r>".toByteArray()), out)
    var got = ByteArray(0)
    val end = System.nanoTime() + 2_000_000_000L
    var failed: FatalTransportError? = null
    while (System.nanoTime() < end) {
      try {
        got += link.readAvailable(100)
      } catch (e: FatalTransportError) {
        failed = e
        break
      }
    }
    assertEquals("OK\r\r>", String(got))
    assertTrue(failed!!.message!!.contains("closed"))
    assertThrows<FatalTransportError> { link.readAvailable(0) }
    assertThrows<FatalTransportError> { link.write("ATZ\r".toByteArray()) }
    assertEquals(0, out.size())
    link.close()
  }

  @Test
  fun streamLinkTimesOutWithEmptyReadsAndCloses() {
    val src = PipedOutputStream()
    val input = PipedInputStream(src)
    val out = ByteArrayOutputStream()
    val link = StreamByteLink(input, out)
    val t0 = System.nanoTime()
    assertEquals(0, link.readAvailable(150).size)
    assertTrue((System.nanoTime() - t0) / 1_000_000 >= 140)
    assertEquals(0, link.readAvailable(0).size)
    src.write("AB".toByteArray())
    src.write("CD".toByteArray())
    src.flush()
    Thread.sleep(50)
    assertEquals("ABCD", String(link.readAvailable(100)))
    link.write("ATI\r".toByteArray())
    assertEquals("ATI\r", out.toString())
    link.close()
    assertThrows<FatalTransportError> { link.readAvailable(0) }
    assertThrows<FatalTransportError> { link.write("ATZ\r".toByteArray()) }
  }

  @Test
  fun streamLinkReadErrorIsFatal() {
    val failing = object : InputStream() {
      override fun read(): Int = throw IOException("socket reset")
      override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("socket reset")
    }
    val link = StreamByteLink(failing, ByteArrayOutputStream())
    val e = assertThrows<FatalTransportError> {
      repeat(50) { link.readAvailable(20) }
    }
    assertTrue(e.message!!.contains("socket reset"))
  }
}
