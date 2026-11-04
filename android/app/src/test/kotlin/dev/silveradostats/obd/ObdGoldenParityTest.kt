package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Parity with the Python probe: golden_decode.json (test resources) was produced by the probe
 * itself from the shipped profile (android/scripts/gen_golden_decode.py, which imports
 * tools/probe/src/ultium_probe): decode of every profile DID for simulator, truck-like and edge
 * payloads, VIN masking, cli.derive() on whole value sets, and the simulator's raw answers at
 * frozen times. Everything must match exactly: same int / float kind and the same double bits.
 *
 * After `./probe export-profile` changes assets/profile.json, regenerate the golden file:
 *   PYTHONDONTWRITEBYTECODE=1 python3 -B android/scripts/gen_golden_decode.py <repo root>
 */
class ObdGoldenParityTest {
  private val golden: Map<*, *> by lazy {
    val text = javaClass.getResourceAsStream("/golden_decode.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: error("golden_decode.json missing from test resources")
    MiniJson.parse(text) as Map<*, *>
  }
  private val profile = TestSupport.profile

  /** Python-equality: same kind (int / float / str / list / dict / None) and the exact value. */
  private fun assertPy(where: String, expected: Any?, actual: Any?) {
    when (expected) {
      null -> if (actual != null) fail("$where: expected None, got $actual")
      is Long -> {
        if (!Py.isInt(actual)) fail("$where: expected int $expected, got ${actual?.javaClass?.simpleName} $actual")
        assertEquals(where, expected, (actual as Number).toLong())
      }
      is Double -> {
        if (actual !is Double) fail("$where: expected float $expected, got ${actual?.javaClass?.simpleName} $actual")
        if (expected != actual) fail("$where: expected $expected, got $actual") // boxed: bitwise, -0.0 != 0.0
      }
      is String -> assertEquals(where, expected, actual)
      is List<*> -> {
        val a = actual as? List<*> ?: throw AssertionError("$where: expected a list, got $actual")
        assertEquals("$where: length", expected.size, a.size)
        for (i in expected.indices) assertPy("$where[$i]", expected[i], a[i])
      }
      is Map<*, *> -> {
        val a = actual as? Map<*, *> ?: throw AssertionError("$where: expected a dict, got $actual")
        assertEquals("$where: keys", expected.keys.toList(), a.keys.toList())
        for (k in expected.keys) assertPy("$where.$k", expected[k], a[k])
      }
      else -> assertEquals(where, expected, actual)
    }
  }

  private fun list(key: String): List<Map<*, *>> = (golden[key] as List<*>).map { it as Map<*, *> }

  @Test
  fun goldenWasMadeFromTheShippedProfile() {
    val sha = MessageDigest.getInstance("SHA-256").digest(TestSupport.profileFile().readBytes())
    assertEquals(
      "assets/profile.json changed since golden_decode.json was generated; rerun android/scripts/gen_golden_decode.py",
      golden["profile_sha256"],
      hexLower(sha),
    )
  }

  @Test
  fun decodeMatchesThePythonProbe() {
    val reads = profile.reads()
    val vectors = list("decode")
    assertTrue(vectors.size > 500)
    val covered = mutableSetOf<DidKey>()
    for (v in vectors) {
      val key = DidKey(v["module"] as String, (v["did"] as Long).toInt())
      val where = "$key (${v["case"]})"
      val fields = reads[key] ?: error("$where: not in the Kotlin profile")
      covered += key
      val data = bytesFromHex(v["hex"] as String)
      val masked = Vin.maskPayload(key.did, data)
      assertEquals("$where masked payload", v["masked_hex"], hexLower(masked))
      val expected = v["values"] as Map<*, *>
      val expectedRaw = v["raw_values"] as Map<*, *>
      assertEquals("$where fields", expected.keys.toList(), fields.map { it.name })
      for (f in fields) {
        assertPy("$where ${f.name}", expected[f.name], f.decode(masked))
        assertPy("$where ${f.name} (unmasked)", expectedRaw[f.name], f.decode(data))
      }
    }
    assertEquals("every profile DID has golden vectors", reads.keys, covered)
  }

  @Test
  fun vinMaskingMatchesThePythonProbe() {
    for (v in list("mask")) {
      val did = (v["did"] as Long).toInt()
      val data = bytesFromHex(v["hex"] as String)
      assertEquals("0x${hex(did, 4)} ${v["hex"]}", v["masked_hex"], hexLower(Vin.maskPayload(did, data)))
      assertEquals(v["vin_mask"], Vin.mask(asciiReplace(data)))
    }
  }

  @Test
  fun deriveMatchesThePythonProbe() {
    val vectors = list("derive")
    assertTrue(vectors.size >= 15)
    for (v in vectors) {
      @Suppress("UNCHECKED_CAST")
      val values = v["values"] as Map<String, Any?>
      assertPy("derive '${v["case"]}'", v["derived"], Derive.derive(values))
    }
  }

  @Test
  fun simulatorMatchesThePythonProbe() {
    val sim = golden["sim"] as Map<*, *>
    var simNow = 0.0
    val u = UltiumSim { simNow } // t0 = 0, like the generator's frozen clock
    val physical = (sim["physical"] as List<*>).map { it as Map<*, *> }
    assertTrue(physical.size > 1000)
    for (v in physical) {
      simNow = v["t"] as Double
      val ecu = (v["ecu"] as Long).toInt()
      val pri = (v["pri"] as Long).toInt()
      val reply = u.answer(ecu, pri, bytesFromHex(v["payload"] as String))
      assertEquals("t=$simNow ${hex(ecu, 2)}@${hex(pri, 2)} ${v["payload"]}", v["reply"], reply?.let { hexLower(it) })
    }
    for (v in (sim["functional"] as List<*>).map { it as Map<*, *> }) {
      val got = u.functional((v["mode"] as Long).toInt(), (v["pid"] as Long).toInt()).map { hexLower(it) }
      assertEquals("functional ${v["mode"]} ${v["pid"]}", v["replies"], got)
    }
  }
}
