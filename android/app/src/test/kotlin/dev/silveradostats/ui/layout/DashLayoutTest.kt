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

  private fun DashLayout.name() = "${widthDp}x$heightDp restricted=$restricted tier=$tier"

  @Test
  fun everyPageFitsTheWindowHeight() {
    forEachWindow { l ->
      assertTrue("Simple: ${l.requiredSimple(false)} > ${l.name()}", l.requiredSimple(false) <= l.heightDp)
      assertTrue("Simple + notice: ${l.requiredSimple(true)} > ${l.name()}", l.requiredSimple(true) <= l.heightDp)
      assertTrue("Live: ${l.requiredLive()} > ${l.name()}", l.requiredLive() <= l.heightDp)
    }
  }

  @Test
  fun theHeaderRowFitsTheWindowWidth() {
    forEachWindow { l -> assertTrue("header ${l.headerNeededDp()} > ${l.name()}", l.headerNeededDp() <= l.widthDp) }
  }

  @Test
  fun theHeaderDropsItsExtrasInOrderOfUse() {
    // Every window that keeps the note also keeps the app title; the summary's words go last.
    forEachWindow { l ->
      if (l.readOnlyNote) assertTrue(l.name(), l.appTitle)
      if (l.appTitle && !l.restricted) assertTrue(l.name(), l.longSummary)
    }
  }

  @Test
  fun whileDrivingTheSummaryIsOnlyItsFigures() {
    forEachWindow { l -> if (l.restricted) assertFalse(l.name(), l.longSummary) }
    assertTrue(DashLayout.of(2331f, 735f, false).longSummary)
    assertFalse(DashLayout.of(2331f, 735f, true).longSummary)
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
      assertEquals(l.name(), !l.restricted, l.ringDetail)
      if (l.restricted) {
        assertFalse(l.name(), l.strip)
        assertFalse(l.name(), l.readOnlyNote)
        assertFalse("no age unless stale: ${l.name()}", l.ages)
        assertEquals(l.name(), 1, l.tileLines)
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
      assertTrue(driving.type.hero > parked.type.hero)
      assertTrue(driving.type.body > parked.type.body)
      assertTrue(driving.type.tab > parked.type.tab)
      assertTrue(driving.tileLines < parked.tileLines)
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
      assertEquals(l.name(), l.tileLines, l.plainTileLines)
      assertEquals(l.name(), 1, l.noticeTileLines)
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

  /** Half a Simple tile holds a figure and its unit: full size on the truck, smaller only where it must be. */
  @Test
  fun twoFiguresFitTheirTile() {
    for (r in listOf(false, true)) {
      for (h in heights) {
        var last = 0
        var tier: SizeTier? = null
        for (w in widths) {
          val l = DashLayout.of(w.toFloat(), h.toFloat(), r)
          // A tier up brings a larger unit and more padding: only within one does more width mean more room.
          if (l.tier != tier) last = 0
          assertTrue("${l.name()}: ${l.twinValueSp} after $last", l.twinValueSp >= last)
          assertTrue(l.name(), l.twinValueSp in DashLayout.LIVE_VALUE_MIN_SP..l.type.value)
          last = l.twinValueSp
          tier = l.tier
        }
      }
    }
    val truck = DashLayout.of(2103f, 602f, false)
    assertEquals(truck.type.value, truck.twinValueSp)
    val driving = DashLayout.of(2103f, 602f, true)
    assertEquals(driving.type.value, driving.twinValueSp)
    assertTrue("a narrow window gives way", DashLayout.of(1000f, 420f, false).twinValueSp < DashLayout.of(1000f, 420f, false).type.value)
  }

  @Test
  fun chartsAlwaysKeepTheirMinimumAndTheRestIsTheirs() {
    forEachWindow { l ->
      assertTrue(l.name(), l.chartMinDp >= 80)
      // What is left above the promises is what the chart absorbs: never negative (asserted above),
      // and a taller window only ever adds to it.
      val taller = DashLayout.of(l.widthDp.toFloat(), l.heightDp + 100f, l.restricted)
      if (taller.tier == l.tier) assertEquals(l.name(), l.bodyDp + 100 - (if (taller.strip && !l.strip) taller.stripDp else 0), taller.bodyDp)
    }
  }

  /** A bigger font takes the room a smaller window would: the page still fits, with its charts. */
  @Test
  fun largerFontsFitToo() {
    for (scale in listOf(1.15f, 1.3f)) {
      for (w in 1000..2800 step 50) for (h in 420..1300 step 10) for (r in listOf(false, true)) {
        val l = DashLayout.of(w.toFloat(), h.toFloat(), r, scale)
        val name = "${l.name()} scale=$scale"
        assertTrue("Simple ${l.requiredSimple(false)} $name", l.requiredSimple(false) <= h)
        assertTrue("Simple + notice ${l.requiredSimple(true)} $name", l.requiredSimple(true) <= h)
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

  @Test
  fun trendsFitsEveryWindowItIsOfferedIn() {
    forEachWindow { l ->
      if (!l.trendsTab) return@forEachWindow
      val leftCol = l.widthDp - 2 * l.pagePadHDp - l.gapDp - l.trendPidsDp
      assertTrue("${l.name()}: tiles leave no chart", l.trendBodyDp - l.trendTileDp - l.gapDp >= l.chartMinDp)
      val tileLines = 2 * l.cardPadVDp + maxOf(l.lineDp(l.type.body), l.badgeDp) + l.lineDp(l.trendValueSp, DashLayout.VALUE_LINE_FACTOR) + 2 * l.lineDp(l.type.label) + 3 * l.cardGapDp
      assertTrue("${l.name()}: a tile's change line must fit: $tileLines > ${l.trendTileDp}", tileLines <= l.trendTileDp)
      assertTrue("${l.name()}: left column $leftCol", leftCol >= DashLayout.TRENDS_MIN_WIDTH_DP / 2)
      assertTrue("${l.name()}: a PID row of ${l.trendRowDp} must keep both its lines", l.trendRowDp >= l.trendRowLinesDp)
      assertTrue("${l.name()}: seven rows", l.cardPadVDp * 2 + l.lineDp(l.type.title) + l.trendChipDp + 2 * l.cardGapDp + DashLayout.TREND_MAX_ROWS * l.trendRowDp <= l.trendBodyDp)
      assertTrue("${l.name()}: L and R stay in their row", l.trendAxisButtonDp < l.trendRowDp)
    }
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
    assertFalse(floor.appTitle)
    // Blazer proxy, 2914 x 1134 px at 200 dpi, below its bars.
    val proxy = DashLayout.of(2331f, 735f, false)
    assertEquals(SizeTier.LARGE, proxy.tier)
    assertTrue(proxy.appTitle)
    assertTrue(proxy.longSummary)
    assertTrue(proxy.strip)
    assertTrue(proxy.requiredLive() < 735)
    // The same panel minus its measured cutouts (right 285 px, top 167 px), below its bars.
    val truck = DashLayout.of(2103f, 602f, false)
    assertEquals(SizeTier.MEDIUM, truck.tier)
    assertTrue(truck.appTitle)
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
