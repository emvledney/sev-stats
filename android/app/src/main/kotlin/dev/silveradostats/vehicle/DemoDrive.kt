package dev.silveradostats.vehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * Pure Kotlin (no android.* imports): a simulated Silverado EV drive for demo mode (the app's
 * demo switch) and for non-car devices, so the Simple tab looks like a real session. Unit tested.
 *
 * A fixed city / highway speed schedule repeats every ~24 minutes; battery power comes from a
 * simple road-load model (mass, rolling resistance, aero, grade, drivetrain and regen efficiency,
 * accessories), which lands near 2.3 mi/kWh with regen on every stop.
 */
class DemoDrive(
  startLevelWh: Double = 152_400.0,
  val capacityWh: Double = 205_080.0,
  private val outsideC: Double = 13.5,
) {
  private var t = 0L
  private var v = 0.0
  private var level = startLevelWh
  private var kw = 0.0

  /** The simulated battery power now, + discharge (kW). */
  val powerKw: Double get() = kw

  /** Advances the simulation to [relMs] (ms since the drive started) and reads the "truck". */
  fun sampleAt(relMs: Long, atMs: Long = relMs): VehicleSample {
    while (t + STEP_MS <= relMs) step()
    return VehicleSample(
      atMs = atMs,
      speedMps = v,
      levelWh = floor(level / LEVEL_STEP_WH) * LEVEL_STEP_WH,
      capacityWh = capacityWh,
      // AOSP sign: + charging, - discharging (mW).
      chargeRateMw = -kw * 1_000_000.0,
      plugConnected = false,
      chargeState = ChargeState.NOT_CHARGING,
      gear = if (t < PARK_MS) Gear.PARK else Gear.DRIVE,
      outsideC = outsideC,
      // The truck's own range: a more pessimistic efficiency than this drive's.
      rangeM = level * TRUCK_M_PER_WH,
    )
  }

  /** Target speed of the schedule at [ms] (m/s). */
  fun targetAt(ms: Long): Double {
    if (ms < PARK_MS) return 0.0
    var s = ((ms - PARK_MS) / 1000.0) % CYCLE_S
    for ((target, seconds) in SCHEDULE) {
      if (s < seconds) return target
      s -= seconds
    }
    return 0.0
  }

  private fun step() {
    val dt = STEP_MS / 1000.0
    val target = targetAt(t)
    val cruise = if (target > 5) target + WOBBLE * sin(2 * PI * t / 47_000.0) else target
    val dv = cruise - v
    // Gentle driving: acceleration and braking also limited by wheel power.
    val vEff = maxOf(v, 1.0)
    val a = if (dv >= 0) min(dv / dt, min(ACCEL, MAX_DRIVE_W / (MASS * vEff))) else maxOf(dv / dt, -min(DECEL, MAX_REGEN_W / (MASS * vEff)))
    val v1 = (v + a * dt).coerceAtLeast(0.0)
    val vm = (v + v1) / 2
    val grade = GRADE * sin(2 * PI * t / 410_000.0)
    val wheel = MASS * a * vm + 0.5 * RHO * CDA * vm * vm * vm + CRR * MASS * G * vm + MASS * G * grade * vm
    val battery = (if (wheel >= 0) wheel / DRIVE_EFF else wheel * REGEN_EFF) + AUX_W
    level -= battery * dt / 3600.0
    kw = battery / 1000.0
    v = v1
    t += STEP_MS
    if (abs(v) < 0.01 && target == 0.0) v = 0.0
  }

  companion object {
    const val STEP_MS = 250L
    const val PARK_MS = 6_000L

    /** Level resolution of the simulated property (Wh). */
    const val LEVEL_STEP_WH = 10.0

    const val TRUCK_M_PER_WH = 3.05

    /** What the Simple tab shows as the last charge in demo mode: never saved, never the truck's. */
    val LAST_CHARGE = LastCharge(toWh = 186_400.0, toPct = 186_400.0 / 205_080.0 * 100.0)
    private val MILES_BEFORE_M = (186_400.0 - 152_400.0) * EffUnits.fromMiPerKwh(2.25)

    /** The same with the miles since: this drive's, after what the energy gone before it took at about 2.25 mi/kWh. */
    fun lastCharge(driveM: Double): LastCharge = LAST_CHARGE.copy(drivenM = MILES_BEFORE_M + driveM)
    private const val MASS = 4_100.0
    private const val CDA = 1.15
    private const val CRR = 0.009
    private const val RHO = 1.2
    private const val G = 9.81
    private const val GRADE = 0.012
    private const val DRIVE_EFF = 0.90
    private const val REGEN_EFF = 0.68
    private const val AUX_W = 1_100.0
    private const val MAX_DRIVE_W = 95_000.0
    private const val MAX_REGEN_W = 60_000.0
    private const val ACCEL = 1.4
    private const val DECEL = 1.6
    private const val WOBBLE = 0.6

    /** (target m/s, seconds): stop-and-go, arterial roads, highway, back into town. */
    val SCHEDULE: List<Pair<Double, Int>> = listOf(
      0.0 to 12, 13.0 to 70, 0.0 to 18, 16.5 to 85, 9.0 to 35, 0.0 to 22,
      24.0 to 220, 29.0 to 310, 22.0 to 120, 13.0 to 60, 0.0 to 25,
      18.0 to 120, 27.0 to 250, 12.0 to 55, 0.0 to 20,
    )
    val CYCLE_S: Int = SCHEDULE.sumOf { it.second }
  }
}
