package dev.silveradostats.vehicle

import android.content.Context
import dev.silveradostats.diag.AppPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Feeds [DrivingTracker] for the main screen, read-only, on a car-service connection of its own (the
 * Diagnostics reader reconnects after a permission grant, which must not flip the screen's mode).
 *
 *  - the UX restrictions listener is registered from [start] (onStart) to [stop] (onStop);
 *  - every [POLL_MS] while started: the current UX restrictions again (a change missed while the
 *    process was frozen, or a service gone quiet) and the secondary hint, gear and speed.
 *
 * Main thread only, except the poll loop, which only makes [VehicleReader]'s blocking reads.
 */
class DrivingMonitor(context: Context, private val scope: CoroutineScope) : Closeable {
  private val app = context.applicationContext
  private val reader = VehicleReader(app) {}
  private val tracker = DrivingTracker(reader.isAutomotive)
  private var loop: Job? = null

  /** True while the app must show its restricted driving layouts. Fail safe: see [DrivingPolicy]. */
  val restricted: StateFlow<Boolean> get() = tracker.restricted

  /** False while [restricted] is only the fail-safe value: the service gives no answer. */
  val stateKnown: StateFlow<Boolean> get() = tracker.known

  /** What was last read, for the one text line that explains the state. */
  val inputs: StateFlow<DrivingInputs> get() = tracker.inputs

  init {
    scope.launch { reader.ux.collect { tracker.onUx(it?.requiresDistractionOptimization) } }
    reader.connect()
  }

  fun start() {
    reader.setUxWatching(true)
    if (!reader.isAutomotive || loop?.isActive == true) return
    loop = scope.launch(Dispatchers.IO) {
      while (isActive) {
        reader.refreshUx()
        tracker.onHints(
          gear = reader.readRaw(KnownProperties.GEAR_SELECTION).number?.toInt(),
          // Without its runtime grant the car service hides the speed's config: no point asking.
          speedMps = if (AppPermissions.isGranted(app, CarPerm.CAR_SPEED)) reader.readRaw(KnownProperties.PERF_VEHICLE_SPEED).number else null,
        )
        delay(POLL_MS)
      }
    }
  }

  fun stop() {
    loop?.cancel()
    loop = null
    reader.setUxWatching(false)
  }

  override fun close() {
    stop()
    reader.disconnect()
  }

  private companion object {
    const val POLL_MS = 1_000L
  }
}
