package dev.silveradostats.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout policy over the whole landscape range, parked and restricted: what each size promises
 * fits the window. Nothing here names a panel except the known sizes at the end, as examples.
 */
class DashLayoutTest {
  private val widths = 1000..2800 step 25
  private val heights = 420..1300 step 10

  private fun forEachWindow(check: (DashLayout) -> Unit) {
    for (w in widths) for (h in heights) for (r in listOf(false, true)) {
      check(DashLayout.of(w.toFloat(), h.toFloat(), r))
    }
  }

  /** Simple's one layout: there is no gear in it. */
  private fun forEachSimpleWindow(check: (DashLayout) -> Unit) {
    for (w in widths) for (h in heights) check(DashLayout.ofSimple(w.toFloat(), h.toFloat()))
  }

  private fun DashLayout.name() = "${widthDp}x$heightDp restricted=$restricted tier=$tier"

  @Test
  fun everyPageFitsTheWindowHeight() {
    forEachSimpleWindow { l ->
      assertTrue("Simple: ${l.requiredSimple(false)} > ${l.name()}", l.requiredSimple(false) <= l.heightDp)
      assertTrue("Simple + notice: ${l.requiredSimple(true)} > ${l.name()}", l.requiredSimple(true) <= l.heightDp)
    }
    forEachWindow { l -> assertTrue("Live: ${l.requiredLive()} > ${l.name()}", l.requiredLive() <= l.heightDp) }
  }

  /** Simple is one layout whatever the gear, and what works in Drive is as big as Drive asks for. */
  @Test
  fun simpleHasOneLayoutWithDrivingTargets() {
    forEachSimpleWindow { l ->
      assertFalse(l.name(), l.restricted)
      assertTrue("tabs fill the header: ${l.name()}", l.headerDp >= DashLayout.MIN_DRIVING_TARGET_DP)
      assertEquals(l.name(), DashLayout.MIN_DRIVING_TARGET_DP, l.mxButtonDp)
      assertTrue(l.name(), l.mxButtonDp <= l.headerDp)
      assertTrue("Reset stays in the header row: ${l.name()}", l.controlDp <= l.headerDp)
      assertTrue("all four tabs and the controls fit: ${l.headerNeededDp()} ${l.name()}", l.headerNeededDp() <= l.widthDp)
    }
  }

  @Test
  fun theHeaderRowFitsTheWindowWidth() {
    forEachWindow { l -> assertTrue("header ${l.headerNeededDp()} > ${l.name()}", l.headerNeededDp() <= l.widthDp) }
  }

  @Test
  fun drivingTargetsAreLargeEnough() {
    forEachWindow { l ->
      if (l.restricted) {
        assertTrue(l.name(), l.mxButtonDp >= 76)
        assertTrue("tabs fill the header: ${l.name()}", l.headerDp >= 76)
        assertTrue(l.name(), l.mxButtonDp <= l.headerDp)
      } else {
        assertEquals(l.name(), l.controlDp, l.mxButtonDp)
      }
      assertTrue(l.name(), l.controlDp <= l.headerDp)
    }
  }

  @Test
  fun drivingLocksEverythingElseAway() {
    forEachWindow { l ->
      assertEquals(l.name(), !l.restricted, l.controls)
      assertEquals(l.name(), !l.restricted, l.diagnosticsTab)
      assertEquals(l.name(), !l.restricted, l.heatmap)
      if (l.restricted) {
        assertFalse("no age unless stale: ${l.name()}", l.ages)
        assertTrue(l.name(), l.shortTitles)
      }
    }
  }

  @Test
  fun drivingFiguresAreLargerAndHaveFewerLines() {
    for (w in widths) for (h in heights) {
      val parked = DashLayout.of(w.toFloat(), h.toFloat(), false)
      val driving = DashLayout.of(w.toFloat(), h.toFloat(), true)
      assertEquals(parked.tier, driving.tier)
      assertTrue(driving.type.value > parked.type.value)
      assertTrue(driving.type.body > parked.type.body)
      assertTrue(driving.type.tab > parked.type.tab)
      assertTrue(driving.liveTileLines <= parked.liveTileLines)
    }
  }

  @Test
  fun aLargerWindowNeverGetsSmallerType() {
    for (r in listOf(false, true)) {
      for (h in heights) {
        var last = 0
        for (w in widths) {
          val v = DashLayout.of(w.toFloat(), h.toFloat(), r).type.value
          assertTrue("$w x $h", v >= last)
          last = v
        }
      }
      for (w in widths) {
        var last = 0
        for (h in heights) {
          val v = DashLayout.of(w.toFloat(), h.toFloat(), r).type.value
          assertTrue("$w x $h", v >= last)
          last = v
        }
      }
    }
  }

  /** At the default font nothing a window of this range promises has to give way. */
  @Test
  fun atTheDefaultFontNothingGivesWay() {
    forEachWindow { l ->
      assertTrue(l.name(), l.liveTileLines >= 1)
      assertEquals(l.name(), DashLayout.LIVE_TILES, l.liveTileCols)
      assertTrue(l.name(), l.liveCards)
      assertFalse(l.name(), l.tooSmall(notice = true))
    }
  }

  @Test
  fun aLargerWindowNeverGetsASmallerLiveFigure() {
    for (r in listOf(false, true)) {
      for (h in heights) {
        var last = 0
        for (w in widths) {
          val v = DashLayout.of(w.toFloat(), h.toFloat(), r).liveValueSp
          assertTrue("$w x $h restricted=$r: $v after $last", v >= last)
          last = v
        }
      }
    }
  }

  /** Simple's three figures fill their tiles (the owner, 2026-11-18), and "204.5 kWh" with its head and line still fits every window. */
  @Test
  fun theSimpleFiguresFitTheirTiles() {
    forEachSimpleWindow { l ->
      for (notice in listOf(false, true)) {
        val name = "${l.name()} notice=$notice"
        assertTrue(name, l.requiredSimple(notice) <= l.heightDp)
        val sp = l.simpleFigureSp(notice)
        val room = l.simpleColumnDp - 2 * l.cardPadHDp
        assertTrue("$name: $sp sp across $room", 5 * sp * 0.54f + 2.1f * l.simpleUnitSp(sp) + 8 <= room + 1)
        val tiles = l.bodyDp - if (notice) l.noticeDp + l.gapDp else 0
        val tile = 2 * l.cardPadVDp + l.simpleHeadDp + l.cardGapDp + l.lineDp(sp, DashLayout.VALUE_LINE_FACTOR)
        // The line under a figure gives way first, and only with the figures already at their smallest.
        if (sp > DashLayout.LIVE_VALUE_MIN_SP) assertTrue("$name: $tile tall", tile + l.simpleLineDp <= tiles)
      }
    }
    // The emulator's page beside the rail, and the truck's: far larger than a tile's figure elsewhere.
    assertTrue(DashLayout.ofSimple(1160f, 610f).withoutHeader().simpleFigureSp(false) >= 90)
    assertTrue(DashLayout.ofSimple(2331f, 735f).withoutHeader().simpleFigureSp(false) >= 90)
  }

  /**
   * The OBD page's window cards (the owner, 2026-11-18), three across under the pack tiles: the mi/kWh beside the miles,
   * then the energy's total, its bar and the three parts side by side, all in the card, and "-12.3" in a third of it.
   */
  @Test
  fun theObdWindowsFit() {
    for (w in widths) for (h in heights) {
      val l = DashLayout.ofLive(w.toFloat(), h.toFloat())
      if (!l.liveCards) continue
      val name = l.name()
      val figureMax = l.obdFigureSp(false)
      val under = l.bodyDp - l.obdTilesDp(figureMax, false) - 2 * l.gapDp
      val areaDp = (under * DashLayout.WINDOWS_SHARE / (DashLayout.PACK_SHARE + DashLayout.WINDOWS_SHARE)).toInt()
      val windowDp = (w - 2 * l.pagePadHDp - 2 * l.gapDp) / 3f
      val part = l.windowPartSp(windowDp, areaDp.toFloat(), figureMax * 3 / 4)
      val sp = l.windowFigureSp(windowDp, areaDp, part, figureMax)
      assertTrue("$name: part $part", part >= l.type.title && part <= maxOf(l.type.title, figureMax * 3 / 4))
      if (part > l.type.title) {
        assertTrue("$name: part $part wide", 5 * 0.54f * part + 4 <= (windowDp - 2 * l.cardPadHDp - 2 * DashLayout.WINDOW_PART_GAP_DP) / 3 + 1)
      }
      if (sp > DashLayout.LIVE_VALUE_MIN_SP) {
        assertTrue("$name: $sp over $part tall", 2 * l.cardPadVDp + l.tileHeadDp + l.lineDp(sp, DashLayout.VALUE_LINE_FACTOR) + l.windowEnergyDp(part) <= areaDp)
        // "12.34 mi/kWh" in half the card.
        assertTrue("$name: $sp wide", 5 * sp * 0.54f + 6 * 0.68f * l.simpleUnitSp(sp) + 12 <= (windowDp - 2 * l.cardPadHDp - DashLayout.WINDOW_PART_GAP_DP) / 2 + 1)
      }
    }
  }

  /** The tiles are three alike, the page's width shared evenly (the owner, 2026-11-18). */
  @Test
  fun theColumnsAreThreeAlike() {
    val l = DashLayout.ofSimple(1470f, 700f).withoutHeader()
    assertEquals(3, DashLayout.SIMPLE_COLUMNS)
    assertEquals((1470f - 2 * l.pagePadHDp - 2 * l.gapDp).toDouble(), (3 * l.simpleColumnDp).toDouble(), 0.5)
  }

  @Test
  fun aLargerWindowNeverGetsASmallerSimpleFigure() {
    for (h in heights) {
      var last = 0
      var tier: SizeTier? = null
      for (w in widths) {
        val l = DashLayout.ofSimple(w.toFloat(), h.toFloat())
        // A tier up brings more padding: only within one does more width mean more room.
        if (l.tier != tier) {
          last = 0
        }
        assertTrue("${l.name()}: ${l.simpleFigureSp(false)} after $last", l.simpleFigureSp(false) >= last)
        last = l.simpleFigureSp(false)
        tier = l.tier
      }
    }
  }

  @Test
  fun chartsAlwaysKeepTheirMinimumAndTheRestIsTheirs() {
    forEachWindow { l ->
      assertTrue(l.name(), l.chartMinDp >= 80)
      // What is left above the promises is what the chart absorbs: never negative (asserted above),
      // and a taller window only ever adds to it.
      val taller = DashLayout.of(l.widthDp.toFloat(), l.heightDp + 100f, l.restricted)
      if (taller.tier == l.tier) assertEquals(l.name(), l.bodyDp + 100, taller.bodyDp)
    }
  }

  /** A bigger font takes the room a smaller window would: the page still fits, with its charts. */
  @Test
  fun largerFontsFitToo() {
    for (scale in listOf(1.15f, 1.3f)) {
      for (w in 1000..2800 step 50) for (h in 420..1300 step 10) for (r in listOf(false, true)) {
        val l = DashLayout.of(w.toFloat(), h.toFloat(), r, scale)
        val name = "${l.name()} scale=$scale"
        val simple = DashLayout.ofSimple(w.toFloat(), h.toFloat(), scale)
        assertTrue("Simple ${simple.requiredSimple(false)} $name", simple.requiredSimple(false) <= h)
        assertTrue("Simple + notice ${simple.requiredSimple(true)} $name", simple.requiredSimple(true) <= h)
        assertTrue("Live ${l.requiredLive()} $name", l.requiredLive() <= h)
        assertTrue("the cards stay: $name", l.liveCards)
        assertEquals("four across: $name", DashLayout.LIVE_TILES, l.liveTileCols)
        assertTrue("header ${l.headerNeededDp()} $name", l.headerNeededDp() <= w)
        assertFalse(name, l.tooSmall(notice = true))
      }
    }
    val small = DashLayout.of(1300f, 700f, false, 1.3f)
    assertEquals("judged in units of text", SizeTier.COMPACT, small.tier)
  }

  /** Axis and row labels are text: with a larger font their room grows too, so no sign is cut off. */
  @Test
  fun axisRoomGrowsWithTheFont() {
    for (r in listOf(false, true)) {
      val base = DashLayout.of(1000f, 420f, r)
      val large = DashLayout.of(1000f, 420f, r, 1.3f)
      assertTrue(large.axisLeftDp >= base.axisLeftDp * 1.3f)
      assertTrue(large.axisBottomDp >= base.axisBottomDp * 1.3f)
      assertTrue(large.heatLabelDp >= base.heatLabelDp * 1.3f)
    }
  }

  /** The review's reproduction: this window lost its chart and drew empty cards. */
  @Test
  fun theFloorWithALargerFontKeepsItsChart() {
    for (scale in listOf(1.15f, 1.3f)) for (r in listOf(false, true)) {
      val l = DashLayout.of(1000f, 420f, r, scale)
      assertEquals(DashLayout.LIVE_TILES, l.liveTileCols)
      assertTrue(l.liveCards)
      assertTrue("${l.requiredLive()}", l.requiredLive() <= 420)
      assertTrue("the figure gave way, no further than the default font's size", l.liveValueSp * scale >= l.type.value)
    }
    val tight = DashLayout.of(1000f, 420f, true, 1.3f)
    assertTrue("too wide across as it was", tight.liveValueSp < tight.type.value)
  }

  /**
   * The OBD page's five figures fill their tiles (the owner, 2026-11-12: text was small in its boxes): never smaller
   * than the type scale gave them, never wider than a tile, and the row never taller than the page's budget for it.
   */
  @Test
  fun theObdFiguresFillTheirTiles() {
    forEachWindow { l ->
      for (lines in listOf(false, true)) {
        val sp = l.obdFigureSp(lines)
        assertTrue(l.name(), sp >= l.liveFigureSp(DashLayout.OBD_TILES))
        assertTrue(l.name(), l.obdTilesDp(sp, lines) <= l.liveTilesDp)
        val tile = (l.widthDp - 2 * l.pagePadHDp - 4 * l.gapDp) / 5 - 2 * l.cardPadHDp
        if (sp > DashLayout.LIVE_VALUE_MIN_SP) assertTrue("${l.name()}: $sp sp in $tile", 6 * 0.54f * sp + 8 + 3 * 0.58f * l.type.unit <= tile + 1)
      }
    }
    // The truck's page beside the rail: well above the type scale's 56.
    assertTrue(DashLayout.ofLive(1861f, 602f).obdFigureSp(false) >= 70)
    // A small pack tile on that page: its label and value fit its row, and "377.1 V" its width.
    val (value, label) = DashLayout.ofLive(1861f, 602f).packSp(63f, 213f, DashLayout.PACK_VALUE_CHARS)
    assertTrue("$value over $label", value * DashLayout.PACK_VALUE_LINE + label * 1.3f + 2 * DashLayout.PACK_PAD_V_DP + DashLayout.PACK_SPACING_DP <= 63f)
    assertTrue(value >= 20 && label >= 14)
  }

  @Test
  fun aZeroFontScaleCannotBreakIt() {
    assertEquals(1f, DashLayout.of(1000f, 420f, false, 0f).fontScale, 0f)
  }

  @Test
  fun narrowWindowsKeepFourAcrossWhileTheFiguresFit() {
    for (r in listOf(false, true)) {
      val l = DashLayout.of(800f, 420f, r)
      assertEquals(DashLayout.LIVE_TILES, l.liveTileCols)
      assertTrue(l.liveCards)
      assertTrue("${l.requiredLive()}", l.requiredLive() <= 420)
      assertTrue(l.liveValueSp >= DashLayout.LIVE_VALUE_MIN_SP)
    }
  }

  @Test
  fun tilesStackOnlyWhereThePageStillFits() {
    val tall = DashLayout.of(500f, 900f, false)
    assertEquals("no room across, room to stack", 2, tall.liveTileCols)
    assertTrue(tall.liveCards)
    assertTrue(tall.requiredLive() <= 900)
    val short = DashLayout.of(500f, 420f, false)
    assertEquals("no room for two rows and the cards", DashLayout.LIVE_TILES, short.liveTileCols)
  }

  /** Below every minimum: the tiles alone rather than empty cards under them, then one line. */
  @Test
  fun aWindowTooShortForTheCardsDropsThem() {
    val short = DashLayout.of(1000f, 230f, false)
    assertFalse(short.liveCards)
    assertTrue(short.liveTilesDp < 230)
    assertFalse(short.tooSmall())
    val tiny = DashLayout.of(728f, 150f, true)
    assertTrue("the review's 150 dp window", tiny.tooSmall())
    assertFalse("just under the floor still draws the page", DashLayout.of(1000f, 370f, true).tooSmall())
    assertTrue("a notice needs room too", DashLayout.of(1000f, 190f, false).tooSmall(notice = true))
  }

  @Test
  fun aColumnDropsEverythingLessImportantThanWhatDidNotFit() {
    fun kept(max: Int, vararg children: Pair<Int, Int>) = FitRules.keptInColumn(children.map { FitRules.ColumnChild(it.first, it.second) }, gap = 2, maxHeight = max).toList()
    // Figure, label, bar, line.
    val tile = arrayOf(0 to 50, 1 to 20, 2 to 8, 3 to 20)
    assertEquals(listOf(true, true, true, true), kept(106, *tile))
    assertEquals("the line goes first", listOf(true, true, true, false), kept(84, *tile))
    assertEquals(listOf(true, true, false, false), kept(72, *tile))
    assertEquals("no bar without its label", listOf(true, false, false, false), kept(60, *tile))
    // The review's reproduction: the figure does not fit, and the small bar must not stay on its own.
    assertEquals(listOf(false, false, false, false), kept(10, *tile))
    // Equal priority: each still has its chance.
    assertEquals(listOf(true, true, false), kept(40, 0 to 30, 3 to 8, 3 to 30))
    assertEquals(listOf(true, false, true), kept(40, 0 to 30, 3 to 30, 3 to 8))
  }

  @Test
  fun aRowGivesWayByImportance() {
    fun widths(total: Int, vararg children: FitRules.RowChild) = FitRules.claimWidths(children.toList(), total, gap = 8).toList()
    val d = FitRules.DROPPED
    // Label (may shrink to 40), badge, pill, or else the pill's dot.
    val label = FitRules.RowChild(priority = 1, natural = 100, min = 40)
    val badge = FitRules.RowChild(priority = 0, natural = 60)
    val pill = FitRules.RowChild(priority = 2, natural = 120, group = 7)
    val dot = FitRules.RowChild(priority = 3, natural = 12, group = 7)
    assertEquals("all whole, and the dot not as well", listOf(100, 60, 120, d), widths(400, label, badge, pill, dot))
    assertEquals("the pill becomes its dot", listOf(100, 60, d, 12), widths(200, label, badge, pill, dot))
    assertEquals("then the label shrinks, and the dot gives way to it", listOf(92, 60, d, d), widths(160, label, badge, pill, dot))
    assertEquals("the badge keeps its width; the dot goes", listOf(50, 60, d, d), widths(118, label, badge, pill, dot))
    assertEquals("a label with no room is not drawn at all: no bare ellipsis", listOf(d, 60, d, 12), widths(90, label, badge, pill, dot))
    assertEquals("a badge that fits nothing else still has first claim", listOf(d, 60, d, d), widths(60, label, badge, pill, dot))
    // A short label is never hidden for its minimum.
    val short = FitRules.RowChild(priority = 1, natural = 30, min = 40)
    assertEquals(listOf(30), widths(30, short))
    // Figures before what qualifies them: a pause count goes first.
    val summary = FitRules.RowChild(priority = 0, natural = 150, min = 1)
    val pauses = FitRules.RowChild(priority = 1, natural = 70)
    assertEquals(listOf(150, d), widths(200, summary, pauses))
    assertEquals(listOf(150, 70), widths(228, summary, pauses))
  }

  /** The measured examples: usable size in dp after insets, not used by the policy itself. */
  @Test
  fun knownWindows() {
    // 1000 x 420, the floor: everything compact, but no line of the promise lost.
    val floor = DashLayout.of(1000f, 420f, false)
    assertEquals(SizeTier.COMPACT, floor.tier)
    // Blazer proxy, 2914 x 1134 px at 200 dpi, below its bars.
    val proxy = DashLayout.of(2331f, 735f, false)
    assertEquals(SizeTier.LARGE, proxy.tier)
    assertTrue(proxy.requiredLive() < 735)
    // The same panel minus its measured cutouts (right 285 px, top 167 px), below its bars.
    val truck = DashLayout.of(2103f, 602f, false)
    assertEquals(SizeTier.MEDIUM, truck.tier)
    assertTrue(truck.requiredLive() < 602)
    // 1408 x 792 at 160 dpi, below the emulator's bars.
    assertEquals(SizeTier.MEDIUM, DashLayout.of(1408f, 620f, false).tier)
  }

  @Test
  fun thePolicyAssumesNoPanel() {
    // No size is special: neighbours of a measured panel behave the same way.
    val a = DashLayout.of(2103f, 602f, true)
    val b = DashLayout.of(2104f, 603f, true)
    assertEquals(a.tier, b.tier)
    assertEquals(a.type, b.type)
    assertEquals(a.headerDp, b.headerDp)
  }
}
