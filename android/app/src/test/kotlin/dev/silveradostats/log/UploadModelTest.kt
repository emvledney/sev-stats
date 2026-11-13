package dev.silveradostats.log

import dev.silveradostats.obd.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upload of the log to the owner's server (owner, 2026-11-13): what is sent and how a run goes. */
class UploadModelTest {
  private fun row(i: Int, source: LogSource = LogSource.MX) =
    LogRow(1_800_000_000_000L + i * 60_000L, -300, source, LogTrigger.MX_SCREEN, mapOf(LogCol.SOC_PCT to 80.0 - i))

  private class Queue(val rows: List<LogRow>) : UploadQueue {
    override fun after(rowId: Long, limit: Int) = rows.mapIndexed { i, r -> QueuedRow(i + 1L, r) }.filter { it.id > rowId }.take(limit)

    override fun waiting(rowId: Long) = rows.size - rowId.coerceAtMost(rows.size.toLong())
  }

  @Test
  fun onlyHttpsAddressesExist() {
    assertEquals("https://example.com/sev/v1/rows", UploadWire.endpoint("https://example.com/sev/", "rows"))
    assertEquals("https://example.com/v1/pair", UploadWire.endpoint(" https://example.com ", "pair"))
    assertNull("never in the clear", UploadWire.endpoint("http://example.com/sev", "rows"))
    assertNull("a build with no server", UploadWire.endpoint("", "rows"))
    assertNull(UploadWire.endpoint("https://", "rows"))
    assertEquals("example.com", UploadWire.host("https://example.com/sev"))
  }

  @Test
  fun aRowGoesOutAsTheServerReadsIt() {
    val r = LogRow(
      1_800_000_000_123L, -300, LogSource.MX, LogTrigger.MX_VISIT,
      linkedMapOf(LogCol.SOC_PCT to 81.25, LogCol.CELL_MIN_V to 3.70456, LogCol.PACK_KW to 1e-7, LogCol.PACK_A to Double.NaN),
      linkedMapOf(LogRows.ARRAY_CELL_V to listOf(3.7012, null, 3.7), LogRows.ARRAY_MODULE_T to listOf(21.5, Double.NaN)),
    )
    val body = UploadWire.rowsBody(listOf(r, row(1, LogSource.CAR)), "0.5.0")
    assertEquals(
      """{"app":"0.5.0","rows":[{"at_ms":1800000000123,"tz_min":-300,"source":"mx","trigger":"mx_visit",""" +
        """"values":{"soc_pct":81.25,"cell_min_v":3.7046,"pack_kw":0},"arrays":{"cell_v":[3.7012,null,3.7],"module_t":[21.5,null]}},""" +
        """{"at_ms":1800000060000,"tz_min":-300,"source":"car","trigger":"mx_screen","values":{"soc_pct":79}}]}""",
      body,
    )
    // It is JSON: the app's own parser reads it back.
    val parsed = MiniJson.parse(body) as Map<*, *>
    assertEquals(2, (parsed["rows"] as List<*>).size)
    assertEquals("""{"app":"a\"b\\c d","rows":[]}""", UploadWire.rowsBody(emptyList(), "a\"b\\c\nd"))
  }

  @Test
  fun arraysSurviveTheLogTable() {
    val a = listOf(3.70123, null, 3.7, null)
    assertEquals("3.7012,,3.7,", UploadWire.packArray(a))
    assertEquals(listOf(3.7012, null, 3.7, null), UploadWire.unpackArray(UploadWire.packArray(a)))
    assertEquals(emptyList<Double?>(), UploadWire.unpackArray(""))
  }

  @Test
  fun aRunSendsEverythingInBatchesAndRemembersEachOne() {
    val queue = Queue((0 until 250).map { row(it) })
    val sizes = ArrayList<Int>()
    val moved = ArrayList<Long>()
    val result = UploadRun.run(queue, 0L, "0.5.0", post = { body ->
      sizes += ((MiniJson.parse(body) as Map<*, *>)["rows"] as List<*>).size
      HttpReply(200, """{"received":1,"new":1,"rejected":0}""")
    }, moved = { moved += it })
    assertEquals(listOf(100, 100, 50), sizes)
    assertEquals(listOf(100L, 200L, 250L), moved)
    assertEquals(UploadResult(250L, 250), result)
    // Nothing new: no call at all.
    assertEquals(UploadResult(250L, 0), UploadRun.run(queue, 250L, "0.5.0", post = { error("no call") }))
  }

  @Test
  fun aBatchThatIsNotStoredStaysQueued() {
    val queue = Queue((0 until 250).map { row(it) })
    var calls = 0
    val lost = UploadRun.run(queue, 0L, "0.5.0", post = { if (++calls == 2) HttpReply(null, "SocketTimeoutException: timeout") else HttpReply(200, "{}") })
    assertEquals(100L, lost.cursor)
    assertEquals(100, lost.sent)
    assertEquals(UploadProblem.NETWORK, lost.problem)
    assertTrue(lost.more)
    assertEquals("stops at the failure", 2, calls)

    val refused = UploadRun.run(queue, 100L, "0.5.0", post = { HttpReply(401, """{"detail":"unknown token"}""") })
    assertEquals(UploadProblem.REFUSED, refused.problem)
    assertEquals(100L, refused.cursor)
    assertEquals("unknown token", refused.message)

    val broken = UploadRun.run(queue, 100L, "0.5.0", post = { HttpReply(502, "<html>Bad Gateway</html>") })
    assertEquals(UploadProblem.SERVER, broken.problem)
    assertEquals("HTTP 502: <html>Bad Gateway</html>", broken.message)
    assertEquals(100L, broken.cursor)
  }

  @Test
  fun aLongBacklogIsSentOverSeveralRuns() {
    val queue = Queue((0 until 450).map { row(it) })
    val first = UploadRun.run(queue, 0L, "0.5.0", post = { HttpReply(200, "{}") }, maxBatches = 2)
    assertEquals(200L, first.cursor)
    assertNull(first.problem)
    assertTrue(first.more)
    val rest = UploadRun.run(queue, first.cursor, "0.5.0", post = { HttpReply(200, "{}") }, maxBatches = 5)
    assertEquals(450L, rest.cursor)
    assertFalse(rest.more)
  }

  @Test
  fun pairing() {
    assertEquals("ABCD2345", UploadPolicy.code(" abcd 2345 "))
    assertTrue(UploadPolicy.codeComplete("abcd 2345"))
    assertFalse(UploadPolicy.codeComplete("ABCD234"))
    assertEquals("""{"code":"ABCD2345"}""", UploadWire.pairBody("abcd-2345"))
    assertEquals("tok" to "Silverado", UploadWire.pairing("""{"token":"tok","device":"Silverado"}"""))
    assertNull(UploadWire.pairing("""{"detail":"code not valid"}"""))
    assertNull(UploadWire.pairing("<html>"))
    assertEquals("code not valid", UploadWire.detail("""{"detail":"code not valid"}"""))
  }

  @Test
  fun whenAScreenTriesAnUpload() {
    val now = 1_800_000_000_000L
    assertFalse("not paired: never", UploadPolicy.due(false, null, now))
    assertTrue(UploadPolicy.due(true, null, now))
    assertFalse(UploadPolicy.due(true, now - 60_000L, now))
    assertTrue(UploadPolicy.due(true, now - UploadPolicy.SCREEN_EVERY_MS, now))
    assertTrue("the clock went back", UploadPolicy.due(true, now + 1, now))
  }
}
