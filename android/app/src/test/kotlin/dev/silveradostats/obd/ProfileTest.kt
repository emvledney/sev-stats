package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of ProfileTests (profile against the simulator, decode rules, derive) plus JSON loading. */
class ProfileTest {
  private val prof = TestSupport.profile

  private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
      block()
    } catch (e: Throwable) {
      if (e is T) return e
      throw AssertionError("expected ${T::class.simpleName}, got $e", e)
    }
    fail("expected ${T::class.simpleName}, nothing thrown")
    throw IllegalStateException()
  }

  @Test
  fun assetProfileLoads() {
    assertEquals("silverado-ev", prof.name)
    assertEquals(57, prof.didFields.size)
    assertEquals(43, prof.reads().size)
    val soc = prof.didFields.single { it.name == "soc" }
    assertEquals(0x27C6, soc.did)
    assertEquals("confirmed", soc.status)
    assertEquals(0.0015259021896696422, soc.scale, 0.0)
    val cells = prof.didFields.filter { it.name.startsWith("cells_") }
    assertEquals(16, cells.size)
    assertEquals((0x2AE1..0x2AF0).toList(), cells.map { it.did })
    assertTrue(cells.all { it.stride == 3 && it.count == 12 && it.module == "becm" })
    assertEquals(DidKey("becm", 0x27C6), Poller.DEFAULT_PROBE)
    assertTrue(Profile.NOT_DISPLAYED.all { n -> prof.didFields.any { it.name == n && it.module == "bcm" } })
  }

  @Test
  fun silveradoProfileAgainstSim() {
    val rig = TestSupport.SimRig()
    val clients = prof.modules.mapValues { (_, m) -> UdsClient(rig.transport.open(m.tx, m.rx), clock = { rig.now }, sleeper = {}) }
    val values = LinkedHashMap<String, Any?>()
    for ((key, fields) in prof.reads()) {
      val r = try {
        clients.getValue(key.module).readDid(key.did)
      } catch (e: NegativeResponse) {
        continue // e.g. the unverified fallback DIDs
      }
      for (f in fields) values[f.name] = f.decode(r.data)
    }
    val d = Derive.derive(values)
    assertEquals(24, d["modules"])
    assertEquals(192, d["cell_groups_read"])
    assertEquals(96.0, d["series_groups"] as Double, 0.5)
    assertEquals(List(8) { 1L } + List(4) { 2L }, values["cell_group_modules_2ae1"])
    val cells = values.filterKeys { it.startsWith("cells_") }.values.flatMap { it as List<*> }.map { it as Double }
    assertEquals(cells.min(), d["cell_min_v"] as Double, 0.0) // frozen clock: no drift between reads
    assertEquals((cells.indexOf(cells.min()) + 1).toLong(), values["cell_min_index_a"])
    assertTrue((values["cell_min_index_b"] as Long) > 96)
    assertEquals(values["cell_min_index_a"], (d["array_min_group"] as Int).toLong())
    // Each half's average times 96 is the pack voltage (session-1 finding).
    val packV = values["pack_voltage"] as Double
    assertEquals(packV, (values["cell_avg_a"] as Double + values["cell_avg_b"] as Double) / 2 * 96, 0.03)
    assertTrue(d.containsKey("ah_nominal_est"))
    assertEquals((d["bms_energy_at_100_kwh"] as Double) * 1000 / ((d["series_groups"] as Double) * 3.6), d["ah_nominal_est"] as Double, 0.05)
  }

  @Test
  fun strideAndSignedDecode() {
    val f = DidField("m", 1, "x", "x", type = "u16", stride = 3, count = 0, scale = 0.0001)
    assertEquals(listOf(4.0309, 4.029, 4.0337), f.decode(TestSupport.hex("9D7501 9D6202 9D9103")))
    val s = DidField("m", 1, "x", "x", type = "s16", scale = 0.05)
    assertEquals(-10.0, s.decode(TestSupport.hex("FF38")) as Double, 1e-12)
    val raw = DidField("m", 1, "x", "x", type = "s24")
    assertEquals(-2L, raw.decode(TestSupport.hex("FFFFFE")))
    assertEquals(0xFFFFFFFFL, DidField("m", 1, "x", "x", type = "u32").decode(TestSupport.hex("FFFFFFFF")))
    assertNull(DidField("m", 1, "x", "x", type = "u16").decode(TestSupport.hex("01")))
    assertEquals(emptyList<Any>(), DidField("m", 1, "x", "x", type = "u16", count = 0).decode(TestSupport.hex("01")))
    assertEquals(listOf(1L), DidField("m", 1, "x", "x", type = "u16", count = 3).decode(TestSupport.hex("000100")))
    assertEquals("1GC", DidField("m", 1, "x", "x", type = "ascii").decode(" 1GC\u0000".toByteArray()))
    assertEquals("9d 75", DidField("m", 1, "x", "x", type = "hex", start = 1).decode(TestSupport.hex("009D75")))
  }

  @Test
  fun wrongPriorityIsSilent() {
    val rig = TestSupport.SimRig()
    assertThrows<NoResponse> { rig.client(0x40, 0x14, timeoutS = 0.05).readDid(0x434F) }
    rig.client(0x40, 0x18).readDid(0x434F)
  }

  @Test
  fun negativeResponse() {
    val e = assertThrows<NegativeResponse> { TestSupport.SimRig().client(0xCB, 0x14).readDid(0xBEEF) }
    assertEquals(0x31, e.nrc)
  }

  @Test
  fun deriveIgnoresPadding() {
    val d = Derive.derive(mapOf("cells_001_012" to List(12) { 3.9 }, "cells_013_024" to listOf(3.8) + List(11) { 0.0 }))
    assertEquals(13, d["cell_groups_read"])
    assertEquals(13, d["array_min_group"])
    assertEquals(3.8, d["array_min_v"])
  }

  @Test
  fun deriveHalvesAndPower() {
    val d = Derive.derive(
      mapOf(
        "cell_min_a" to 3.6692, "cell_max_a" to 3.6769, "cell_avg_a" to 3.6729,
        "cell_min_b" to 3.6709, "cell_max_b" to 3.6769, "cell_avg_b" to 3.6732,
        "pack_voltage" to 352.7, "pack_current" to 0.6, "energy_remaining" to 85.3, "soc" to 41.88,
        "module_soc_raw" to List(24) { 100L },
      ),
    )
    assertEquals(3.6692, d["cell_min_v"])
    assertEquals(3.6769, d["cell_max_v"])
    assertEquals(7.7, d["cell_spread_mv"])
    assertEquals(96.0, d["series_groups"])
    assertEquals(0.21, d["power_kw"])
    assertEquals(24, d["modules"])
    assertEquals(203.7, d["bms_energy_at_100_kwh"])
    assertEquals(589.4, d["ah_nominal_est"])
    assertTrue(Derive.LABELS.getValue("ah_nominal_est").first.contains("nominal estimate"))
    assertTrue(Derive.LABELS.keys.containsAll(d.keys))
  }

  @Test
  fun profileRejectsBadEntries() {
    val module = """"modules": {"b": {"tx": 349883377, "rx": 338358731}}"""
    fun json(field: String) = """{"name": "t", $module, "fields": [{"module": "b", "did": 1, "name": "x", $field}]}"""
    Profile.fromJson(json(""""period": 0.5"""))
    for (bad in listOf(
      """"period": 0""", """"period": 0.4""", """"type": "f32"""", """"status": "guess"""",
      """"start": -1""", """"stride": -3""", """"count": -1""", """"did": 70000""",
    )) {
      val text = if (bad.startsWith("\"did\"")) json(bad).replace(""""did": 1, """, "") else json(bad)
      assertThrows<IllegalArgumentException> { Profile.fromJson(text) }
    }
    assertThrows<IllegalArgumentException> {
      Profile.fromJson("""{"name": "t", $module, "fields": [{"module": "nope", "did": 1, "name": "x"}]}""")
    }
    assertThrows<IllegalArgumentException> {
      Profile.fromJson("""{"name": "t", $module, "fields": [{"module": "b", "did": 1, "name": "x"}, {"module": "b", "did": 2, "name": "x"}]}""")
    }
    assertThrows<IllegalArgumentException> { // non-diagnostic address
      Profile.fromJson("""{"name": "t", "modules": {"b": {"tx": 2020, "rx": 2028}}, "fields": [{"module": "b", "did": 1, "name": "x"}]}""")
    }
    assertThrows<IllegalArgumentException> { // two modules on the same IDs
      Profile.fromJson(
        """{"name": "t", "modules": {"a": {"ecu": 203, "pri": 20}, "b": {"tx": 349883377, "rx": 338358731}}, "fields": [{"module": "b", "did": 1, "name": "x"}]}""",
      )
    }
    assertThrows<IllegalArgumentException> { Profile.fromJson("[1, 2]") }
    assertThrows<IllegalArgumentException> { Profile.fromJson("""{"name": "t", $module, "fields": [""") }
  }

  @Test
  fun miniJson() {
    val v = MiniJson.parse("""{"a": [1, 2.0, -3e2, "xé\"\\", true, false, null], "b": {}, "c": 0.0015259021896696422, "d": -0.0}""") as Map<*, *>
    assertEquals(listOf(1L, 2.0, -300.0, "xé\"\\", true, false, null), v["a"])
    assertEquals(emptyMap<String, Any?>(), v["b"])
    assertEquals(0.0015259021896696422, v["c"])
    assertEquals(-0.0, v["d"])
    assertEquals(listOf("a", "b", "c", "d"), v.keys.toList())
    for (bad in listOf("", "{", "[1,]x", "01", "1.", "\"abc", "{\"a\" 1}", "tru", "[1] 2")) {
      assertThrows<IllegalArgumentException> { MiniJson.parse(bad) }
    }
  }
}
