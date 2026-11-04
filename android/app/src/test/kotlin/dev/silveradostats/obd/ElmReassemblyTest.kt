package dev.silveradostats.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of ElmReassemblyTests (ISO-TP from ELM 'headers on, spaces off' lines). */
class ElmReassemblyTest {
  private fun hex(s: String) = TestSupport.hex(s)

  @Test
  fun elevenBitSingleAndMultiFrame() {
    val lines = listOf(
      "7EC0562415B0A1B", "7EC101462400D010203", "7EC2104050607080910",
      "7EC220A0B0C0D0E0F10", "NO DATA",
    )
    val out = Elm.reassemble(lines, 3)
    assertEquals(0x7ECL, out[0].first)
    assertArrayEquals(hex("62415B0A1B"), out[0].second)
    assertEquals(0x14, out[1].second.size)
    assertArrayEquals(hex("62400D010203 04050607080910 0A0B0C0D0E0F10").copyOf(0x14), out[1].second)
  }

  @Test
  fun twentyNineBitUltiumFrames() {
    // Single frame as quoted in WiCAN #606 (44.19 % SoC) and a 2AF5 multi-frame.
    val lines = listOf("142AF1CB056227C67123", "142AF1CB100D622AF59D759D62", "142AF1CB219D91B5179113AAAA")
    val out = Elm.reassemble(lines, 8)
    assertEquals(0x142AF1CBL, out[0].first)
    assertArrayEquals(hex("6227C67123"), out[0].second)
    assertArrayEquals(hex("622AF59D759D629D91B5179113"), out[1].second)
    val soc = DidField("becm", 0x27C6, "soc", "soc", type = "u16", scale = 0.0015259021896696422)
      .decode(out[0].second.sliceFrom(3)) as Double
    assertEquals(44.19, soc, 0.005)
  }

  @Test
  fun interleavedModulesAndJunk() {
    val lines = listOf(
      "18DAF117100862F19031474353", // first frame from 17
      "SEARCHING...", "18 DA F1 CB 03 62 27 C6", // spaces are removed; a 3-byte single frame
      "18DAF11721494D554C41544544", // completes the 8-byte message from 17
      "18DAF1172230303030300000", // a further consecutive frame with no message open: ignored
      "18DAF145", // header only: skipped
      "18DAF11Z056227C67123", // not hex
      "18DAF145037F2231",
    )
    val out = Elm.reassemble(lines, 8)
    assertEquals(listOf(0x18DAF1CBL, 0x18DAF117L, 0x18DAF145L), out.map { it.first })
    assertArrayEquals(hex("6227C6"), out[0].second)
    // first frame says 8 bytes total: 7 in the first frame, the 8th from the consecutive frame
    assertArrayEquals(hex("62F19031474353 49"), out[1].second)
    assertArrayEquals(hex("7F2231"), out[2].second)
  }

  @Test
  fun consecutiveFrameWithoutFirstFrameIsIgnored() {
    assertEquals(0, Elm.reassemble(listOf("142AF1CB219D91B5179113AAAA"), 8).size)
    // odd number of hex digits: the last nibble is dropped
    val out = Elm.reassemble(listOf("142AF1CB056227C671230"), 8)
    assertArrayEquals(hex("6227C67123"), out[0].second)
  }
}
