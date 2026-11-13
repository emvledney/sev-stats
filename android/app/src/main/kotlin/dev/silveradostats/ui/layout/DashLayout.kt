package dev.silveradostats.ui.layout

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
  /** Tile figures. */
  val value: Int,
  val unit: Int,
  val cardTitle: Int,
  val title: Int,
  /** Secondary lines. */
  val body: Int,
  /** Badges and small marks. */
  val label: Int,
  /** Tabs and the header's controls. */
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
  /** Height of the header's buttons and toggles, the MX+ button aside ([mxButtonDp]). */
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
  val noticeDp: Int,
  val chartMinDp: Int,
  val axisLeftDp: Int,
  val axisBottomDp: Int,
  val heatLabelDp: Int,
  /** The header row is tight: "Reset", "MX+ off" instead of "Reset session", "Disconnect MX+". */
  val shortChrome: Boolean,
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
  /** Simple's one layout: the tabs and the MX+ button are as tall in Park as Drive wants them. See [ofSimple]. */
  val drivingTargets: Boolean = false,
) {
  /** Live's Retry, and every other parked control. Not the MX+ button: that stays while driving. Simple's page does not ask. */
  val controls: Boolean get() = !restricted

  /** Connect / Disconnect MX+, the one control that stays while driving: as tall as the other driving targets then. */
  val mxButtonDp: Int get() = if (restricted || drivingTargets) MIN_DRIVING_TARGET_DP else controlDp
  val diagnosticsTab: Boolean get() = !restricted
  val trendsTab: Boolean get() = !restricted && widthDp >= TRENDS_MIN_WIDTH_DP

  /** The 192-cell heatmap and the module temperature strip. */
  val heatmap: Boolean get() = !restricted

  /** The age of a value that is not stale. */
  val ages: Boolean get() = !restricted && tier != SizeTier.COMPACT
  val shortTitles: Boolean get() = restricted || tier == SizeTier.COMPACT

  // --- Budgets ------------------------------------------------------------------------------------

  /** Height of a text line of [sp] at this font scale. */
  fun lineDp(sp: Int, factor: Float = LINE_FACTOR): Int = ceil(sp * factor * fontScale).toInt()

  val badgeDp: Int get() = lineDp(type.label) + 4
  val tileHeadDp: Int get() = max(lineDp(type.title), badgeDp)
  val valueLineDp: Int get() = lineDp(type.value, VALUE_LINE_FACTOR)

  /** Height of the module temperature strip. */
  val heatStripDp: Int get() = lineDp(type.axis) + 6

  /** Height of the module-number row. */
  val heatHeadDp: Int get() = lineDp(type.axis)

  /** What is left under the header and page padding. */
  val bodyDp: Int get() = heightDp - headerDp - 2 * pagePadVDp

  /** A tile's height: head, figure, [lines] secondary lines. */
  fun tileDp(lines: Int, valueSp: Int = type.value): Int =
    2 * cardPadVDp + tileHeadDp + cardGapDp + lineDp(valueSp, VALUE_LINE_FACTOR) + lines * (cardGapDp + lineDp(type.body))

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

  private val cardHeadDp: Int get() = lineDp(type.cardTitle)
  private val noticeBudgetDp: Int get() = noticeDp + gapDp

  // --- Simple: four columns of two cells alike, the last charge's row under all four ---

  /** The shortest page Simple can be: the figures at their smallest, and no room for Range's line, which is the first to give way. */
  fun requiredSimple(notice: Boolean): Int = page(2 * tileDp(0, LIVE_VALUE_MIN_SP) + gapDp + gapDp + chargeMinDp, notice)

  /** A column's width: the page's, shared evenly by [SIMPLE_COLUMNS]. */
  val simpleColumnDp: Float get() = (widthDp - 2f * pagePadHDp - (SIMPLE_COLUMNS - 1) * gapDp) / SIMPLE_COLUMNS

  /** The height the tiles share, under any notice. */
  private fun simpleTilesDp(notice: Boolean): Int = bodyDp - if (notice) noticeBudgetDp else 0

  /** The height the columns have above the last charge. */
  private fun simpleColumnsDp(notice: Boolean): Int = simpleTilesDp(notice) - simpleChargeDp(notice) - gapDp

  /** The largest figure of [ems] that fits a column and [heightDp], with its unit beside it. */
  private fun figureFitSp(ems: Float, heightDp: Int): Int {
    val forWidth = (simpleColumnDp - 2 * cardPadHDp - LIVE_FIGURE_GAP_DP) / (ems * fontScale)
    val forHeight = heightDp / (VALUE_LINE_FACTOR * fontScale)
    return max(LIVE_VALUE_MIN_SP, minOf(forWidth, forHeight).toInt())
  }

  /** All eight cells, one size: each has its head and room for one line under the figure, as Range uses. */
  fun simpleFigureSp(notice: Boolean): Int {
    val tile = (simpleColumnsDp(notice) - gapDp) / 2
    // Each part of a tile rounds up: without the spare room a tall window would lose Range's line.
    return figureFitSp(SIMPLE_FIGURE_EM, tile - 2 * cardPadVDp - tileHeadDp - cardGapDp - simpleLineDp - TILE_ROUNDING_DP)
  }

  /** Under a cell's figure: the same in every cell, so that a row's figures line up with or without a line there. */
  val simpleLineDp: Int get() = cardGapDp + lineDp(type.body)

  /** A figure's unit: in proportion, down to what can still be read. */
  fun simpleUnitSp(figureSp: Int): Int = max(type.label, (figureSp * SIMPLE_UNIT_SHARE).toInt())

  /**
   * The words under each figure of the last charge's row: as a tile's head, but where the height is short, or where a
   * column is too narrow for "last charged to" and "of capacity" side by side at that size: the figures would only
   * shrink for words that do not.
   */
  val simpleChargeLabelSp: Int get() = if (tier == SizeTier.COMPACT || !chargeWordsFit(type.title)) type.label else type.title

  private fun chargeWordsFit(sp: Int): Boolean =
    (CHARGE_REACHED_KWH.labelChars + CHARGE_REACHED_PCT.labelChars) * SUMMARY_EM * sp * fontScale + cardPadHDp <= simpleColumnDp - 2 * cardPadHDp

  private val chargeLabelDp: Int get() = cardGapDp + lineDp(simpleChargeLabelSp)

  private val chargeMinDp: Int get() = 2 * cardPadVDp + lineDp(LIVE_VALUE_MIN_SP, VALUE_LINE_FACTOR) + chargeLabelDp

  /** The last charge's row: a share of the height, so that its figure never takes room from the cells above as the width grows. */
  fun simpleChargeDp(notice: Boolean): Int = max(chargeMinDp, (simpleTilesDp(notice) * SIMPLE_CHARGE_SHARE).toInt())

  /** What goes beside a figure in the last charge's row. */
  fun simpleChargeUnitSp(figureSp: Int): Int = max(type.label, (figureSp * CHARGE_UNIT_SHARE).toInt())

  /** A figure of that row at [sp]: the figure and its unit, or the words under them, whichever is wider. A unit stops shrinking long before a figure does. */
  private fun chargeFigureDp(c: ChargeFigure, sp: Int): Float {
    val figure = (c.figureChars * FIGURE_EM * sp + c.unitChars * CHARGE_UNIT_CHAR_EM * simpleChargeUnitSp(sp)) * fontScale + LIVE_FIGURE_GAP_DP + CHARGE_ROUNDING_DP
    val words = c.labelChars * SUMMARY_EM * simpleChargeLabelSp * fontScale
    return max(figure, words)
  }

  /** What the charge reached, the kWh then the %, side by side in the row's first part: each as wide as it gets, the kWh with a card's padding after it. */
  private fun chargeReachedDp(sp: Int): List<Float> = listOf(chargeFigureDp(CHARGE_REACHED_KWH, sp) + cardPadHDp, chargeFigureDp(CHARGE_REACHED_PCT, sp))

  /** Each of the row's four parts holds what it has at [sp], inside the padding of the column above it. */
  private fun chargeFits(sp: Int): Boolean {
    val room = simpleColumnDp - 2 * cardPadHDp
    return chargeReachedDp(sp).sum() <= room && CHARGE_SINCE.all { chargeFigureDp(it, sp) <= room }
  }

  /**
   * The last charge's figures: as tall as the row leaves over its words, as long as each of its four parts still
   * fits under its column (what the charge reached, then the energy used since, the mi/kWh since and the drift).
   */
  fun simpleChargeSp(notice: Boolean): Int {
    var sp = ((simpleChargeDp(notice) - 2 * cardPadVDp - chargeLabelDp) / (VALUE_LINE_FACTOR * fontScale)).toInt()
    while (sp > LIVE_VALUE_MIN_SP && !chargeFits(sp)) sp -= 1
    return max(LIVE_VALUE_MIN_SP, sp)
  }

  /**
   * The row's three parts that hold one figure each: their own size, as large as their tile holds, by the owner's
   * choice (2026-11-12). The first part's two figures side by side kept all four small. Never smaller than those.
   */
  fun simpleChargeSingleSp(notice: Boolean): Int {
    val floor = simpleChargeSp(notice)
    val room = simpleColumnDp - 2 * cardPadHDp
    var sp = ((simpleChargeDp(notice) - 2 * cardPadVDp - chargeLabelDp) / (VALUE_LINE_FACTOR * fontScale)).toInt()
    // Bold digits this large run wider than the estimate: a tenth to spare.
    while (sp > floor && !CHARGE_SINCE.all { chargeFigureDp(it, sp) <= room * CHARGE_SINGLE_FILL }) sp -= 1
    return max(floor, sp)
  }

  /** The kWh's and the %'s shares of the first part: what each needs at its widest, so that the % stays put as the figures change. */
  fun simpleChargeReachedWeights(notice: Boolean): List<Float> = chargeReachedDp(simpleChargeSp(notice))

  fun requiredLive(): Int = page(liveTilesDp + if (liveCards) gapDp + liveCardsDp() else 0, notice = false)

  private fun liveCardsDp(): Int {
    val tempRows = if (restricted) TEMP_SUMMARY_LINES else TEMP_LINES
    val temps = 2 * cardPadVDp + cardHeadDp + tempRows * (cardGapDp + lineDp(type.body))
    val heat = if (heatmap) {
      2 * cardPadVDp + cardHeadDp + cardGapDp + heatHeadDp + HEAT_ROWS * HEAT_ROW_MIN_DP + cardGapDp + lineDp(type.label)
    } else {
      0
    }
    return maxOf(temps, heat)
  }

  private fun page(content: Int, notice: Boolean): Int =
    heightDp - bodyDp + content + if (notice) noticeBudgetDp else 0

  // --- Trends: tiles and a chart on the left, the PID list on the right ------------------------------

  /** Trends' height under the header: its cards reach the header. */
  val trendBodyDp: Int get() = heightDp - headerDp - pagePadVDp

  /** The PID list's width: its rows and seven group chips need the minimum, in units of text. */
  val trendPidsDp: Int get() = max((widthDp * TREND_PIDS_SHARE).toInt(), ceil(TREND_PIDS_MIN_DP * fontScale).toInt())

  /** A capacity tile's figure: smaller than Live's, where four figures share the row with a chart. */
  val trendValueSp: Int get() = max(LIVE_VALUE_MIN_SP, type.value * 2 / 3)

  /** Title, figure, source and change, a gap between each: the change is the line a tile is there for. */
  val trendTileDp: Int
    get() = 2 * cardPadVDp + max(lineDp(type.body), badgeDp) + lineDp(trendValueSp, VALUE_LINE_FACTOR) + 2 * lineDp(type.label) + 3 * cardGapDp + TILE_ROUNDING_DP

  val trendChipDp: Int get() = max(TREND_CHIP_MIN_DP, lineDp(type.body) + 8)

  /** Both lines of a PID's name and source, with the room a marker adds. */
  val trendRowLinesDp: Int get() = lineDp(type.body) + lineDp(type.axis) + 2

  /** The height a PID row gets: seven must fit, whichever group is chosen, so no group ever scrolls. */
  val trendRowDp: Int
    get() {
      val room = trendBodyDp - 2 * cardPadVDp - lineDp(type.title) - trendChipDp - 2 * cardGapDp
      return minOf(room / TREND_MAX_ROWS, trendRowLinesDp + TREND_ROW_SPARE_DP)
    }

  /** The widest the L and R buttons get, and as tall as their row allows. */
  val trendAxisButtonDp: Int get() = minOf(trendRowDp - 4, TREND_BUTTON_MAX_DP)

  /** What the header row needs: the tabs, the buttons, and the least of a status for the tabs that draw one. */
  /** For a screen whose status and controls are drawn in the navigation rail: no header row, so the page gets its height. */
  fun withoutHeader(): DashLayout = copy(headerDp = 0)

  /** The screen's status and buttons are stacked at the foot of the navigation rail, each as wide as the rail. */
  val inRail: Boolean get() = headerDp == 0

  fun headerNeededDp(): Int = headerWidth(type, restricted, shortChrome, fontScale, segmentPadDp, tabMinWidthDp, headerGapDp, pagePadHDp, widthDp)

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

  /**
   * The figure size for a row of [tiles] tiles: the page's own ([liveValueSp], worked out for [LIVE_TILES] a
   * row), or smaller when more tiles than that share the row (the OBD page has five).
   */
  fun liveFigureSp(tiles: Int): Int = if (tiles <= LIVE_TILES) liveValueSp else max(LIVE_VALUE_MIN_SP, min(liveValueSp, figureSp(tiles)))

  /** The largest figure, up to [TypeScale.value], that fits beside its unit with [cols] tiles a row. */
  private fun figureSp(cols: Int): Int = minOf(type.value, figureRoomSp(cols))

  /** The largest figure a tile's width holds beside its unit with [cols] tiles a row, whatever the type scale says. */
  private fun figureRoomSp(cols: Int): Int {
    val tile = (widthDp - 2 * pagePadHDp - (cols - 1) * gapDp) / cols - 2 * cardPadHDp
    val room = tile - LIVE_FIGURE_GAP_DP - textDp(LIVE_UNIT_CHARS, type.unit, fontScale)
    return (room / (LIVE_VALUE_CHARS * FIGURE_EM * fontScale)).toInt()
  }

  /**
   * The OBD page's five tiles in a row: the figure fills the tile's width, by the owner's choice (2026-11-12, text
   * was small in its boxes), and the row is only as tall as that needs, never taller than the page's budget for it
   * ([liveTilesDp]). [lines]: a tile has a line of news under its figure, which keeps its room.
   */
  fun obdFigureSp(lines: Boolean): Int {
    val floor = liveFigureSp(OBD_TILES)
    // Bold digits this large run wider than the estimate: at the full width "205.20 kWh" lost its unit on the emulator.
    var sp = max(floor, (figureRoomSp(OBD_TILES) * OBD_FIGURE_FILL).toInt())
    while (sp > floor && tileDp(if (lines) 1 else 0, sp) + TILE_ROUNDING_DP > liveTilesDp) sp -= 1
    return sp
  }

  fun obdTilesDp(sp: Int, lines: Boolean): Int = min(liveTilesDp, tileDp(if (lines) 1 else 0, sp) + TILE_ROUNDING_DP)

  /**
   * The grid of small tiles beside the heatmap: a value's size and its label's, from the row's height ([rowDp], a
   * label over a value) and the narrowest thing that must hold [chars] characters ([widthDp]). The type scale's
   * sizes left wide tiles nearly empty on a tall window and cost a short one its labels.
   */
  fun packSp(rowDp: Float, widthDp: Float, chars: Int): Pair<Int, Int> {
    // A bold value's line is taller than asked for (1.4 of its size on the emulator), and each part rounds up.
    val tall = (rowDp - 2 * PACK_PAD_V_DP - PACK_SPACING_DP - TILE_ROUNDING_DP) / (fontScale * (PACK_VALUE_LINE + LINE_FACTOR * PACK_LABEL_SHARE))
    val wide = widthDp / (chars * CHAR_EM * fontScale)
    val value = max(type.axis, minOf(tall, wide).toInt())
    return value to max(type.axis, (value * PACK_LABEL_SHARE).toInt())
  }

  companion object {
    const val LINE_FACTOR = 1.3f

    /** A big figure's real height: its font's natural line, more than the line height asked for. */
    const val VALUE_LINE_FACTOR = 1.2f

    /** The MX+ button and the tabs are at least this tall while restricted (one tap, eyes on the road). */
    const val MIN_DRIVING_TARGET_DP = 76
    const val LIVE_TILES = 4
    const val OBD_TILES = 5
    private const val OBD_FIGURE_FILL = 0.9f
    const val PACK_PAD_V_DP = 4
    const val PACK_SPACING_DP = 2

    /** The widest value of a small pack tile: "377.1 V" (the coolants have a tile each since 2026-11-13). */
    const val PACK_VALUE_CHARS = 7

    /** The Expert page's tile grid and cell grid share its width 1.6 : 1.8; the cell grid had 2.4 : 1 until 2026-11-13. */
    const val PACK_SHARE = 1.6f
    const val HEAT_SHARE = 1.8f

    /** One of five columns in a wide pack tile: "3.913". */
    const val PACK_PART_CHARS = 5
    private const val PACK_LABEL_SHARE = 0.66f
    const val PACK_VALUE_LINE = 1.4f

    /** A Live tile's figure is never drawn smaller than this. */
    const val LIVE_VALUE_MIN_SP = 26
    const val HEAT_ROWS = 8
    const val HEAT_ROW_MIN_DP = 9

    /** Simple's columns, all alike: the owner had the battery ring taken out for cells like the others (2026-11-07). */
    const val SIMPLE_COLUMNS = 4

    /** The rules between the last charge's columns. */
    const val SIMPLE_RULE_DP = 1
    private const val SIMPLE_UNIT_SHARE = 0.27f

    /** The last charge's row against the height of the tiles. */
    private const val SIMPLE_CHARGE_SHARE = 0.24f
    private const val CHARGE_UNIT_SHARE = 0.42f
    const val CHARGE_SINGLE_FILL = 0.9f

    /** A unit's characters: "kWh" and "mi/kWh" are wide ones, and an end cut off would say nothing. */
    private const val CHARGE_UNIT_CHAR_EM = 0.68f
    private const val TILE_ROUNDING_DP = 4

    /** A figure's glyphs round up to whole pixels at small sizes: without this, "100.0 %" lost its % on a 1250 dp window. */
    const val CHARGE_ROUNDING_DP = 4

    /** One figure of the last charge's row, at its widest. */
    private class ChargeFigure(val figureChars: Int, val unitChars: Int, val labelChars: Int)

    private val CHARGE_REACHED_KWH = ChargeFigure(5, 3, 15) // 186.4 kWh, last charged to
    private val CHARGE_REACHED_PCT = ChargeFigure(5, 1, 11) // 100.0 %, of capacity

    /** The row's other three parts, one figure each. */
    private val CHARGE_SINCE = listOf(
      ChargeFigure(5, 3, 10), // 186.4 kWh, used since
      ChargeFigure(4, 6, 5), // 2.24 mi/kWh, since
      ChargeFigure(5, 3, 5), // +12.3 kWh, drift
    )

    /** What a short window is sure to keep of the pack and temperature card; a taller one shows them all. */
    const val TEMP_LINES = 5
    const val TEMP_SUMMARY_LINES = 4

    private const val CHAR_EM = 0.58f

    /** Width of a figure's average character: digits and sign, with a narrow point among them. */
    private const val FIGURE_EM = 0.54f

    /** "204.5 kWh" in ems of the figure: five characters, then "kWh" at [SIMPLE_UNIT_SHARE] of the size. */
    private const val SIMPLE_FIGURE_EM = 5 * FIGURE_EM + SIMPLE_UNIT_SHARE * 2.1f
    private const val SUMMARY_EM = 0.5f // digits and narrow punctuation: "1:23 · 123.4 mi"

    private const val LABEL_MIN_EM = 2.5f
    private const val LIVE_VALUE_CHARS = 6 // 208.44
    private const val LIVE_UNIT_CHARS = 3 // kWh
    private const val LIVE_FIGURE_GAP_DP = 8
    private const val MIN_SEGMENT_PAD_DP = 6

    /** A toggle's segment stays this wide, whatever its text needs: still one easy tap. */
    const val MIN_SEGMENT_DP = 44
    /** The tabs are buttons in a rail on the left: this much space between two of them. */
    const val TAB_GAP_DP = 8

    /** Space between a rail button's edge and its label. */
    const val NAV_BUTTON_PAD_DP = 12
    private const val NAV_RAIL_PAD_DP = 10
    private const val NAV_LONGEST_CHARS = 11 // Diagnostics
    private const val NAV_BUTTONS = 5

    /**
     * The navigation rail down the left edge, sized from the whole window: wide enough for its longest
     * label, and five buttons at the driving size (76 dp or more) unless the window is too short for
     * that. It does not depend on the gear: the buttons are the same size parked and driving, by the
     * owner's choice (2026-11-11).
     */
    fun navRail(widthDp: Float, heightDp: Float, fontScale: Float = 1f): NavRail {
      val l = of(widthDp, heightDp, true, fontScale)
      val room = (heightDp.toInt() - 2 * NAV_RAIL_PAD_DP - (NAV_BUTTONS - 1) * TAB_GAP_DP) / NAV_BUTTONS
      return NavRail(
        widthDp = textDp(NAV_LONGEST_CHARS, l.type.tab, l.fontScale) + 2 * NAV_BUTTON_PAD_DP + 2 * NAV_RAIL_PAD_DP,
        buttonDp = min(l.headerDp, room),
        textSp = l.type.tab,
        footSp = l.type.body,
        padDp = NAV_RAIL_PAD_DP,
      )
    }

    /** Trends' tiles, chart, PID list and controls need this much window; narrower, the tab is not offered. */
    const val TRENDS_MIN_WIDTH_DP = 1100
    const val TREND_MAX_ROWS = 7
    private const val TREND_PIDS_SHARE = 0.36f
    private const val TREND_PIDS_MIN_DP = 420
    private const val TREND_ROW_SPARE_DP = 20
    private const val TREND_CHIP_MIN_DP = 36
    private const val TREND_BUTTON_MAX_DP = 48
    private const val STATUS_CHARS = 15 // The least Live's link state and Trends' row count get.
    private const val RESET_CHARS = 13 // Reset session
    private const val RESET_SHORT_CHARS = 5 // Reset
    private const val MX_CHARS = 14 // Disconnect MX+
    private const val MX_SHORT_CHARS = 7 // MX+ off

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
      var short = false
      fun fits() = headerWidth(type, restricted, short, scale, segmentPad, tabMin, headerGap, pagePadH, widthDp.toInt()) <= widthDp
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
        // These hold text: they grow with the font, or an axis label would be cut at the card's edge.
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
        shortChrome = short,
        liveTileLines = liveLines,
        liveTileCols = LIVE_TILES,
        liveValueSp = type.value,
        liveCards = true,
        liveShort = restricted || w < 1700f,
      ).fitLive()
    }

    /**
     * Simple's one layout, whatever the gear: Park's, with the tabs and the MX+ button as tall as Drive
     * wants them, so that shifting moves nothing on the page. Its controls work in every gear.
     */
    /**
     * The OBD page's layout: the parked one in every gear, by the owner's choice (2026-11-11), with its status
     * and buttons in the navigation rail. The page does not change when the truck leaves Park.
     */
    fun ofLive(widthDp: Float, heightDp: Float, fontScale: Float = 1f): DashLayout = of(widthDp, heightDp, false, fontScale).withoutHeader()

    fun ofSimple(widthDp: Float, heightDp: Float, fontScale: Float = 1f): DashLayout {
      val l = of(widthDp, heightDp, false, fontScale)
      return l.copy(headerDp = max(l.headerDp, MIN_DRIVING_TARGET_DP), drivingTargets = true)
    }

    private fun typeFor(tier: SizeTier, restricted: Boolean): TypeScale = when (tier) {
      SizeTier.COMPACT ->
        if (restricted) {
          TypeScale(value = 48, unit = 20, cardTitle = 22, title = 20, body = 18, label = 16, tab = 29, axis = 14)
        } else {
          TypeScale(value = 40, unit = 18, cardTitle = 20, title = 18, body = 16, label = 14, tab = 22, axis = 12)
        }
      SizeTier.MEDIUM ->
        if (restricted) {
          TypeScale(value = 68, unit = 26, cardTitle = 26, title = 24, body = 22, label = 18, tab = 31, axis = 16)
        } else {
          TypeScale(value = 56, unit = 22, cardTitle = 24, title = 20, body = 18, label = 16, tab = 26, axis = 14)
        }
      SizeTier.LARGE ->
        if (restricted) {
          TypeScale(value = 80, unit = 32, cardTitle = 30, title = 28, body = 24, label = 20, tab = 34, axis = 18)
        } else {
          TypeScale(value = 64, unit = 28, cardTitle = 28, title = 24, body = 20, label = 18, tab = 29, axis = 16)
        }
    }

    private fun textDp(chars: Int, sp: Int, scale: Float): Int = ceil(chars * sp * CHAR_EM * scale).toInt()

    private fun headerWidth(
      type: TypeScale,
      restricted: Boolean,
      short: Boolean,
      scale: Float,
      segmentPad: Int,
      tabMin: Int,
      gap: Int,
      pagePad: Int,
      windowDp: Int,
    ): Int {
      val segment = type.tab - 2
      val reset = if (restricted) 0 else textDp(if (short) RESET_SHORT_CHARS else RESET_CHARS, segment, scale) + 2 * segmentPad
      val mx = textDp(if (short) MX_SHORT_CHARS else MX_CHARS, segment, scale) + 2 * segmentPad
      val status = ceil(STATUS_CHARS * type.title * SUMMARY_EM * scale).toInt()
      val gaps = if (restricted) 1 else 2 // between a status, the MX+ button and, parked, Reset (the tabs are in the rail)
      return 2 * pagePad + reset + mx + status + gaps * gap
    }
  }
}

/** The navigation rail's sizes: see [DashLayout.navRail]. */
data class NavRail(val widthDp: Int, val buttonDp: Int, val textSp: Int, val footSp: Int, val padDp: Int)

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
