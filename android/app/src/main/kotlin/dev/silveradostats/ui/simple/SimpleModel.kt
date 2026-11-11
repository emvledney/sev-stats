package dev.silveradostats.ui.simple

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.LastCharge
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.Signal
import dev.silveradostats.vehicle.driftWh
import dev.silveradostats.vehicle.efficiencyMPerWh
import dev.silveradostats.vehicle.usedNowWh
import kotlin.math.abs

/*
 * Pure Kotlin (no android.* imports): SessionState -> exactly what the Simple tab draws. Unit tested;
 * the Compose code only lays it out.
 */

/** A figure on the page. [marker]: where it comes from, beside the title. */
data class Stat(
  val title: String,
  val value: String,
  val unit: String,
  val lines: List<String>,
  val hasValue: Boolean = true,
  val marker: String? = null,
)

/**
 * The last charge's row, each with the words under its figure as its title: what it reached, in kWh and %, what
 * has gone since, the mi/kWh since, and how far the level has drifted from that.
 */
data class ChargeView(val to: Stat, val toPct: Stat, val used: Stat, val efficiency: Stat, val drift: Stat)

enum class NoticeAction { GRANT, OPEN_DIAGNOSTICS }

/** A problem with the data feed, shown above the tiles. */
data class Notice(val title: String, val text: String, val action: NoticeAction?)

data class SimpleView(
  val notice: Notice?,
  /** The energy left, "72.7 kWh". */
  val remaining: Stat,
  /** The state of charge, "35.5 %". */
  val soc: Stat,
  /** From the MX+ when it has read it, else the car's API; [Stat.marker] says which. */
  val capacity: Stat,
  val range: Stat,
  /** This drive's distance. */
  val trip: Stat,
  /** This drive's net energy, regen included. */
  val energyUsed: Stat,
  /** This drive's distance over its energy: [trip] over [energyUsed], no warm-up. */
  val efficiency: Stat,
  /** The same over the last 15 minutes: the whole drive until it is that long. */
  val efficiencyRecent: Stat,
  val charge: ChargeView,
)

object SimpleModel {
  private val DASH = Units.DASH

  fun build(s: SessionState, dist: DistUnit, mxCapacityKwh: Double? = null, lastCharge: LastCharge? = null): SimpleView = SimpleView(
    notice = notice(s),
    remaining = remaining(s),
    soc = soc(s),
    capacity = capacity(s, mxCapacityKwh),
    range = range(s, dist),
    trip = trip(s, dist),
    energyUsed = energyUsed(s),
    efficiency = efficiency(s.plainEfficiency.driveMPerWh, EFF_DRIVE, dist),
    efficiencyRecent = efficiency(s.plainEfficiency.recentMPerWh, EFF_RECENT, dist),
    charge = charge(s, lastCharge, dist),
  )

  // --- Units ------------------------------------------------------------------------------------

  fun effUnit(d: DistUnit): String = if (d == DistUnit.MI) "mi/kWh" else "km/kWh"

  /** Efficiency in the user's unit (mi/kWh or km/kWh). */
  fun effValue(mPerWh: Double, d: DistUnit): Double = if (d == DistUnit.MI) EffUnits.miPerKwh(mPerWh) else EffUnits.kmPerKwh(mPerWh)

  fun effText(mPerWh: Double, d: DistUnit): String = Units.num(effValue(mPerWh, d), 2)

  fun distValue(m: Double, d: DistUnit): Double = Units.distFromMeters(m, d)

  /** "9.8 mi" / "15.8 km". */
  fun distText(m: Double, d: DistUnit, decimals: Int = 1): String = "${Units.num(distValue(m, d), decimals)} ${d.symbol}"

  /** "4.21" (two decimals below 100 kWh, one above). */
  fun kwhValue(wh: Double): String = Units.num(wh / 1000.0, if (abs(wh) < 100_000) 2 else 1)

  /** A runtime permission is missing: the tiles say so, not "not offered" (the notice has the button). */
  private fun noAccess(s: SessionState): Boolean = s.feed.missingPermissions.isNotEmpty()

  const val NEEDS_ACCESS = "Needs access to car data"

  fun notice(s: SessionState): Notice? {
    val f = s.feed
    return when {
      f.kind == FeedKind.NOT_AUTOMOTIVE -> Notice(
        "No car data on this device",
        "Turn on demo data under Diagnostics › OBDLink MX+ link to see a simulated drive.",
        NoticeAction.OPEN_DIAGNOSTICS,
      )
      f.missingPermissions.isNotEmpty() -> Notice(
        "Allow access to car data",
        "The session needs ${f.missingPermissions.joinToString(" and ") { it.substringAfterLast('.') }} " +
          "(battery energy and vehicle speed, read-only). Android lists them as car information and location.",
        NoticeAction.GRANT,
      )
      f.kind == FeedKind.NO_CAR_SERVICE -> Notice("Car service unavailable", f.message, null)
      f.kind == FeedKind.FAILED -> Notice("Measuring stopped", f.message, null)
      f.kind == FeedKind.STARTING && !s.hasData -> Notice("Connecting to the car service…", "Values appear in a moment.", null)
      else -> null
    }
  }

  // --- Capacity and range -----------------------------------------------------------------------

  const val FROM_API = "API"
  const val FROM_MX = "MX+"
  private const val CAPACITY = "Capacity"
  private const val RANGE = "Range"

  /** The capacity the MX+ is reading now (27AE), or null with no live reading to go by. */
  fun mxCapacityKwh(live: LiveState): Double? =
    if (live.status == LinkStatus.CONNECTED || live.status == LinkStatus.DEMO) (live.derived["capacity_kwh"] as? Number)?.toDouble() else null

  /** From the MX+ when it has read it, else the car's API. Usable now when the truck offers it; else what the pack was rated at. */
  fun capacity(s: SessionState, mxKwh: Double? = null): Stat {
    if (mxKwh != null) return Stat(CAPACITY, Units.num(mxKwh, 1), "kWh", emptyList(), marker = FROM_MX)
    val cap = s.capacityWh
    if (cap == null) {
      val why = when {
        noAccess(s) -> NEEDS_ACCESS
        Signal.CAPACITY in s.feed.missing -> "Capacity not offered by this car"
        else -> "Waiting for the capacity"
      }
      return Stat(CAPACITY, DASH, "kWh", listOf(why), hasValue = false)
    }
    return Stat(CAPACITY, Units.num(cap / 1000.0, 1), "kWh", emptyList(), marker = FROM_API)
  }

  /** The energy left at the last 15 minutes' efficiency, with the truck's own figure under it. */
  fun range(s: SessionState, d: DistUnit): Stat {
    val truck = when {
      s.rangeM != null -> "Truck says ${Units.num(distValue(s.rangeM, d), 0)} ${d.symbol}"
      !noAccess(s) && Signal.RANGE in s.feed.missing -> "Truck range not offered"
      else -> "Truck range $DASH"
    }
    val r = s.rangeEstimate ?: return Stat(RANGE, DASH, d.symbol, listOf(truck), hasValue = false)
    return Stat(RANGE, Units.num(distValue(r.m, d), 0), d.symbol, listOf(truck))
  }

  // --- What is left ----------------------------------------------------------------------------

  const val REMAINING = "Remaining"
  const val STATE_OF_CHARGE = "State of charge"

  /** The energy left, one decimal. Unknown: a dash, not a zero. */
  fun remaining(s: SessionState): Stat =
    Stat(REMAINING, s.levelWh?.let { Units.num(it / 1000.0, 1) } ?: DASH, "kWh", emptyList(), hasValue = s.levelWh != null)

  fun soc(s: SessionState): Stat =
    Stat(STATE_OF_CHARGE, s.socPct?.let { Units.num(it, 1) } ?: DASH, "%", emptyList(), hasValue = s.socPct != null)

  // --- Trip -------------------------------------------------------------------------------------

  /** This drive's distance. */
  fun trip(s: SessionState, d: DistUnit): Stat {
    val noSpeed = Signal.SPEED in s.feed.missing
    return Stat(
      "Trip",
      if (noSpeed) DASH else Units.num(distValue(s.distanceM, d), 1),
      d.symbol,
      emptyList(),
      hasValue = !noSpeed && s.hasData,
    )
  }

  /** This drive's net energy, regen included. */
  fun energyUsed(s: SessionState): Stat = Stat("Used", kwhValue(s.usedWh), "kWh", emptyList(), hasValue = s.hasData)

  // --- mi/kWh -----------------------------------------------------------------------------------

  private const val EFF_DRIVE = "This drive"
  private const val EFF_RECENT = "Last 15 min"

  /** A figure in mi/kWh under what it covers. Null: a dash, with nothing to divide yet or net regen. */
  private fun efficiency(mPerWh: Double?, covers: String, d: DistUnit): Stat =
    Stat(covers, mPerWh?.let { effText(it, d) } ?: DASH, effUnit(d), emptyList(), hasValue = mPerWh != null)

  // --- Last charge ------------------------------------------------------------------------------

  const val LAST_CHARGED_TO = "last charged to"
  const val NO_CHARGE_YET = "no charge yet"
  const val OF_CAPACITY = "of capacity"
  const val USED_SINCE = "used since"
  const val SINCE = "since"
  const val DRIFT = "drift"

  /** One decimal: the level moves in small steps, and these are read at a glance. */
  private fun kwhShort(wh: Double): String = Units.num(wh / 1000.0, 1)

  fun charge(s: SessionState, last: LastCharge?, d: DistUnit): ChargeView {
    val to = Stat(if (last == null) NO_CHARGE_YET else LAST_CHARGED_TO, last?.let { kwhShort(it.toWh) } ?: DASH, "kWh", emptyList(), hasValue = last != null)
    val pct = last?.toPct
    val toPct = Stat(OF_CAPACITY, pct?.let { Units.num(it, 1) } ?: DASH, "%", emptyList(), hasValue = pct != null)
    val used = last?.usedNowWh(s.levelWh)
    val usedStat = Stat(USED_SINCE, used?.let { kwhShort(it) } ?: DASH, "kWh", emptyList(), hasValue = used != null)
    val eff = last?.efficiencyMPerWh(used)
    val effStat = Stat(SINCE, eff?.let { effText(it, d) } ?: DASH, effUnit(d), emptyList(), hasValue = eff != null)
    val drift = last?.driftWh(s.levelWh)
    // + above what the charge and the energy used say, - below.
    val driftStat = Stat(DRIFT, drift?.let { Units.signed(it / 1000.0, 1) } ?: DASH, "kWh", emptyList(), hasValue = drift != null)
    return ChargeView(to, toPct, usedStat, effStat, driftStat)
  }
}
