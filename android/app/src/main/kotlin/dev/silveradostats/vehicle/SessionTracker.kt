package dev.silveradostats.vehicle

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Kotlin (no android.* imports): the current drive, built from the truck's own vehicle data (no
 * OBD adapter). Unit tested on the JVM with a virtual clock.
 *
 * A drive starts when the truck first leaves Park, and again when it leaves Park after DRIVE_BREAK_MS
 * parked or switched off, or after a charge: everything below starts again from zero there. A long wait
 * in gear is the same drive, and so is a stretch with no readings (another app in front) that no
 * reading saw the truck parked in.
 *
 * Inputs are timestamped samples (VehicleSample) at about 1-2 Hz. Outputs (SessionState):
 *  - distance: trapezoidal integration of |speed|, never across a gap > GAP_MS between samples;
 *  - energy used: from the power that actually flowed where there is a signal for it, else from the
 *    battery level. In order: the MX+ (pack V x A, [addPack]), EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
 *    the level. A power signal counts only once it agrees with the level over at least
 *    [SessionTracker.TRUST_MIN_WH] of movement ([SignalCheck]), which also settles its sign and rules
 *    out a stuck or wrongly scaled one: AOSP says + charging, and GM's has not been checked under load.
 *    Regen comes back through the same signal, so the figure is net. Nothing is counted across a gap or
 *    a charge;
 *  - measured efficiency: distance / energy. With a power signal both advance at every reading. The
 *    level property moves in steps (by value and/or by time: a held value that jumps every few
 *    seconds), so on the level alone they are taken at level CHANGE points: the distance driven since
 *    the last step has no energy against it yet, and measuring from the first step to the latest (the
 *    "basis") holds the figure steady between steps. The first WARMUP_MS of each drive are left out of
 *    it: the BMS is still settling and the level falls far faster than the energy used. Reported only
 *    after that, WARMUP_M and WARMUP_WH, marked "early" until EARLY_M and EARLY_WH; once reported it
 *    never goes back to warming up (a long descent reads NET_REGEN instead);
 *  - the same figure over only the last RECENT_MS: the basis now against the basis then;
 *  - plain efficiency, for the Simple tab: this drive's distance / this drive's energy used, and the
 *    same over the last RECENT_MS. No warm-up, so not the measured efficiency above (the report keeps
 *    that one);
 *  - range: the energy left x that plain figure over the last RECENT_MS, by the owner's choice
 *    (2026-11-06), which is the whole drive until it is that long. Standing still it keeps the figure
 *    it took as it stopped: the window would otherwise slide down to the last moments of braking and
 *    then to nothing, in a long wait and at a charger. A figure stands for RANGE_EVERY_MS, so that it
 *    can be read.
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
  const val PARK = 4
  const val DRIVE = 8
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
  /** IGNITION_STATE (AOSP VehicleIgnitionState). */
  val ignition: Int? = null,
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
  /** 0..1 progress through the warm-up (the drive's first minutes, then both distance and energy). */
  val progress: Double,
  /** Distance the figure is measured over (between battery-level steps), m. */
  val basisM: Double,
  /** Net energy the figure is measured over, Wh. */
  val basisWh: Double,
  /** A figure, but over too little distance or energy to say much yet. */
  val early: Boolean = false,
  /** What is left of this drive's [SessionTracker.WARMUP_MS], all of it until the truck moves: nothing counts yet, ms. */
  val warmupLeftMs: Long = 0,
) {
  companion object {
    val NONE = Efficiency(EffStatus.WARMING_UP, null, 0.0, 0.0, 0.0, warmupLeftMs = SessionTracker.WARMUP_MS)
  }
}

/** The figure over only the last [SessionTracker.RECENT_MS]: what [Efficiency] leaves out stays out of this too. */
data class RecentEfficiency(
  /** Metres per Wh; null with too little distance, or too little net energy (a descent), to go by. */
  val mPerWh: Double? = null,
  val basisM: Double = 0.0,
  val basisWh: Double = 0.0,
)

/** What the session's energy figure is measured from at the moment. */
enum class EnergySource {
  /** Pack voltage x pack current from the MX+. */
  MX_PACK,

  /** The truck's EV_BATTERY_INSTANTANEOUS_CHARGE_RATE. */
  CHARGE_RATE,

  /** Changes of the truck's battery level: no power signal is live or trusted. */
  BATTERY_LEVEL,
}

/** A power signal against the battery level over the same time, net Wh, + discharge as the signal reads. */
data class SignalVerdict(
  val signalWh: Double = 0.0,
  val levelWh: Double = 0.0,
  /** 1: agrees with the level. -1: agrees, opposite sign. 0: not trusted (yet). */
  val sign: Int = 0,
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
  /** INFO_EV_BATTERY_CAPACITY, Wh: what the car's API says the pack was when new. */
  val ratedWh: Double? = null,
)

/** The energy left x the last 15 minutes' efficiency: [m] metres at [mPerWh]. */
data class RangeEstimate(val m: Double, val mPerWh: Double)

/**
 * Distance / energy with no warm-up: from the drive's first reading, and over the last
 * [SessionTracker.RECENT_MS] (the whole drive until it is that long). Null with nothing to divide.
 */
data class PlainEfficiency(val driveMPerWh: Double? = null, val recentMPerWh: Double? = null)

/** Everything the Simple tab shows about the session. */
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
  /** Out of the battery while a power signal measured it, Wh. */
  val energyOutWh: Double = 0.0,
  /** Back into the battery (regen) while a power signal measured it, Wh. */
  val energyInWh: Double = 0.0,
  /** What the battery level alone says was used over the same session, Wh: for comparison. */
  val levelUsedWh: Double = 0.0,
  val energySource: EnergySource = EnergySource.BATTERY_LEVEL,
  val chargeRateCheck: SignalVerdict = SignalVerdict(),
  val mxPackCheck: SignalVerdict = SignalVerdict(),
  /** Energy added while charging was detected, Wh. */
  val chargedWh: Double = 0.0,
  /** Charging detected: energy accounting is paused. */
  val charging: Boolean = false,
  /** The plug or charge state says charging, as opposed to [charging] which a rising level can set too. */
  val chargeSignal: Boolean = false,
  val gaps: Int = 0,
  val gapMs: Long = 0,
  val efficiency: Efficiency = Efficiency.NONE,
  val recentEfficiency: RecentEfficiency = RecentEfficiency(),
  val speedMps: Double? = null,
  val levelWh: Double? = null,
  val capacityWh: Double? = null,
  val rangeM: Double? = null,
  val outsideC: Double? = null,
  val gear: Int? = null,
  val ignition: Int? = null,
  /** Smallest battery-level step seen, Wh (how coarse the level property is). */
  val levelStepWh: Double? = null,
  /** Distance driven since the battery level last changed, m. */
  val levelUnchangedM: Double = 0.0,
  /** The charge-rate property has reported a non-zero value in this session (it is live). */
  val chargeRateSeenNonZero: Boolean = false,
  val feed: Feed = Feed(),
  /** The app's own range, null with nothing to work it out from. See [SessionTracker.rangeFrom]. */
  val rangeEstimate: RangeEstimate? = null,
  val plainEfficiency: PlainEfficiency = PlainEfficiency(),
) {
  val socPct: Double?
    get() {
      val l = levelWh ?: return null
      val c = capacityWh?.takeIf { it > 0 } ?: return null
      return l / c * 100.0
    }

  /** Average speed over the sampled time (stops included), m/s. */
  val avgSpeedMps: Double? get() = if (activeMs >= 1_000) distanceM / (activeMs / 1000.0) else null

  val hasData: Boolean get() = samples > 0
}

/** Single-threaded: the owner (VehicleSession) feeds and reads it from one thread. */
class SessionTracker(
  /** The start of each drive left out of the efficiency. */
  private val warmupMs: Long = WARMUP_MS,
  private val clock: () -> Long,
) {
  /** A battery-level change point: session distance, basis distance, basis energy, level, when. */
  private data class Point(val d: Double, val k: Double, val e: Double, val level: Double, val at: Long = 0)

  /** This drive's distance and net energy so far, at [at]. */
  private data class Totals(val at: Long, val d: Double, val e: Double)

  /** A power signal against the battery level: the signal counts only while its [sign] is not 0. */
  private class SignalCheck {
    var signalWh = 0.0
    var levelWh = 0.0
    var sign = 0
    /** The first sign it settled on: a signal does not change direction later. */
    private var settled = 0

    fun add(signal: Double, level: Double, minWh: Double) {
      signalWh += signal
      levelWh += level
      // Judged on energy gone out. Too little has moved to say anything, or a descent has undone it, and
      // a level that rose can be a wobble, which says nothing about which way a signal runs: the last
      // verdict stands. Taking back a trust given takes more: each change of source keeps the level's
      // wobble at that moment in the energy used.
      if (levelWh < (if (sign == 0) minWh else minWh * DISTRUST_FACTOR)) return
      val ratio = signalWh / levelWh
      val verdict = when {
        ratio in MIN_SIGNAL_RATIO..MAX_SIGNAL_RATIO -> 1
        -ratio in MIN_SIGNAL_RATIO..MAX_SIGNAL_RATIO -> -1
        else -> 0
      }
      if (settled == 0) settled = verdict
      sign = if (verdict == -settled) 0 else verdict
    }

    /** Judges from here on: the last verdict stands until enough has moved again. */
    fun restart() {
      signalWh = 0.0
      levelWh = 0.0
    }

    fun verdict() = SignalVerdict(signalWh, levelWh, sign)
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
  private var ignition: Int? = null
  private var chargeSignal = false

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
  /** Measured energy minus what the level says over the same readings: added to the level's total. */
  private var measuredOverLevelWh = 0.0
  private var energyOutWh = 0.0
  private var energyInWh = 0.0
  private var energySource = EnergySource.BATTERY_LEVEL
  /** The reading the next energy step starts from (null: the next reading starts a new stretch). */
  private var energyAtMs: Long? = null

  // Drive warm-up.
  /** When the warm-up ends: [NOT_MOVED] until the truck moves, null once it is over. */
  private var warmupEndsAtMs: Long? = NOT_MOVED

  // Drives.
  /** The latest reading with the truck being driven; null until it has been. */
  private var drivenAtMs: Long? = null
  /** A reading has seen the truck parked since then. */
  private var parkedSeen = false
  /** A charge ended since then: the next driving is a new drive, however soon. */
  private var chargedSinceDriven = false
  /** The gear as far as a reading since the last gap says: [DrivingPolicy.GEAR_UNKNOWN] is no answer. */
  private var knownGear: Int? = null
  /** The truck has given a gear at all. If not, standing still is all there is to go by. */
  private var gearOffered = false

  // The range on screen.
  private var ownRange: RangeEstimate? = null
  private var ownRangeAtMs: Long? = null
  private var movedAtMs: Long? = null

  // The plain efficiency on screen, and this drive's running totals every RANGE_EVERY_MS of the last RECENT_MS.
  private var plain = PlainEfficiency()
  private var plainAtMs: Long? = null
  private val totals = ArrayDeque<Totals>()

  // Basis (points).
  private var last = Point(0.0, 0.0, 0.0, Double.NaN)
  private var anchorPending = true
  /** A figure has been reported in this session. */
  private var warmed = false
  /** The points of the last [RECENT_MS], from the newest one at least that old. */
  private val recent = ArrayDeque<Point>()
  private var observedLevel: Double? = null
  private var levelStepWh: Double? = null
  /** Session distance when the level last changed (or a gap restarted the watch). */
  private var levelChangedAtM = 0.0

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

  // Charge rate.
  private var rateKw: Double? = null
  private var ratePrevKw: Double? = null
  private var rateAtMs: Long? = null
  private var zeroWhileMovingMs = 0L
  private var rateLooksDead = false
  private var rateSeenNonZero = false
  /** The last raw rate and when it last changed (for a value held with an old timestamp). */
  private var rateRaw: Double? = null
  private var rateChangedAtMs = 0L
  private var rateHeld = false
  private var rateCheck = SignalCheck()

  // MX+ pack power.
  private var packKw: Double? = null
  private var packAtMs: Long? = null
  /** Integrated since the last energy step. */
  private var packStepWh = 0.0
  private var packCheck = SignalCheck()

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
    measuredOverLevelWh = 0.0
    energyOutWh = 0.0
    energyInWh = 0.0
    energySource = EnergySource.BATTERY_LEVEL
    energyAtMs = null
    warmupEndsAtMs = NOT_MOVED
    drivenAtMs = null
    parkedSeen = false
    chargedSinceDriven = false
    ownRange = null
    ownRangeAtMs = null
    movedAtMs = null
    plain = PlainEfficiency()
    plainAtMs = null
    totals.clear()
    last = Point(0.0, 0.0, 0.0, Double.NaN)
    anchorPending = true
    warmed = false
    recent.clear()
    observedLevel = null
    levelStepWh = null
    levelChangedAtM = 0.0
    charging = false
    chargingByRise = false
    chargeStartLevel = null
    chargeClaimStale = false
    stationary = false
    stopMinLevel = null
    stopMinPoint = null
    rateKw = null
    ratePrevKw = null
    rateAtMs = null
    zeroWhileMovingMs = 0
    rateLooksDead = false
    rateSeenNonZero = false
    rateRaw = null
    rateHeld = false
    rateCheck = SignalCheck()
    packKw = null
    packAtMs = null
    packStepWh = 0.0
    packCheck = SignalCheck()
  }

  /**
   * Starts a new drive at this reading if it is the first of one: see the top of this file. A stretch
   * with no readings is not parked unless a reading says so, so a drive goes on behind Maps.
   */
  private fun noteDriving(s: VehicleSample, v: Double?) {
    // The gear from before a gap says nothing about now, and a speed-only reading can't settle it.
    if (lastAtMs?.let { s.atMs - it > GAP_MS } == true) knownGear = null
    s.gear?.takeIf { it != DrivingPolicy.GEAR_UNKNOWN }?.let {
      knownGear = it
      gearOffered = true
    }
    val g = knownGear
    val driven = (v != null && v > MOVING_MPS) || (g != null && g != Gear.PARK)
    if (!driven) {
      if (g == Gear.PARK || (!gearOffered && v != null)) parkedSeen = true
      return
    }
    val since = drivenAtMs
    val newDrive = since == null || chargedSinceDriven || (parkedSeen && s.atMs - since >= DRIVE_BREAK_MS)
    if (newDrive && samples > 0) reset()
    drivenAtMs = s.atMs
    parkedSeen = false
    chargedSinceDriven = false
  }

  /** Feeds one sample. Samples must come in time order; an older one is ignored. */
  fun add(s: VehicleSample) {
    if (lastAtMs?.let { s.atMs < it } == true) return
    val v = s.speedMps?.takeIf { it.isFinite() }?.let { abs(it) }?.takeIf { it <= MAX_SPEED_MPS }
    noteDriving(s, v)
    val prevAt = lastAtMs
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
    s.ignition?.let { ignition = it }

    // Distance: trapezoid between consecutive speed samples, never across a gap.
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
      if (v > MOVING_MPS) {
        noteMoving(s.atMs)
        movedAtMs = s.atMs
      }
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
    chargeSignal = !chargeClaimStale && ChargeReading.saysCharging(plug, chargeState)
    if (explicit && !charging) startCharging(byRise = false)

    readRate(s, gap)
    val lv = plausibleLevel(s.levelWh?.takeIf { it.isFinite() && it >= 0 }, s.atMs)
    if (lv != null) {
      level = lv
      if (!charging && speedOutSinceMs == null) account(lv, s.atMs) else restartEnergy()
      if (charging && chargeStartLevel == null) chargeStartLevel = lv
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
    noteTotals(s.atMs)
  }

  /** Keeps what the rolling figure needs, whether or not anyone is reading it. */
  private fun noteTotals(atMs: Long) {
    if (totals.lastOrNull()?.let { atMs - it.at < RANGE_EVERY_MS } == true) return
    totals.addLast(Totals(atMs, distanceM, usedNow()))
    while (totals.size >= 2 && atMs - totals[1].at >= RECENT_MS) totals.removeFirst()
  }

  /**
   * One pack power reading from the MX+, kW (+ discharge). Integrated between readings; the next
   * [add] with a level decides whether it counts. Readings further apart than [PACK_GAP_MS] start
   * again: a slow poll cycle is fine, an unread stretch is not energy.
   */
  fun addPack(atMs: Long, kw: Double) {
    if (!kw.isFinite() || abs(kw) > MAX_RATE_KW) return
    val prevAt = packAtMs
    val prevKw = packKw
    if (prevAt != null && prevKw != null && atMs > prevAt && atMs - prevAt <= PACK_GAP_MS) {
      packStepWh += (prevKw + kw) / 2.0 * (atMs - prevAt) / 3600.0
    }
    packKw = kw
    packAtMs = atMs
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

  /** The truck is moving. The first time, the warm-up starts now; a pause changes nothing: the BMS's clock kept running. */
  private fun noteMoving(atMs: Long) {
    if (warmupEndsAtMs == NOT_MOVED) warmupEndsAtMs = atMs + warmupMs
  }

  /** A gap: the energy segment closes; distance and energy restart from the next readings. */
  private fun rebase() {
    closeSegment()
    levelUnchecked = true
    levelChangedAtM = distanceM
    restartEnergy()
    packAtMs = null
    observedLevel = null
    anchorPending = true
    stationary = false
    stopMinLevel = null
    stopMinPoint = null
  }

  /** Nothing since the last reading counts: a gap or a charge. */
  private fun restartEnergy() {
    energyAtMs = null
    packStepWh = 0.0
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
      stopMinPoint?.let { stop ->
        last = stop
        while ((recent.lastOrNull()?.at ?: 0L) > stop.at) recent.removeLast()
      }
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
    // Whatever the length of the charge, the next movement is a new drive.
    warmupEndsAtMs = NOT_MOVED
    chargedSinceDriven = true
  }

  /** Energy accounting for one level reading (not while charging). */
  private fun account(lv: Double, atMs: Long) {
    // In the warm-up the basis only moves along with the truck; the first reading after it starts the figure.
    warmupEndsAtMs?.let { end ->
      anchorPending = true
      if (atMs >= end) {
        warmupEndsAtMs = null
        // The level was what is settling: it judges the power signals only from here on.
        packCheck.restart()
        rateCheck.restart()
      }
    }
    if (segStartLevel == null) segStartLevel = lv
    val obs = observedLevel
    val levelWh = if (obs != null && abs(lv - obs) >= LEVEL_EPS_WH) obs - lv else 0.0
    val wasOnLevel = energySource == EnergySource.BATTERY_LEVEL
    val levelWasOut = energyAtMs?.let { atMs - it > RATE_FRESH_MS } == true
    val measuredWh = measuredStep(atMs, levelWh)
    if (obs == null) {
      observedLevel = lv
      return
    }
    if (levelWh != 0.0) {
      levelStepWh = min(levelStepWh ?: abs(levelWh), abs(levelWh))
      levelChangedAtM = distanceM
      observedLevel = lv
    }
    // Across a switch of source the basis must keep distance and energy over the same stretch: a level
    // step reaches back to the last step, a signal's reading only to the last reading.
    when {
      measuredWh != null && !wasOnLevel -> {
        addPoint(lv, measuredWh)
        measuredOverLevelWh += measuredWh - levelWh
      }
      measuredWh != null && levelWh != 0.0 -> addPoint(lv, last.level - lv)
      measuredWh != null -> {
        anchorPending = true
        addPoint(lv, 0.0)
      }
      !wasOnLevel -> {
        // The signal has already counted what this step of the level covers, unless the level was out:
        // then the step is all there is for that stretch.
        if (!levelWasOut) measuredOverLevelWh -= levelWh
        anchorPending = true
        addPoint(lv, 0.0)
      }
      levelWh != 0.0 -> addPoint(lv, last.level - lv)
    }
    val m = stopMinLevel
    if (levelWh != 0.0 && stationary && m != null && lv < m) {
      stopMinLevel = lv
      stopMinPoint = last
    }
  }

  /** Advances the basis to here, with [wh] of energy since the last point. */
  private fun addPoint(lv: Double, wh: Double) {
    val d = distanceM
    val at = lastAtMs ?: 0L
    val p = if (anchorPending) Point(d, last.k, last.e, lv, at) else Point(d, last.k + max(0.0, d - last.d), last.e + wh, lv, at)
    last = p
    recent.addLast(p)
    if (p.k >= WARMUP_M && p.e >= warmUpWh()) warmed = true
    anchorPending = false
  }

  /**
   * The energy since the last reading from the best power signal that is trusted, or null when there
   * is none and the level has to do. Every signal with data is checked against the level, used or not.
   */
  private fun measuredStep(atMs: Long, levelWh: Double): Double? {
    val stepMs = energyAtMs?.let { atMs - it }
    energyAtMs = atMs
    val minWh = trustMinWh()
    val pack = packStepWh.takeIf { packAtMs?.let { at -> atMs - at <= PACK_GAP_MS } == true }
    packStepWh = 0.0
    if (pack != null && stepMs != null) packCheck.add(pack, levelWh, minWh)
    val rate = rateStepWh(stepMs, atMs)
    if (rate != null) rateCheck.add(rate, levelWh, minWh)
    val wh: Double
    when {
      pack != null && packCheck.sign != 0 -> {
        energySource = EnergySource.MX_PACK
        wh = pack * packCheck.sign
      }
      rate != null && rateCheck.sign != 0 && !rateLooksDead && !rateHeld -> {
        energySource = EnergySource.CHARGE_RATE
        wh = rate * rateCheck.sign
      }
      else -> {
        energySource = EnergySource.BATTERY_LEVEL
        return null
      }
    }
    if (wh >= 0) energyOutWh += wh else energyInWh -= wh
    return wh
  }

  /** The charge rate's energy over this step, as the signal reads (+ discharge by the AOSP sign). */
  private fun rateStepWh(stepMs: Long?, atMs: Long): Double? {
    // Longer than this and the level was out: one reading of power can't speak for the whole stretch.
    if (stepMs == null || stepMs > RATE_FRESH_MS) return null
    val now = rateKw ?: return null
    if (rateAtMs?.let { atMs - it <= RATE_FRESH_MS } != true) return null
    return ((ratePrevKw ?: now) + now) / 2.0 * stepMs / 3600.0
  }

  /** The level's own uncertainty sets how much it must move before it can judge a signal. */
  private fun trustMinWh(): Double = max(TRUST_MIN_WH, WARMUP_STEPS * (levelStepWh ?: 0.0))

  // --- Charge rate ------------------------------------------------------------------------------

  private fun readRate(s: VehicleSample, gap: Boolean) {
    val rate = s.chargeRateMw?.takeIf { it.isFinite() && abs(it) <= MAX_RATE_MW } ?: return
    val prevAt = rateAtMs
    val sincePrev = if (prevAt != null && !gap) s.atMs - prevAt else null
    if (rate != rateRaw) {
      rateRaw = rate
      rateChangedAtMs = s.atMs
    }
    ratePrevKw = if (gap) null else rateKw
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

  /**
   * Energy needed before an efficiency is shown: [WARMUP_WH], or on the level alone [WARMUP_STEPS]
   * level steps when it is coarser than 0.1 kWh (each end of the basis can be off by up to one step).
   */
  private fun warmUpWh(): Double =
    if (energySource == EnergySource.BATTERY_LEVEL) max(WARMUP_WH, WARMUP_STEPS * (levelStepWh ?: 0.0)) else WARMUP_WH

  // --- Snapshot ---------------------------------------------------------------------------------

  /** The basis now against the basis [RECENT_MS] ago, or as long ago as it goes. */
  private fun recentEfficiency(needWh: Double): RecentEfficiency {
    val now = lastAtMs ?: return RecentEfficiency()
    while (recent.size >= 2 && now - recent[1].at >= RECENT_MS) recent.removeFirst()
    val start = recent.firstOrNull() ?: return RecentEfficiency()
    val k = last.k - start.k
    val e = last.e - start.e
    return RecentEfficiency(if (k >= WARMUP_M && e >= needWh) k / e else null, k, e)
  }

  /**
   * A figure stands for [RANGE_EVERY_MS]; with none on screen, the first there is shows at once. On the
   * move and for [RANGE_STOPPING_MS] after it goes by [recentMPerWh], then by the efficiency it took then.
   */
  private fun ownRangeNow(recentMPerWh: Double?): RangeEstimate? {
    val now = lastAtMs ?: return null
    if (ownRange != null && ownRangeAtMs?.let { now - it < RANGE_EVERY_MS } == true) return ownRange
    val moving = movedAtMs?.let { now - it <= RANGE_STOPPING_MS } == true
    ownRange = rangeFrom(level, if (moving) recentMPerWh else ownRange?.mPerWh)
    ownRangeAtMs = now
    return ownRange
  }

  /** A figure stands for [RANGE_EVERY_MS], like the range; with none on screen, the first there is shows at once. */
  private fun plainNow(usedWh: Double): PlainEfficiency {
    val now = lastAtMs ?: return PlainEfficiency()
    if (plain.driveMPerWh != null && plainAtMs?.let { now - it < RANGE_EVERY_MS } == true) return plain
    val start = totals.firstOrNull()
    plain = PlainEfficiency(plainFrom(distanceM, usedWh), start?.let { plainFrom(distanceM - it.d, usedWh - it.e) })
    plainAtMs = now
    return plain
  }

  /** What the level says was used, closed segments and the open one. */
  private fun levelUsedNow(): Double {
    val open = segStartLevel
    val cur = level
    return usedClosedWh + if (!charging && open != null && cur != null) open - cur else 0.0
  }

  /** This drive's net energy: the level's total, corrected by what the power signals measured. */
  private fun usedNow(): Double = levelUsedNow() + measuredOverLevelWh

  fun snapshot(): SessionState {
    val k = last.k
    val e = last.e
    val needWh = warmUpWh()
    val ok = k >= WARMUP_M && e >= needWh
    val warmupLeft = warmupEndsAtMs?.let { (it - (lastAtMs ?: 0L)).coerceIn(0L, warmupMs) } ?: 0L
    val basisProgress = min(1.0, min(k / WARMUP_M, max(0.0, e) / needWh)).coerceAtLeast(0.0)
    val progress = if (ok || warmed) 1.0 else (warmupMs - warmupLeft + WARMUP_BASIS_MS * basisProgress) / (warmupMs + WARMUP_BASIS_MS)
    val eff = Efficiency(
      status = if (ok) EffStatus.OK else if (warmed) EffStatus.NET_REGEN else EffStatus.WARMING_UP,
      mPerWh = if (ok) k / e else null,
      progress = progress,
      basisM = k,
      basisWh = e,
      early = ok && (k < EARLY_M || e < EARLY_WH),
      warmupLeftMs = warmupLeft,
    )
    val cur = level
    val levelUsed = levelUsedNow()
    val chargeStart = chargeStartLevel
    val charged = chargedWh + if (charging && cur != null && chargeStart != null) max(0.0, cur - chargeStart) else 0.0
    val used = usedNow()
    val plainFigures = plainNow(used)
    return SessionState(
      startedAtMs = startedAtMs,
      activeMs = activeMs,
      samples = samples,
      lastSampleAtMs = lastAtMs,
      distanceM = distanceM,
      usedWh = used,
      energyOutWh = energyOutWh,
      energyInWh = energyInWh,
      levelUsedWh = levelUsed,
      energySource = energySource,
      chargeRateCheck = rateCheck.verdict(),
      mxPackCheck = packCheck.verdict(),
      chargedWh = charged,
      charging = charging,
      gaps = gaps,
      gapMs = gapMs,
      efficiency = eff,
      recentEfficiency = recentEfficiency(needWh),
      rangeEstimate = ownRangeNow(plainFigures.recentMPerWh),
      plainEfficiency = plainFigures,
      speedMps = speed,
      levelWh = level,
      capacityWh = capacity,
      rangeM = range,
      outsideC = outside,
      gear = gear,
      ignition = ignition,
      chargeSignal = chargeSignal,
      levelStepWh = levelStepWh,
      levelUnchangedM = max(0.0, distanceM - levelChangedAtM),
      chargeRateSeenNonZero = rateSeenNonZero,
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

    /** The start of a drive left out of the efficiency: the BMS takes about this long to settle (seen on the truck). */
    const val WARMUP_MS = 5 * 60_000L

    /** Parked or switched off for this long: leaving Park is a new drive. */
    const val DRIVE_BREAK_MS = 5 * 60_000L

    /** The range moves this often: every reading is too often to read. */
    const val RANGE_EVERY_MS = 10_000L

    /**
     * Stopped for this long, the range holds: time for one more figure after the wheels stop, with what the
     * level has still to say about the stop, which lands after it, and before the window has slid at all.
     */
    const val RANGE_STOPPING_MS = 15_000L

    /** [warmupEndsAtMs] until the truck moves: the warm-up has not started. */
    private const val NOT_MOVED = Long.MAX_VALUE

    /** Then at least 1.0 mi and 0.3 kWh measured before an efficiency is shown. */
    const val WARMUP_M = EffUnits.METERS_PER_MILE
    const val WARMUP_WH = 300.0
    const val WARMUP_STEPS = 3.0

    /** About how long that distance and energy take: their share of the warm-up's progress. */
    const val WARMUP_BASIS_MS = 75_000L

    /** The rolling figure beside the overall one. */
    const val RECENT_MS = 15 * 60_000L

    /** Below these a figure is shown but marked early. */
    const val EARLY_M = 5 * EffUnits.METERS_PER_MILE
    const val EARLY_WH = 2_000.0

    /** No range is projected from a figure above this (6 mi/kWh): only a descent gets there, m/Wh. */
    const val MAX_RANGE_M_PER_WH = 6 * EffUnits.METERS_PER_MILE / 1000.0

    /** The energy left x [mPerWh]. Null with either unknown and above [MAX_RANGE_M_PER_WH]. */
    fun rangeFrom(levelWh: Double?, mPerWh: Double?): RangeEstimate? {
      val level = levelWh?.takeIf { it >= 0 } ?: return null
      val e = mPerWh?.takeIf { it > 0 && it <= MAX_RANGE_M_PER_WH } ?: return null
      return RangeEstimate(level * e, e)
    }

    /** No figure above this (20 mi/kWh): the first moments of a drive or a descent, not an efficiency to read, m/Wh. */
    const val MAX_PLAIN_M_PER_WH = 20 * EffUnits.METERS_PER_MILE / 1000.0

    /** Null with nothing to divide (no distance yet, or regen has put back all that was used) and above [MAX_PLAIN_M_PER_WH]. */
    fun plainFrom(distanceM: Double, usedWh: Double): Double? {
      if (distanceM <= 0 || usedWh <= 0) return null
      return (distanceM / usedWh).takeIf { it <= MAX_PLAIN_M_PER_WH }
    }

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

    /** A power signal is judged once the level has moved this far: enough to outweigh its steps and wobble. */
    const val TRUST_MIN_WH = 500.0

    /** How many times [TRUST_MIN_WH] the level must have moved before a trusted signal can lose its trust. */
    const val DISTRUST_FACTOR = 4.0

    /** How far a signal's energy may be from the level's and still count as the same energy. */
    const val MIN_SIGNAL_RATIO = 0.5
    const val MAX_SIGNAL_RATIO = 2.0

    /** MX+ pack readings further apart than this are not integrated: a poll cycle takes a few seconds. */
    const val PACK_GAP_MS = 10_000L

    /** A charge-rate reading beyond this (2 MW) is junk, mW. */
    const val MAX_RATE_MW = 2.0e9

    /** A pack power reading beyond this (2 MW) is junk, kW. */
    const val MAX_RATE_KW = 2_000.0
  }
}
