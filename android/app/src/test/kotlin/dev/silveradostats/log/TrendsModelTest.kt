package dev.silveradostats.log

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.obd.TestSupport
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.vehicle.KnownProperties
import dev.silveradostats.vehicle.ReadProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsModelTest {
  private val now = 1_800_000_000_000L
  private val hour = 3_600_000L

  private fun row(hoursAgo: Double, vararg v: Pair<LogCol, Double>, source: LogSource = LogSource.CAR) =
    LogRow(now - (hoursAgo * hour).toLong(), 0, source, LogTrigger.JOB, mapOf(*v))

  private fun chart(rows: List<LogRow>, left: TrendMeasure = TrendMeasure.SOC, right: TrendMeasure = TrendMeasure.OUTSIDE, range: TrendRange = TrendRange.MONTH, temp: TempUnit = TempUnit.C) =
    TrendsModel.chart(rows, range, left, right, temp, DistUnit.MI, now)

  @Test
  fun eachSideHasItsOwnScale() {
    val c = chart(listOf(row(2.0, LogCol.SOC_PCT to 80.0, LogCol.OUTSIDE_C to 10.0), row(1.0, LogCol.SOC_PCT to 40.0, LogCol.OUTSIDE_C to 12.0)))
    assertTrue(c.left.axis.max >= 80.0 && c.left.axis.min <= 40.0)
    assertTrue(c.right.axis.max >= 12.0 && c.right.axis.min <= 10.0)
    assertTrue("the outside scale is not stretched to the SoC's", c.right.axis.max < 40.0)
  }

  @Test
  fun lineBreaksWhereTheTruckWasOff() {
    val s = chart(listOf(row(30.0, LogCol.SOC_PCT to 80.0), row(29.0, LogCol.SOC_PCT to 78.0), row(5.0, LogCol.SOC_PCT to 60.0), row(4.0, LogCol.SOC_PCT to 58.0))).left
    assertEquals(listOf(2, 2), s.segments.map { it.size })
  }

  @Test
  fun minMaxMeasuresAreBands() {
    val s = chart(listOf(row(1.0, LogCol.MODULE_TEMP_MIN_C to 16.0, LogCol.MODULE_TEMP_MAX_C to 19.0)), left = TrendMeasure.MODULE_TEMPS).left
    assertTrue(s.band)
    assertEquals(16.0, s.segments[0][0].low, 0.0)
    assertEquals(19.0, s.segments[0][0].high, 0.0)
  }

  @Test
  fun aBandNeedsBothEnds() {
    assertFalse(chart(listOf(row(1.0, LogCol.MODULE_TEMP_MIN_C to 16.0)), left = TrendMeasure.MODULE_TEMPS).left.hasData)
  }

  @Test
  fun temperaturesFollowTheChosenUnit() {
    val s = chart(listOf(row(1.0, LogCol.OUTSIDE_C to 10.0)), temp = TempUnit.F).right
    assertEquals(50.0, s.segments[0][0].mean, 1e-9)
    assertEquals("°F", s.unit)
    assertEquals("kWh", TrendMeasure.ENERGY.unitIn(TempUnit.F, DistUnit.MI))
  }

  @Test
  fun aMeasureOnlyEverShowsItsOwnSource() {
    val rows = listOf(
      row(2.0, LogCol.CAR_SOC_PCT to 41.0, LogCol.CAR_NOMINAL_KWH to 205.0),
      row(1.0, LogCol.SOC_PCT to 42.0, LogCol.CAPACITY_KWH to 208.0, LogCol.IMPLIED_KWH to 203.0, source = LogSource.MX),
    )
    assertEquals(listOf(42.0), chart(rows, left = TrendMeasure.SOC).left.segments.flatten().map { it.mean })
    assertEquals(listOf(41.0), chart(rows, left = TrendMeasure.CAR_SOC).left.segments.flatten().map { it.mean })
    assertEquals(listOf(208.0), chart(rows, left = TrendMeasure.CAPACITY_BMS).left.segments.flatten().map { it.mean })
    assertEquals(listOf(203.0), chart(rows, left = TrendMeasure.CAPACITY_CALC).left.segments.flatten().map { it.mean })
    assertEquals(listOf(205.0), chart(rows, left = TrendMeasure.CAR_NOMINAL).left.segments.flatten().map { it.mean })
  }

  @Test
  fun aFlatLineHasARealScaleNotZoomedRoundingNoise() {
    val a = chart(listOf(row(2.0, LogCol.CAR_NOMINAL_KWH to 205.0), row(1.0, LogCol.CAR_NOMINAL_KWH to 205.0)), left = TrendMeasure.CAR_NOMINAL).left.axis
    assertTrue(a.max > a.min)
    assertTrue("a flat 205 kWh is not drawn on a scale of tenths", a.max - a.min >= 2.0)
    assertEquals(0, TrendsModel.chart(listOf(row(1.0, LogCol.OUTSIDE_C to 25.0)), TrendRange.DAY, TrendMeasure.OUTSIDE, TrendMeasure.SOC, TempUnit.F, DistUnit.MI, now).left.axis.decimals)
    assertTrue(a.ticks.size >= 2)
  }

  @Test
  fun niceAxisUsesRoundSteps() {
    val a = TrendsModel.niceAxis(203.2, 204.1)
    assertTrue(a.min <= 203.2 && a.max >= 204.1)
    val steps = a.ticks.zipWithNext { x, y -> y - x }
    assertEquals(1, steps.map { Math.round(it * 1000) }.distinct().size)
    assertEquals(1, a.decimals)
    assertEquals(0, TrendsModel.niceAxis(10.0, 90.0).decimals)
    assertEquals(3, TrendsModel.niceAxis(3.671, 3.679).decimals)
  }

  @Test
  fun rowsOutsideTheRangeAreLeftOut() {
    val rows = listOf(row(48.0, LogCol.SOC_PCT to 80.0), row(1.0, LogCol.SOC_PCT to 40.0))
    assertEquals(1, chart(rows, range = TrendRange.DAY).left.segments.flatten().size)
    assertEquals(2, chart(rows, range = TrendRange.ALL).left.segments.flatten().size)
  }

  @Test
  fun aLongHistoryIsThinnedButItsScaleKeepsTheExtremes() {
    val rows = (0 until 5000).map { i -> row((5000 - i) / 7.0, LogCol.SOC_PCT to if (i == 2500) 5.0 else 50.0) }
    val s = chart(rows, range = TrendRange.ALL).left
    assertTrue(s.segments.sumOf { it.size } <= TrendsModel.MAX_POINTS + 1)
    assertEquals(5.0, s.segments.flatten().minOf { it.low }, 0.0)
    assertTrue("a one-row dip stays on the scale", s.axis.min <= 5.0)
  }

  @Test
  fun nearestAndTheReadout() {
    val s = chart(listOf(row(3.0, LogCol.SOC_PCT to 80.0), row(1.0, LogCol.SOC_PCT to 40.0))).left
    val p = TrendsModel.nearest(s, now - 1 * hour - 60_000, hour)!!
    assertEquals(40.0, p.mean, 0.0)
    assertEquals("SoC 40.0 %", TrendsModel.valueText(s, p))
    assertNull(TrendsModel.nearest(s, now - 100 * hour, hour))
  }

  @Test
  fun emptyLogStillDrawsAChart() {
    val c = chart(emptyList(), range = TrendRange.ALL)
    assertFalse(c.left.hasData)
    assertEquals(24 * hour, c.endMs - c.startMs)
    assertEquals("Nothing logged yet", TrendsModel.summary(0, null, now))
    assertEquals("12 rows · newest 2 h ago", TrendsModel.summary(12, now - 2 * hour, now))
    assertEquals("1 row · newest 2 h ago", TrendsModel.summary(1, now - 2 * hour, now))
    assertEquals("12 rows · newest 12 min ago", TrendsModel.summary(12, now - 12 * 60_000, now))
    assertEquals("12 rows · newest now", TrendsModel.summary(12, now - 1_000, now))
  }

  @Test
  fun timeTicksEndAtNow() {
    val c = chart(emptyList(), range = TrendRange.MONTH)
    assertEquals("30 d ago", c.timeTicks.first().label)
    assertEquals("now", c.timeTicks.last().label)
  }

  // --- The catalog ----------------------------------------------------------------------------------

  private val moduleNames = mapOf("becm" to "BECM", "dmcm" to "Drive unit", "dmc2" to "Drive unit 2", "bcm" to "BCM")

  @Test
  fun everyLoggedColumnIsInTheList() {
    val listed = TrendMeasure.entries.flatMap { listOfNotNull(it.col, it.high) }.toSet()
    // car_capacity_kwh is one of the two capacities, which have their own lines; its flag says which.
    assertEquals(setOf(LogCol.CAR_CAPACITY_KWH, LogCol.CAR_CAPACITY_NOMINAL), LogCol.entries.toSet() - listed)
  }

  @Test
  fun everyGroupFitsTheListWithoutScrolling() {
    for (source in LogSource.entries) {
      for (group in TrendMeasure.groups(source)) {
        val n = TrendMeasure.of(source).count { it.group == group }
        assertTrue("$source $group has $n", n <= DashLayout.TREND_MAX_ROWS)
      }
    }
  }

  @Test
  fun aCarMeasureNamesThePropertyItReads() {
    for (m in TrendMeasure.of(LogSource.CAR).filter { it.property != null }) assertEquals(KnownProperties.byId[m.property]?.name, m.pid)
  }

  @Test
  fun anMxMeasureNamesTheDidItIsReadFrom() {
    val fieldOf = (LogRows.MX_FIELDS + LogRows.MX_MILE_FIELDS + LogRows.MX_HEX_FIELDS).entries.associate { (field, col) -> col to field } + mapOf(
      LogCol.SOC_PCT to "soc",
      LogCol.ENERGY_KWH to "energy_remaining",
      LogCol.CAPACITY_KWH to "battery_capability",
      LogCol.BECM_27AE_KWH to "battery_capability",
      LogCol.CAPACITY_AH to "pack_capacity_ah",
      LogCol.BECM_4676_AH to "pack_capacity_ah",
      LogCol.MODULE_TEMP_MIN_C to "module_temps",
    )
    val fields = TestSupport.profile.didFields.associateBy { it.name }
    var checked = 0
    for (m in TrendMeasure.of(LogSource.MX)) {
      val field = fields[fieldOf[m.col] ?: continue] ?: error("no profile field for ${m.name}")
      assertEquals(m.name, "${moduleNames.getValue(field.module)} ${"%04X".format(field.did)}", m.pid)
      checked += 1
    }
    assertTrue("the PIDs read as themselves, not only the calculated lines: $checked", checked >= 28)
  }

  @Test
  fun onlyStatesAndCodesStayOffTheChart() {
    assertEquals(
      setOf(TrendMeasure.CAR_CHARGE_STATE, TrendMeasure.CAR_PLUGGED, TrendMeasure.CAR_PORT_OPEN, TrendMeasure.CAR_IGNITION, TrendMeasure.CAR_GEAR, TrendMeasure.CAR_REGEN, TrendMeasure.CHARGE_CODE),
      TrendMeasure.entries.filterNot { it.plottable }.toSet(),
    )
  }

  @Test
  fun aDefaultChartCanBeDrawnAndIsOfItsOwnSource() {
    for (source in LogSource.entries) {
      val p = TrendsModel.defaultPick(source)
      assertEquals(source, p.left.source)
      assertEquals(source, p.right.source)
      assertTrue(p.left.plottable && p.right.plottable && p.left != p.right)
    }
  }

  @Test
  fun aMeasureOnOneSideSwapsRatherThanSitsOnBoth() {
    val p = ChartPick(TrendMeasure.CAPACITY_BMS, TrendMeasure.AH_BMS)
    assertEquals(ChartPick(TrendMeasure.AH_BMS, TrendMeasure.CAPACITY_BMS), p.withLeft(TrendMeasure.AH_BMS))
    assertEquals(ChartPick(TrendMeasure.AH_BMS, TrendMeasure.CAPACITY_BMS), p.withRight(TrendMeasure.CAPACITY_BMS))
    assertEquals(ChartPick(TrendMeasure.SOC, TrendMeasure.AH_BMS), p.withLeft(TrendMeasure.SOC))
    assertEquals(ChartPick(TrendMeasure.CAPACITY_BMS, TrendMeasure.SOC), p.withRight(TrendMeasure.SOC))
  }

  // --- Tiles, stats and the PID list ----------------------------------------------------------------

  private fun mx(vararg rows: LogRow, problems: Map<Int, ReadProblem> = emptyMap()) = data(LogSource.MX, rows.toList(), problems)

  private fun car(vararg rows: LogRow, problems: Map<Int, ReadProblem> = emptyMap()) = data(LogSource.CAR, rows.toList(), problems)

  private fun data(source: LogSource, rows: List<LogRow>, problems: Map<Int, ReadProblem>) =
    TrendData(source, TrendsModel.defaultPick(source), rows, rows.size.toLong(), rows.lastOrNull()?.atMs, now, false, TrendsModel.latest(rows), problems)

  private fun tiles(d: TrendData, range: TrendRange = TrendRange.MONTH) = TrendsModel.tiles(d, range, TempUnit.F, DistUnit.MI).associateBy { it.measure }

  private fun list(d: TrendData, group: TrendGroup, dist: DistUnit = DistUnit.MI) = TrendsModel.pidRows(d, group, TempUnit.F, dist).associateBy { it.measure }

  @Test
  fun anMxCapacityTileSaysItsPidAndHowMuchItChanged() {
    val t = tiles(mx(row(100.0, LogCol.CAPACITY_KWH to 208.82, source = LogSource.MX), row(1.0, LogCol.CAPACITY_KWH to 208.44, source = LogSource.MX))).getValue(TrendMeasure.CAPACITY_BMS)
    assertEquals("208.44", t.value)
    assertEquals("kWh", t.unit)
    assertEquals("Total capacity", t.title)
    assertEquals(TrendBadge.UNVERIFIED, t.badge)
    assertEquals("BECM 27AE", t.line)
    assertEquals("−0.38 kWh in 30 d", t.change)
  }

  @Test
  fun aTileWithOneRowClaimsNoChange() {
    assertNull(tiles(mx(row(1.0, LogCol.CAPACITY_KWH to 208.44, source = LogSource.MX))).getValue(TrendMeasure.CAPACITY_BMS).change)
  }

  @Test
  fun aChangeOverEverythingDoesNotNameARange() {
    val d = mx(row(2000.0, LogCol.CAPACITY_KWH to 209.0, source = LogSource.MX), row(1.0, LogCol.CAPACITY_KWH to 208.0, source = LogSource.MX))
    assertEquals("−1.00 kWh overall", tiles(d, TrendRange.ALL).getValue(TrendMeasure.CAPACITY_BMS).change)
  }

  @Test
  fun aRejectedCapacitySaysSoAndWhatWasRead() {
    val t = tiles(mx(row(1.0, LogCol.BECM_27AE_KWH to 90.0, source = LogSource.MX))).getValue(TrendMeasure.CAPACITY_BMS)
    assertNull(t.value)
    assertEquals("BECM 27AE failed its check: 90.00 kWh as read", t.line)
  }

  @Test
  fun theDeviationTileSaysItsPidIsNotKnownUntilItIs() {
    val none = mx(row(1.0, LogCol.SOC_PCT to 42.0, source = LogSource.MX))
    assertEquals("PID not identified yet", tiles(none).getValue(TrendMeasure.AH_DEVIATION).line)
    val listed = list(none, TrendGroup.CAPACITY).getValue(TrendMeasure.AH_DEVIATION)
    assertEquals(TrendBadge.NO_PID, listed.badge)
    assertEquals("DID not identified", listed.detail)
    val found = list(mx(row(1.0, LogCol.AH_DEVIATION to 22.0, source = LogSource.MX)), TrendGroup.CAPACITY).getValue(TrendMeasure.AH_DEVIATION)
    assertEquals("22", found.value)
    assertNull(found.badge)
  }

  @Test
  fun theFixedNominalCapacitySaysSoAndTheMissingUsableOneSaysWhy() {
    val rows = arrayOf(row(100.0, LogCol.CAR_NOMINAL_KWH to 205.0), row(1.0, LogCol.CAR_NOMINAL_KWH to 205.0))
    val t = tiles(car(*rows, problems = mapOf(KnownProperties.EV_CURRENT_BATTERY_CAPACITY to ReadProblem.NOT_SUPPORTED)))
    assertEquals("INFO_EV_BATTERY_CAPACITY", t.getValue(TrendMeasure.CAR_NOMINAL).line)
    assertEquals("no change, this figure is fixed", t.getValue(TrendMeasure.CAR_NOMINAL).change)
    assertNull("the words say it, and the title needs the room", t.getValue(TrendMeasure.CAR_NOMINAL).badge)
    assertEquals(TrendBadge.FIXED, list(car(*rows), TrendGroup.CAPACITY).getValue(TrendMeasure.CAR_NOMINAL).badge)
    assertNull(t.getValue(TrendMeasure.CAR_USABLE).value)
    assertEquals("EV_CURRENT_BATTERY_CAPACITY: not offered by this truck", t.getValue(TrendMeasure.CAR_USABLE).line)
  }

  @Test
  fun aMissingPidSaysWhyAsFarAsThatIsKnown() {
    assertEquals("Not offered by this truck", TrendsModel.missingText(TrendMeasure.CAR_USABLE, mapOf(KnownProperties.EV_CURRENT_BATTERY_CAPACITY to ReadProblem.NOT_SUPPORTED)))
    assertEquals("Permission not granted", TrendsModel.missingText(TrendMeasure.CAR_USABLE, mapOf(KnownProperties.EV_CURRENT_BATTERY_CAPACITY to ReadProblem.NO_PERMISSION)))
    assertEquals("Unavailable right now", TrendsModel.missingText(TrendMeasure.CAR_USABLE, mapOf(KnownProperties.EV_CURRENT_BATTERY_CAPACITY to ReadProblem.UNAVAILABLE)))
    assertEquals("No reading logged", TrendsModel.missingText(TrendMeasure.CAR_USABLE, emptyMap()))
    assertEquals("No reading logged", TrendsModel.missingText(TrendMeasure.SOC, emptyMap()))
  }

  @Test
  fun theListShowsEachValueInItsOwnWay() {
    val d = mx(
      row(
        2.0,
        LogCol.PACK_A to 1.14,
        LogCol.BMS_RANGE_KM to 160.9344,
        LogCol.MODULE_TEMP_MIN_C to 20.0,
        LogCol.MODULE_TEMP_MAX_C to 25.0,
        LogCol.CELL_MIN_GROUP to 41.0,
        LogCol.CHARGE_STATE_CODE to 0.0,
        LogCol.RAW_2770 to 50.0,
        source = LogSource.MX,
      ),
    )
    val power = list(d, TrendGroup.POWER).getValue(TrendMeasure.PACK_CURRENT)
    assertEquals("+1.1", power.value)
    assertEquals("Drive unit 2414 · 2 h ago", power.detail)
    assertTrue(power.chartable)
    assertEquals("100", list(d, TrendGroup.ENERGY).getValue(TrendMeasure.BMS_RANGE).value)
    assertEquals("mi", list(d, TrendGroup.ENERGY).getValue(TrendMeasure.BMS_RANGE).unit)
    assertEquals("161", list(d, TrendGroup.ENERGY, DistUnit.KM).getValue(TrendMeasure.BMS_RANGE).value)
    assertEquals("68–77", list(d, TrendGroup.TEMPS).getValue(TrendMeasure.MODULE_TEMPS).value)
    assertEquals("2AE1–2AF0 · 2 h ago · module 6", list(d, TrendGroup.CELLS).getValue(TrendMeasure.CELL_LOW_GROUP).detail)
    val code = list(d, TrendGroup.CHARGING).getValue(TrendMeasure.CHARGE_CODE)
    assertEquals("0", code.value)
    assertFalse("a code is not a measure", code.chartable)
    val raw = list(d, TrendGroup.UNKNOWN).getValue(TrendMeasure.RAW_2770)
    assertEquals("50", raw.value)
    assertEquals("0x32", raw.unit)
    assertEquals("BECM 2770 · 2 h ago · meaning unknown", raw.detail)
    val none = list(d, TrendGroup.ENERGY).getValue(TrendMeasure.SOC)
    assertNull(none.value)
    assertFalse("nothing to draw", none.chartable)
    assertEquals("No reading logged", none.missing)
  }

  @Test
  fun aStateReadsAsWords() {
    val d = car(row(1.0, LogCol.CAR_CHARGE_STATE to 3.0, LogCol.CAR_PLUGGED to 1.0, LogCol.CAR_IGNITION to 99.0))
    assertEquals("Not charging", list(d, TrendGroup.CHARGING).getValue(TrendMeasure.CAR_CHARGE_STATE).value)
    assertEquals("Yes", list(d, TrendGroup.CHARGING).getValue(TrendMeasure.CAR_PLUGGED).value)
    assertEquals("an unknown code still shows", "99", list(d, TrendGroup.TRUCK).getValue(TrendMeasure.CAR_IGNITION).value)
    assertFalse(list(d, TrendGroup.CHARGING).getValue(TrendMeasure.CAR_CHARGE_STATE).chartable)
  }

  @Test
  fun theNewestValueComesFromTheNewestRowThatHasOne() {
    val latest = TrendsModel.latest(listOf(row(3.0, LogCol.SOC_PCT to 80.0, LogCol.CAPACITY_KWH to 208.0), row(1.0, LogCol.SOC_PCT to 40.0)))
    assertEquals(40.0, latest.getValue(LogCol.SOC_PCT).v, 0.0)
    assertEquals(208.0, latest.getValue(LogCol.CAPACITY_KWH).v, 0.0)
    assertEquals(now - 3 * hour, latest.getValue(LogCol.CAPACITY_KWH).atMs)
  }

  @Test
  fun statsCoverEveryRowInRangeEvenWhenTheChartThinsThem() {
    val rows = (0 until 5000).map { i -> row((5000 - i) / 7.0, LogCol.SOC_PCT to if (i == 2500) 5.0 else 50.0 + i / 1000.0) }
    val s = chart(rows, range = TrendRange.ALL).left
    val stats = s.stats!!
    assertEquals(5000, stats.rows)
    assertEquals(5.0, stats.low, 0.0)
    assertEquals(4.999, stats.change, 1e-9)
    assertEquals("low 5.0 · high 55.0 · change +5.0 % · 5000 rows", TrendsModel.statsText(s))
    assertEquals("no rows in this range", TrendsModel.statsText(chart(emptyList()).left))
  }

  @Test
  fun aPageLetsGoOfItsRows() {
    val p = TrendsModel.page(mx(row(2.0, LogCol.SOC_PCT to 40.0, source = LogSource.MX)), TrendRange.MONTH, TempUnit.C, DistUnit.MI)
    assertTrue(p.data.rows.isEmpty())
    assertEquals(1, p.chart.left.stats?.rows ?: 0 + 1)
    assertEquals(4, p.tiles.size)
  }

  @Test
  fun aChartLoadsOnlyTheColumnsItNeedsAndTheListAllOfItsSource() {
    val cols = TrendsModel.chartColumns(LogSource.MX, ChartPick(TrendMeasure.MODULE_TEMPS, TrendMeasure.SOC))
    assertTrue(LogCol.MODULE_TEMP_MAX_C in cols && LogCol.SOC_PCT in cols && LogCol.CAPACITY_KWH in cols)
    assertFalse(LogCol.PACK_V in cols)
    assertTrue(LogCol.PACK_V in TrendsModel.loggedColumns(LogSource.MX))
    assertFalse(LogCol.OUTSIDE_C in TrendsModel.loggedColumns(LogSource.MX))
  }

  @Test
  fun theHeaderSaysWhetherMoreRowsAreComing() {
    assertEquals("reading now", TrendsModel.sourceText(LogSource.MX, LinkStatus.CONNECTED))
    assertEquals("MX+ disconnected", TrendsModel.sourceText(LogSource.MX, LinkStatus.DISCONNECTED))
    assertEquals("no adapter needed", TrendsModel.sourceText(LogSource.CAR, LinkStatus.DISCONNECTED))
  }

  @Test
  fun theLegendNamesTheMeasureUntilATapShowsItsValue() {
    val s = chart(listOf(row(1.0, LogCol.SOC_PCT to 40.0))).left
    assertEquals("State of charge (%)", TrendsModel.legendText(s, null, tapped = false, short = false))
    assertEquals("SoC (%)", TrendsModel.legendText(s, null, tapped = false, short = true))
    assertEquals("SoC 40.0 %", TrendsModel.legendText(s, s.segments[0][0], tapped = true, short = false))
    assertEquals("SoC: no row here", TrendsModel.legendText(s, null, tapped = true, short = false))
  }

  @Test
  fun demoRowsFillTheScreenExceptWhatTheTruckDoesNotOffer() {
    val rows = DemoLog.rows(now)
    for (source in LogSource.entries) {
      val latest = TrendsModel.latest(rows.filter { it.source == source })
      val empty = TrendMeasure.of(source).filter { it.col !in latest }.toSet()
      val expected = if (source == LogSource.CAR) setOf(TrendMeasure.CAR_USABLE, TrendMeasure.CAR_BATTERY_TEMP, TrendMeasure.CAR_CHARGE_TIME) else setOf(TrendMeasure.AH_DEVIATION)
      assertEquals(expected, empty)
    }
  }
}
