package dev.silveradostats.log

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import dev.silveradostats.upload.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Keeps the hourly job scheduled. Persisted, so it survives a reboot of the head unit; it runs only
 * while the head unit is up, and a run that came due while the truck was off runs soon after it wakes.
 * Android decides the exact time: each row's trigger shows the cadence the truck really gives.
 */
object LogScheduler {
  private const val JOB_ID = 1

  fun ensure(context: Context) {
    val app = context.applicationContext
    val jobs = app.getSystemService(JobScheduler::class.java) ?: return
    if (jobs.getPendingJob(JOB_ID) != null) return
    jobs.schedule(
      JobInfo.Builder(JOB_ID, ComponentName(app, LogJobService::class.java))
        .setPeriodic(LogPolicy.JOB_PERIOD_MS, FLEX_MS)
        .setPersisted(true)
        .build(),
    )
  }

  private const val FLEX_MS = 15 * 60_000L
}

/**
 * The hourly job: one row of the built-in data, then, if the truck reports it is on and an MX+ is
 * chosen, one row from the MX+ (LogHub.visit). Last, with a server paired, the rows the server does not
 * have yet are sent to it (owner, 2026-11-13). Never keeps anything running after it returns.
 */
class LogJobService : JobService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var running: Job? = null

  override fun onStartJob(params: JobParameters): Boolean {
    val hub = LogHub.get(this)
    running = scope.launch {
      try {
        hub.visit(hub.logCar(LogTrigger.JOB))
        Uploader.get(this@LogJobService).run()
      } finally {
        jobFinished(params, false)
      }
    }
    return true
  }

  /** Android wants the time back: stop at once (a visit's link closes); the next period tries again. */
  override fun onStopJob(params: JobParameters): Boolean {
    running?.cancel()
    return false
  }
}

/** The CSV export, to where the system file picker's user chose: a USB stick on the truck. */
object LogExport {
  /** Returns the number of rows written. */
  fun save(context: Context, uri: Uri, rows: Iterable<LogRow>): Int {
    val app = context.applicationContext
    val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream for the chosen file")
    return out.bufferedWriter().use { LogCsv.appendTo(rows, it) }
  }
}
