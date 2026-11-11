package dev.silveradostats.vehicle

import kotlin.math.abs

/*
 * Pure Kotlin (no android.* imports): the last charge and what has been used since, worked out from
 * battery-level readings, because the truck reports neither. Unit tested. LogHub feeds it what the session
 * and the log read and keeps its state between runs of the app.
 *
 * The owner's question (2026-11-06) is how the energy used compares with what the truck thought it had when
 * the charge finished, and the level moves by itself: 104 kWh at the end of a charge can read 102 or 106 the
 * next time the truck is on. So:
 *
 * A charge is one of:
 *  - the level rising by [ChargeWatch.MIN_ADDED_WH] while the readings say charging, with the head unit up;
 *  - the level [ChargeWatch.MIN_JUMP_WH] above its lowest since the truck last moved, which is what a charge
 *    with the head unit asleep looks like, or one with no plug or charge state to read. Far more when already
 *    moving again. Less than that is the level settling, whether or not the truck is plugged in.
 * The level it reached follows the level up only while the readings say charging: what the level does after
 * that is not what the charge reached.
 *
 * What is used since is a running total, not the level it reached minus the level now: what the level falls
 * (and regen puts back) between two readings with the truck on and the head unit up in between. Not counted:
 * anything with the truck off or the head unit asleep, a charge, the first [ChargeWatch.SETTLE_MS] after the
 * truck comes on if it has not moved, and a step larger than the truck could use ([ChargeWatch.CORRECTION_WH]),
 * which is the battery's own figure being corrected. What that leaves out shows as the drift ([driftWh]).
 *
 * The efficiency since, the owner's figure (2026-11-06), is the distance the session drove at the readings that
 * counted, over that same total, so that the two cover the same stretches. It has no figure until the next charge
 * after a charge from before the miles were counted, or a stretch that used energy with nothing measuring the
 * miles: a figure with kWh and no miles against them would read low for the rest of the charge.
 *
 * Not seen: a charge that was driven back below the previous reading with no reading in between, a charge under
 * [ChargeWatch.MIN_JUMP_WH] with the head unit asleep throughout, and the level at the end of a charge with the
 * head unit asleep: the first reading after it wakes stands in for it.
 */

/**
 * The last charge: the level it reached, the state of charge at that level as Simple's State of charge cell works it out,
 * and what has been used since. [usedWh] is null only for a figure that is not tracked (the demo's).
 * [levelWh] is the reading [usedWh] goes with, so that [driftWh] never mixes a newer level with an older total.
 * [drivenM] is the distance driven since, null where it is not known: see [efficiencyMPerWh].
 */
data class LastCharge(val toWh: Double, val toPct: Double?, val usedWh: Double? = null, val levelWh: Double? = null, val drivenM: Double? = null)

/** One reading of the truck, from either source. */
data class ChargeReading(
  val levelWh: Double?,
  val capacityWh: Double?,
  /** The plug or charge state says charging, not just that the level rose. */
  val charging: Boolean,
  val moving: Boolean,
  /** See [truckOn]. */
  val on: Boolean = true,
  /** Monotonic ms, counting through sleep. */
  val atMs: Long = 0L,
  /** The head unit has stayed up since the previous reading: see [ReadingClock]. */
  val awake: Boolean = true,
  /** The session's distance for this drive, which starts over with each drive; null from a reading that has none. */
  val tripM: Double? = null,
) {
  companion object {
    /** From a read of the car's properties. A charging claim at speed is a held value (as in [SessionTracker]). */
    fun fromCar(levelWh: Double?, capacityWh: Double?, plugged: Boolean?, chargeState: ChargeState?, speedMps: Double?, ignition: Int? = null, gear: Int? = null): ChargeReading {
      val speed = speedMps?.takeIf { it.isFinite() }?.let { abs(it) } ?: 0.0
      val moving = speed > SessionTracker.MOVING_MPS
      return ChargeReading(levelWh, capacityWh, saysCharging(plugged, chargeState) && speed <= SessionTracker.RATE_ZERO_MPS, moving, truckOn(ignition, gear, moving))
    }

    fun fromSession(s: SessionState): ChargeReading {
      val moving = (s.speedMps ?: 0.0) > SessionTracker.MOVING_MPS
      return ChargeReading(s.levelWh, s.capacityWh, s.chargeSignal, moving, truckOn(s.ignition, s.gear, moving), tripM = s.distanceM)
    }

    /** Plugged in is charging unless the charge state knows better: a charge that has finished is not going on. */
    fun saysCharging(plugged: Boolean?, chargeState: ChargeState?): Boolean =
      chargeState == ChargeState.CHARGING || (plugged == true && (chargeState == null || chargeState == ChargeState.UNKNOWN))

    /**
     * Moving or out of Park it is on, whatever a held value says. Parked, the ignition decides: ON or START.
     * With no ignition to read, a head unit that is up with the truck parked counts as on.
     */
    fun truckOn(ignition: Int?, gear: Int?, moving: Boolean): Boolean = when {
      moving || (gear != null && gear != 0 && gear != Gear.PARK) -> true
      ignition == null || ignition == IGNITION_UNDEFINED -> true
      else -> ignition == IGNITION_ON || ignition == IGNITION_START
    }

    private const val IGNITION_UNDEFINED = 0
    private const val IGNITION_ON = 4
    private const val IGNITION_START = 5
  }
}

/**
 * When a reading was taken, by the three clocks that tell the head unit sleeping or restarting (the truck was
 * off) from the app merely not having run (the truck may have been driven all the while).
 */
data class ReadingClock(val elapsedMs: Long, val uptimeMs: Long, val wallMs: Long) {
  fun stayedUpSince(prev: ReadingClock): Boolean {
    val gap = elapsedMs - prev.elapsedMs
    if (gap < 0 || gap > MAX_GAP_MS) return false
    // Elapsed time counts through a sleep and uptime does not.
    if (gap - (uptimeMs - prev.uptimeMs) > MAX_ASLEEP_MS) return false
    // A restart: the moment elapsed time counts from has moved.
    return abs((wallMs - elapsedMs) - (prev.wallMs - prev.elapsedMs)) <= MAX_BOOT_SHIFT_MS
  }

  companion object {
    /** No reading for this long, the hourly job included: nothing says what happened in between. */
    const val MAX_GAP_MS = 2 * 3_600_000L
    const val MAX_ASLEEP_MS = 60_000L
    const val MAX_BOOT_SHIFT_MS = 30_000L
  }
}

/** What is kept between runs of the app. */
data class ChargeWatchState(
  val charge: LastCharge? = null,
  /** The level of the newest reading. */
  val seenWh: Double? = null,
  /** A charge is going on, as far as the readings say: [charge] follows the level up. */
  val inCharge: Boolean = false,
  /** The truck was on at the newest reading. */
  val on: Boolean = false,
)

/** Single-threaded: the owner feeds and reads it from one thread. */
class ChargeWatch(start: ChargeWatchState = ChargeWatchState()) {
  var state: ChargeWatchState = start
    private set

  /** The level when the current claim of charging began, or null with no such claim. */
  private var claimFromWh: Double? = null

  /** The lowest level since the truck last moved. */
  private var lowWh: Double? = null

  /** Until then the truck has only just come on: the level is still settling. */
  private var settleUntilMs = 0L

  /** When the level last moved; null until a reading of this run has said. */
  private var levelMovedAtMs: Long? = null

  /** The newest reading of this run: when, and how far the session had driven. */
  private var readAtMs: Long? = null
  private var tripSeenM: Double? = null

  init {
    // Saved before what is used was counted.
    if (start.charge != null && start.charge.usedWh == null) usedFromLevels()
  }

  fun add(r: ChargeReading) {
    // A car service that is only just up can answer 0: that is no reading, and the next would look like a charge.
    val level = r.levelWh?.takeIf { it.isFinite() && it > 0 } ?: return
    val seen = state.seenWh
    val charge = state.charge

    if (!r.awake || (r.on && !state.on)) settleUntilMs = r.atMs + SETTLE_MS
    if (r.moving) settleUntilMs = 0L
    val settling = r.atMs < settleUntilMs

    // Moving ends it, and so does a level well below what it reached: it was driven down with no reading in between.
    val following = state.inCharge && r.awake && r.charging && !r.moving && (charge == null || level > charge.toWh - END_FALL_WH)
    // The level moving as the truck comes on is not charging, plugged in or not: a claim starts from what it settled at.
    val from = if (!r.charging) null else if (settling) level else minOf(claimFromWh ?: seen ?: level, level)
    val added = from?.let { level - it } ?: 0.0
    val low = if (r.moving) level else minOf(lowWh ?: seen ?: level, level)
    val rose = if (r.moving) seen?.let { level - it } ?: 0.0 else level - low
    val started = rose >= (if (r.moving) MIN_JUMP_MOVING_WH else MIN_JUMP_WH) || (!following && added >= MIN_ADDED_WH)
    // Up with a charge: what it has added is no rise left over for what the level does after it.
    lowWh = if (started || following) level else low
    // A counted charge starts the claim over: plugged in afterwards, a falling level is not a new one when it ends.
    claimFromWh = if (r.charging && (following || started)) level else from

    // + used, - put back. The level moving further than the truck could use in the time is the figure being corrected.
    val step = seen?.let { it - level } ?: 0.0
    val correction = levelMovedAtMs?.let { abs(step) >= CORRECTION_WH + MAX_USE_WH_PER_MS * (r.atMs - it) } ?: false
    if (levelMovedAtMs == null || abs(step) >= SessionTracker.LEVEL_EPS_WH) levelMovedAtMs = r.atMs
    val counts = r.awake && state.on && r.on && !settling && !correction && !following && !started && !(r.charging && step < 0)

    val trip = r.tripM?.takeIf { it.isFinite() && it >= 0 }
    val lastTrip = tripSeenM
    // A new drive or a reset starts the session's count over.
    val driven = if (trip == null || lastTrip == null) 0.0 else if (trip >= lastTrip) trip - lastTrip else trip
    if (trip != null) tripSeenM = trip
    // Energy gone between readings far apart, the first of a run included: no one was counting the miles.
    val milesLost = abs(step) >= MILES_LOST_WH && readAtMs?.let { r.atMs - it > SessionTracker.GAP_MS } != false
    readAtMs = r.atMs

    val next = when {
      started || (following && (charge == null || level > charge.toWh)) -> LastCharge(level, pct(level, r.capacityWh, charge), 0.0, drivenM = 0.0)
      charge != null && counts -> charge.copy(usedWh = (charge.usedWh ?: 0.0) + step, drivenM = if (milesLost) null else charge.drivenM?.plus(driven))
      else -> charge
    }
    state = ChargeWatchState(next, level, (started || following) && !r.moving, r.on)
  }

  /** What the level alone says has gone: the best there is for a charge from before this was counted, or from the log. */
  fun usedFromLevels() {
    val charge = state.charge ?: return
    state = state.copy(charge = charge.copy(usedWh = charge.usedSinceWh(state.seenWh) ?: 0.0, drivenM = null))
  }

  /** At the capacity read now; with none, the one the last charge's own figures imply. */
  private fun pct(level: Double, capacityWh: Double?, last: LastCharge?): Double? {
    val capacity = capacityWh?.takeIf { it.isFinite() && it > 0 } ?: last?.toPct?.takeIf { it > 0 }?.let { last.toWh / it * 100.0 }
    return capacity?.let { level / it * 100.0 }
  }

  companion object {
    /** Added while the readings say charging: past what regen landing late after a stop can look like (about 370 Wh). */
    const val MIN_ADDED_WH = 500.0

    /** A rise that means a charge, parked: past the level settling, seen by the owner at 2 kWh either way. */
    const val MIN_JUMP_WH = 5_000.0

    /** The same, already moving again: no descent gets this far, and any real charge does. */
    const val MIN_JUMP_MOVING_WH = 10_000.0

    /** The level this far below what a charge reached: that charge is over. */
    const val END_FALL_WH = 1_000.0

    /** After the truck comes on or the head unit wakes, until the truck moves: the level is not usage yet. */
    const val SETTLE_MS = 2 * 60_000L

    /** One step of the level this large, beyond what the truck could use since it last moved, is no usage. */
    const val CORRECTION_WH = 1_500.0
    const val MAX_USE_WH_PER_MS = 150_000.0 / 3_600_000.0

    /** Used in one step with nothing measuring: more than a moment of driving, so the miles since are no longer known. */
    const val MILES_LOST_WH = 500.0

    /** How far [ChargeWatchState] may drift from what is saved: every reading would be far too many writes. */
    private const val SAVE_EVERY_WH = 100.0
    private const val SAVE_EVERY_M = 500.0

    /** Whether [now] differs from what was last saved by enough to write it. */
    fun saveDue(saved: ChargeWatchState?, now: ChargeWatchState): Boolean {
      if (saved == null) return true
      if (saved.inCharge != now.inCharge || saved.on != now.on || (saved.charge == null) != (now.charge == null)) return true
      if ((saved.charge?.drivenM == null) != (now.charge?.drivenM == null)) return true
      val to = abs((saved.charge?.toWh ?: 0.0) - (now.charge?.toWh ?: 0.0))
      val used = abs((saved.charge?.usedWh ?: 0.0) - (now.charge?.usedWh ?: 0.0))
      val seen = abs((saved.seenWh ?: 0.0) - (now.seenWh ?: 0.0))
      val driven = abs((saved.charge?.drivenM ?: 0.0) - (now.charge?.drivenM ?: 0.0))
      return to >= SAVE_EVERY_WH || used >= SAVE_EVERY_WH || seen >= SAVE_EVERY_WH || driven >= SAVE_EVERY_M
    }
  }
}

/** The level it reached minus [levelWh], never below zero: the demo's figure, and all a charge from before the running total has. */
fun LastCharge.usedSinceWh(levelWh: Double?): Double? = levelWh?.let { (toWh - it).coerceAtLeast(0.0) }

/** What the Simple tab's last line reads: the running total where there is one. Never below zero. */
fun LastCharge.usedNowWh(levelWh: Double?): Double? = usedWh?.coerceAtLeast(0.0) ?: usedSinceWh(levelWh)

/**
 * How far the level stands from what the charge and the energy used say it should be, the owner's figure
 * (2026-11-06): + above that, - below it. 104 kWh, 2 used and a level of 97 is 5 below. Everything the
 * running total leaves out ends up here: the level settling, parked drain, the battery's figure being corrected.
 */
fun LastCharge.driftWh(levelNowWh: Double?): Double? {
  val level = levelWh ?: levelNowWh ?: return null
  return level - (toWh - (usedWh ?: usedSinceWh(level) ?: 0.0))
}

/** Metres per Wh since the charge: [drivenM] over [used], what the last line reads. Null where the miles are not known or there is nothing to divide. */
fun LastCharge.efficiencyMPerWh(used: Double?): Double? {
  val driven = drivenM ?: return null
  return used?.let { SessionTracker.plainFrom(driven, it) }
}
