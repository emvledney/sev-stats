package dev.silveradostats.report

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** POSTs the JSON report to the laptop (`./probe receive`). Blocking: call off the main thread. */
object ReportSender {
  data class SendResult(val ok: Boolean, val code: Int?, val message: String, val durationMs: Long)

  private const val TIMEOUT_MS = 5_000

  fun post(url: String, json: String): SendResult {
    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000
    val u = try {
      URL(url.trim())
    } catch (e: Exception) {
      return SendResult(false, null, "Bad URL: ${e.message}", 0)
    }
    if (u.protocol != "http" && u.protocol != "https") {
      return SendResult(false, null, "URL must start with http:// or https://", 0)
    }
    val body = json.toByteArray(Charsets.UTF_8)
    var conn: HttpURLConnection? = null
    return try {
      conn = u.openConnection() as HttpURLConnection
      conn.connectTimeout = TIMEOUT_MS
      conn.readTimeout = TIMEOUT_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      conn.setFixedLengthStreamingMode(body.size)
      conn.outputStream.use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val reply = stream?.use { s -> String(s.readNBytesCompat(512), Charsets.UTF_8) }.orEmpty().trim()
      SendResult(code in 200..299, code, "HTTP $code${if (reply.isNotEmpty()) ": $reply" else ""}", ms())
    } catch (e: IOException) {
      SendResult(false, null, "${e.javaClass.simpleName}: ${e.message}", ms())
    } finally {
      conn?.disconnect()
    }
  }

  /** Reads at most [max] bytes (InputStream.readNBytes needs API 33). */
  private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
      val r = read(buf, n, max - n)
      if (r <= 0) break
      n += r
    }
    return buf.copyOf(n)
  }
}
