package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of SafetyTests / AddressingTests in tools/probe/tests/test_probe.py, plus app-side additions. */
class SafetyTest {

  private inline fun <reified T : Throwable> assertThrows(msg: String = "", block: () -> Unit): T {
    try {
      block()
    } catch (e: Throwable) {
      if (e is T) return e
      throw AssertionError("expected ${T::class.simpleName} $msg, got $e", e)
    }
    fail("expected ${T::class.simpleName} $msg, nothing thrown")
    throw IllegalStateException()
  }

  private fun bytes(vararg v: Int) = bytesOf(*v)

  @Test
  fun onlyReadsPassPhysical() {
    val ch = ScriptedChannel()
    ch.request(bytes(0x22, 0x27, 0xC6), 0.01)
    for (mode in listOf(0x03, 0x07, 0x0A)) ch.request(bytes(mode), 0.01)
    for (sid in 0 until 0x100) {
      if (sid == 0x22) continue
      assertThrows<UnsafeRequestError>("SID 0x${hex(sid, 2)}") { ch.request(bytes(sid, 0x01, 0x02), 0.01) }
    }
    assertEquals(4, ch.sent.size)
  }

  @Test
  fun dangerousRequestsRejected() {
    val ch = ScriptedChannel()
    val payloads = listOf(
      listOf(0x10, 0x03), listOf(0x11, 0x01), listOf(0x14, 0xFF, 0xFF, 0xFF), listOf(0x27, 0x01),
      listOf(0x28, 0x03, 0x01), listOf(0x2E, 0x40, 0x28, 0x00), listOf(0x31, 0x01, 0x02, 0x03),
      listOf(0x3E, 0x00), listOf(0x85, 0x02), listOf(0x04), listOf(0x08), listOf(0x14), listOf(0x19, 0x02, 0xFF),
      listOf(0x23, 0x00, 0x00), listOf(0x2C, 0x01), listOf(0x22), listOf(0x22, 0x40),
      listOf(0x22, 0x40, 0x28, 0x40, 0x29), listOf(0x03, 0x00), emptyList(),
    )
    for (p in payloads) assertThrows<UnsafeRequestError>(p.toString()) { ch.request(bytes(*p.toIntArray()), 0.01) }
    assertTrue(ch.sent.isEmpty())
  }

  @Test
  fun everyNon22ServiceRejectedAtEveryLength() {
    for (sid in 0 until 0x100) {
      for (len in 0..8) {
        val p = ByteArray(len) { if (it == 0) sid.toByte() else 0x01 }
        val ok = (len == 3 && sid == 0x22) || (len == 1 && sid in setOf(0x03, 0x07, 0x0A))
        try {
          Safety.checkPhysical(p)
          assertTrue("0x${hex(sid, 2)} len $len must be rejected", ok)
        } catch (e: UnsafeRequestError) {
          assertFalse("0x${hex(sid, 2)} len $len must pass", ok)
        }
      }
    }
  }

  @Test
  fun functionalOnlyModes01And09() {
    val ch = ScriptedChannel(functional = true)
    ch.request(bytes(0x01, 0x0D), 0.01)
    ch.request(bytes(0x09, 0x02), 0.01)
    val bad = listOf(
      listOf(0x04), listOf(0x03), listOf(0x07), listOf(0x08, 0x01), listOf(0x0A), listOf(0x22, 0x40, 0x28),
      listOf(0x01, 0x0D, 0x0C), listOf(0x10, 0x01), listOf(0x03, 0x00), emptyList(),
    )
    for (p in bad) assertThrows<UnsafeRequestError>(p.toString()) { ch.request(bytes(*p.toIntArray()), 0.01) }
    for (mode in 0 until 0x100) {
      if (mode == 0x01 || mode == 0x09) continue
      assertThrows<UnsafeRequestError> { Safety.checkFunctional(bytes(mode, 0x00)) }
    }
    assertEquals(2, ch.sent.size)
  }

  @Test
  fun rateLimiterFloor() {
    for (bad in listOf(0.001, 0.01, 0.049)) assertThrows<IllegalArgumentException> { RateLimiter(bad) }
    RateLimiter(0.05)
  }

  @Test
  fun rateLimiterSleepsTheRemainingGap() {
    var now = 100.0
    val slept = mutableListOf<Double>()
    val lim = RateLimiter(0.075, { now }, { s ->
      slept += s
      now += s
    })
    lim.await() // first request: no wait
    assertEquals(emptyList<Double>(), slept)
    now += 0.02
    lim.await()
    assertEquals(1, slept.size)
    assertEquals(0.055, slept[0], 1e-9)
    now += 1.0
    lim.await()
    assertEquals(1, slept.size)
    lim.await() // back to back
    assertEquals(0.075, slept.last(), 1e-9)
  }

  @Test
  fun onlyDiagnosticAddresses() {
    Safety.checkAddress(0x14DACBF1, 0x142AF1CB, false)
    Safety.checkAddress(0x18DA40F1, 0x18DAF140, false)
    Safety.checkAddress(0x18DB33F1, 0x18DAF100, true)
    val bad = listOf(
      Triple(0x18DB33F1L, 0x18DAF1CBL, false), // broadcast as physical
      Triple(0x7E4L, 0x7ECL, false), Triple(0x7DFL, 0x7E8L, true), // 11-bit
      Triple(0x0CF00400L, 0x18DAF1CBL, false), // J1939-style ID
      Triple(0x14DACBF1L, 0x18DAF1CBL, false), // mismatched pair
      Triple(0x10DACBF1L, 0x102AF1CBL, false), // unknown priority
      Triple(0x14DACBF2L, 0x142AF1CBL, false), // not the tester address
      Triple(0x18DA33F1L, 0x18DAF133L, false),
      Triple(0x14DACBF1L, 0x142AF1CBL, true), // physical as functional
      Triple(0x18DAF1F1L, 0x18DAF1F1L, false), // the tester's own address
      Triple(0x1_14DACBF1L, 0x142AF1CBL, false), // above 29 bits
      Triple(-0x14DACBF1L, 0x142AF1CBL, false),
    )
    for ((tx, rx, fn) in bad) {
      assertThrows<UnsafeRequestError>("${hexId(tx)}/${hexId(rx)}") { Safety.checkAddress(tx, rx, fn) }
    }
  }

  @Test
  fun channelRejectsBadAddress() {
    assertThrows<UnsafeRequestError> { SimTransport().open(0x18DB33F1, 0x18DAF1CB) }
    assertThrows<UnsafeRequestError> { SimTransport().open(0x7E4, 0x7EC) }
  }

  @Test
  fun elmAllowlistExcludesBusWriters() {
    for (cmd in listOf(
      "ATMA", "ATBD", "ATWM", "ATSW", "ATD", "STPX", "STM", "ATPP", "ATBRD",
      "ATSD", "3E00", "1003", "ATSP0", "ATSPA", "ATSP6", "ATAR", "ATWS", "ATCSM0",
    )) {
      assertFalse(cmd, Elm.isAllowedAt(cmd))
      assertFalse(cmd, Elm.isAllowedLine(cmd))
    }
  }

  @Test
  fun linkLineAllowlist() {
    for (ok in listOf("ATZ", "ATE0", "ATSP7", "ATSH DACBF1".replace(" ", ""), "ATCRA142AF1CB", "AT@1", "STI", "2227C6", "03", "07", "0A", "0100", "090A")) {
      assertTrue(ok, Elm.isAllowedLine(ok))
    }
    for (bad in listOf(
      "", " ", "ATZ ", " ATZ", "ATZ\r0100", "ATZ\n", "atz", "22 27C6", "2227C", "2227C6C6", "2227c6", "1101", "3E00",
      "04", "08", "0101FF", "0102030405", "2E402800", "1003", "\r", "ATMA", "STPX", "22", "0B", "09",
    )) {
      assertFalse("'${bad.replace("\r", "\\r").replace("\n", "\\n")}'", Elm.isAllowedLine(bad))
    }
  }

  @Test
  fun elmRefusesBareCrAndUnlistedLines() {
    val writes = mutableListOf<ByteArray>()
    val io = object : ByteLink {
      override fun write(bytes: ByteArray) {
        writes += bytes
      }

      override fun readAvailable(timeoutMs: Long) = ByteArray(0)
      override fun close() {}
    }
    val link = ElmLink(io)
    assertThrows<TransportError> { link.command("", 0.1) }
    assertThrows<TransportError> { link.command("  ", 0.1) }
    assertThrows<UnsafeRequestError> { link.command("1101", 0.1) }
    assertThrows<UnsafeRequestError> { link.command("ATZ\r0100", 0.1) }
    assertThrows<UnsafeRequestError> { link.command("ATMA", 0.1) }
    assertTrue("nothing may be written", writes.isEmpty())
  }

  @Test
  fun ultiumIds() {
    assertEquals(0x14DACBF1L to 0x142AF1CBL, Addressing.ids(0xCB, 0x14))
    assertEquals(0x18DA40F1L to 0x18DAF140L, Addressing.ids(0x40, 0x18))
    assertEquals(0x14DACBF1L, Addressing.flowControlTarget(0x142AF1CB))
    assertEquals(0x18DA17F1L, Addressing.flowControlTarget(0x18DAF117))
    assertEquals(0x7E4L, Addressing.flowControlTarget(0x7EC))
    assertThrows<IllegalArgumentException> { Addressing.ids(0xCB, 0x10) }
    assertThrows<IllegalArgumentException> { Addressing.ids(0x100, 0x14) }
  }

  @Test
  fun profileAddressTable() {
    val expected = mapOf(
      "becm" to (0x14DACBF1L to 0x142AF1CBL),
      "dmcm" to (0x14DA17F1L to 0x142AF117L),
      "dmc2" to (0x14DA1DF1L to 0x142AF11DL),
      "bcm" to (0x18DA40F1L to 0x18DAF140L),
      "gwm" to (0x18DA45F1L to 0x18DAF145L),
    )
    val modules = TestSupport.profile.modules
    assertEquals(expected.keys, modules.keys)
    for ((k, ids) in expected) {
      assertEquals(k, ids, modules.getValue(k).tx to modules.getValue(k).rx)
      Safety.checkAddress(ids.first, ids.second, false)
    }
  }

  @Test
  fun vinMask() {
    assertEquals("1GCSIMULAT*******", Vin.mask("1GCSIMULATED00000"))
    assertEquals("1GCSIMULAT*******", String(Vin.maskPayload(0xF190, "1GCSIMULATED00000".toByteArray())))
    assertEquals("1GCSIMULAT*******", String(Vin.maskPayload(0x2AE1, "1GCSIMULATED00000".toByteArray())))
    assertEquals("SHORT", String(Vin.maskPayload(0x2AE1, "SHORT".toByteArray())))
    assertEquals("VIN 1GCSIMULAT******* seen", Vin.maskText("VIN 1GCSIMULATED00000 seen"))
    assertEquals("x1GCSIMULATED000001", Vin.maskText("x1GCSIMULATED000001"))
  }
}
