package dev.silveradostats.vehicle

import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): where the energy went, by the owner's request (2026-11-16): driving,
 * climate and the rest, for this drive, the last 15 minutes and since the last charge. Unit tested.
 *
 * The truck counts it itself. The BECM keeps three totals since its last full charge, read through the MX+
 * (27B9 total, 27BA driving and accessories, 27BB thermal management: Car Scanner's mapping, not yet matched
 * on this truck), and each window is the totals now minus the totals when it began. The car's API has nothing
 * of the kind for a third-party app, so with no MX+ there is no breakdown.
 *
 * "Other" is what the total holds beyond the two named parts. A window only starts at a reading: a drive that
 * began before the MX+ connected is counted from the first reading, not from its first mile.
 */

/** The BECM's totals since its last full charge, Wh. */
data class EnergyCounters(val totalWh: Double, val drivingWh: Double, val climateWh: Double) {
  companion object {
    val ZERO = EnergyCounters(0.0, 0.0, 0.0)

    const val TOTAL = "energy_used_total"
    const val DRIVING = "energy_used_driving"
    const val CLIMATE = "energy_used_climate"

    /** From the profile's three fields in kWh; null unless all three were read. */
    fun ofKwh(total: Double?, driving: Double?, climate: Double?): EnergyCounters? {
      if (total == null || driving == null || climate == null) return null
      if (!(total.isFinite() && driving.isFinite() && climate.isFinite()) || total < 0 || driving < 0 || climate < 0) return null
      return EnergyCounters(total * 1000.0, driving * 1000.0, climate * 1000.0)
    }
  }
}

/** What one window used, Wh. Driving and climate as the truck counts them, negative too; what is left over for "other" never is. */
data class EnergyParts(val drivingWh: Double, val climateWh: Double, val otherWh: Double) {
  val totalWh: Double get() = drivingWh + climateWh + otherWh

  companion object {
    fun between(from: EnergyCounters, to: EnergyCounters): EnergyParts {
      // As counted, negative too (the owner, 2026-11-17): regen can put back more than a window's driving used.
      val driving = to.drivingWh - from.drivingWh
      val climate = to.climateWh - from.climateWh
      return EnergyParts(driving, climate, max(0.0, to.totalWh - from.totalWh - driving - climate))
    }
  }
}

/** The three windows; null where there is no reading to go by. */
data class EnergySplit(val drive: EnergyParts? = null, val recent: EnergyParts? = null, val charge: EnergyParts? = null)

/** The totals when the last charge ended, kept between runs of the app. [toWh] says which charge. */
data class EnergyChargeBase(val toWh: Double, val counters: EnergyCounters)

/** Single-threaded: the owner (LogHub) feeds and reads it from one thread. */
class EnergySplitTracker(chargeBase: EnergyChargeBase? = null) {
  private class At(val atMs: Long, val c: EnergyCounters)

  var chargeBase: EnergyChargeBase? = chargeBase
    private set

  private var driveId: Long? = null
  private var driveBase: EnergyCounters? = null
  private val recent = ArrayDeque<At>()

  /**
   * One reading of the totals. [driveId] changes when a new drive starts (SessionState.startedAtMs).
   * [charge] is the last charge, null where none is tracked (the demo's has no running total): then the
   * third window is the truck's own, since its last full charge.
   */
  fun add(atMs: Long, c: EnergyCounters, driveId: Long, charge: LastCharge?): EnergySplit {
    // Each window is the totals now minus the totals at its first reading, and nothing else (the owner, 2026-11-17).
    // A total going down is not taken for the truck starting its count over: regen lowers them, and twice on the
    // truck that rule put the totals themselves in all three windows. A charge starts a new drive and a new
    // last charge, which both take a new first reading anyway.
    if (driveId != this.driveId) {
      this.driveId = driveId
      driveBase = c
      // The last 15 minutes are the whole drive until it is that long, as the mi/kWh beside it.
      recent.clear()
    }

    if (recent.lastOrNull()?.let { atMs - it.atMs < RECENT_EVERY_MS } != true) recent.addLast(At(atMs, c))
    // The newest reading at least 15 minutes old starts the window. One from long before a stretch
    // with no readings (another app in front) would make it far more than 15 minutes.
    while (recent.size >= 2 && atMs - recent[1].atMs >= SessionTracker.RECENT_MS) recent.removeFirst()
    while (recent.size >= 2 && atMs - recent[0].atMs > SessionTracker.RECENT_MS + RECENT_SLACK_MS) recent.removeFirst()

    val tracked = charge?.takeIf { it.usedWh != null }
    // Nothing used since the charge yet: it may still be going on, and what it took is not the truck's use since.
    if (tracked != null && (chargeBase?.toWh != tracked.toWh || tracked.usedWh == 0.0)) chargeBase = EnergyChargeBase(tracked.toWh, c)

    return EnergySplit(
      drive = driveBase?.let { EnergyParts.between(it, c) },
      recent = recent.firstOrNull()?.let { EnergyParts.between(it.c, c) },
      charge = EnergyParts.between(if (tracked != null) chargeBase?.counters ?: c else EnergyCounters.ZERO, c),
    )
  }

  companion object {
    /** The totals move by 0.01 kWh: a reading of them every few seconds is all the window needs. */
    const val RECENT_EVERY_MS = 10_000L

    /** How much older than 15 minutes the window's first reading may be. */
    const val RECENT_SLACK_MS = 2 * 60_000L
  }
}
