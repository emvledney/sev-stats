package dev.silveradostats.log

import dev.silveradostats.obd.MiniJson
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Pure Kotlin (no android.* imports): what the upload of the log to the owner's server sends, and how a
 * run of it goes (owner, 2026-11-13). Unit tested on the JVM. The server is server/ in this repository;
 * its README describes the calls.
 *
 * The log on the head unit stays the record. The upload keeps one number, the id of the newest row the
 * server has answered for, and sends what comes after it. The server ignores a row it already has, so a
 * batch whose answer was lost is simply sent again. Only rows of the log go out: no position, no VIN,
 * and never demo data, which never reaches the log.
 */

/** A row of the log with its id in the table: ids only grow, so "after this id" is "not sent yet". */
data class QueuedRow(val id: Long, val row: LogRow)

/** The log as the upload reads it. Blocking: call off the main thread. */
interface UploadQueue {
  /** At most [limit] rows with an id above [rowId], lowest id first, with their arrays. */
  fun after(rowId: Long, limit: Int): List<QueuedRow>

  /** How many rows have an id above [rowId]. */
  fun waiting(rowId: Long): Long
}

/** An answer of the server; [code] null = none came (no network, a timeout). */
data class HttpReply(val code: Int?, val text: String)

enum class UploadProblem {
  /** No answer: tried again later, nothing lost. */
  NETWORK,

  /** The server no longer knows this truck's token (uploads stopped there, or the truck was paired again). */
  REFUSED,

  /** An answer other than "stored". */
  SERVER,
}

/** How a run went. [cursor]: the id the server has answered for now. [more]: rows are still waiting. */
data class UploadResult(val cursor: Long, val sent: Int, val problem: UploadProblem? = null, val message: String? = null, val more: Boolean = false)

object UploadPolicy {
  /** Rows per call: with every cell group in each MX+ row a call stays far below the server's 1 MB. */
  const val BATCH_ROWS = 100

  /** Calls per run, so that a long backlog (the first upload) cannot hold the hourly job for long. */
  const val MAX_BATCHES = 30

  /** While a screen is up, an upload is tried this often. */
  const val SCREEN_EVERY_MS = 5 * 60_000L

  /** How often a screen checks whether one is due. */
  const val SCREEN_CHECK_MS = 60_000L

  const val CODE_LENGTH = 8

  fun due(paired: Boolean, lastTryMs: Long?, nowMs: Long): Boolean =
    paired && (lastTryMs == null || nowMs - lastTryMs >= SCREEN_EVERY_MS || nowMs < lastTryMs)

  /** A pairing code as the server wants it: the dashboard shows it in two groups, and either case may be typed. */
  fun code(typed: String): String = typed.filter { it.isLetterOrDigit() }.uppercase()

  fun codeComplete(typed: String): Boolean = code(typed).length == CODE_LENGTH
}

object UploadWire {
  /**
   * The address of one call, or null when [base] is not an https address: the log is never sent in the
   * clear, whatever the network security config allows for the report to the laptop.
   */
  fun endpoint(base: String, path: String): String? {
    val b = base.trim().trimEnd('/')
    if (!b.startsWith("https://") || b.length <= "https://".length) return null
    return "$b/v1/$path"
  }

  /** The server's name, for the Settings page. */
  fun host(base: String): String = base.trim().substringAfter("://").substringBefore('/')

  fun pairBody(code: String): String = """{"code":${text(UploadPolicy.code(code))}}"""

  fun rowsBody(rows: List<LogRow>, appVersion: String): String {
    val sb = StringBuilder(rows.size * 1024)
    sb.append("""{"app":""").append(text(appVersion)).append(""","rows":[""")
    rows.forEachIndexed { i, r ->
      if (i > 0) sb.append(',')
      sb.append("""{"at_ms":""").append(r.atMs).append(""","tz_min":""").append(r.tzMinutes)
      sb.append(""","source":""").append(text(r.source.code)).append(""","trigger":""").append(text(r.trigger.code))
      sb.append(""","values":{""")
      var first = true
      for ((c, v) in r.values) {
        if (!v.isFinite()) continue
        if (!first) sb.append(',')
        first = false
        sb.append(text(c.key)).append(':').append(number(v))
      }
      sb.append('}')
      if (r.arrays.isNotEmpty()) {
        sb.append(""","arrays":{""")
        r.arrays.entries.forEachIndexed { k, (kind, vals) ->
          if (k > 0) sb.append(',')
          sb.append(text(kind)).append(":[")
          vals.forEachIndexed { j, x ->
            if (j > 0) sb.append(',')
            sb.append(if (x == null || !x.isFinite()) "null" else number(x))
          }
          sb.append(']')
        }
        sb.append('}')
      }
      sb.append('}')
    }
    return sb.append("]}").toString()
  }

  /** The token and the truck's name out of the answer to a pairing, or null when it holds none. */
  fun pairing(reply: String): Pair<String, String>? {
    val o = runCatching { MiniJson.parse(reply) }.getOrNull() as? Map<*, *> ?: return null
    val token = (o["token"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return token to ((o["device"] as? String).orEmpty())
  }

  /** The server's own words for a refusal ({"detail": "..."}), else the start of what it sent. */
  fun detail(reply: String): String {
    val o = runCatching { MiniJson.parse(reply) }.getOrNull() as? Map<*, *>
    return (o?.get("detail") as? String) ?: reply.take(120)
  }

  /** An array as the log table keeps it: numbers between commas, nothing where a position had no reading. */
  fun packArray(vals: List<Double?>): String = vals.joinToString(",") { x -> if (x == null || !x.isFinite()) "" else number(x) }

  fun unpackArray(packed: String): List<Double?> = if (packed.isEmpty()) emptyList() else packed.split(',').map { it.toDoubleOrNull() }

  /** As the CSV: at most four decimals (0.1 mV), no trailing zeros, never an exponent. */
  private fun number(x: Double): String = BigDecimal(x).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

  /** A JSON string. Everything sent is a plain name or a version, so escaping is only a guard. */
  private fun text(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (ch in s) {
      when {
        ch == '"' || ch == '\\' -> sb.append('\\').append(ch)
        ch < ' ' -> sb.append(' ')
        else -> sb.append(ch)
      }
    }
    return sb.append('"').toString()
  }
}

object UploadRun {
  /**
   * Sends what is after [cursor], a batch at a time, through [post] (the body of one call in, the answer
   * out). [moved] is told each new cursor as soon as the server has answered for it, so that a run cut
   * short loses nothing. Stops at the first batch that is not stored: its rows stay queued.
   */
  fun run(queue: UploadQueue, cursor: Long, appVersion: String, post: (String) -> HttpReply, moved: (Long) -> Unit = {}, maxBatches: Int = UploadPolicy.MAX_BATCHES): UploadResult {
    var at = cursor
    var sent = 0
    repeat(maxBatches) {
      val batch = queue.after(at, UploadPolicy.BATCH_ROWS)
      if (batch.isEmpty()) return UploadResult(at, sent)
      val reply = post(UploadWire.rowsBody(batch.map { it.row }, appVersion))
      when (reply.code) {
        200 -> {
          at = batch.last().id
          sent += batch.size
          moved(at)
        }
        null -> return UploadResult(at, sent, UploadProblem.NETWORK, reply.text, more = true)
        401 -> return UploadResult(at, sent, UploadProblem.REFUSED, UploadWire.detail(reply.text), more = true)
        else -> return UploadResult(at, sent, UploadProblem.SERVER, "HTTP ${reply.code}: ${UploadWire.detail(reply.text)}", more = true)
      }
    }
    return UploadResult(at, sent, more = queue.waiting(at) > 0)
  }
}
