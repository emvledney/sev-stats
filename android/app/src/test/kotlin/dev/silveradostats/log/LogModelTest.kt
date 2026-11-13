package dev.silveradostats.log

import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.LiveValue
import dev.silveradostats.obd.TestSupport
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogModelTest {
  private val at = 1_800_000_000_000L
  private val mono = 500_000L

  private fun value(name: String, v: Any?, periodS: Double = 2.0, ageMs: Long = 0) = name to LiveValue(name, name, v, "", "confirmed", periodS, mono - ageMs)

  private fun live(status: LinkStatus = LinkStatus.CONNECTED, demo: Boolean = false, extra: Map<String, LiveValue> = emptyMap(), derived: Map<String, Any> = DERIVED) = LiveState(
    status = status,
    demo = demo,
    values = mapOf(
      value("soc", 42.0),
      value("energy_remaining", 85.3, 30.0),
      value("battery_capability", 208.44, 30.0),
      value("pack_capacity_ah", 602.2, 60.0),
      value("cell_min_a", 3.67),
      value("pack_current", 1.0, 1.0),
      value("module_temps", listOf(16.0, 17.0, 18.0, -80.0), 10.0),
    ) + extra,
    derived = derived,
  )

  private fun row(s: LiveState) = LogRows.fromLive(s, mono, at, -300, LogTrigger.MX_SCREEN)

  /** For the upload (owner, 2026-11-13): every cell group and every module, position by position. */
  @Test
  fun mxRowCarriesEveryCellGroupAndModule() {
    val cells = mapOf(
      value("cells_001_012", listOf(3.701, 3.702, 0.0, 3.704), 30.0),
      value("cells_013_024", listOf(3.705, "x", 0.0, 0.0), 30.0),
    )
    val r = row(live(extra = cells))!!
    assertEquals("padding is no reading, and none trails", listOf(3.701, 3.702, null, 3.704, 3.705), r.arrays[LogRows.ARRAY_CELL_V])
    assertEquals("one figure per module: the mean of its two sensors", listOf(16.5, 18.0), r.arrays[LogRows.ARRAY_MODULE_T])
    // Without a fresh read there is no array, never an old one.
    val stale = row(live(extra = mapOf(value("cells_001_012", listOf(3.7, 3.7), 30.0, ageMs = 10 * 60_000L))))!!
    assertNull(stale.arrays[LogRows.ARRAY_CELL_V])
    assertTrue(LogRows.fromCar(CarReading(levelWh = 1.0), at, 0, LogTrigger.JOB)!!.arrays.isEmpty())
  }

  @Test
  fun carRowComputesSocFromTheCapacityAndSaysWhichCapacity() {
    val nominal = LogRows.fromCar(CarReading(levelWh = 85_000.0, nominalWh = 205_000.0, outsideC = 11.5, plugged = true), at, -300, LogTrigger.JOB)!!
    assertEquals(205.0, nominal[LogCol.CAR_CAPACITY_KWH]!!, 1e-9)
    assertEquals(1.0, nominal[LogCol.CAR_CAPACITY_NOMINAL]!!, 0.0)
    assertEquals(85_000.0 / 205_000.0 * 100, nominal[LogCol.CAR_SOC_PCT]!!, 1e-9)
    assertEquals(11.5, nominal[LogCol.OUTSIDE_C]!!, 0.0)
    assertEquals(1.0, nominal[LogCol.CAR_PLUGGED]!!, 0.0)
    assertEquals(LogSource.CAR, nominal.source)

    val usable = LogRows.fromCar(CarReading(levelWh = 85_000.0, usableWh = 200_000.0, nominalWh = 205_000.0), at, 0, LogTrigger.JOB)!!
    assertEquals(200.0, usable[LogCol.CAR_CAPACITY_KWH]!!, 1e-9)
    assertEquals(0.0, usable[LogCol.CAR_CAPACITY_NOMINAL]!!, 0.0)
  }

  @Test
  fun carRowKeepsTheTwoCapacitiesApart() {
    val nominalOnly = LogRows.fromCar(CarReading(nominalWh = 205_000.0), at, 0, LogTrigger.JOB)!!
    assertEquals(205.0, nominalOnly[LogCol.CAR_NOMINAL_KWH]!!, 1e-9)
    assertNull("this truck offers no usable capacity: never filled in from the nominal one", nominalOnly[LogCol.CAR_USABLE_KWH])

    val both = LogRows.fromCar(CarReading(usableWh = 200_000.0, nominalWh = 205_000.0), at, 0, LogTrigger.JOB)!!
    assertEquals(200.0, both[LogCol.CAR_USABLE_KWH]!!, 1e-9)
    assertEquals(205.0, both[LogCol.CAR_NOMINAL_KWH]!!, 1e-9)
  }

  @Test
  fun carRowHoldsEveryPropertyInTheUnitItsColumnSays() {
    val r = LogRows.fromCar(
      CarReading(
        chargeRateMw = -3_500_000.0,
        batteryTempC = 21.0,
        chargeState = 3.0,
        portOpen = false,
        chargeLimitPct = 80.0,
        drawLimitA = 48.0,
        chargeTimeS = 5400.0,
        regenState = 3.0,
        ignition = 4.0,
        gear = 4.0,
        speedMs = 10.0,
      ),
      at,
      0,
      LogTrigger.CAR_SCREEN,
    )!!
    assertEquals(-3.5, r[LogCol.CAR_CHARGE_KW]!!, 1e-9)
    assertEquals(21.0, r[LogCol.CAR_BATTERY_TEMP_C]!!, 0.0)
    assertEquals(3.0, r[LogCol.CAR_CHARGE_STATE]!!, 0.0)
    assertEquals(0.0, r[LogCol.CAR_PORT_OPEN]!!, 0.0)
    assertEquals(80.0, r[LogCol.CAR_CHARGE_LIMIT_PCT]!!, 0.0)
    assertEquals(48.0, r[LogCol.CAR_DRAW_LIMIT_A]!!, 0.0)
    assertEquals(90.0, r[LogCol.CAR_CHARGE_MINUTES]!!, 1e-9)
    assertEquals(3.0, r[LogCol.CAR_REGEN_STATE]!!, 0.0)
    assertEquals(4.0, r[LogCol.CAR_IGNITION]!!, 0.0)
    assertEquals(4.0, r[LogCol.CAR_GEAR]!!, 0.0)
    assertEquals(36.0, r[LogCol.CAR_SPEED_KMH]!!, 1e-9)
    assertEquals(LogTrigger.CAR_SCREEN, LogTrigger.of("car_screen"))
  }

  @Test
  fun carRowWithNothingReadIsNoRow() {
    assertNull(LogRows.fromCar(CarReading(), at, 0, LogTrigger.JOB))
    assertNull(LogRows.fromCar(CarReading(outsideC = Double.NaN, levelWh = -1.0), at, 0, LogTrigger.JOB))
  }

  @Test
  fun mxRowHoldsEveryRequestedValue() {
    val r = row(live())!!
    assertEquals(LogSource.MX, r.source)
    assertEquals(42.0, r[LogCol.SOC_PCT]!!, 0.0)
    assertEquals(85.3, r[LogCol.ENERGY_KWH]!!, 0.0)
    assertEquals(208.44, r[LogCol.CAPACITY_KWH]!!, 0.0)
    assertEquals(203.1, r[LogCol.IMPLIED_KWH]!!, 0.0)
    assertEquals(602.2, r[LogCol.CAPACITY_AH]!!, 0.0)
    assertEquals(588.0, r[LogCol.NOMINAL_AH]!!, 0.0)
    assertEquals("padding (-80) is not a temperature", 16.0, r[LogCol.MODULE_TEMP_MIN_C]!!, 0.0)
    assertEquals(18.0, r[LogCol.MODULE_TEMP_MAX_C]!!, 0.0)
    assertEquals(3.67, r[LogCol.CELL_MIN_V]!!, 0.0)
    assertEquals(3.68, r[LogCol.CELL_MAX_V]!!, 0.0)
    assertEquals(0.4, r[LogCol.PACK_KW]!!, 0.0)
  }

  @Test
  fun rawCapacityReadsAreKeptEvenWhenTheChecksReject() {
    val r = row(live(derived = DERIVED - "capacity_kwh" - "capacity_ah"))!!
    assertNull(r[LogCol.CAPACITY_KWH])
    assertNull(r[LogCol.CAPACITY_AH])
    assertEquals(208.44, r[LogCol.BECM_27AE_KWH]!!, 0.0)
    assertEquals(602.2, r[LogCol.BECM_4676_AH]!!, 0.0)
  }

  @Test
  fun ahDeviationIsLoggedOnlyOnceItsProfileFieldExists() {
    assertNull(row(live())!![LogCol.AH_DEVIATION])
    val found = row(live(extra = mapOf(value(LogRows.AH_DEVIATION_FIELD, 22.0, 60.0))))!!
    assertEquals(22.0, found[LogCol.AH_DEVIATION]!!, 0.0)
    val raw = row(live(extra = mapOf(value("unknown_2770_raw", "32", 60.0))))!!
    assertEquals(50.0, raw[LogCol.RAW_2770]!!, 0.0)
    assertNull("an unidentified DID is logged as itself, not as the deviation", raw[LogCol.AH_DEVIATION])
  }

  @Test
  fun everySingleValuePidOfTheProfileHasAColumn() {
    val special = setOf(
      // Their own columns, in fromLive.
      "soc", "energy_remaining", "battery_capability", "pack_capacity_ah", LogRows.AH_DEVIATION_FIELD, "cell_min_a", "cell_min_b", "cell_max_a", "cell_max_b",
      // The same groups are logged from the 192-cell array.
      "cell_min_index_a", "cell_min_index_b", "cell_min_module_a", "cell_min_module_b", "cell_max_index_a", "cell_max_index_b", "cell_max_module_a", "cell_max_module_b",
    )
    val tables = LogRows.MX_FIELDS.keys + LogRows.MX_MILE_FIELDS.keys + LogRows.MX_HEX_FIELDS.keys
    val fields = TestSupport.profile.didFields.filter { it.count == 1 }.map { it.name }.toSet()
    assertEquals("a table names a field the profile does not have", emptySet<String>(), tables - fields)
    // 2AF8, 2AF9 and the temperature stats are records of several values, kept out of a numeric column.
    val records = setOf("unknown_2af8_raw", "unknown_2af9_raw", "temp_stats_a_raw", "temp_stats_b_raw")
    assertEquals("a PID that is read but not logged", emptySet<String>(), fields - tables - special - records)
  }

  @Test
  fun mxRowHoldsEveryPidInTheUnitItsColumnSays() {
    val asRead = LogRows.MX_FIELDS.keys.withIndex().map { (i, name) -> value(name, 100.0 + i, 10.0) }.toMap()
    val r = row(live(extra = asRead + mapOf(value("range_estimate", 100.0, 30.0), value("distance_since_full", 10.0, 60.0), value("cells_001_012", listOf(3.7), 5.0)), derived = DERIVED + mapOf("cell_spread_mv" to 11.7, "array_min_group" to 41, "array_max_group" to 81)))!!
    LogRows.MX_FIELDS.values.forEachIndexed { i, col -> assertEquals(col.key, 100.0 + i, r[col]!!, 0.0) }
    assertEquals(160.9344, r[LogCol.BMS_RANGE_KM]!!, 1e-9)
    assertEquals(16.09344, r[LogCol.SINCE_FULL_KM]!!, 1e-9)
    assertEquals(11.7, r[LogCol.CELL_SPREAD_MV]!!, 0.0)
    assertEquals(41.0, r[LogCol.CELL_MIN_GROUP]!!, 0.0)
    assertEquals(81.0, r[LogCol.CELL_MAX_GROUP]!!, 0.0)
  }

  @Test
  fun hexFieldsAreLoggedAsTheirNumberUnlessTheyAreRecords() {
    val r = row(live(extra = mapOf(value("unknown_27b9_raw", "00 15 66", 60.0), value("unknown_2984_raw", "not hex", 60.0), value("unknown_270d_raw", "01 02 03 04 05", 60.0))))!!
    assertEquals(0x1566.toDouble(), r[LogCol.RAW_27B9]!!, 0.0)
    assertNull(r[LogCol.RAW_2984])
    assertNull(r[LogCol.RAW_270D])
    val stale = row(live(extra = mapOf(value("unknown_27b9_raw", "00 15 66", 60.0, ageMs = 600_000))))!!
    assertNull(stale[LogCol.RAW_27B9])
  }

  @Test
  fun demoOrAnUnconnectedLinkIsNeverLogged() {
    assertNotNull(row(live()))
    assertNull(row(live(demo = true)))
    assertNull(row(live(status = LinkStatus.DEMO)))
    for (s in LinkStatus.entries.filter { it != LinkStatus.CONNECTED }) assertNull(s.name, row(live(status = s)))
  }

  @Test
  fun aStaleReadingIsLeftOutNotCarriedOver() {
    assertNull("SoC 3 periods old means no row", row(live(extra = mapOf(value("soc", 42.0, 2.0, ageMs = 60_000)))))
    val staleTemps = row(live(extra = mapOf(value("module_temps", listOf(16.0, 17.0), 10.0, ageMs = 120_000))))!!
    assertNull(staleTemps[LogCol.MODULE_TEMP_MIN_C])
    val staleCells = row(live(extra = mapOf(value("cell_min_a", 3.67, 2.0, ageMs = 60_000))))!!
    assertNull(staleCells[LogCol.CELL_MIN_V])
    val staleCurrent = row(live(extra = mapOf(value("pack_current", 1.0, 1.0, ageMs = 60_000))))!!
    assertNull(staleCurrent[LogCol.PACK_KW])
  }

  @Test
  fun anUnsetClockIsNotValid() {
    assertFalse(LogRows.wallClockValid(0L))
    assertFalse(LogRows.wallClockValid(LogRows.MIN_VALID_WALL_MS - 1))
    assertTrue(LogRows.wallClockValid(at))
  }

  @Test
  fun aVisitNeedsThePositiveIgnitionOn() {
    fun visit(ignition: Int?, demo: Boolean = false, chosen: Boolean = true, off: Boolean = false, last: Long? = null) =
      LogPolicy.visitAllowed(ignition, demo, chosen, off, last, at)
    assertTrue(visit(4))
    assertTrue(visit(5))
    assertFalse("unknown ignition is no visit", visit(null))
    for (state in listOf(0, 1, 2, 3)) assertFalse("ignition $state", visit(state))
    assertFalse(visit(4, demo = true))
    assertFalse(visit(4, chosen = false))
    assertFalse("the user disconnected the MX+", visit(4, off = true))
    assertFalse("a recent row makes a visit pointless", visit(4, last = at - 5 * 60_000))
    assertTrue(visit(4, last = at - LogPolicy.JOB_PERIOD_MS))
    assertTrue("a clock that went backwards must not stop the log", visit(4, last = at + 60_000))
  }

  @Test
  fun rowCadence() {
    assertTrue(LogPolicy.mxRowDue(null, at))
    assertFalse(LogPolicy.mxRowDue(at - 59_000, at))
    assertTrue(LogPolicy.mxRowDue(at - 60_000, at))
    assertTrue(LogPolicy.mxRowDue(at + 1, at))
    assertFalse(LogPolicy.carRowDue(at - 10 * 60_000, at))
    assertTrue(LogPolicy.carRowDue(at - 61 * 60_000, at))
    assertTrue(LogPolicy.carScreenRowDue(null, at))
    assertFalse(LogPolicy.carScreenRowDue(at - 4 * 60_000, at))
    assertTrue(LogPolicy.carScreenRowDue(at - 5 * 60_000, at))
    assertTrue("a clock that went backwards must not stop the log", LogPolicy.carScreenRowDue(at + 1, at))
  }

  @Test
  fun csvHasOneColumnPerValueAndEmptyCellsForUnknowns() {
    val out = StringBuilder()
    LogCsv.appendTo(listOf(LogRow(at, -300, LogSource.MX, LogTrigger.MX_VISIT, mapOf(LogCol.CELL_MIN_V to 3.67291, LogCol.SOC_PCT to 42.0))), out)
    val (header, line) = out.toString().trimEnd().split("\n")
    val names = header.split(",")
    val cells = line.split(",")
    assertEquals(names.size, cells.size)
    assertEquals("2027-01-15T08:00:00Z", cells[names.indexOf("time_utc")])
    assertEquals("-300", cells[names.indexOf("tz_minutes")])
    assertEquals("mx", cells[names.indexOf("source")])
    assertEquals("mx_visit", cells[names.indexOf("trigger")])
    assertEquals("3.6729", cells[names.indexOf("cell_min_v")])
    assertEquals("42", cells[names.indexOf("soc_pct")])
    assertEquals("", cells[names.indexOf("capacity_kwh")])
  }

  @Test
  fun modelsNeverOverlapWithDemoStorage() {
    // Demo rows exist only in memory, for the Trends tab; they look like any other row to the chart.
    val rows = DemoLog.rows(at)
    assertTrue(rows.isNotEmpty())
    val c = TrendsModel.chart(rows, TrendRange.MONTH, TrendMeasure.SOC, TrendMeasure.MODULE_TEMPS, TempUnit.F, DistUnit.MI, at)
    assertTrue(c.left.hasData && c.right.hasData)
  }

  private companion object {
    val DERIVED: Map<String, Any> = mapOf(
      "capacity_kwh" to 208.44,
      "bms_energy_at_100_kwh" to 203.1,
      "capacity_ah" to 602.2,
      "ah_nominal_est" to 588.0,
      "cell_min_v" to 3.67,
      "cell_max_v" to 3.68,
      "power_kw" to 0.4,
    )
  }
}
