package dev.silveradostats.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownPropertiesTest {

  private val vinId = 0x11100100

  @Test
  fun idsAndNamesUnique() {
    val ids = KnownProperties.all.map { it.id }
    assertEquals("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.toSet().size)
    val names = KnownProperties.all.map { it.name }
    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun specIdsPresentWithExactValues() {
    val expected = mapOf(
      "EV_BATTERY_LEVEL" to 0x11600309, "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
      "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030C,
      "EV_BATTERY_AVERAGE_TEMPERATURE" to 0x1160030E, "EV_CHARGE_STATE" to 0x11400F41,
      "EV_CHARGE_PORT_OPEN" to 0x1120030A, "EV_CHARGE_PORT_CONNECTED" to 0x1120030B,
      "EV_CHARGE_CURRENT_DRAW_LIMIT" to 0x11600F3F, "EV_CHARGE_PERCENT_LIMIT" to 0x11600F40,
      "EV_CHARGE_TIME_REMAINING" to 0x11400F43, "EV_REGENERATIVE_BRAKING_STATE" to 0x11400F44,
      "EV_BRAKE_REGENERATION_LEVEL" to 0x1140040C, "EV_STOPPING_MODE" to 0x1140040D,
      "EV_BATTERY_DISPLAY_UNITS" to 0x11400603, "RANGE_REMAINING" to 0x11600308,
      "ENV_OUTSIDE_TEMPERATURE" to 0x11600703, "PERF_VEHICLE_SPEED" to 0x11600207,
      "PERF_ODOMETER" to 0x11600204, "GEAR_SELECTION" to 0x11400400, "CURRENT_GEAR" to 0x11400401,
      "IGNITION_STATE" to 0x11400409, "PARKING_BRAKE_ON" to 0x11200402, "NIGHT_MODE" to 0x11200407,
      "INFO_MAKE" to 0x11100101, "INFO_MODEL" to 0x11100102, "INFO_MODEL_YEAR" to 0x11400103,
      "INFO_FUEL_TYPE" to 0x11410105, "INFO_EV_CONNECTOR_TYPE" to 0x11410107,
      "INFO_EV_PORT_LOCATION" to 0x11400109, "DISTANCE_DISPLAY_UNITS" to 0x11400600,
      "HVAC_TEMPERATURE_DISPLAY_UNITS" to 0x1140050E, "TIRE_PRESSURE" to 0x17600309,
      "HVAC_TEMPERATURE_CURRENT" to 0x15600502,
    )
    val byName = KnownProperties.all.associateBy { it.name }
    for ((name, id) in expected) {
      val kp = byName[name]
      assertNotNull("missing $name", kp)
      assertEquals(name, id, kp!!.id)
    }
  }

  @Test
  fun consistentWithAospCatalog() {
    for (kp in KnownProperties.all) {
      val entry = AospPropertyCatalog.entries[kp.id]
      assertNotNull("${kp.name} (${kp.idHex}) not in the AOSP catalog", entry)
      assertEquals("name of ${kp.idHex}", entry!!.first, kp.name)
      assertTrue("read permissions of ${kp.name}", kp.readPermissions.isNotEmpty())
      assertTrue(
        "read permissions of ${kp.name}: ${kp.readPermissions} not within AOSP ${entry.second}",
        entry.second.containsAll(kp.readPermissions),
      )
    }
  }

  @Test
  fun vinIsNeverKnownOrCatalogued() {
    assertFalse(KnownProperties.all.any { it.id == vinId })
    assertNull(AospPropertyCatalog.entries[vinId])
    assertTrue(vinId in KnownProperties.EXCLUDED_IDS)
    assertFalse(KnownProperties.all.any { it.name.contains("VIN") })
  }

  @Test
  fun mwToKw_signAndScale() {
    assertEquals("+7.20 kW (charging)", Fmt.mwToKw(7_200_000f))
    assertEquals("-35.00 kW (discharging)", Fmt.mwToKw(-35_000_000f))
    assertEquals("0.00 kW (idle)", Fmt.mwToKw(0f))
    assertEquals("0.00 kW (idle)", Fmt.mwToKw(-1_000f)) // -0.001 kW rounds to idle
    assertEquals("+350.50 kW (charging)", Fmt.mwToKw(350_500_000.0))
    assertEquals(-35.0, Fmt.kwFromMw(-35_000_000)!!, 1e-9)
    assertEquals("+7.20 kW (charging)", KnownProperties.format(KnownProperties.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, 7_200_000f))
  }

  @Test
  fun whToKwh() {
    assertEquals("123.46 kWh", Fmt.whToKwh(123_456f))
    assertEquals("0.00 kWh", Fmt.whToKwh(0))
    assertEquals("205.00 kWh", KnownProperties.format(KnownProperties.INFO_EV_BATTERY_CAPACITY, 205_000f))
    assertEquals("150.00 kWh", KnownProperties.format(KnownProperties.EV_BATTERY_LEVEL, 150_000f))
  }

  @Test
  fun otherFormatters() {
    assertEquals("256.2 mi", Fmt.meters(412_300f))
    assertEquals("70.7 °F", Fmt.celsius(21.5f))
    assertEquals("80.0 %", Fmt.percent(80f))
    assertEquals("1 h 05 min (3900 s)", Fmt.seconds(3900))
    assertEquals("22.4 mph", Fmt.speedMs(10f))
    assertEquals("100.0 mi", Fmt.kilometers(160.9344f))
    assertEquals("PARK (4)", KnownProperties.format(KnownProperties.GEAR_SELECTION, 4))
    assertEquals("CHARGING (1)", KnownProperties.format(KnownProperties.EV_CHARGE_STATE, 1))
    assertEquals("ELECTRIC (10)", KnownProperties.format(KnownProperties.INFO_FUEL_TYPE, listOf(10)))
    assertEquals("J1772 (1), COMBO_1 (4)", KnownProperties.format(KnownProperties.INFO_EV_CONNECTOR_TYPE, listOf(1, 4)))
    assertEquals("KILOMETER (35)", KnownProperties.format(KnownProperties.DISTANCE_DISPLAY_UNITS, 0x23))
    assertEquals("UNKNOWN_VALUE (77)", KnownProperties.format(KnownProperties.GEAR_SELECTION, 77))
    assertEquals(75.0, Fmt.socPercent(150_000f, 200_000f)!!, 1e-9)
    assertNull(Fmt.socPercent(150_000f, 0f))
  }

  @Test
  fun formattersNeverThrowOnWrongTypes() {
    for (kp in KnownProperties.all) {
      for (v in listOf(null, "text", true, 1, 2.5f, listOf(1, 2), byteArrayOf(1, 2))) {
        kp.format(v) // must not throw
      }
    }
  }

  @Test
  fun rawAndVinMasking() {
    assertEquals("[1, 2.5, true]", Fmt.raw(listOf(1, 2.5f, true)))
    assertEquals("bytes[2]: 0A FF", Fmt.raw(byteArrayOf(0x0A, 0xFF.toByte())))
    assertEquals("42", Fmt.raw(42f))
    val masked = Fmt.raw("VIN 1GC40ZEL0RZ123456 end")
    assertFalse(masked.contains("1GC40ZEL0RZ123456"))
    assertTrue(masked.contains("masked"))
    assertEquals("Chevrolet", Fmt.raw("Chevrolet"))
  }

  @Test
  fun idBits() {
    assertEquals("system", PropertyIdBits.group(0x11600309))
    assertEquals("vendor", PropertyIdBits.group(0x21600309))
    assertEquals("global", PropertyIdBits.areaType(0x11600309))
    assertEquals("wheel", PropertyIdBits.areaType(0x17600309))
    assertEquals("seat", PropertyIdBits.areaType(0x15600502))
    assertEquals("float", PropertyIdBits.valueType(0x11600309))
    assertEquals("int32[]", PropertyIdBits.valueType(0x11410105))
    assertEquals("0x11600309", PropertyIdBits.hex(0x11600309))
    assertEquals("vendor/unknown 0x21600309", KnownProperties.nameOf(0x21600309))
    assertEquals("WHEEL_TICK", KnownProperties.nameOf(0x11510306))
    assertEquals(listOf(CarPerm.CAR_VENDOR_EXTENSION), KnownProperties.readPermissionsOf(0x21600309))
  }

  @Test
  fun liveSetCoversEnergyPanel() {
    val live = KnownProperties.live.map { it.name }.toSet()
    for (n in listOf("EV_BATTERY_LEVEL", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "RANGE_REMAINING", "EV_CHARGE_STATE", "INFO_EV_BATTERY_CAPACITY")) {
      assertTrue("$n should be live", n in live)
    }
  }
}
