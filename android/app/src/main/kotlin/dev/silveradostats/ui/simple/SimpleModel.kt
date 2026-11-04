package dev.silveradostats.ui.simple

import dev.silveradostats.link.PowerPoint
import dev.silveradostats.ui.live.DashboardModel
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Tone
import dev.silveradostats.ui.live.Units
import dev.silveradostats.vehicle.EffSegment
import dev.silveradostats.vehicle.EffStatus
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.FeedKind
import dev.silveradostats.vehicle.PowerDirection
import dev.silveradostats.vehicle.SessionState
import dev.silveradostats.vehicle.SessionTracker
import dev.silveradostats.vehicle.Signal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Kotlin (no android.* imports): SessionState -> exactly what the Simple tab draws (layout A
 * "Efficiency" and layout B "Battery"). Unit tested; the Compose code only lays it out.
 *
 * Units: mi/kWh first and Wh/mi second (km/kWh and Wh/km in metric), °C / °F as chosen.
 */

/** A KPI tile. [marker]: "est." etc. beside the title; [pill]: direction label with [pillTone]. */
data class Stat(
  val title: String,
  val value: String,
  val unit: String,
  val lines: List<String>,
  val hasValue: Boolean = true,
  val pill: String? = null,
  val pillTone: Tone = Tone.NORMAL,
  val marker: String? = null,
  /** The title on a narrow tile and while driving. */
  val shortTitle: String = title,
  /** The unit on a narrow tile and while driving. */
  val shortUnit: String = unit,
  /** False when [lines] only explain a missing figure: while driving the tile shows just the dash. */
  val linesWhileDriving: Boolean = true,
)

enum class HeroKind {
  /** A measured figure. */
  VALUE,
  WARMING,

  /** Measured before, but net energy is about zero or negative now (a long descent). */
  NET_REGEN,

  /** Cannot measure: no access to the data, or the car does not offer it. */
  BLOCKED,
}

data class HeroView(
  /** "2.32", "Warming up", "Net regen", "No access", "No data". */
  val value: String,
  val unit: String,
  val kind: HeroKind,
  /** 0..1 warm-up progress. */
  val progress: Double,
  /** "431 Wh/mi · 4.21 kWh used over 9.8 mi" (what the figure is measured over), or why there is none. */
  val secondary: String,
  /** "Truck's range implies 2.05 mi/kWh", or null when the truck gives nothing to compare. */
  val truck: String?,
  /** A figure over too little to say much yet. */
  val early: Boolean = false,
  /** "431 Wh/mi": the same figure the other way round, all of [secondary] that stays while driving. */
  val consumption: String? = null,
) {
  val warming: Boolean get() = kind == HeroKind.WARMING
}

data class ChartPoint(val x: Double, val y: Double)

/** One per-mile (per-km) bar; [y] null = no figure ([regen] = net regen over it). */
data class EffBar(val index: Int, val x0: Double, val x1: Double, val y: Double?, val regen: Boolean)

/** Cumulative efficiency vs distance over per-mile bars, in the user's units. */
data class EffChart(
  val xMax: Double,
  val xStep: Double,
  val yMax: Double,
  val yStep: Double,
  val line: List<ChartPoint>,
  val bars: List<EffBar>,
  val distUnit: String,
  val effUnit: String,
  /** Nothing to draw yet (the line starts after the warm-up). */
  val warming: Boolean,
  /** What the empty chart says: "The line starts after the warm-up (1 mi and 0.3 kWh)". */
  val warmupText: String = "",
)

/** Power, last 10 minutes: + discharge up (orange), − regen / charge down (green). */
data class PowerChartView(
  val points: List<PowerPoint>,
  val nowMs: Long,
  val windowMs: Long,
  val scaleKw: Double,
  val now: String,
  val nowLabel: String,
  val nowTone: Tone,
  val peak: String,
  val regen: String,
  val estimated: Boolean,
  /** "Peak drive", with "(est.)" when the last 10 minutes include estimated power. */
  val peakTitle: String = "Peak drive",
  val regenTitle: String = "Peak regen / charge",
)

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
  val hero: HeroView,
  val chart: EffChart,
  val battery: Stat,
  val batteryFraction: Double?,
  val power: Stat,
  val range: Stat,
  val session: Stat,
  val ring: RingView,
  val powerChart: PowerChartView,
  val efficiencyMini: Stat,
  val spark: List<ChartPoint>,
  val sessionMini: Stat,
)

object SimpleModel {
  private val DASH = Units.DASH
  const val WINDOW_MS = SessionTracker.HISTORY_MS
  private const val MPH_PER_MPS = 2.2369362920544

  fun build(s: SessionState, temp: TempUnit, dist: DistUnit, nowMs: Long): SimpleView {
    val chart = chart(s, dist)
    return SimpleView(
      header = header(s, dist),
      summary = summary(s, dist),
      pauses = pauses(s),
      note = note(s, dist),
      hint = hint(s),
      hintWarn = s.gaps > 0,
      notice = notice(s),
      hero = hero(s, dist),
      chart = chart,
      battery = battery(s),
      batteryFraction = s.socPct?.let { (it / 100.0).coerceIn(0.0, 1.0) },
      power = power(s, dist),
      range = range(s, dist),
      session = session(s, temp, dist),
      ring = ring(s, dist),
      powerChart = powerChart(s, nowMs),
      efficiencyMini = efficiencyMini(s, dist),
      spark = chart.line,
      sessionMini = sessionMini(s, dist),
    )
  }

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

  /** "4.21 kWh" (two decimals below 100 kWh, one above). */
  fun kwhText(wh: Double): String = "${Units.num(wh / 1000.0, if (abs(wh) < 100_000) 2 else 1)} kWh"

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
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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

  // --- Layout A ---------------------------------------------------------------------------------

  fun hero(s: SessionState, d: DistUnit): HeroView {
    val e = s.efficiency
    val truck = s.truckImpliedMPerWh?.let { "Truck's range implies ${effText(it, d)} ${effUnit(d)}" }
    val m = e.mPerWh
    if (e.status == EffStatus.OK && m != null) {
      return HeroView(
        value = effText(m, d),
        unit = effUnit(d),
        kind = HeroKind.VALUE,
        progress = 1.0,
        // The figure's own energy and distance: session totals are in the "This session" tile.
        secondary = "${consumptionText(m, d)} · ${kwhText(e.basisWh)} used over ${distText(e.basisM, d)}",
        truck = truck,
        early = e.early,
        consumption = consumptionText(m, d),
      )
    }
    if (e.status == EffStatus.NET_REGEN) {
      val text = if (e.basisWh <= 0) "More energy back than used" else "Almost no net energy used"
      return HeroView("Net regen", "", HeroKind.NET_REGEN, 1.0, "$text over ${distText(e.basisM, d)}", truck)
    }
    if (noAccess(s)) return HeroView("No access", "", HeroKind.BLOCKED, 0.0, NEEDS_ACCESS, truck)
    if (Signal.SPEED in s.feed.missing) return HeroView("No data", "", HeroKind.BLOCKED, 0.0, "Needs vehicle speed: not offered by this car", truck)
    if (Signal.LEVEL in s.feed.missing) return HeroView("No data", "", HeroKind.BLOCKED, 0.0, "Needs the battery level: not offered by this car", truck)
    val needs = if (levelStuck(s)) {
      "The truck's battery level has not changed for ${distText(s.levelUnchangedM, d)}: nothing to measure yet"
    } else {
      "Needs ${if (d == DistUnit.MI) "1.0 mi" else "1.6 km"} and ${kwhNeed(e.needWh)} kWh measured · so far ${distText(e.basisM, d)} · ${kwhText(max(0.0, e.basisWh))}"
    }
    return HeroView("Warming up", effUnit(d), HeroKind.WARMING, e.progress, needs, truck)
  }

  /** The warm-up's energy, "0.3" (more when the battery level moves in coarse steps). */
  private fun kwhNeed(wh: Double): String = Units.num(wh / 1000.0, 1)

  fun chart(s: SessionState, d: DistUnit): EffChart {
    val unitM = if (d == DistUnit.MI) EffUnits.METERS_PER_MILE else 1000.0
    // Series points fall on every 0.1 mi; the line ends where the hero figure was measured, at that figure.
    val series = s.series.map { ChartPoint(it.distM / unitM, effValue(it.mPerWh, d)) }
    val hero = s.efficiency.mPerWh?.takeIf { s.efficiency.status == EffStatus.OK }?.let { ChartPoint(s.efficiency.atM / unitM, effValue(it, d)) }
    val line = if (hero != null && (series.isEmpty() || hero.x >= series.last().x)) {
      if (series.isNotEmpty() && hero.x == series.last().x) series.dropLast(1) + hero else series + hero
    } else {
      series
    }
    val segments: List<EffSegment> = if (d == DistUnit.MI) s.miSegments else s.kmSegments
    val bars = segments.map { seg ->
      EffBar(seg.index, (seg.index - 1).toDouble(), seg.index.toDouble(), seg.mPerWh?.let { effValue(it, d) }, seg.netRegen)
    }
    val xMax = niceCeil(max(distValue(s.distanceM, d), 1.0))
    val xStep = step(xMax, 8, listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0))
    // y: most bars (an outlier mile, e.g. downhill, is drawn capped), and the line while it stays near
    // them: the start of a long descent must not flatten everything else.
    val barYs = bars.mapNotNull { it.y }.sorted()
    val barRef = if (barYs.isEmpty()) 0.0 else barYs[((barYs.size - 1) * 0.8).toInt()]
    val lineMax = line.maxOfOrNull { it.y } ?: 0.0
    val lineRef = if (barRef > 0) min(lineMax, barRef * LINE_OVER_BARS) else lineMax
    val floorY = if (d == DistUnit.MI) 3.0 else 5.0
    val capY = if (d == DistUnit.MI) 8.0 else 13.0
    val top = (max(lineRef, barRef) * 1.12).coerceIn(floorY, capY)
    val yStep = step(top, 6, listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0))
    val yMax = ceil(top / yStep) * yStep
    val warmup = if (noAccess(s)) NEEDS_ACCESS else "The line starts after the warm-up (${if (d == DistUnit.MI) "1 mi" else "1.6 km"} and ${kwhNeed(s.efficiency.needWh)} kWh)"
    return EffChart(xMax, xStep, yMax, yStep, line, bars, d.symbol, effUnit(d), warming = line.isEmpty(), warmupText = warmup)
  }

  /** Bar number at chart distance [x] (user units), or null outside the bars. */
  fun barAt(c: EffChart, x: Double): EffBar? = c.bars.firstOrNull { x >= it.x0 && x < it.x1 }

  /**
   * The tapped bar: "Mile 7: 2.31 mi/kWh · cumulative 2.25 at 7.0 mi" (net regen and missing data
   * said in words; the cumulative figure is the line's point closest to the bar's end).
   */
  fun barDetail(c: EffChart, b: EffBar): String {
    val name = if (c.distUnit == "mi") "Mile" else "Km"
    val value = when {
      b.y != null -> "${Units.num(b.y, 2)} ${c.effUnit}"
      b.regen -> "net regen (more energy back than used)"
      else -> "too little energy to measure"
    }
    val cum = c.line.minByOrNull { abs(it.x - b.x1) }?.takeIf { abs(it.x - b.x1) <= 0.5 }
      ?.let { " · cumulative ${Units.num(it.y, 2)} at ${Units.num(it.x, 1)} ${c.distUnit}" } ?: ""
    return "$name ${b.index}: $value$cum"
  }

  /** The smallest step from [steps] that divides [span] into at most [maxTicks] intervals. */
  fun step(span: Double, maxTicks: Int, steps: List<Double>): Double = steps.firstOrNull { span / it <= maxTicks } ?: steps.last()

  /** 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8 × 10^n at or above [x] (x > 0): never much empty axis. */
  fun niceCeil(x: Double): Double {
    if (x <= 0) return 1.0
    var mag = 1.0
    while (mag * 10 <= x) mag *= 10
    while (mag > x) mag /= 10
    return NICE_STEPS.map { it * mag }.first { it >= x - 1e-9 }
  }

  private val NICE_STEPS = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

  /** The line may rise this far above most of the bars before it is drawn capped. */
  private const val LINE_OVER_BARS = 1.5

  fun battery(s: SessionState): Stat {
    val level = s.levelWh
    if (level == null) {
      val why = when {
        noAccess(s) -> NEEDS_ACCESS
        Signal.LEVEL in s.feed.missing -> "Battery level not offered by this car"
        else -> "Waiting for the battery level"
      }
      return Stat("Battery", DASH, "kWh", listOf(why), hasValue = false, linesWhileDriving = false)
    }
    val cap = s.capacityWh
    val unit = if (cap != null) "/ ${Units.num(cap / 1000.0, 1)} kWh" else "kWh"
    val lines = mutableListOf<String>()
    lines += s.socPct?.let { "${Units.num(it, 1)} % remaining" } ?: "Capacity not offered"
    if (cap != null) lines += if (s.feed.capacityNominal) "Remaining / nominal capacity" else "Remaining / usable capacity"
    return Stat("Battery", Units.num(level / 1000.0, 1), unit, lines, shortUnit = "kWh")
  }

  fun direction(p: PowerDirection): Pair<String, Tone> = when (p) {
    PowerDirection.DISCHARGING -> "Discharging" to Tone.DISCHARGE
    PowerDirection.REGEN -> "Regen" to Tone.CHARGE
    PowerDirection.CHARGING -> "Charging" to Tone.CHARGE
    PowerDirection.IDLE -> "Idle" to Tone.DIM
  }

  fun power(s: SessionState, d: DistUnit): Stat {
    val speed = when {
      s.speedMps != null -> "Speed ${speedText(s.speedMps, d)}"
      noAccess(s) -> "Speed $DASH"
      Signal.SPEED in s.feed.missing -> "Speed not offered"
      else -> "Speed $DASH"
    }
    val p = s.power ?: return Stat(
      "Power now", DASH, "kW",
      listOf(
        speed,
        when {
          noAccess(s) -> NEEDS_ACCESS
          Signal.LEVEL in s.feed.missing -> "Needs the charge rate or the battery level"
          else -> "Waiting for battery data"
        },
      ),
      hasValue = false,
      shortTitle = POWER_SHORT,
    )
    val (label, tone) = direction(p.direction)
    val source = if (p.estimated) "Estimated from the battery level (30 s)" else "From the truck's charge rate"
    return Stat("Power now", Units.signed(p.kw, 1), "kW", listOf(speed, source), pill = label, pillTone = tone, marker = if (p.estimated) "est." else null, shortTitle = POWER_SHORT)
  }

  private const val RANGE_SHORT = "Range"
  private const val POWER_SHORT = "Power"
  private const val EFFICIENCY_SHORT = "Efficiency"

  fun range(s: SessionState, d: DistUnit): Stat {
    val truck = when {
      s.rangeM != null -> "Truck says ${Units.num(distValue(s.rangeM, d), 0)} ${d.symbol}"
      noAccess(s) -> "Truck range $DASH"
      Signal.RANGE in s.feed.missing -> "Truck range not offered"
      else -> "Truck range $DASH"
    }
    val r = s.rangeAtMeasuredM
    val m = s.efficiency.mPerWh
    val level = s.levelWh
    if (r == null || m == null || level == null) {
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
      Units.num(distValue(r, d), 0),
      d.symbol,
      listOf(truck, "${Units.num(level / 1000.0, 1)} kWh × ${effText(m, d)} ${effUnit(d)}"),
      shortTitle = RANGE_SHORT,
    )
  }

  fun session(s: SessionState, temp: TempUnit, d: DistUnit): Stat {
    val noSpeed = Signal.SPEED in s.feed.missing
    val avg = s.avgSpeedMps?.takeIf { !noSpeed }?.let { "avg ${speedText(it, d)}" } ?: "avg $DASH"
    val outside = "outside ${Units.tempText(s.outsideC, temp)}"
    return Stat(
      "This session",
      if (noSpeed) DASH else Units.num(distValue(s.distanceM, d), 1),
      d.symbol,
      listOf("${kwhText(s.usedWh)} used · ${duration(s.activeMs)}", "$avg · $outside"),
      hasValue = !noSpeed && s.hasData,
    )
  }

  // --- Layout B ---------------------------------------------------------------------------------

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

  fun powerChart(s: SessionState, nowMs: Long): PowerChartView {
    val pts = s.powerHistory
    val ref = s.lastSampleAtMs ?: nowMs
    val peakAbs = pts.maxOfOrNull { abs(it.kw) } ?: 0.0
    val p = s.power
    val (label, tone) = p?.let { direction(it.direction) } ?: ("No data" to Tone.DIM)
    val est = if (s.powerHistoryEstimated) " (est.)" else ""
    return PowerChartView(
      points = pts,
      nowMs = ref,
      windowMs = WINDOW_MS,
      scaleKw = DashboardModel.powerScale(peakAbs),
      now = p?.let { "${Units.signed(it.kw, 1)} kW" } ?: "$DASH kW",
      nowLabel = label,
      nowTone = tone,
      peak = s.peakDischargeKw?.let { "${Units.signed(it, 0)} kW" } ?: DASH,
      regen = s.peakRegenKw?.let { "${Units.signed(it, 0)} kW" } ?: DASH,
      estimated = p?.estimated == true,
      peakTitle = "Peak drive$est",
      regenTitle = "Peak regen / charge$est",
    )
  }

  /** The power point nearest to [atMs] (within 5 s), for a tap on the chart. */
  fun powerAt(v: PowerChartView, atMs: Long): PowerPoint? =
    v.points.minByOrNull { abs(it.atMs - atMs) }?.takeIf { abs(it.atMs - atMs) <= 5_000 }

  /** "2 min ago: +45.2 kW" for a tapped power point. */
  fun powerDetail(v: PowerChartView, p: PowerPoint): String = "${Units.age(max(0L, v.nowMs - p.atMs))} ago: ${Units.signed(p.kw, 1)} kW"

  fun efficiencyMini(s: SessionState, d: DistUnit): Stat {
    val h = hero(s, d)
    val title = "Efficiency since opened"
    return when (h.kind) {
      HeroKind.VALUE -> Stat(title, h.value, h.unit, listOf(consumptionText(s.efficiency.mPerWh!!, d)), marker = if (h.early) EARLY else null, shortTitle = EFFICIENCY_SHORT)
      HeroKind.WARMING -> Stat(title, h.value, "", listOf("${Units.num(h.progress * 100, 0)} % of the warm-up"), hasValue = false, shortTitle = EFFICIENCY_SHORT)
      HeroKind.NET_REGEN, HeroKind.BLOCKED -> Stat(title, h.value, "", listOf(h.secondary), hasValue = false, shortTitle = EFFICIENCY_SHORT, linesWhileDriving = false)
    }
  }

  fun sessionMini(s: SessionState, d: DistUnit): Stat {
    val noSpeed = Signal.SPEED in s.feed.missing
    val avg = s.avgSpeedMps?.takeIf { !noSpeed }?.let { " · avg ${speedText(it, d)}" } ?: ""
    return Stat(
      "This session",
      if (noSpeed) DASH else Units.num(distValue(s.distanceM, d), 1),
      d.symbol,
      listOf("${kwhText(s.usedWh)} used", "${duration(s.activeMs)}$avg"),
      hasValue = !noSpeed && s.hasData,
    )
  }
}
