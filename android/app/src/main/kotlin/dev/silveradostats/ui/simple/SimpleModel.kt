package dev.silveradostats.ui.simple

import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.EnergyParts
import dev.silveradostats.vehicle.EnergySplit
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal

/*
 * Pure Kotlin (no android.* imports): SessionState -> exactly what the Simple tab (API on screen) draws, and the
 * OBD page's three windows from the MX+ ([SimpleModel.windows]). Unit tested; the Compose code only lays it out.
 *
 * The API page shows the car's API only, the OBD page the MX+ only (the owner, 2026-11-18): the capacity from the
 * MX+ and the energy band went from this page to the OBD one.
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

/** One part of an energy window: its name, its kWh, and its share of the bar (0..1). */
data class EnergyPart(val label: String, val value: String, val share: Float)

/** One window of the energy band: what it covers, its total (or why there is none), and driving, climate and the rest. */
data class EnergyWindow(val title: String, val total: String, val parts: List<EnergyPart>, val hasValue: Boolean)

/** One of the OBD page's windows: its mi/kWh over its miles, then where the energy went. All from the MX+. */
data class WindowView(val efficiency: Stat, val miles: Stat, val energy: EnergyWindow)

enum class NoticeAction { GRANT, OPEN_DIAGNOSTICS }

/** A problem with the data feed, shown above the tiles. */
data class Notice(val title: String, val text: String, val action: NoticeAction?)

/** The API page: three figures from the car's API (the owner, 2026-11-18), and any problem with the feed. */
data class SimpleView(
  val notice: Notice?,
  /** The energy left, "72.7 kWh". */
  val remaining: Stat,
  /** The state of charge, "35.5 %". */
  val soc: Stat,
  val capacity: Stat,
  /** The state of charge, 0..1, for the battery drawn on the page; null when unknown. */
  val socFraction: Float? = null,
)

object SimpleModel {
  private val DASH = Units.DASH

  fun build(s: SessionState): SimpleView = SimpleView(
    notice = notice(s),
    remaining = remaining(s),
    soc = soc(s),
    capacity = capacity(s),
    socFraction = s.socPct?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() },
  )

  /**
   * The OBD page's windows, this drive, the last 15 minutes and since the charge: each its mi/kWh over its miles, then
   * its energy band. All the truck's own counts (the owner, 2026-11-18): since the charge as read (27B9 and 27C0), the
   * other two their change since the window's first reading. [split] null: no MX+ link.
   */
  fun windows(split: EnergySplit?, d: DistUnit): List<WindowView> =
    listOf(
      Triple(EFF_DRIVE, split?.drive, split?.driveM),
      Triple(EFF_RECENT, split?.recent, split?.recentM),
      Triple(SINCE_CHARGE, split?.charge, split?.chargeM),
    ).map { (title, parts, m) ->
      val eff = if (parts != null && m != null) SessionTracker.plainFrom(m, parts.totalWh) else null
      WindowView(
        efficiency(eff, title, d),
        Stat(title, m?.let { Units.num(distValue(it, d), 1) } ?: DASH, d.symbol, emptyList(), hasValue = m != null),
        energyWindow(title, parts, linked = split != null),
      )
    }

  // --- Units ------------------------------------------------------------------------------------

  fun effUnit(d: DistUnit): String = if (d == DistUnit.MI) "mi/kWh" else "km/kWh"

  /** Efficiency in the user's unit (mi/kWh or km/kWh). */
  fun effValue(mPerWh: Double, d: DistUnit): Double = if (d == DistUnit.MI) EffUnits.miPerKwh(mPerWh) else EffUnits.kmPerKwh(mPerWh)

  fun effText(mPerWh: Double, d: DistUnit): String = Units.num(effValue(mPerWh, d), 2)

  fun distValue(m: Double, d: DistUnit): Double = Units.distFromMeters(m, d)

  /** "9.8 mi" / "15.8 km". */
  fun distText(m: Double, d: DistUnit, decimals: Int = 1): String = "${Units.num(distValue(m, d), decimals)} ${d.symbol}"

  /** A runtime permission is missing: the tiles say so, not "not offered" (the notice has the button). */
  private fun noAccess(s: SessionState): Boolean = s.feed.missingPermissions.isNotEmpty()

  const val NEEDS_ACCESS = "Needs access to car data"

  fun notice(s: SessionState): Notice? {
    val f = s.feed
    return when {
      f.kind == FeedKind.NOT_AUTOMOTIVE -> Notice(
        "No car data on this device",
        "Turn on demo data under Settings › OBDLink MX+ link to see a simulated drive.",
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

  // --- Capacity -----------------------------------------------------------------------

  private const val CAPACITY = "Capacity"

  /** The car's API's: usable now when the truck offers it; else what the pack was rated at. */
  fun capacity(s: SessionState): Stat {
    val cap = s.capacityWh
    if (cap == null) {
      val why = when {
        noAccess(s) -> NEEDS_ACCESS
        Signal.CAPACITY in s.feed.missing -> "Capacity not offered by this car"
        else -> "Waiting for the capacity"
      }
      return Stat(CAPACITY, DASH, "kWh", listOf(why), hasValue = false)
    }
    // No tag: everything on this page is the API's (the owner, 2026-11-18).
    return Stat(CAPACITY, Units.num(cap / 1000.0, 1), "kWh", emptyList())
  }

  // --- What is left ----------------------------------------------------------------------------

  const val REMAINING = "Remaining"
  const val STATE_OF_CHARGE = "State of charge"

  /** The energy left, one decimal. Unknown: a dash, not a zero. */
  fun remaining(s: SessionState): Stat =
    Stat(REMAINING, s.levelWh?.let { Units.num(it / 1000.0, 1) } ?: DASH, "kWh", emptyList(), hasValue = s.levelWh != null)

  fun soc(s: SessionState): Stat =
    Stat(STATE_OF_CHARGE, s.socPct?.let { Units.num(it, 1) } ?: DASH, "%", emptyList(), hasValue = s.socPct != null)

  // --- Where the energy went --------------------------------------------------------------------

  const val NEEDS_MX = "needs the MX+"
  const val NO_READING = "no reading"
  private val ENERGY_PARTS = listOf("Driving", "Climate", "Other")

  private fun energyWindow(title: String, p: EnergyParts?, linked: Boolean): EnergyWindow {
    if (p == null) return EnergyWindow(title, if (linked) NO_READING else NEEDS_MX, ENERGY_PARTS.map { EnergyPart(it, DASH, 0f) }, hasValue = false)
    val wh = listOf(p.drivingWh, p.climateWh, p.otherWh)
    // The bar shows what was used: a part that regen has taken below zero has its figure, and no length.
    val used = wh.sumOf { it.coerceAtLeast(0.0) }
    // One decimal (the owner, 2026-11-17): the truck counts these in tenths of a kWh.
    return EnergyWindow(title, "${kwhShort(p.totalWh)} kWh", ENERGY_PARTS.indices.map { EnergyPart(ENERGY_PARTS[it], kwhShort(wh[it]), if (used > 0) (wh[it].coerceAtLeast(0.0) / used).toFloat() else 0f) }, hasValue = true)
  }

  // --- mi/kWh -----------------------------------------------------------------------------------

  private const val EFF_DRIVE = "This drive"
  private const val EFF_RECENT = "Last 15 min"

  /** A figure in mi/kWh under what it covers. Null: a dash, with nothing to divide yet or net regen. */
  private fun efficiency(mPerWh: Double?, covers: String, d: DistUnit): Stat =
    Stat(covers, mPerWh?.let { effText(it, d) } ?: DASH, effUnit(d), emptyList(), hasValue = mPerWh != null)

  const val SINCE_CHARGE = "Since charge"

  /** One decimal: the truck counts these in tenths of a kWh. */
  private fun kwhShort(wh: Double): String = Units.num(wh / 1000.0, 1)
}
