package dev.silveradostats.upload

import android.content.Context
import androidx.core.content.edit
import dev.silveradostats.BuildConfig
import dev.silveradostats.log.HttpReply
import dev.silveradostats.log.LogHub
import dev.silveradostats.log.UploadPolicy
import dev.silveradostats.log.UploadProblem
import dev.silveradostats.log.UploadResult
import dev.silveradostats.log.UploadRun
import dev.silveradostats.log.UploadWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the Settings page shows of the upload. */
data class UploadState(
  /** This build was given a server (UPLOAD_URL in local.properties). */
  val configured: Boolean,
  val host: String,
  val paired: Boolean,
  /** The truck's name on the server. */
  val device: String? = null,
  /** Rows of the log the server has not answered for; null until counted. */
  val waiting: Long? = null,
  val lastOkMs: Long? = null,
  /** How the newest pairing or upload went, in words; [failed] says which colour. */
  val status: String? = null,
  val failed: Boolean = false,
  val busy: Boolean = false,
)

/**
 * The process's one upload of the log to the owner's server (owner, 2026-11-13): rows the log already
 * holds, over HTTPS, to the one address this build was given. It reads the log's table and nothing
 * else: no car service, no MX+, no position.
 *
 *  - [run]: from the hourly job, after its rows are in the log, and every few minutes while a screen is
 *    up ([whileOnScreen]). With no network it fails quietly and the rows wait;
 *  - [pair]: once, in Park, with the code the server's dashboard shows. The answer is a token kept in
 *    the app's private storage. It is in no report and no export.
 */
class Uploader private constructor(private val app: Context) {
  private val prefs = app.getSharedPreferences("upload", Context.MODE_PRIVATE)
  private val base = BuildConfig.UPLOAD_URL
  private val rowsUrl = UploadWire.endpoint(base, "rows")
  private val pairUrl = UploadWire.endpoint(base, "pair")

  @OptIn(ExperimentalCoroutinesApi::class)
  private val io = Dispatchers.IO.limitedParallelism(1)
  private val oneAtATime = Mutex()

  @Volatile private var lastTryMs: Long? = null

  private val _state = MutableStateFlow(
    UploadState(
      configured = rowsUrl != null,
      host = UploadWire.host(base),
      paired = prefs.getString(KEY_TOKEN, null) != null,
      device = prefs.getString(KEY_DEVICE, null),
      lastOkMs = prefs.getLong(KEY_LAST_OK, 0L).takeIf { it > 0 },
    ),
  )
  val state: StateFlow<UploadState> = _state.asStateFlow()

  /** For as long as a screen of the app is up (returns only when cancelled). */
  suspend fun whileOnScreen() {
    while (true) {
      if (UploadPolicy.due(_state.value.paired, lastTryMs, System.currentTimeMillis())) run() else count()
      delay(UploadPolicy.SCREEN_CHECK_MS)
    }
  }

  /** Sends what the server does not have yet. Null when there is no server or no pairing. */
  suspend fun run(): UploadResult? {
    val url = rowsUrl ?: return null
    return oneAtATime.withLock {
      val token = prefs.getString(KEY_TOKEN, null) ?: return@withLock null
      lastTryMs = System.currentTimeMillis()
      _state.update { it.copy(busy = true) }
      val queue = LogHub.get(app).uploadQueue
      val result = withContext(io) {
        UploadRun.run(
          queue,
          prefs.getLong(KEY_CURSOR, 0L),
          BuildConfig.VERSION_NAME,
          post = { body -> UploadClient.post(url, token, body) },
          moved = { cursor -> prefs.edit { putLong(KEY_CURSOR, cursor) } },
        )
      }
      val now = System.currentTimeMillis()
      val waiting = withContext(io) { queue.waiting(result.cursor) }
      when (result.problem) {
        null -> {
          prefs.edit { putLong(KEY_LAST_OK, now) }
          _state.update { it.copy(busy = false, waiting = waiting, lastOkMs = now, failed = false, status = if (result.sent > 0) "Sent ${result.sent} rows" else "Nothing new to send") }
        }
        // The server has dropped this truck's token: nothing more is sent until it is paired again.
        UploadProblem.REFUSED -> {
          prefs.edit { remove(KEY_TOKEN) }
          _state.update { it.copy(busy = false, waiting = waiting, paired = false, failed = true, status = "The server no longer accepts this truck: pair it again") }
        }
        UploadProblem.NETWORK -> _state.update { it.copy(busy = false, waiting = waiting, failed = true, status = "No answer from the server (${result.message}). The rows wait.") }
        UploadProblem.SERVER -> _state.update { it.copy(busy = false, waiting = waiting, failed = true, status = "The server refused the rows: ${result.message}") }
      }
      result
    }
  }

  /** Pairs with the code the dashboard shows, then sends the whole log from its first row. */
  suspend fun pair(code: String) {
    val url = pairUrl ?: return
    val paired = oneAtATime.withLock {
      _state.update { it.copy(busy = true, status = "Pairing…", failed = false) }
      val reply: HttpReply = withContext(io) { UploadClient.post(url, null, UploadWire.pairBody(code)) }
      val got = if (reply.code == 200) UploadWire.pairing(reply.text) else null
      if (got == null) {
        val why = when (reply.code) {
          null -> "No answer from the server (${reply.text})"
          403 -> "That code is not valid, or has expired"
          429 -> "Too many attempts: wait a few minutes"
          else -> "HTTP ${reply.code}: ${UploadWire.detail(reply.text)}"
        }
        _state.update { it.copy(busy = false, failed = true, status = why) }
        return@withLock false
      }
      // The server ignores rows it has, so starting over is safe whichever truck entry this code was for.
      prefs.edit {
        putString(KEY_TOKEN, got.first)
        putString(KEY_DEVICE, got.second)
        putLong(KEY_CURSOR, 0L)
      }
      _state.update { it.copy(busy = false, paired = true, device = got.second, failed = false, status = "Paired") }
      true
    }
    if (paired) run()
  }

  /** Forgets the token: nothing more is sent. What the server holds stays there. */
  fun unpair() {
    prefs.edit {
      remove(KEY_TOKEN)
      remove(KEY_DEVICE)
      remove(KEY_CURSOR)
    }
    _state.update { it.copy(paired = false, device = null, status = "Unpaired", failed = false) }
  }

  private suspend fun count() {
    val waiting = withContext(io) { LogHub.get(app).uploadQueue.waiting(prefs.getLong(KEY_CURSOR, 0L)) }
    _state.update { it.copy(waiting = waiting) }
  }

  companion object {
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE = "device"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_LAST_OK = "last_ok_ms"

    @Volatile private var instance: Uploader? = null

    fun get(context: Context): Uploader =
      instance ?: synchronized(this) { instance ?: Uploader(context.applicationContext).also { instance = it } }
  }
}
