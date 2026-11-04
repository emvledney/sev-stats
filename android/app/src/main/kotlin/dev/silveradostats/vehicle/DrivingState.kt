package dev.silveradostats.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.sample
import java.util.Locale
import kotlin.math.abs

/*
 * Pure Kotlin (no android.* imports): whether the app must be in its restricted driving mode.
 * Unit tested on the JVM; DrivingMonitor (Android) feeds it from the car service. Read-only.
 */

/** What the car says right now. Null = not known (never read, unreadable, or not offered). */
data class DrivingInputs(
  /** A device with a car service (FEATURE_AUTOMOTIVE). Anything else is never restricted. */
  val carDevice: Boolean,
  /** CarUxRestrictions.isRequiresDistractionOptimization; null = service gone or listener failed. */
  val requiresOptimization: Boolean? = null,
  /** GEAR_SELECTION as read. */
  val gear: Int? = null,
  /** PERF_VEHICLE_SPEED, m/s, as read. */
  val speedMps: Double? = null,
)

object DrivingPolicy {
  /** VehicleGear.GEAR_PARK. */
  const val GEAR_PARK = 4

  /** VehicleGear.GEAR_UNKNOWN: the property has no answer, which says nothing about the gear. */
  const val GEAR_UNKNOWN = 0

  /**
   * A speed this small, read with the gear in Park, is the sensor's noise: a truck in Park is not
   * moving, and taking it for driving would keep Diagnostics locked in a parked truck.
   */
  const val PARK_SPEED_NOISE_MPS = SessionTracker.MOVING_MPS

  /**
   * Fail safe: on a car device, restricted unless the UX restrictions service positively says it is
   * not needed. Even then a gear out of Park or a speed in either direction (negative = reversing)
   * restricts (the secondary hint). A gear that was not read, or reads as unknown, is no hint: the
   * service is still the authority, and treating it as Drive could lock a truck that never reports a
   * gear out of Diagnostics for good. Any speed counts then; only a gear read as Park allows noise.
   */
  fun restricted(i: DrivingInputs): Boolean = when {
    !i.carDevice -> false
    i.requiresOptimization != false -> true
    i.gear != null && i.gear != GEAR_PARK && i.gear != GEAR_UNKNOWN -> true
    i.speedMps != null && abs(i.speedMps) > (if (i.gear == GEAR_PARK) PARK_SPEED_NOISE_MPS else 0.0) -> true
    else -> false
  }

  /** "Gear Park · speed 0.1 m/s · restrictions off": what [restricted] is going by, as read. */
  fun reason(i: DrivingInputs): String {
    val gear = i.gear?.let { g -> CarEnums.GEAR[g]?.lowercase()?.replaceFirstChar { it.uppercase() } ?: g.toString() } ?: "not read"
    val speed = i.speedMps?.let { String.format(Locale.US, "%.1f m/s", it) } ?: "not read"
    val ux = when (i.requiresOptimization) {
      null -> "no answer"
      true -> "on"
      false -> "off"
    }
    return "Gear $gear · speed $speed · restrictions $ux"
  }
}

/** The one driving state of a screen: [restricted], from whatever [DrivingMonitor] last read. */
class DrivingTracker(carDevice: Boolean) {
  private val lock = Any()
  private val _inputs = MutableStateFlow(DrivingInputs(carDevice))

  /** What was last read; [restricted] follows from it. */
  val inputs: StateFlow<DrivingInputs> = _inputs.asStateFlow()

  private val _restricted = MutableStateFlow(DrivingPolicy.restricted(_inputs.value))
  val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

  /** False while the service gives no answer, so [restricted] is the fail-safe value, not a reading. */
  private val _known = MutableStateFlow(!carDevice)
  val known: StateFlow<Boolean> = _known.asStateFlow()

  /** The UX restrictions service's answer; null when the service or its listener failed. */
  fun onUx(requiresOptimization: Boolean?) = update { it.copy(requiresOptimization = requiresOptimization) }

  /** The secondary hint: null for what could not be read. */
  fun onHints(gear: Int?, speedMps: Double?) = update { it.copy(gear = gear, speedMps = speedMps) }

  private fun update(change: (DrivingInputs) -> DrivingInputs) {
    synchronized(lock) {
      val i = change(_inputs.value)
      _inputs.value = i
      _known.value = !i.carDevice || i.requiresOptimization != null
      _restricted.value = DrivingPolicy.restricted(i)
    }
  }
}

/** While [restricted], at most one value per [periodMs] (the latest); otherwise every value at once. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun <T> StateFlow<T>.heldWhile(restricted: StateFlow<Boolean>, periodMs: Long = DRIVING_UPDATE_MS): Flow<T> =
  restricted.flatMapLatest { held -> if (held) sample(periodMs) else this }

/** Values on screen change at most this often while driving. */
const val DRIVING_UPDATE_MS = 1_000L
