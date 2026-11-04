package dev.silveradostats.vehicle

import dev.silveradostats.link.PowerHistory
import dev.silveradostats.link.PowerPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Kotlin (no android.* imports): the "since the app was opened" session built from the
 * truck's own vehicle data (no OBD adapter). Unit tested on the JVM with a virtual clock.
 *
 * Inputs are timestamped samples (VehicleSample) at about 1-2 Hz. Outputs (SessionState):
 *  - distance: trapezoidal integration of |speed|, never across a gap > GAP_MS between samples;
 *  - energy used: start level - current level (net: regen counts), in segments; a gap or a charge
 *    closes the segment and the next one starts from the level seen afterwards, so energy spent
 *    while nothing was sampled, and energy added while charging, is never counted;
 *  - measured efficiency: distance / energy, both taken at battery-level CHANGE points. The level
 *    property moves in steps (by value and/or by time: a held value that jumps every few seconds),
 *    so the distance driven since the last step has no energy against it yet. Measuring from the
 *    first step to the latest step (the "basis") holds the figure steady between steps instead of
 *    letting it climb and fall back at every step. Reported only after WARMUP_M and WARMUP_WH, marked
 *    "early" until EARLY_M and EARLY_WH; once reported it never goes back to warming up (a long
 *    descent reads NET_REGEN instead);
 *  - a cumulative efficiency series (one point per 0.1 mi) and per-mile / per-km segment figures,
 *    with the energy at each boundary interpolated between the change points around it;
 *  - power now: from EV_BATTERY_INSTANTANEOUS_CHARGE_RATE (AOSP: + charging, - discharging; shown
 *    here as + discharge), or estimated from d(level)/dt over ~30 s when that property is missing,
 *    stays exactly 0 while driving (seen on GM in Park; unknown while driving), is held with an old
 *    timestamp, or has the opposite sign to the energy the level says moved (RateCheck, in the
 *    diagnostics report: only 0.00 kW has been seen on the truck so far);
 *  - a 10-minute power history at 1 s resolution, peaks, range at the measured efficiency.
 *
 * Readings are not taken on trust: a speed no truck reaches is dropped; a level jump neither driving
 * nor a charger can cause must repeat before it counts, and then it counts as a new start, not as
 * energy; a plug or charge state that says "charging" at speed is stale until it changes.
 *
 * Nothing divides by zero: every ratio is guarded and an unknown value is null.
 */

/** AOSP EV_CHARGE_STATE values. */
enum class ChargeState(val aosp: Int) {
  UNKNOWN(0),
  CHARGING(1),
  FULLY_CHARGED(2),
  NOT_CHARGING(3),
  ERROR(4),
  ;

  companion object {
    fun fromAosp(v: Int?): ChargeState? = v?.let { x -> entries.firstOrNull { it.aosp == x } ?: UNKNOWN }
  }
}

/** AOSP VehicleGear values the session cares about. */
object Gear {
  const val NEUTRAL = 1
  const val REVERSE = 2
  const val PARK = 4
  const val DRIVE = 8

  fun letter(g: Int?): String? = when (g) {
    null -> null
    PARK -> "P"
    REVERSE -> "R"
    NEUTRAL -> "N"
    DRIVE -> "D"
    else -> if (g in 16..4096) "D" else null
  }
}

/** One reading of the truck's built-in data. Null = not read in this sample (or not offered). */
data class VehicleSample(
  /** Monotonic milliseconds (same clock as the tracker's). */
  val atMs: Long,
  /** PERF_VEHICLE_SPEED, m/s (negative in reverse). */
  val speedMps: Double? = null,
  /** EV_BATTERY_LEVEL, Wh remaining. */
  val levelWh: Double? = null,
  /** EV_CURRENT_BATTERY_CAPACITY, else INFO_EV_BATTERY_CAPACITY, Wh. */
  val capacityWh: Double? = null,
  /** EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, mW (AOSP: + charging, - discharging). */
  val chargeRateMw: Double? = null,
  /** Age of that value by the property's own timestamp, ms (null = unknown). */
  val chargeRateAgeMs: Long? = null,
  /** EV_CHARGE_PORT_CONNECTED. */
  val plugConnected: Boolean? = null,
  /** EV_CHARGE_STATE. */
  val chargeState: ChargeState? = null,
  /** GEAR_SELECTION (AOSP VehicleGear). */
  val gear: Int? = null,
  /** ENV_OUTSIDE_TEMPERATURE, °C. */
  val outsideC: Double? = null,
  /** RANGE_REMAINING, m (the truck's own estimate). */
  val rangeM: Double? = null,
)

/** Efficiency conversions. The tracker works in metres per Wh, which equals km per kWh. */
object EffUnits {
  const val METERS_PER_MILE = 1609.344

  fun miPerKwh(mPerWh: Double): Double = mPerWh * 1000.0 / METERS_PER_MILE

  fun kmPerKwh(mPerWh: Double): Double = mPerWh

  /** Wh per mile; null for a non-positive efficiency. */
  fun whPerMi(mPerWh: Double): Double? = if (mPerWh > 0) METERS_PER_MILE / mPerWh else null

  /** Wh per km; null for a non-positive efficiency. */
  fun whPerKm(mPerWh: Double): Double? = if (mPerWh > 0) 1000.0 / mPerWh else null

  fun fromMiPerKwh(miPerKwh: Double): Double = miPerKwh * METERS_PER_MILE / 1000.0
}

enum class EffStatus {
  WARMING_UP,
  OK,

  /** Was OK, but net energy has fallen below what a figure needs (a long descent). Not warming up. */
  NET_REGEN,
}

data class Efficiency(
  val status: EffStatus,
  /** Metres per Wh (= km/kWh); null while warming up. */
  val mPerWh: Double?,
  /** 0..1 progress towards the warm-up threshold (both distance and energy are needed). */
  val progress: Double,
  /** Distance the figure is measured over (between battery-level steps), m. */
  val basisM: Double,
  /** Net energy the figure is measured over, Wh. */
  val basisWh: Double,
  /** Energy the warm-up asks for: more than [SessionTracker.WARMUP_WH] when the level is coarse, Wh. */
  val needWh: Double = SessionTracker.WARMUP_WH,
  /** A figure, but over too little distance or energy to say much yet. */
  val early: Boolean = false,
  /** Session distance of the latest level step: where the figure was measured up to, m. */
  val atM: Double = 0.0,
) {
  companion object {
    val NONE = Efficiency(EffStatus.WARMING_UP, null, 0.0, 0.0, 0.0)
  }
}

/** Cumulative efficiency at one distance (one point per 0.1 mi of session distance). */
data class EffPoint(val distM: Double, val mPerWh: Double)

/**
 * One finished distance segment (mile or km number [index], 1-based): the distance inside it that
 * has energy data ([knownM], less than [lengthM] across a gap or a charge) and the net energy used.
 */
data class EffSegment(val index: Int, val lengthM: Double, val knownM: Double, val usedWh: Double) {
  /** Null when there is too little energy to divide by (mostly regen, or no data). */
  val mPerWh: Double? get() = if (knownM > 0 && usedWh >= SessionTracker.MIN_SEGMENT_WH) knownM / usedWh else null

  /** Net energy came back over this segment (downhill / regen). */
  val netRegen: Boolean get() = knownM > 0 && usedWh <= 0
}

enum class PowerSource { CHARGE_RATE, ESTIMATED }

enum class PowerDirection { DISCHARGING, REGEN, CHARGING, IDLE }

/** Battery power now: + discharge, - charge / regen (as on the Live dashboard). */
data class PowerNow(val kw: Double, val direction: PowerDirection, val source: PowerSource) {
  val estimated: Boolean get() = source == PowerSource.ESTIMATED
}

/**
 * The charge rate against the battery level, over windows between level steps at least
 * [SessionTracker.RATE_CHECK_MS] long: the first real drive settles what sign and how live the
 * truck's charge rate is. Figures are in kW, + discharge. Goes into the diagnostics report.
 */
data class RateCheck(
  /** Windows where both moved at least 1 kW the same way. */
  val agreed: Int = 0,
  /** Windows where they moved opposite ways by more than [SessionTracker.RATE_DISAGREE_KW]. */
  val disagreed: Int = 0,
  val lastRateKw: Double? = null,
  val lastLevelKw: Double? = null,
)

/** Where the session's data comes from right now. */
enum class FeedKind {
  /** Not decided yet / connecting to the car service. */
  STARTING,

  /** The truck's built-in data (CarPropertyManager, read-only). */
  VEHICLE,

  /** Simulated drive (the app's demo switch). */
  DEMO,

  /** Not an Android Automotive device and demo data is off. */
  NOT_AUTOMOTIVE,

  /** The car service could not be reached. */
  NO_CAR_SERVICE,

  /** Measuring hit an internal error and stopped (kept in the crash log): nothing here is live. */
  FAILED,
}

/** A vehicle signal the session uses, for "not offered" hints. */
enum class Signal { SPEED, LEVEL, CAPACITY, CHARGE_RATE, RANGE, OUTSIDE_TEMP, GEAR, CHARGE_STATE, PLUG }

data class Feed(
  val kind: FeedKind = FeedKind.STARTING,
  val message: String = "",
  /** Sampling is running (a UI is attached, or was within the grace period). */
  val sampling: Boolean = false,
  /** The runtime permissions the session needs and lacks (CAR_ENERGY, CAR_SPEED). */
  val missingPermissions: List<String> = emptyList(),
  /** Signals the car service does not offer (or this app may not read). */
  val missing: Set<Signal> = emptySet(),
  /** The capacity is the nominal one (INFO_EV_BATTERY_CAPACITY): no usable-capacity property. */
  val capacityNominal: Boolean = false,
)

/** Everything the Simple tab and Battery Glance show about the session. */
data class SessionState(
  /** Tracker clock when the session started (app opened, or the last reset). */
  val startedAtMs: Long = 0,
  /** Sampled time, gaps excluded. */
  val activeMs: Long = 0,
  val samples: Long = 0,
  val lastSampleAtMs: Long? = null,
  val distanceM: Double = 0.0,
  /** Net energy used (regen included), charging excluded, Wh. */
  val usedWh: Double = 0.0,
  /** Energy added while charging was detected, Wh. */
  val chargedWh: Double = 0.0,
  /** Charging detected: energy accounting is paused. */
  val charging: Boolean = false,
  val gaps: Int = 0,
  val gapMs: Long = 0,
  val efficiency: Efficiency = Efficiency.NONE,
  val series: List<EffPoint> = emptyList(),
  val miSegments: List<EffSegment> = emptyList(),
  val kmSegments: List<EffSegment> = emptyList(),
  val power: PowerNow? = null,
  /** Last 10 minutes, at most one point per second, + discharge. */
  val powerHistory: List<PowerPoint> = emptyList(),
  /** Largest discharge in [powerHistory], kW (> 0), or null. */
  val peakDischargeKw: Double? = null,
  /** Largest regen / charge in [powerHistory], kW (< 0), or null. */
  val peakRegenKw: Double? = null,
  /** Some of [powerHistory] comes from the estimate, so the peaks may too. */
  val powerHistoryEstimated: Boolean = false,
  val speedMps: Double? = null,
  val levelWh: Double? = null,
  val capacityWh: Double? = null,
  val rangeM: Double? = null,
  val outsideC: Double? = null,
  val gear: Int? = null,
  /** Smallest battery-level step seen, Wh (how coarse the level property is). */
  val levelStepWh: Double? = null,
  /** Distance driven since the battery level last changed, m. */
  val levelUnchangedM: Double = 0.0,
  /** The charge-rate property has reported a non-zero value in this session (it is live). */
  val chargeRateSeenNonZero: Boolean = false,
  val rateCheck: RateCheck = RateCheck(),
  val feed: Feed = Feed(),
) {
  val socPct: Double?
    get() {
      val l = levelWh ?: return null
      val c = capacityWh?.takeIf { it > 0 } ?: return null
      return l / c * 100.0
    }

  /** Average speed over the sampled time (stops included), m/s. */
  val avgSpeedMps: Double? get() = if (activeMs >= 1_000) distanceM / (activeMs / 1000.0) else null

  /**
   * Remaining energy x the measured efficiency, m. Withheld while the figure is early, and above
   * [SessionTracker.MAX_RANGE_M_PER_WH]: a descent's figure says nothing about the battery's range.
   */
  val rangeAtMeasuredM: Double?
    get() {
      if (efficiency.early) return null
      val e = efficiency.mPerWh?.takeIf { it <= SessionTracker.MAX_RANGE_M_PER_WH } ?: return null
      val l = levelWh?.takeIf { it >= 0 } ?: return null
      return l * e
    }

  /** The efficiency the truck's own range implies (range / remaining energy), m/Wh. */
  val truckImpliedMPerWh: Double?
    get() {
      val r = rangeM?.takeIf { it > 0 } ?: return null
      val l = levelWh?.takeIf { it > 100 } ?: return null
      return r / l
    }

  val hasData: Boolean get() = samples > 0
}

/** Single-threaded: the owner (VehicleSession) feeds and reads it from one thread. */
class SessionTracker(private val clock: () -> Long) {
  /** A battery-level change point: session distance, basis distance, basis energy, level. */
  private data class Point(val d: Double, val k: Double, val e: Double, val level: Double)

  /** Fixed-step distance boundaries; the (basis distance, basis energy) at each finished one. */
  private class Boundaries(val step: Double) {
    /** Index of the next boundary (distance = next * step). */
    var next = 1

    /** k and e at boundary i (index 0 = distance 0, before any data). */
    val k = ArrayList<Double>().apply { add(0.0) }
    val e = ArrayList<Double>().apply { add(0.0) }

    /** Resolves every boundary in (from.d, to.d]; [onBoundary] gets (index, k, e). */
    fun resolve(from: Point, to: Point, onBoundary: (Int, Double, Double) -> Unit = { _, _, _ -> }) {
      while (next * step <= to.d + 1e-9) {
        val b = next * step
        val span = to.d - from.d
        val f = if (span > 0) ((b - from.d) / span).coerceIn(0.0, 1.0) else 1.0
        val kb = from.k + (to.k - from.k) * f
        val eb = from.e + (to.e - from.e) * f
        k += kb
        e += eb
        onBoundary(next, kb, eb)
        next += 1
      }
    }

    fun segments(): List<EffSegment> = (1 until k.size).map { i -> EffSegment(i, step, k[i] - k[i - 1], e[i] - e[i - 1]) }
  }

  private var startedAtMs = clock()
  private var lastAtMs: Long? = null
  private var samples = 0L
  private var activeMs = 0L
  private var gaps = 0
  private var gapMs = 0L

  // Latest values.
  private var speed: Double? = null
  private var lastSpeedAtMs: Long? = null
  private var level: Double? = null
  private var capacity: Double? = null
  private var range: Double? = null
  private var outside: Double? = null
  private var gear: Int? = null
  private var plug: Boolean? = null
  private var chargeState: ChargeState? = null

  /** Speed stopped arriving while the other readings go on (since then). */
  private var speedOutSinceMs: Long? = null

  // Level plausibility.
  private var levelChangedAtMs = 0L
  private var levelCandidate: Double? = null
  private var suspectCount = 0
  /** The next level reading is a fresh start (a new session, or after a gap): no jump to judge. */
  private var levelUnchecked = true

  // Distance.
  private var distanceM = 0.0

  // Energy totals (segments).
  private var usedClosedWh = 0.0
  private var segStartLevel: Double? = null
  private var chargedWh = 0.0

  // Basis (change points).
  private var last = Point(0.0, 0.0, 0.0, Double.NaN)
  private var anchorPending = true
  /** A figure has been reported in this session. */
  private var warmed = false
  private var observedLevel: Double? = null
  private var levelStepWh: Double? = null
  /** Session distance when the level last changed (or a gap restarted the watch). */
  private var levelChangedAtM = 0.0
  private val series = ArrayList<EffPoint>()
  private val seriesB = Boundaries(SERIES_STEP_M)
  private val miles = Boundaries(EffUnits.METERS_PER_MILE)
  private val kms = Boundaries(1000.0)

  // Charging.
  private var charging = false
  private var chargingByRise = false
  /** Level when the charge started (null until a level is known). */
  private var chargeStartLevel: Double? = null
  /** The plug / charge state says "charging" and the truck has driven since: stale until it changes. */
  private var chargeClaimStale = false
  private var stationary = false
  private var stopMinLevel: Double? = null
  private var stopMinPoint: Point? = null

  // Power.
  private var rateKw: Double? = null
  private var rateAtMs: Long? = null
  private var zeroWhileMovingMs = 0L
  private var rateLooksDead = false
  private var rateSeenNonZero = false
  /** The last raw rate and when it last changed (for a value held with an old timestamp). */
  private var rateRaw: Double? = null
  private var rateChangedAtMs = 0L
  private var rateHeld = false
  // Rate against level: a window from one level step to a later one, with the rate's energy over it.
  private var checkFromMs: Long? = null
  private var checkFromLevel = 0.0
  private var checkRateWh = 0.0
  private var checkRateMs = 0L
  private var rateDisagrees = false
  private var rateCheck = RateCheck()
  /** Level changes (time, level) for the d(level)/dt estimate. */
  private val levelLog = ArrayDeque<Pair<Long, Double>>()
  private var levelLogLevel: Double? = null
  private var levelSeenSinceMs: Long? = null
  private var power: PowerNow? = null
  private val history = PowerHistory(HISTORY_MS, HISTORY_CAPACITY)
  private var historySecond = Long.MIN_VALUE
  private var lastEstimatedAtMs: Long? = null

  // Snapshot caches (rebuilt only when changed).
  private var seriesCache: List<EffPoint> = emptyList()
  private var seriesDirty = false
  private var miCache: List<EffSegment> = emptyList()
  private var kmCache: List<EffSegment> = emptyList()
  private var segmentsDirty = false

  /** Starts a new session now: everything is cleared. */
  fun reset() {
    startedAtMs = clock()
    lastAtMs = null
    samples = 0
    activeMs = 0
    gaps = 0
    gapMs = 0
    speed = null
    lastSpeedAtMs = null
    speedOutSinceMs = null
    levelCandidate = null
    suspectCount = 0
    levelUnchecked = true
    // Slow-changing context values stay (they are still the truck's current values).
    distanceM = 0.0
    usedClosedWh = 0.0
    segStartLevel = null
    chargedWh = 0.0
    last = Point(0.0, 0.0, 0.0, Double.NaN)
    anchorPending = true
    warmed = false
    observedLevel = null
    levelStepWh = null
    levelChangedAtM = 0.0
    series.clear()
    seriesB.next = 1
    for (b in listOf(seriesB, miles, kms)) {
      b.next = 1
      b.k.clear()
      b.e.clear()
      b.k += 0.0
      b.e += 0.0
    }
    charging = false
    chargingByRise = false
    chargeStartLevel = null
    chargeClaimStale = false
    stationary = false
    stopMinLevel = null
    stopMinPoint = null
    zeroWhileMovingMs = 0
    rateLooksDead = false
    rateSeenNonZero = false
    rateRaw = null
    rateHeld = false
    resetRateCheck()
    rateDisagrees = false
    rateCheck = RateCheck()
    levelLog.clear()
    levelLogLevel = null
    levelSeenSinceMs = null
    power = null
    history.clear()
    historySecond = Long.MIN_VALUE
    lastEstimatedAtMs = null
    seriesCache = emptyList()
    miCache = emptyList()
    kmCache = emptyList()
    seriesDirty = false
    segmentsDirty = false
  }

  /** Feeds one sample. Samples must come in time order; an older one is ignored. */
  fun add(s: VehicleSample) {
    val prevAt = lastAtMs
    if (prevAt != null && s.atMs < prevAt) return
    val dt = if (prevAt != null) s.atMs - prevAt else null
    val gap = dt != null && dt > GAP_MS
    if (gap) {
      gaps += 1
      gapMs += dt
      rebase()
    } else if (dt != null && speedOutSinceMs == null) {
      activeMs += dt
    }
    lastAtMs = s.atMs
    samples += 1

    s.capacityWh?.takeIf { it.isFinite() && it > 0 }?.let { capacity = it }
    s.rangeM?.takeIf { it.isFinite() && it >= 0 }?.let { range = it }
    s.outsideC?.takeIf { it.isFinite() }?.let { outside = it }
    s.gear?.let { gear = it }
    s.plugConnected?.let { plug = it }
    s.chargeState?.let { chargeState = it }

    // Distance: trapezoid between consecutive speed samples, never across a gap.
    val v = s.speedMps?.takeIf { it.isFinite() }?.let { abs(it) }?.takeIf { it <= MAX_SPEED_MPS }
    val speedSeenAt = lastSpeedAtMs
    if (v == null && !gap && speedOutSinceMs == null && speedSeenAt != null && s.atMs - speedSeenAt > GAP_MS) {
      // Energy with no distance against it would ruin the figure: left out like a gap, until speed is back.
      speedOutSinceMs = s.atMs
      gaps += 1
      speed = null
      rebase()
    }
    if (v != null) {
      speedOutSinceMs?.let { since ->
        gapMs += s.atMs - since
        speedOutSinceMs = null
        rebase()
      }
      val ps = speed
      val pt = lastSpeedAtMs
      if (!gap && ps != null && pt != null && s.atMs > pt && s.atMs - pt <= GAP_MS) {
        distanceM += (ps + v) / 2.0 * (s.atMs - pt) / 1000.0
      }
      speed = v
      lastSpeedAtMs = s.atMs
      if (v <= STOPPED_MPS) {
        if (!stationary) {
          stationary = true
          // After a gap the last level is from before it: the stop's minimum starts at the next reading.
          stopMinLevel = if (observedLevel == null) null else level
          stopMinPoint = last
        }
      } else {
        stationary = false
        stopMinLevel = null
        stopMinPoint = null
      }
    }

    // Charging by plug / charge state: pause before this sample's level is counted.
    // A truck does not drive on a charging cable: at speed these are held values, until they change.
    val claim = plug == true || chargeState == ChargeState.CHARGING
    if (!claim) chargeClaimStale = false else if (v != null && v > RATE_ZERO_MPS) chargeClaimStale = true
    val explicit = claim && !chargeClaimStale
    if (explicit && !charging) startCharging(byRise = false)

    val lv = plausibleLevel(s.levelWh?.takeIf { it.isFinite() && it >= 0 }, s.atMs)
    if (lv != null) {
      level = lv
      if (!charging && speedOutSinceMs == null) account(lv)
      if (charging && chargeStartLevel == null) chargeStartLevel = lv
      logLevel(s.atMs, lv)
      // A stop that began before the first level reading (a session opened while parked).
      if (stationary && stopMinLevel == null) {
        stopMinLevel = lv
        stopMinPoint = last
      }
    }

    // Charging by a level rise while standing still (no plug / charge-state data needed).
    val min = stopMinLevel
    if (!charging && stationary && lv != null && min != null && lv - min > CHARGE_RISE_WH) startCharging(byRise = true)

    if (charging) {
      val moving = v != null && v > MOVING_MPS
      val ended = if (chargingByRise) !explicit && moving else !explicit
      if (ended) endCharging()
    }

    updatePower(s, gap)
  }

  /**
   * [raw], or null while it is a jump no drive or charger can cause. A jump that repeats
   * [LEVEL_CONFIRM] times is real, but it is still not energy used: the measurement restarts there.
   */
  private fun plausibleLevel(raw: Double?, atMs: Long): Double? {
    if (raw == null) return null
    val prev = level
    if (prev != null && !levelUnchecked) {
      // A coarse or held level moves by more at once, so the allowance grows with the time since the last step.
      val sinceStep = min(max(0L, atMs - levelChangedAtMs), LEVEL_JUMP_WINDOW_MS)
      if (abs(raw - prev) > MAX_LEVEL_STEP_WH + MAX_LEVEL_WH_PER_MS * sinceStep) {
        val c = levelCandidate
        suspectCount = if (c != null && abs(c - raw) <= MAX_LEVEL_STEP_WH) suspectCount + 1 else 1
        levelCandidate = raw
        if (suspectCount < LEVEL_CONFIRM) return null
        rebase()
      }
    }
    if (prev == null || abs(raw - prev) >= LEVEL_EPS_WH) levelChangedAtMs = atMs
    levelUnchecked = false
    levelCandidate = null
    suspectCount = 0
    return raw
  }

  /** A gap: the energy segment closes; distance and energy restart from the next readings. */
  private fun rebase() {
    closeSegment()
    levelUnchecked = true
    levelChangedAtM = distanceM
    resetRateCheck()
    observedLevel = null
    anchorPending = true
    stationary = false
    stopMinLevel = null
    stopMinPoint = null
    levelLog.clear()
    levelLogLevel = null
    levelSeenSinceMs = null
  }

  private fun closeSegment() {
    val start = segStartLevel
    val cur = level
    if (start != null && cur != null) usedClosedWh += start - cur
    segStartLevel = null
  }

  private fun startCharging(byRise: Boolean) {
    if (byRise) {
      // Undo what the rise added (negative use) since the lowest level of this stop.
      val minLevel = stopMinLevel ?: level ?: return
      stopMinPoint?.let { last = it }
      val start = segStartLevel
      if (start != null) usedClosedWh += start - minLevel
      segStartLevel = null
      chargeStartLevel = minLevel
    } else {
      closeSegment()
      chargeStartLevel = level
    }
    charging = true
    chargingByRise = byRise
    observedLevel = null
    anchorPending = true
  }

  private fun endCharging() {
    val cur = level
    val start = chargeStartLevel
    if (cur != null && start != null) chargedWh += max(0.0, cur - start)
    charging = false
    chargingByRise = false
    levelChangedAtM = distanceM
    segStartLevel = cur
    observedLevel = cur
    anchorPending = true
    stopMinLevel = if (stationary) cur else null
    stopMinPoint = if (stationary) last else null
  }

  /** Energy accounting for one level reading (not while charging). */
  private fun account(lv: Double) {
    if (segStartLevel == null) segStartLevel = lv
    val obs = observedLevel
    if (obs == null) {
      observedLevel = lv
      return
    }
    val step = abs(lv - obs)
    if (step < LEVEL_EPS_WH) return
    levelStepWh = min(levelStepWh ?: step, step)
    val d = distanceM
    val p = if (anchorPending) {
      Point(d, last.k, last.e, lv)
    } else {
      Point(d, last.k + max(0.0, d - last.d), last.e + (last.level - lv), lv)
    }
    resolveBoundaries(last, p)
    last = p
    if (p.k >= WARMUP_M && p.e >= warmUpWh()) warmed = true
    levelChangedAtM = d
    anchorPending = false
    observedLevel = lv
    val m = stopMinLevel
    if (stationary && m != null && lv < m) {
      stopMinLevel = lv
      stopMinPoint = p
    }
  }

  private fun resolveBoundaries(from: Point, to: Point) {
    val needWh = warmUpWh()
    seriesB.resolve(from, to) { i, k, e ->
      if (k >= WARMUP_M && e >= needWh) {
        series += EffPoint(i * SERIES_STEP_M, k / e)
        if (series.size > MAX_SERIES) {
          // Keep the chart light on very long sessions: drop every other point, keep the newest.
          val kept = series.filterIndexed { idx, _ -> idx % 2 == 1 || idx == series.lastIndex }
          series.clear()
          series.addAll(kept)
        }
        seriesDirty = true
      }
    }
    miles.resolve(from, to) { _, _, _ -> segmentsDirty = true }
    kms.resolve(from, to) { _, _, _ -> segmentsDirty = true }
  }

  // --- Power ------------------------------------------------------------------------------------

  private fun logLevel(atMs: Long, lv: Double) {
    if (levelSeenSinceMs == null) levelSeenSinceMs = atMs
    val prev = levelLogLevel
    if (prev == null) {
      levelLogLevel = lv
      return
    }
    if (abs(lv - prev) >= LEVEL_EPS_WH) {
      levelLog.addLast(atMs to lv)
      levelLogLevel = lv
      checkRateAgainstLevel(atMs, lv)
      while (levelLog.size > 2 && atMs - levelLog.first().first > LEVEL_LOG_MS) levelLog.removeFirst()
      while (levelLog.size > LEVEL_LOG_MAX) levelLog.removeFirst()
    }
  }

  /** d(level)/dt between level change points about [EST_WINDOW_MS] apart; + = discharge, kW. */
  private fun estimateKw(nowMs: Long): Double? {
    if (levelLog.size < 2) {
      // No two change points yet: a level that has not moved for a minute means about 0 kW.
      val since = levelSeenSinceMs ?: return null
      val lastChange = levelLog.lastOrNull()?.first ?: since
      return if (nowMs - lastChange >= IDLE_AFTER_MS) 0.0 else null
    }
    val newest = levelLog.last()
    var old = levelLog.first()
    for (p in levelLog) {
      if (p.first <= newest.first - EST_WINDOW_MS) old = p else break
    }
    if (old === newest) old = levelLog[levelLog.size - 2]
    val span = (newest.first - old.first).toDouble()
    if (span <= 0) return null
    val drop = old.second - newest.second
    val changes = levelLog.count { it.first >= old.first } - 1
    val interval = span / max(1, changes)
    val since = nowMs - newest.first
    // Standing still with no step for much longer than usual: nothing is flowing.
    if (stationary && since > max(2 * interval, IDLE_AFTER_MS.toDouble())) return 0.0
    // No step for much longer than usual: power has dropped; let the estimate decay towards 0.
    val kw = if (since > max(2 * interval, STALE_STEP_MS.toDouble())) {
      drop / (nowMs - old.first).toDouble() * 3600.0
    } else {
      drop / span * 3600.0
    }
    return kw.takeIf { it.isFinite() }
  }

  /**
   * At a level step: does the charge rate's energy since an earlier step (at least [RATE_CHECK_MS]
   * ago) go the same way as the level's? Both ends are level steps, so the level's lag cancels out.
   */
  private fun checkRateAgainstLevel(atMs: Long, lv: Double) {
    val from = checkFromMs
    if (from != null) {
      val span = atMs - from
      if (span < RATE_CHECK_MS) return
      if (checkRateMs >= span * 8 / 10) {
        val rateKwMean = checkRateWh / span * 3600.0
        val levelKwMean = (checkFromLevel - lv) / span * 3600.0
        val opposite = rateKwMean * levelKwMean < 0 && abs(rateKwMean - levelKwMean) > RATE_DISAGREE_KW
        val same = rateKwMean * levelKwMean > 0 && min(abs(rateKwMean), abs(levelKwMean)) >= RATE_AGREE_KW
        if (opposite) rateDisagrees = true
        if (same) rateDisagrees = false
        rateCheck = rateCheck.copy(
          agreed = rateCheck.agreed + if (same) 1 else 0,
          disagreed = rateCheck.disagreed + if (opposite) 1 else 0,
          lastRateKw = rateKwMean,
          lastLevelKw = levelKwMean,
        )
      }
    }
    checkFromMs = atMs
    checkFromLevel = lv
    checkRateWh = 0.0
    checkRateMs = 0
  }

  private fun resetRateCheck() {
    checkFromMs = null
    checkRateWh = 0.0
    checkRateMs = 0
  }

  private fun updatePower(s: VehicleSample, gap: Boolean) {
    val rate = s.chargeRateMw?.takeIf { it.isFinite() && abs(it) <= MAX_RATE_MW }
    if (rate != null) {
      val prevAt = rateAtMs
      val sincePrev = if (prevAt != null && !gap) s.atMs - prevAt else null
      if (sincePrev != null && sincePrev <= GAP_MS) {
        checkRateWh += (rateKw ?: 0.0) * sincePrev / 3600.0
        checkRateMs += sincePrev
      }
      if (rate != rateRaw) {
        rateRaw = rate
        rateChangedAtMs = s.atMs
      }
      rateKw = -rate / 1_000_000.0
      rateAtMs = s.atMs
      // Both, so that a timestamp on another clock cannot retire a value that is plainly changing.
      val age = s.chargeRateAgeMs
      rateHeld = rate != 0.0 && age != null && age > RATE_HELD_MS && s.atMs - rateChangedAtMs > RATE_HELD_MS
      if (rate != 0.0) {
        if (!rateHeld) {
          // Recovered (or never stuck): the property is live.
          rateSeenNonZero = true
          zeroWhileMovingMs = 0
          rateLooksDead = false
        }
      } else if ((speed ?: 0.0) > RATE_ZERO_MPS && sincePrev != null) {
        zeroWhileMovingMs += sincePrev
        if (zeroWhileMovingMs > RATE_ZERO_MS) rateLooksDead = true
      }
    }
    val rateFresh = rateAtMs?.let { s.atMs - it <= RATE_FRESH_MS } == true
    val (kw, src) = if (rateFresh && !rateLooksDead && !rateHeld && !rateDisagrees) {
      rateKw to PowerSource.CHARGE_RATE
    } else {
      estimateKw(s.atMs) to PowerSource.ESTIMATED
    }
    if (kw == null) {
      power = null
      return
    }
    val dir = when {
      kw > IDLE_KW -> PowerDirection.DISCHARGING
      // Charging only when a charge was detected; a held regen figure after stopping is just that.
      kw < -IDLE_KW -> if (charging) PowerDirection.CHARGING else PowerDirection.REGEN
      else -> PowerDirection.IDLE
    }
    power = PowerNow(kw, dir, src)
    val sec = Math.floorDiv(s.atMs, 1000L)
    if (sec != historySecond) {
      historySecond = sec
      history.add(s.atMs, kw)
      if (src == PowerSource.ESTIMATED) lastEstimatedAtMs = s.atMs
    }
  }

  /**
   * Energy needed before an efficiency is shown: [WARMUP_WH], or [WARMUP_STEPS] level steps when the
   * level property is coarser than 0.1 kWh (each end of the basis can be off by up to one step).
   */
  private fun warmUpWh(): Double = max(WARMUP_WH, WARMUP_STEPS * (levelStepWh ?: 0.0))

  /** Whether the charge-rate property is being bypassed for the estimate (for tests and hints). */
  val usingEstimate: Boolean get() = power?.estimated == true

  // --- Snapshot ---------------------------------------------------------------------------------

  fun snapshot(): SessionState {
    val k = last.k
    val e = last.e
    val needWh = warmUpWh()
    val ok = k >= WARMUP_M && e >= needWh
    val progress = if (ok || warmed) 1.0 else min(1.0, min(k / WARMUP_M, max(0.0, e) / needWh)).coerceAtLeast(0.0)
    val eff = Efficiency(
      status = if (ok) EffStatus.OK else if (warmed) EffStatus.NET_REGEN else EffStatus.WARMING_UP,
      mPerWh = if (ok) k / e else null,
      progress = progress,
      basisM = k,
      basisWh = e,
      needWh = needWh,
      early = ok && (k < EARLY_M || e < EARLY_WH),
      atM = last.d,
    )
    if (seriesDirty) {
      seriesCache = series.toList()
      seriesDirty = false
    }
    if (segmentsDirty) {
      miCache = miles.segments()
      kmCache = kms.segments()
      segmentsDirty = false
    }
    val now = lastAtMs ?: clock()
    val hist = history.snapshot(now)
    val peakD = hist.maxOfOrNull { it.kw }?.takeIf { it > IDLE_KW }
    val peakR = hist.minOfOrNull { it.kw }?.takeIf { it < -IDLE_KW }
    val open = segStartLevel
    val cur = level
    val used = usedClosedWh + if (!charging && open != null && cur != null) open - cur else 0.0
    val chargeStart = chargeStartLevel
    val charged = chargedWh + if (charging && cur != null && chargeStart != null) max(0.0, cur - chargeStart) else 0.0
    return SessionState(
      startedAtMs = startedAtMs,
      activeMs = activeMs,
      samples = samples,
      lastSampleAtMs = lastAtMs,
      distanceM = distanceM,
      usedWh = used,
      chargedWh = charged,
      charging = charging,
      gaps = gaps,
      gapMs = gapMs,
      efficiency = eff,
      series = seriesCache,
      miSegments = miCache,
      kmSegments = kmCache,
      power = power,
      powerHistory = hist,
      peakDischargeKw = peakD,
      peakRegenKw = peakR,
      powerHistoryEstimated = lastEstimatedAtMs?.let { now - it <= HISTORY_MS } == true,
      speedMps = speed,
      levelWh = level,
      capacityWh = capacity,
      rangeM = range,
      outsideC = outside,
      gear = gear,
      levelStepWh = levelStepWh,
      levelUnchangedM = max(0.0, distanceM - levelChangedAtM),
      chargeRateSeenNonZero = rateSeenNonZero,
      rateCheck = rateCheck,
    )
  }

  companion object {
    /** Samples further apart than this are a gap: nothing is integrated across it. */
    const val GAP_MS = 5_000L

    /** A speed reading above this is junk (about 220 mph), m/s. */
    const val MAX_SPEED_MPS = 100.0

    /** A level jump beyond this step plus this rate (over the time since the last step, capped) is suspect. */
    const val MAX_LEVEL_STEP_WH = 2_000.0
    const val MAX_LEVEL_WH_PER_MS = 400_000.0 / 3_600_000.0
    const val LEVEL_JUMP_WINDOW_MS = 120_000L

    /** Readings in a row it takes for a suspect level jump to count. */
    const val LEVEL_CONFIRM = 3

    /** Warm-up: at least 1.0 mi and 0.3 kWh measured before an efficiency is shown. */
    const val WARMUP_M = EffUnits.METERS_PER_MILE
    const val WARMUP_WH = 300.0
    const val WARMUP_STEPS = 3.0

    /** Below these a figure is shown but marked early, and no range is projected from it. */
    const val EARLY_M = 5 * EffUnits.METERS_PER_MILE
    const val EARLY_WH = 2_000.0

    /** No range is projected from a figure above this (6 mi/kWh): only a descent gets there, m/Wh. */
    const val MAX_RANGE_M_PER_WH = 6 * EffUnits.METERS_PER_MILE / 1000.0

    /** One cumulative-efficiency point per 0.1 mi. */
    const val SERIES_STEP_M = EffUnits.METERS_PER_MILE / 10.0
    const val MAX_SERIES = 4_000

    /** A segment needs this much net energy for an efficiency figure. */
    const val MIN_SEGMENT_WH = 20.0

    /** "Speed == 0" for charge detection (m/s). */
    const val STOPPED_MPS = 0.05

    /** Moving (ends a charge detected by a level rise; regen vs charging), m/s. */
    const val MOVING_MPS = 0.5

    /**
     * Level rise while standing still that means charging, Wh. Well above what a hard stop's regen
     * returns (about 370 Wh from 70 mph), which can land after the stop when the level is held.
     */
    const val CHARGE_RISE_WH = 1_000.0

    /** Level changes smaller than this are noise, Wh. */
    const val LEVEL_EPS_WH = 0.5

    /** Charge rate exactly 0 while faster than this for longer than [RATE_ZERO_MS]: use the estimate. */
    const val RATE_ZERO_MPS = 2.0
    const val RATE_ZERO_MS = 30_000L

    /** A charge-rate reading older than this no longer counts. */
    const val RATE_FRESH_MS = 5_000L

    /** A non-zero rate unchanged for this long, with a property timestamp as old, is a held value. */
    const val RATE_HELD_MS = 60_000L

    /** Windows of the rate-against-level check, and how far apart counts as disagreeing, kW. */
    const val RATE_CHECK_MS = 60_000L
    const val RATE_DISAGREE_KW = 5.0
    const val RATE_AGREE_KW = 1.0

    /** A charge-rate reading beyond this (2 MW) is junk, mW. */
    const val MAX_RATE_MW = 2.0e9

    /** Smoothing window of the d(level)/dt estimate. */
    const val EST_WINDOW_MS = 30_000L

    /** Level changes kept for the estimate. */
    const val LEVEL_LOG_MS = 15 * 60_000L
    const val LEVEL_LOG_MAX = 2_000

    /** No level step for this long (and much longer than usual): the estimate decays. */
    const val STALE_STEP_MS = 10_000L

    /** A level that has not moved for this long reads as ~0 kW (estimate). */
    const val IDLE_AFTER_MS = 60_000L

    const val IDLE_KW = 0.1

    const val HISTORY_MS = 10 * 60_000L
    const val HISTORY_CAPACITY = 660
  }
}
