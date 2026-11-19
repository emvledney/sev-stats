package dev.silveradostats.vehicle

import dev.silveradostats.link.LiveValue
import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): where the energy went, by the owner's request (2026-11-16): driving,
 * climate and the rest, for this drive, the last 15 minutes and since the last charge. Unit tested.
 *
 * The truck counts it itself. The BECM keeps three totals since its last full charge, read through the MX+
 * (27B9 total, 27BA driving and accessories, 27BB thermal management: Car Scanner's mapping, not yet matched
 * on this truck). This drive and the last 15 minutes are the totals now minus the totals when the window began;
 * since the charge is the totals as the truck gives them, no delta (the owner, 2026-11-17, after three builds
 * where a base kept from the app's own last charge read wrong on the truck). The car's API has nothing of the
 * kind for a third-party app, so with no MX+ there is no breakdown.
 *
 * The miles go the same way (the owner, 2026-11-18): 27C0, the distance since the last full charge as Car Scanner
 * reads it, for since the charge, and its change over the two drive windows. With no 27C0 reading at both ends of a
 * window, the session's own distance.
 *
 * "Other" is what the total holds beyond the two named parts. A window only starts at a reading: a drive that
 * began before the MX+ connected is counted from the first reading, not from its first mile.
 */

/** The BECM's totals since its last full charge, Wh, and the distance since it (27C0), m, when that was read. */
data class EnergyCounters(val totalWh: Double, val drivingWh: Double, val climateWh: Double, val distanceM: Double? = null) {
  /** All the truck has counted, one figure: the since-charge window's total. The session's energy is its change (SessionTracker.addCounter). */
  val usedWh: Double get() = max(totalWh, drivingWh + climateWh)

  companion object {
    val ZERO = EnergyCounters(0.0, 0.0, 0.0)

    const val TOTAL = "energy_used_total"
    const val DRIVING = "energy_used_driving"
    const val CLIMATE = "energy_used_climate"
    const val DISTANCE = "distance_since_full"

    /** From what the link holds, with when the newest of the three was read; null unless all three were. */
    fun read(values: Map<String, LiveValue>): Pair<Long, EnergyCounters>? {
      val read = listOf(TOTAL, DRIVING, CLIMATE).map { values[it] ?: return null }
      val c = ofKwh((read[0].v as? Number)?.toDouble(), (read[1].v as? Number)?.toDouble(), (read[2].v as? Number)?.toDouble()) ?: return null
      val miles = (values[DISTANCE]?.v as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
      return read.maxOf { it.atMs } to c.copy(distanceM = miles?.let { it * EffUnits.METERS_PER_MILE })
    }

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

/**
 * The three windows; null where there is no reading to go by. [driveM], [recentM] and [chargeM] are the metres driven
 * over the same windows, from their first reading: what their mi/kWh divide, since a window can start after the drive did.
 */
data class EnergySplit(
  val drive: EnergyParts? = null,
  val recent: EnergyParts? = null,
  val charge: EnergyParts? = null,
  val driveM: Double? = null,
  val recentM: Double? = null,
  val chargeM: Double? = null,
)

/** Single-threaded: the owner (LogHub) feeds and reads it from one thread. */
class EnergySplitTracker {
  private class At(val atMs: Long, val c: EnergyCounters, val m: Double)

  private var driveId: Long? = null
  private var driveBase: At? = null
  private val recent = ArrayDeque<At>()

  /** One reading of the totals. [driveId] changes when a new drive starts (SessionState.startedAtMs); [distanceM] is that drive's so far. */
  fun add(atMs: Long, c: EnergyCounters, driveId: Long, distanceM: Double = 0.0): EnergySplit {
    // The two drive windows are the totals now minus the totals at their first reading, and nothing else (the owner,
    // 2026-11-17). A total going down is not taken for the truck starting its count over: regen lowers them, and
    // twice on the truck that rule put the totals themselves in all three windows.
    if (driveId != this.driveId) {
      this.driveId = driveId
      driveBase = At(atMs, c, distanceM)
      // The last 15 minutes are the whole drive until it is that long, as the mi/kWh beside it.
      recent.clear()
    }

    if (recent.lastOrNull()?.let { atMs - it.atMs < RECENT_EVERY_MS } != true) recent.addLast(At(atMs, c, distanceM))
    // The newest reading at least 15 minutes old starts the window. One from long before a stretch
    // with no readings (another app in front) would make it far more than 15 minutes.
    while (recent.size >= 2 && atMs - recent[1].atMs >= SessionTracker.RECENT_MS) recent.removeFirst()
    while (recent.size >= 2 && atMs - recent[0].atMs > SessionTracker.RECENT_MS + RECENT_SLACK_MS) recent.removeFirst()

    return EnergySplit(
      drive = driveBase?.let { EnergyParts.between(it.c, c) },
      recent = recent.firstOrNull()?.let { EnergyParts.between(it.c, c) },
      // The truck's own totals since its last full charge, as read.
      charge = EnergyParts.between(EnergyCounters.ZERO, c),
      driveM = driveBase?.let { driven(it, c, distanceM) },
      recentM = recent.firstOrNull()?.let { driven(it, c, distanceM) },
      chargeM = c.distanceM,
    )
  }

  /** The truck's count of the distance where both ends have it, else the session's. */
  private fun driven(from: At, c: EnergyCounters, distanceM: Double): Double {
    val a = from.c.distanceM
    val b = c.distanceM
    return if (a != null && b != null) b - a else distanceM - from.m
  }

  companion object {
    /** The totals move by 0.01 kWh: a reading of them every few seconds is all the window needs. */
    const val RECENT_EVERY_MS = 10_000L

    /** How much older than 15 minutes the window's first reading may be. */
    const val RECENT_SLACK_MS = 2 * 60_000L
  }
}
