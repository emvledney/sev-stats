package dev.silveradostats.ui.simple

import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.EnergySource
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.RangeBasis
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): SessionState -> exactly what the Simple tab draws. Unit tested;
 * the Compose code only lays it out.
 *
 * Units: mi/kWh first and Wh/mi second (km/kWh and Wh/km in metric), °C / °F as chosen.
 */

/** A KPI tile, or one of the two figures of a tile that holds two. [marker]: "early" etc. beside the title. */
data class Stat(
  val title: String,
  val value: String,
  val unit: String,
  val lines: List<String>,
  val hasValue: Boolean = true,
  val marker: String? = null,
  /** The title on a narrow tile and while driving. */
  val shortTitle: String = title,
  /** The unit on a narrow tile and while driving. */
  val shortUnit: String = unit,
  /** False when [lines] only explain a missing figure: while driving the tile shows just the dash. */
  val linesWhileDriving: Boolean = true,
)

data class ChartPoint(val x: Double, val y: Double)

data class RingView(
  /** 0..1 state of charge, null if unknown. */
  val fraction: Double?,
  val soc: String,
  val remaining: String,
  val max: String,
  val truckRange: String,
)

enum class NoticeAction { GRANT, OPEN_DIAGNOSTICS }

/** A problem with the data feed, shown above the tiles. */
data class Notice(val title: String, val text: String, val action: NoticeAction?)

data class SimpleView(
  /** "Since app opened 0:24 · 9.8 mi". */
  val header: String,
  /** "0:24 · 9.8 mi": [header] on a narrow header row and while driving. */
  val summary: String,
  /** "2 pauses": left out of the figures, so it stays visible while driving; null when none. */
  val pauses: String?,
  /** Pauses, charging, demo: one line under the header, or null. */
  val note: String?,
  /** When measuring runs and what pauses it, or null. */
  val hint: String?,
  /** Something was already left out, so [hint] is a warning, not just advice. */
  val hintWarn: Boolean,
  val notice: Notice?,
  /** The car API's capacity, always. */
  val capacity: Stat,
  val range: Stat,
  val ring: RingView,
  /** The whole session's figure, or why there is none yet. */
  val efficiency: Stat,
  /** The figure over only the last 15 minutes, beside [efficiency]; null while that has no figure to stand beside. */
  val recentEfficiency: Stat?,
  val spark: List<ChartPoint>,
  val trip: Stat,
  val energyUsed: Stat,
)

object SimpleModel {
  private val DASH = Units.DASH
  private const val MPH_PER_MPS = 2.2369362920544

  fun build(s: SessionState, temp: TempUnit, dist: DistUnit): SimpleView = SimpleView(
    header = header(s, dist),
    summary = summary(s, dist),
    pauses = pauses(s),
    note = note(s, dist),
    hint = hint(s),
    hintWarn = s.gaps > 0,
    notice = notice(s),
    capacity = capacity(s),
    range = range(s, dist),
    ring = ring(s, dist),
    efficiency = efficiency(s, dist),
    recentEfficiency = recentEfficiency(s, dist),
    spark = spark(s, dist),
    trip = trip(s, temp, dist),
    energyUsed = energyUsed(s),
  )

  // --- Units ------------------------------------------------------------------------------------

  fun effUnit(d: DistUnit): String = if (d == DistUnit.MI) "mi/kWh" else "km/kWh"

  fun consumptionUnit(d: DistUnit): String = if (d == DistUnit.MI) "Wh/mi" else "Wh/km"

  /** Efficiency in the user's unit (mi/kWh or km/kWh). */
  fun effValue(mPerWh: Double, d: DistUnit): Double = if (d == DistUnit.MI) EffUnits.miPerKwh(mPerWh) else EffUnits.kmPerKwh(mPerWh)

  fun effText(mPerWh: Double, d: DistUnit): String = Units.num(effValue(mPerWh, d), 2)

  /** "431 Wh/mi" / "268 Wh/km"; dash for a non-positive efficiency. */
  fun consumptionText(mPerWh: Double, d: DistUnit): String {
    val v = if (d == DistUnit.MI) EffUnits.whPerMi(mPerWh) else EffUnits.whPerKm(mPerWh)
    return "${v?.let { Units.num(it, 0) } ?: DASH} ${consumptionUnit(d)}"
  }

  fun distValue(m: Double, d: DistUnit): Double = Units.distFromMeters(m, d)

  /** "9.8 mi" / "15.8 km". */
  fun distText(m: Double, d: DistUnit, decimals: Int = 1): String = "${Units.num(distValue(m, d), decimals)} ${d.symbol}"

  /** "45 mph" / "72 km/h". */
  fun speedText(mps: Double, d: DistUnit): String =
    if (d == DistUnit.MI) "${Units.num(mps * MPH_PER_MPS, 0)} mph" else "${Units.num(mps * 3.6, 0)} km/h"

  /** "4.21" (two decimals below 100 kWh, one above). */
  fun kwhValue(wh: Double): String = Units.num(wh / 1000.0, if (abs(wh) < 100_000) 2 else 1)

  /** "4.21 kWh". */
  fun kwhText(wh: Double): String = "${kwhValue(wh)} kWh"

  /** h:mm, e.g. "0:24", "1:05". */
  fun duration(ms: Long): String {
    val min = max(0L, ms) / 60_000
    return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
  }

  // --- Header -----------------------------------------------------------------------------------

  fun header(s: SessionState, d: DistUnit): String = "Since app opened ${summary(s, d)}"

  fun summary(s: SessionState, d: DistUnit): String =
    "${duration(s.activeMs)} · ${if (Signal.SPEED in s.feed.missing) DASH else distText(s.distanceM, d)}"

  fun pauses(s: SessionState): String? = s.gaps.takeIf { it > 0 }?.let { "$it ${if (it == 1) "pause" else "pauses"}" }

  fun note(s: SessionState, d: DistUnit): String? {
    val parts = mutableListOf<String>()
    if (s.feed.kind == FeedKind.DEMO) parts += "Simulated drive (demo data)"
    if (!s.feed.sampling && s.hasData && s.feed.kind != FeedKind.FAILED) parts += "Paused: resumes while a screen of the app is open"
    if (s.charging) {
      parts += "Charging: energy not counted" + if (s.chargedWh >= 50) " (+${kwhText(s.chargedWh)})" else ""
    } else if (s.chargedWh >= 50) {
      parts += "+${kwhText(s.chargedWh)} charged, not counted"
    }
    if (s.gaps > 0) {
      parts += "${s.gaps} ${if (s.gaps == 1) "pause" else "pauses"} left out (${Units.age(s.gapMs)})"
    }
    if (s.efficiency.status != EffStatus.WARMING_UP && levelStuck(s)) {
      parts += "Battery level unchanged for ${distText(s.levelUnchangedM, d)}"
    }
    // A later drive of the session: the figure stands still meanwhile, and this says why.
    if (s.efficiency.status != EffStatus.WARMING_UP && s.efficiency.warmupLeftMs > 0) parts += warmupText(s.efficiency.warmupLeftMs)
    energyNote(s)?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  /** With a power signal in use: what it says went out and came back, against the battery level's figure. */
  private fun energyNote(s: SessionState): String? {
    if (s.energySource == EnergySource.BATTERY_LEVEL) return null
    return "Energy from ${sourceName(s.energySource)}: ${kwhText(s.energyOutWh)} out, ${kwhText(s.energyInWh)} back " +
      "(battery level says ${kwhText(s.levelUsedWh)} used)"
  }

  private fun sourceName(e: EnergySource) = when (e) {
    EnergySource.MX_PACK -> "the MX+"
    EnergySource.CHARGE_RATE -> "the truck's charge rate"
    EnergySource.BATTERY_LEVEL -> "the battery level"
  }

  /** The badge on the session's energy: what it is measured from. */
  fun sourceMarker(e: EnergySource): String = when (e) {
    EnergySource.MX_PACK -> "MX+"
    EnergySource.CHARGE_RATE -> "charge rate"
    EnergySource.BATTERY_LEVEL -> "battery level"
  }

  /**
   * Android freezes an app none of whose screens is showing, within seconds with other apps in front,
   * so a drive is measured while this app or Battery Glance is on screen. Shown from the start, not
   * only once part of a drive has been lost.
   */
  fun hint(s: SessionState): String? =
    if (s.feed.kind == FeedKind.VEHICLE && !noAccess(s)) MEASURING_HINT else null

  const val MEASURING_HINT = "Measuring runs while this app or Battery Glance is on screen; another app in front, such as Maps, pauses it."

  /** The battery level has not moved over a distance that should have moved it. */
  private fun levelStuck(s: SessionState): Boolean = s.levelWh != null && s.levelUnchangedM >= LEVEL_STUCK_M

  private const val LEVEL_STUCK_M = 3 * EffUnits.METERS_PER_MILE

  /** Marks an efficiency over the first few miles. */
  const val EARLY = "early"

  /** Marks a range worked out from an earlier drive's efficiency. */
  const val LAST_DRIVE = "last drive"

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

  // --- Efficiency -------------------------------------------------------------------------------

  private const val EFFICIENCY_TITLE = "Efficiency since opened"
  private const val EFFICIENCY_SHORT = "Efficiency"
  private const val OVERALL = "Overall"
  private const val RECENT = "Last ${SessionTracker.RECENT_MS / 60_000} min"
  private const val NET_REGEN = "Net regen"

  fun efficiency(s: SessionState, d: DistUnit): Stat {
    val e = s.efficiency
    val m = e.mPerWh
    if (e.status == EffStatus.OK && m != null) {
      return Stat(OVERALL, effText(m, d), effUnit(d), listOf(consumptionText(m, d)), marker = if (e.early) EARLY else null)
    }
    if (e.status == EffStatus.NET_REGEN) return Stat(OVERALL, NET_REGEN, "", listOf(netRegenText(e.basisM, e.basisWh, d)), hasValue = false, linesWhileDriving = false)
    fun none(value: String, why: String) = Stat(EFFICIENCY_TITLE, value, "", listOf(why), hasValue = false, shortTitle = EFFICIENCY_SHORT, linesWhileDriving = false)
    if (noAccess(s)) return none("No access", NEEDS_ACCESS)
    if (Signal.SPEED in s.feed.missing) return none("No data", "Needs vehicle speed: not offered by this car")
    if (Signal.LEVEL in s.feed.missing) return none("No data", "Needs the battery level: not offered by this car")
    val needs = if (levelStuck(s)) {
      "Battery level unchanged for ${distText(s.levelUnchangedM, d)}: nothing to measure yet"
    } else if (e.warmupLeftMs > 0) {
      warmupText(e.warmupLeftMs)
    } else {
      "Needs ${if (d == DistUnit.MI) "1.0 mi" else "1.6 km"} and ${kwhNeed(e.needWh)} kWh measured · so far ${distText(e.basisM, d)} · ${kwhText(max(0.0, e.basisWh))}"
    }
    return Stat(EFFICIENCY_TITLE, "Warming up", "", listOf("${Units.num(e.progress * 100, 0)} % of the warm-up", needs), hasValue = false, shortTitle = EFFICIENCY_SHORT)
  }

  /** The figure over only the last 15 minutes. Until there are 15 minutes it is the whole of the overall figure. */
  fun recentEfficiency(s: SessionState, d: DistUnit): Stat? {
    if (s.efficiency.status == EffStatus.WARMING_UP) return null
    val r = s.recentEfficiency
    val m = r.mPerWh
    return when {
      m != null -> Stat(RECENT, effText(m, d), effUnit(d), listOf(consumptionText(m, d)))
      r.basisM >= SessionTracker.WARMUP_M -> Stat(RECENT, NET_REGEN, "", listOf(netRegenText(r.basisM, r.basisWh, d)), hasValue = false, linesWhileDriving = false)
      else -> Stat(RECENT, DASH, effUnit(d), listOf("Under ${if (d == DistUnit.MI) "1 mi" else "1.6 km"} measured"), hasValue = false, linesWhileDriving = false)
    }
  }

  private fun netRegenText(basisM: Double, basisWh: Double, d: DistUnit): String =
    "${if (basisWh <= 0) "More energy back than used" else "Almost no net energy used"} over ${distText(basisM, d)}"

  /** The warm-up's energy, "0.3" (more when the battery level moves in coarse steps). */
  private fun kwhNeed(wh: Double): String = Units.num(wh / 1000.0, 1)

  /** Why the start of a drive is not in the figure, and how much of it is left. */
  private fun warmupText(leftMs: Long): String {
    val left = if (leftMs >= SessionTracker.WARMUP_MS) "starts when moving" else "${ceil(leftMs / 60_000.0).toInt()} min left"
    return "First $WARMUP_MIN min of a drive not counted · $left"
  }

  private const val WARMUP_MIN = SessionTracker.WARMUP_MS / 60_000

  /**
   * Cumulative efficiency vs distance, in the user's units. Series points fall on every 0.1 mi; the line
   * ends where the figure was measured, at that figure.
   */
  fun spark(s: SessionState, d: DistUnit): List<ChartPoint> {
    val unitM = if (d == DistUnit.MI) EffUnits.METERS_PER_MILE else 1000.0
    val series = s.series.map { ChartPoint(it.distM / unitM, effValue(it.mPerWh, d)) }
    val now = s.efficiency.mPerWh?.takeIf { s.efficiency.status == EffStatus.OK }?.let { ChartPoint(s.efficiency.atM / unitM, effValue(it, d)) } ?: return series
    return when {
      series.isEmpty() || now.x > series.last().x -> series + now
      now.x == series.last().x -> series.dropLast(1) + now
      else -> series
    }
  }

  // --- Capacity and range -----------------------------------------------------------------------

  /** The car API's capacity. Usable now when the truck offers it; else what the pack was rated at. */
  fun capacity(s: SessionState): Stat {
    val cap = s.capacityWh
    if (cap == null) {
      val why = when {
        noAccess(s) -> NEEDS_ACCESS
        Signal.CAPACITY in s.feed.missing -> "Capacity not offered by this car"
        else -> "Waiting for the capacity"
      }
      return Stat("Battery capacity", DASH, "kWh", listOf(why), hasValue = false, shortTitle = CAPACITY_SHORT, linesWhileDriving = false)
    }
    val lines = mutableListOf(if (s.feed.capacityNominal) "Nominal, from the car's API" else "Usable now, from the car's API")
    s.feed.ratedWh?.takeIf { !s.feed.capacityNominal }?.let { lines += "Rated ${Units.num(it / 1000.0, 1)} kWh when new" }
    return Stat("Battery capacity", Units.num(cap / 1000.0, 1), "kWh", lines, marker = "API", shortTitle = CAPACITY_SHORT)
  }

  private const val RANGE_SHORT = "Range"
  private const val CAPACITY_SHORT = "Capacity"

  fun range(s: SessionState, d: DistUnit): Stat {
    val truck = when {
      s.rangeM != null -> "Truck says ${Units.num(distValue(s.rangeM, d), 0)} ${d.symbol}"
      noAccess(s) -> "Truck range $DASH"
      Signal.RANGE in s.feed.missing -> "Truck range not offered"
      else -> "Truck range $DASH"
    }
    val r = s.rangeEstimate
    val level = s.levelWh
    if (r == null || level == null) {
      val why = when {
        noAccess(s) -> NEEDS_ACCESS
        s.efficiency.status == EffStatus.WARMING_UP -> "After the warm-up"
        s.efficiency.early -> "After ${if (d == DistUnit.MI) "5 mi" else "8 km"} and 2 kWh measured"
        else -> "Not from this efficiency: mostly downhill so far"
      }
      return Stat("Range at your efficiency", DASH, d.symbol, listOf(truck, why), hasValue = false, shortTitle = RANGE_SHORT)
    }
    return Stat(
      "Range at your efficiency",
      Units.num(distValue(r.m, d), 0),
      d.symbol,
      listOf(truck, "${Units.num(level / 1000.0, 1)} kWh × ${effText(r.mPerWh, d)} ${effUnit(d)}"),
      marker = when (r.basis) {
        RangeBasis.THIS_DRIVE -> null
        RangeBasis.LAST_DRIVE -> LAST_DRIVE
        RangeBasis.EARLY -> EARLY
      },
      shortTitle = RANGE_SHORT,
    )
  }

  // --- Ring -------------------------------------------------------------------------------------

  fun ring(s: SessionState, d: DistUnit): RingView {
    val cap = s.capacityWh
    return RingView(
      fraction = s.socPct?.let { (it / 100.0).coerceIn(0.0, 1.0) },
      soc = s.socPct?.let { Units.num(it, 1) } ?: DASH,
      remaining = s.levelWh?.let { "${Units.num(it / 1000.0, 1)} kWh" } ?: "$DASH kWh",
      max = if (cap != null) "of ${Units.num(cap / 1000.0, 1)} kWh ${if (s.feed.capacityNominal) "nominal" else "max"}" else "Capacity not offered",
      truckRange = s.rangeM?.let { "Truck range ${Units.num(distValue(it, d), 0)} ${d.symbol}" } ?: "Truck range $DASH",
    )
  }

  // --- Trip -------------------------------------------------------------------------------------

  /** The session's distance, with how long it took and the outside temperature under it. */
  fun trip(s: SessionState, temp: TempUnit, d: DistUnit): Stat {
    val noSpeed = Signal.SPEED in s.feed.missing
    val avg = s.avgSpeedMps?.takeIf { !noSpeed }?.let { " · avg ${speedText(it, d)}" } ?: ""
    return Stat(
      "Trip",
      if (noSpeed) DASH else Units.num(distValue(s.distanceM, d), 1),
      d.symbol,
      listOf("${duration(s.activeMs)}$avg", "outside ${Units.tempText(s.outsideC, temp)}"),
      hasValue = !noSpeed && s.hasData,
    )
  }

  /** The session's net energy, regen included: the whole of it, the warm-up too, unlike the efficiency figures. */
  fun energyUsed(s: SessionState): Stat = Stat(
    "Used",
    kwhValue(s.usedWh),
    "kWh",
    emptyList(),
    hasValue = s.hasData,
    marker = sourceMarker(s.energySource).takeIf { s.usedWh != 0.0 },
  )
}
