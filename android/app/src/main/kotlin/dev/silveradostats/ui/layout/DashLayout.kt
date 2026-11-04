package dev.silveradostats.ui.layout

import kotlin.math.ceil
import kotlin.math.max

/*
 * Pure Kotlin (no android.* imports): how the dashboards fit one page in the window they actually
 * got. Input: the window's size after insets, whether the app is restricted (driving), the font
 * scale. Output: type sizes, which lines show, grid shapes, minimum chart heights. Nothing here
 * assumes a particular panel: the test walks every landscape size from 1000 x 420 dp up.
 *
 * The height and width budgets are estimates: FitColumn drops a line that does not fit and every
 * text ellipsizes as the last resort. They choose how the page gives way (secondary lines first,
 * then a smaller figure, and only then a whole row of cards), and they let the test say, for every
 * size, that what is left really does fit.
 */

enum class SizeTier { COMPACT, MEDIUM, LARGE }

/** Text sizes, sp. */
data class TypeScale(
  /** The one big figure of layout A. */
  val hero: Int,
  /** Tile figures. */
  val value: Int,
  /** The figures above a power chart. */
  val stat: Int,
  val unit: Int,
  val cardTitle: Int,
  val title: Int,
  /** Secondary lines. */
  val body: Int,
  /** Badges and small marks. */
  val label: Int,
  /** Tabs and the layout selector. */
  val tab: Int,
  /** Chart axes. */
  val axis: Int,
)

data class DashLayout(
  val widthDp: Int,
  val heightDp: Int,
  val restricted: Boolean,
  val fontScale: Float,
  val tier: SizeTier,
  val type: TypeScale,
  val headerDp: Int,
  /** Height of the Efficiency / Battery selector. */
  val selectorDp: Int,
  /** Height of the unit toggles and buttons (not shown while driving). */
  val controlDp: Int,
  val segmentPadDp: Int,
  val tabMinWidthDp: Int,
  val headerGapDp: Int,
  val pagePadHDp: Int,
  val pagePadVDp: Int,
  val gapDp: Int,
  val cardPadHDp: Int,
  val cardPadVDp: Int,
  val cardGapDp: Int,
  val barDp: Int,
  val noticeDp: Int,
  val chartMinDp: Int,
  val axisLeftDp: Int,
  val axisBottomDp: Int,
  val heatLabelDp: Int,
  /** App title, "Read-only" and the long session summary fit the header row beside everything else. */
  val appTitle: Boolean,
  val readOnlyNote: Boolean,
  val longSummary: Boolean,
  /** Tight even without those: "Reset", "DEMO" instead of "Reset session", "DEMO DATA". */
  val shortChrome: Boolean,
  /** The one-line note / how-measuring-works strip under the header. */
  val strip: Boolean,
  /** Secondary lines a Simple tile may show. */
  val tileLines: Int,
  /** Of [tileLines], how many are sure to fit a Simple page: all of them at the default font size. */
  val plainTileLines: Int,
  /** The same with a notice above the tiles. */
  val noticeTileLines: Int,
  /** Secondary lines a Live tile shows. */
  val liveTileLines: Int,
  /** Live tiles per row. */
  val liveTileCols: Int,
  /** A Live tile's figure: [TypeScale.value] unless the tile is too narrow for it and its unit. */
  val liveValueSp: Int,
  /** Live's row of cards. False only when it would get less than its minimum: no empty cards. */
  val liveCards: Boolean,
  /** Live's short titles and labels: its tiles and cards are narrow beside a badge or a marker. */
  val liveShort: Boolean,
) {
  /** Unit toggles, Reset session, Retry, Allow access, Open Diagnostics. */
  val controls: Boolean get() = !restricted
  val tapToInspect: Boolean get() = !restricted
  val diagnosticsTab: Boolean get() = !restricted

  /** The 192-cell heatmap and the module temperature strip. */
  val heatmap: Boolean get() = !restricted
  val legends: Boolean get() = !restricted

  /** The hero's and the ring's explanatory lines. */
  val heroDetail: Boolean get() = !restricted

  /** Peak figures above the power chart. */
  val peakStats: Boolean get() = !restricted

  /** The age of a value that is not stale. */
  val ages: Boolean get() = !restricted && tier != SizeTier.COMPACT
  val shortTitles: Boolean get() = restricted || tier == SizeTier.COMPACT

  // --- Budgets ------------------------------------------------------------------------------------

  /** Height of a text line of [sp] at this font scale. */
  fun lineDp(sp: Int, factor: Float = LINE_FACTOR): Int = ceil(sp * factor * fontScale).toInt()

  val stripDp: Int get() = lineDp(type.body)
  val badgeDp: Int get() = lineDp(type.label) + 4
  val tileHeadDp: Int get() = max(lineDp(type.title), badgeDp)
  val valueLineDp: Int get() = lineDp(type.value, VALUE_LINE_FACTOR)

  /** Height of the module temperature strip. */
  val heatStripDp: Int get() = lineDp(type.axis) + 6

  /** Height of the module-number row. */
  val heatHeadDp: Int get() = lineDp(type.axis)

  /** What is left under the header, strip and page padding. */
  val bodyDp: Int get() = heightDp - headerDp - (if (strip) stripDp else 0) - 2 * pagePadVDp

  /** A tile's height: head, figure, optional fill bar, [lines] secondary lines. */
  fun tileDp(lines: Int, bar: Boolean = false, valueSp: Int = type.value): Int =
    2 * cardPadVDp + tileHeadDp + cardGapDp + lineDp(valueSp, VALUE_LINE_FACTOR) + (if (bar) cardGapDp + barDp else 0) + lines * (cardGapDp + lineDp(type.body))

  /**
   * Height of the row of Live tiles (two rows when the window is too narrow for four across), with
   * room for pixel rounding: each part of a tile rounds up, and one row short would drop a line.
   */
  val liveTilesDp: Int
    get() {
      val tile = tileDp(liveTileLines, valueSp = liveValueSp) + TILE_ROUNDING_DP
      return if (liveTileCols >= LIVE_TILES) tile else 2 * tile + gapDp
    }

  /** The room a label must have to be worth showing: less would leave only an ellipsis. */
  fun labelMinDp(sp: Int): Int = ceil(sp * LABEL_MIN_EM * fontScale).toInt()

  /**
   * Not even one figure per tile fits under the header: one plain line instead of empty cards. Far
   * below the smallest window the dashboards are made for; nothing in that range gets here.
   */
  fun tooSmall(notice: Boolean = false): Boolean =
    bodyDp - (if (notice) noticeBudgetDp else 0) < 2 * (2 * cardPadVDp + valueLineDp) + gapDp

  private val heroLineDp: Int get() = lineDp(type.hero, VALUE_LINE_FACTOR)
  private val cardHeadDp: Int get() = lineDp(type.cardTitle)
  private val noticeBudgetDp: Int get() = noticeDp + gapDp

  /** The shortest page layout A can be: a tile keeps the lines this window is sure to fit. */
  fun requiredSimpleA(notice: Boolean): Int = simpleA(notice, if (notice) noticeTileLines else plainTileLines)

  private fun simpleA(notice: Boolean, lines: Int): Int {
    val grid = tileDp(lines, bar = true) + gapDp + tileDp(lines)
    val chartCard = 2 * cardPadVDp + cardHeadDp + cardGapDp + heroLineDp + cardGapDp + chartMinDp
    return page(max(grid, chartCard), notice)
  }

  fun requiredSimpleB(notice: Boolean): Int = simpleB(notice, if (notice) noticeTileLines else plainTileLines)

  private fun simpleB(notice: Boolean, lines: Int): Int {
    val ringLines = (if (heroDetail) 2 else 1) * (cardGapDp + lineDp(type.body))
    val ring = 2 * cardPadVDp + cardHeadDp + cardGapDp + chartMinDp + ringLines
    val statsDp = if (peakStats) lineDp(type.body) + lineDp(type.stat, VALUE_LINE_FACTOR) else lineDp(type.stat, VALUE_LINE_FACTOR)
    val power = 2 * cardPadVDp + cardHeadDp + cardGapDp + statsDp + cardGapDp + chartMinDp
    // With a notice above it, the sparkline is what gives way (FitColumn drops it).
    val minis = tileDp(lines) + gapDp + tileDp(lines) + if (notice) 0 else cardGapDp + SPARK_MIN_DP
    return page(maxOf(ring, power, minis), notice)
  }

  fun requiredLive(): Int = page(liveTilesDp + if (liveCards) gapDp + liveCardsDp() else 0, notice = false)

  private fun liveCardsDp(): Int {
    val power = 2 * cardPadVDp + cardHeadDp + cardGapDp + (if (legends) lineDp(type.body) + cardGapDp else 0) + chartMinDp
    val tempRows = if (restricted) TEMP_SUMMARY_LINES else TEMP_LINES
    val temps = 2 * cardPadVDp + cardHeadDp + tempRows * (cardGapDp + lineDp(type.body))
    val heat = if (heatmap) {
      2 * cardPadVDp + cardHeadDp + cardGapDp + heatHeadDp + HEAT_ROWS * HEAT_ROW_MIN_DP + cardGapDp + heatStripDp + cardGapDp + lineDp(type.label)
    } else {
      0
    }
    return maxOf(power, temps, heat)
  }

  private fun page(content: Int, notice: Boolean): Int =
    heightDp - bodyDp + content + if (notice) noticeBudgetDp else 0

  /** The widest the header row can get: the Simple tab, with the demo chip and a long summary. */
  fun headerNeededDp(): Int = headerWidth(type, restricted, appTitle, readOnlyNote, longSummary, shortChrome, fontScale, segmentPadDp, tabMinWidthDp, headerGapDp, pagePadHDp)

  /** The tiles' figures and secondary lines give way until the Live page fits; the cards go last. */
  private fun fitLive(): DashLayout {
    val across = figureSp(LIVE_TILES)
    if (across < LIVE_VALUE_MIN_SP) {
      // Too narrow for four across: two rows, but only where the page still has room for them.
      val stacked = copy(liveTileCols = 2, liveValueSp = max(figureSp(2), LIVE_VALUE_MIN_SP))
      if (stacked.requiredLive() <= heightDp) return stacked
    }
    val start = copy(liveTileCols = LIVE_TILES, liveValueSp = max(across, LIVE_VALUE_MIN_SP))
    var l = start
    while (l.requiredLive() > heightDp && l.liveTileLines > 0) l = l.copy(liveTileLines = l.liveTileLines - 1)
    while (l.requiredLive() > heightDp && l.liveValueSp > LIVE_VALUE_MIN_SP) l = l.copy(liveValueSp = l.liveValueSp - 1)
    // Still no room for the cards: the tiles have the page to themselves, as they were.
    return if (l.requiredLive() > heightDp) start.copy(liveCards = false) else l
  }

  /** The largest figure, up to [TypeScale.value], that fits beside its unit with [cols] tiles a row. */
  private fun figureSp(cols: Int): Int {
    val tile = (widthDp - 2 * pagePadHDp - (cols - 1) * gapDp) / cols - 2 * cardPadHDp
    val room = tile - LIVE_FIGURE_GAP_DP - textDp(LIVE_UNIT_CHARS, type.unit, fontScale)
    return minOf(type.value, (room / (LIVE_VALUE_CHARS * FIGURE_EM * fontScale)).toInt())
  }

  private fun fitSimple(): DashLayout {
    fun keeps(lines: Int, notice: Boolean) = simpleA(notice, lines) <= heightDp && simpleB(notice, lines) <= heightDp
    val plain = (tileLines downTo 0).firstOrNull { keeps(it, notice = false) } ?: 0
    val withNotice = (minOf(tileLines, 1) downTo 0).firstOrNull { keeps(it, notice = true) } ?: 0
    return copy(plainTileLines = plain, noticeTileLines = withNotice)
  }

  companion object {
    const val LINE_FACTOR = 1.3f

    /** A big figure's real height: its font's natural line, more than the line height asked for. */
    const val VALUE_LINE_FACTOR = 1.2f

    /** The selector and the tabs are at least this tall while restricted (one tap, eyes on the road). */
    const val MIN_DRIVING_TARGET_DP = 76
    const val LIVE_TILES = 4

    /** A Live tile's figure is never drawn smaller than this. */
    const val LIVE_VALUE_MIN_SP = 26
    const val HEAT_ROWS = 8
    const val HEAT_ROW_MIN_DP = 9
    const val SPARK_MIN_DP = 24
    private const val TILE_ROUNDING_DP = 4
    const val TEMP_LINES = 5
    const val TEMP_SUMMARY_LINES = 4

    private const val CHAR_EM = 0.58f

    /** Width of a figure's average character: digits and sign, with a narrow point among them. */
    private const val FIGURE_EM = 0.54f
    private const val SUMMARY_EM = 0.5f // digits and narrow punctuation: "1:23 · 123.4 mi"
    private const val LABEL_MIN_EM = 2.5f
    private const val LIVE_VALUE_CHARS = 6 // +345.6
    private const val LIVE_UNIT_CHARS = 2 // kW
    private const val LIVE_FIGURE_GAP_DP = 8
    private const val MIN_SEGMENT_PAD_DP = 6

    /** A unit toggle's segment stays this wide, whatever its text needs: still one easy tap. */
    const val MIN_SEGMENT_DP = 44
    private val TAB_CHARS = listOf(6, 4, 11) // Simple, Live, Diagnostics
    private const val DRIVING_TABS = 2 // Simple, Live
    private val SELECTOR_CHARS = listOf(10, 7) // Efficiency, Battery
    private const val APP_TITLE_CHARS = 15 // Silverado Stats
    private const val READ_ONLY_CHARS = 9 // Read-only
    private const val SUMMARY_CHARS = 15 // 1:23 · 123.4 mi
    private const val LONG_SUMMARY_CHARS = 31 // Since app opened 1:23 · 123.4 mi
    private const val DEMO_CHARS = 9 // DEMO DATA
    private const val DEMO_SHORT_CHARS = 4 // DEMO
    private const val RESET_CHARS = 13 // Reset session
    private const val RESET_SHORT_CHARS = 5 // Reset

    fun of(widthDp: Float, heightDp: Float, restricted: Boolean, fontScale: Float = 1f): DashLayout {
      val scale = if (fontScale > 0f) fontScale else 1f
      // A larger font takes the room a smaller window would: judge the size in units of text.
      val w = widthDp / scale
      val h = heightDp / scale
      val tier = when {
        h < 560f || w < 1240f -> SizeTier.COMPACT
        h < 720f || w < 1700f -> SizeTier.MEDIUM
        else -> SizeTier.LARGE
      }
      var type = typeFor(tier, restricted)
      var segmentPad = when (tier) {
        SizeTier.COMPACT -> if (restricted) 20 else 10
        SizeTier.MEDIUM -> if (restricted) 24 else 14
        SizeTier.LARGE -> if (restricted) 28 else 20
      }
      val tabMin = if (restricted) 120 else 64
      val headerGap = when (tier) {
        SizeTier.COMPACT -> 8
        SizeTier.MEDIUM -> 12
        SizeTier.LARGE -> 16
      }
      val pagePadH = when (tier) {
        SizeTier.COMPACT -> 12
        SizeTier.MEDIUM -> 16
        SizeTier.LARGE -> 24
      }
      var appTitle = true
      var note = !restricted
      // The session's figures alone while driving: "1:23 · 123.4 mi", never a sentence around them.
      var long = !restricted
      var short = false
      fun fits() = headerWidth(type, restricted, appTitle, note, long, short, scale, segmentPad, tabMin, headerGap, pagePadH) <= widthDp
      // Least useful first: the note, the name of the app, the words around the summary, then labels.
      if (!fits()) note = false
      if (!fits()) appTitle = false
      if (!fits()) long = false
      if (!fits()) short = true
      // A larger font on a narrow window: tighter segments, then chrome text no smaller than the
      // default font would have drawn it.
      while (!fits() && segmentPad > MIN_SEGMENT_PAD_DP) segmentPad -= 1
      val smallestTab = ceil(type.tab / scale).toInt()
      while (!fits() && type.tab > smallestTab) type = type.copy(tab = type.tab - 1)
      val liveLines = when {
        restricted -> if (tier == SizeTier.COMPACT) 1 else 2
        tier == SizeTier.COMPACT -> 1
        tier == SizeTier.MEDIUM -> 2
        else -> 3
      }
      return DashLayout(
        widthDp = widthDp.toInt(),
        heightDp = heightDp.toInt(),
        restricted = restricted,
        fontScale = scale,
        tier = tier,
        type = type,
        headerDp = when (tier) {
          SizeTier.COMPACT -> if (restricted) MIN_DRIVING_TARGET_DP else 60
          SizeTier.MEDIUM -> if (restricted) 84 else 64
          SizeTier.LARGE -> if (restricted) 92 else 72
        },
        selectorDp = if (restricted) MIN_DRIVING_TARGET_DP else if (tier == SizeTier.COMPACT) 52 else 56,
        controlDp = if (tier == SizeTier.COMPACT) 52 else 56,
        segmentPadDp = segmentPad,
        tabMinWidthDp = tabMin,
        headerGapDp = headerGap,
        pagePadHDp = pagePadH,
        pagePadVDp = when (tier) {
          SizeTier.COMPACT -> 6
          SizeTier.MEDIUM -> 8
          SizeTier.LARGE -> 10
        },
        gapDp = when (tier) {
          SizeTier.COMPACT -> 8
          SizeTier.MEDIUM -> 12
          SizeTier.LARGE -> 16
        },
        cardPadHDp = when (tier) {
          SizeTier.COMPACT -> 12
          SizeTier.MEDIUM -> 16
          SizeTier.LARGE -> 20
        },
        cardPadVDp = when (tier) {
          SizeTier.COMPACT -> 8
          SizeTier.MEDIUM -> 10
          SizeTier.LARGE -> 14
        },
        cardGapDp = when (tier) {
          SizeTier.COMPACT -> 2
          SizeTier.MEDIUM -> 4
          SizeTier.LARGE -> 6
        },
        barDp = when (tier) {
          SizeTier.COMPACT -> 8
          SizeTier.MEDIUM -> 12
          SizeTier.LARGE -> 16
        },
        noticeDp = when (tier) {
          SizeTier.COMPACT -> 44
          SizeTier.MEDIUM -> 56
          SizeTier.LARGE -> 64
        },
        chartMinDp = when (tier) {
          SizeTier.COMPACT -> 80
          SizeTier.MEDIUM -> 120
          SizeTier.LARGE -> 160
        },
        // These hold text: they grow with the font, or "−100 kW" would lose its sign at the card's edge.
        axisLeftDp = ceil(scale * when (tier) {
          SizeTier.COMPACT -> 52
          SizeTier.MEDIUM -> 60
          SizeTier.LARGE -> 72
        }).toInt(),
        axisBottomDp = ceil(scale * when (tier) {
          SizeTier.COMPACT -> 20
          SizeTier.MEDIUM -> 24
          SizeTier.LARGE -> 30
        }).toInt(),
        heatLabelDp = ceil(scale * when (tier) {
          SizeTier.COMPACT -> 30
          SizeTier.MEDIUM -> 36
          SizeTier.LARGE -> 44
        }).toInt(),
        appTitle = appTitle,
        readOnlyNote = note,
        longSummary = long,
        shortChrome = short,
        strip = !restricted && h >= 480f,
        tileLines = if (restricted) 1 else 2,
        plainTileLines = 0,
        noticeTileLines = 0,
        liveTileLines = liveLines,
        liveTileCols = LIVE_TILES,
        liveValueSp = type.value,
        liveCards = true,
        liveShort = restricted || w < 1700f,
      ).fitLive().fitSimple()
    }

    private fun typeFor(tier: SizeTier, restricted: Boolean): TypeScale = when (tier) {
      SizeTier.COMPACT ->
        if (restricted) {
          TypeScale(hero = 72, value = 48, stat = 36, unit = 20, cardTitle = 22, title = 20, body = 18, label = 16, tab = 24, axis = 14)
        } else {
          TypeScale(hero = 60, value = 40, stat = 28, unit = 18, cardTitle = 20, title = 18, body = 16, label = 14, tab = 18, axis = 12)
        }
      SizeTier.MEDIUM ->
        if (restricted) {
          TypeScale(hero = 104, value = 68, stat = 52, unit = 26, cardTitle = 26, title = 24, body = 22, label = 18, tab = 26, axis = 16)
        } else {
          TypeScale(hero = 88, value = 56, stat = 38, unit = 22, cardTitle = 24, title = 20, body = 18, label = 16, tab = 22, axis = 14)
        }
      SizeTier.LARGE ->
        if (restricted) {
          TypeScale(hero = 128, value = 80, stat = 60, unit = 32, cardTitle = 30, title = 28, body = 24, label = 20, tab = 28, axis = 18)
        } else {
          TypeScale(hero = 112, value = 64, stat = 44, unit = 28, cardTitle = 28, title = 24, body = 20, label = 18, tab = 24, axis = 16)
        }
    }

    private fun textDp(chars: Int, sp: Int, scale: Float): Int = ceil(chars * sp * CHAR_EM * scale).toInt()

    private fun headerWidth(
      type: TypeScale,
      restricted: Boolean,
      appTitle: Boolean,
      note: Boolean,
      long: Boolean,
      short: Boolean,
      scale: Float,
      segmentPad: Int,
      tabMin: Int,
      gap: Int,
      pagePad: Int,
    ): Int {
      val segment = type.tab - 2
      val tabs = TAB_CHARS.take(if (restricted) DRIVING_TABS else TAB_CHARS.size).sumOf { max(tabMin, textDp(it, type.tab, scale) + 2 * segmentPad) }
      val selector = SELECTOR_CHARS.sumOf { textDp(it, segment, scale) + 2 * segmentPad }
      val units = if (restricted) 0 else 2 * 2 * max(MIN_SEGMENT_DP, textDp(2, segment, scale) + 2 * segmentPad)
      val reset = if (restricted) 0 else textDp(if (short) RESET_SHORT_CHARS else RESET_CHARS, segment, scale) + 2 * segmentPad
      val demo = textDp(if (short) DEMO_SHORT_CHARS else DEMO_CHARS, type.body, scale) + 2 * 10
      val summary = ceil((if (long) LONG_SUMMARY_CHARS else SUMMARY_CHARS) * type.title * SUMMARY_EM * scale).toInt()
      val title = if (appTitle) textDp(APP_TITLE_CHARS, type.tab, scale) + gap else 0
      val readOnly = if (note) textDp(READ_ONLY_CHARS, type.body, scale) + gap else 0
      val parts = if (restricted) 3 else 5 // tabs, demo, summary, selector and, parked, units and reset
      return 2 * pagePad + tabs + selector + units + reset + demo + summary + title + readOnly + parts * gap
    }
  }
}

/** Who keeps what when a card or a row runs out of room: priorities only, no Compose. */
object FitRules {
  /** One child of a column: lower [priority] is kept longer; [height] with the gap above it not counted. */
  data class ColumnChild(val priority: Int, val height: Int)

  /**
   * Which children of a column of [maxHeight] stay, most important first. Once one does not fit,
   * nothing less important stays either: a fill bar without its figure would say nothing.
   */
  fun keptInColumn(children: List<ColumnChild>, gap: Int, maxHeight: Int): BooleanArray {
    val kept = BooleanArray(children.size)
    var used = 0
    var count = 0
    var failedAt = Int.MAX_VALUE
    for (i in children.indices.sortedBy { children[it].priority }) {
      val c = children[i]
      if (c.priority > failedAt) continue
      val need = c.height + if (count > 0) gap else 0
      if (used + need > maxHeight) {
        failedAt = minOf(failedAt, c.priority)
        continue
      }
      used += need
      count += 1
      kept[i] = true
    }
    return kept
  }

  /**
   * One child of a row: lower [priority] claims width first. It takes [natural] if it can and makes
   * do with no less than [min]; [group] is one thing in several forms, of which the first that fits
   * is shown (a direction pill, or else only its dot).
   */
  data class RowChild(val priority: Int, val natural: Int, val min: Int = natural, val group: Int? = null)

  /** Each child's width in a row of [total], or [DROPPED]. A child that cannot fit is not drawn at all. */
  fun claimWidths(children: List<RowChild>, total: Int, gap: Int): IntArray {
    val widths = IntArray(children.size) { DROPPED }
    var left = total
    var count = 0
    val shown = HashSet<Int>()
    for (i in children.indices.sortedBy { children[it].priority }) {
      val c = children[i]
      if (c.group != null && c.group in shown) continue
      val room = left - if (count > 0) gap else 0
      if (room < minOf(c.min, c.natural)) continue
      val w = minOf(c.natural, room)
      widths[i] = w
      left = room - w
      count += 1
      if (c.group != null) shown += c.group
    }
    return widths
  }

  const val DROPPED = -1
}
