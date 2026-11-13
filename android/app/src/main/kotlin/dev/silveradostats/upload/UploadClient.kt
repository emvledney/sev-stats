package dev.silveradostats.upload

import dev.silveradostats.log.HttpReply
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One HTTPS POST of JSON to the owner's server. Blocking: call off the main thread.
 * No redirects are followed, so the token can only go to the address this build was given.
 */
object UploadClient {
  private const val CONNECT_TIMEOUT_MS = 10_000
  private const val READ_TIMEOUT_MS = 30_000
  private const val MAX_REPLY_BYTES = 2048

  /** [url]: from UploadWire.endpoint, which hands out https addresses only. */
  fun post(url: String, token: String?, json: String): HttpReply {
    val body = json.toByteArray(Charsets.UTF_8)
    var conn: HttpURLConnection? = null
    return try {
      val u = URL(url)
      if (u.protocol != "https") return HttpReply(null, "Not an https address")
      conn = u.openConnection() as HttpURLConnection
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
      conn.setFixedLengthStreamingMode(body.size)
      conn.outputStream.use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      HttpReply(code, stream?.use { s -> String(s.readAtMost(MAX_REPLY_BYTES), Charsets.UTF_8) }.orEmpty().trim())
    } catch (e: IOException) {
      HttpReply(null, "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: IllegalArgumentException) {
      HttpReply(null, "Bad address: ${e.message}")
    } finally {
      conn?.disconnect()
    }
  }

  private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
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
