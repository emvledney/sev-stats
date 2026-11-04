package dev.silveradostats.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last uncaught exception in filesDir/last_crash.txt. The truck has no adb or logcat,
 * so the next launch shows it on the Diagnostics screen and puts it in the report.
 */
object CrashLog {
  private const val FILE_NAME = "last_crash.txt"
  const val MAX_CHARS = 64 * 1024

  private var installed = false

  /** Installs the recorder once per process; the previous default handler still runs afterwards. */
  @Synchronized
  fun install(context: Context, versionName: String, versionCode: Int) {
    if (installed) return
    installed = true
    val app = context.applicationContext // the handler lives as long as the process; an activity must not
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
      record(app, versionName, versionCode, thread.name, e)
      previous?.uncaughtException(thread, e)
    }
  }

  /** Writes the record. Also for an error that was caught but ended something (the session's sampling). */
  fun record(context: Context, versionName: String, versionCode: Int, threadName: String, e: Throwable) {
    try {
      val file = file(context)
      file.writeText(
        format(
          atMs = System.currentTimeMillis(),
          app = "$versionName ($versionCode)",
          build = "${Build.DISPLAY} (SDK ${Build.VERSION.SDK_INT})",
          threadName = threadName,
          e = e,
        ),
      )
    } catch (_: Throwable) {
      // Never let the recorder hide the original crash, or add a failure of its own.
    }
  }

  /** Plain-text crash record, at most [MAX_CHARS] characters. */
  fun format(atMs: Long, app: String, build: String, threadName: String, e: Throwable): String {
    val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(atMs))
    val text = "time: $time\napp: $app\nbuild: $build\nthread: $threadName\n\n${e.stackTraceToString()}"
    return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n… (truncated)"
  }

  fun read(context: Context): String? = try {
    file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
  } catch (_: Exception) {
    null
  }

  fun clear(context: Context) {
    file(context).delete()
  }

  private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
