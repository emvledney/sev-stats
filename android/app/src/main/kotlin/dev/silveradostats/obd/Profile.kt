package dev.silveradostats.obd

/**
 * DID profiles: which modules to talk to and how to decode what they return (port of profile.py).
 *
 * The app loads the JSON written by `./probe export-profile` (assets/profile.json); the TOML in
 * tools/probe/dids is the source of truth. Decoding is purely declarative (type, byte offset,
 * stride, scale, offset, count), so no profile can run code.
 */
data class Module(val key: String, val tx: Long, val rx: Long, val description: String = "")

/** (module key, DID): one request per poll of that DID. */
data class DidKey(val module: String, val did: Int) {
  override fun toString(): String = "$module:0x${hex(did, 4)}"
}

/** One decoded value of one DID (the probe's `Field`). */
class DidField(
  val module: String,
  val did: Int,
  val name: String,
  val label: String,
  val unit: String = "",
  val type: String = "u16",
  val start: Int = 0,
  /** Bytes between array elements (0 = element size). */
  val stride: Int = 0,
  val scale: Double = 1.0,
  val offset: Double = 0.0,
  /** 1 = scalar, >1 = array of that many, 0 = as many as the reply holds. */
  val count: Int = 1,
  /** Seconds between reads when polling. */
  val period: Double = 1.0,
  val status: String = "unverified",
  val source: String = "",
  val note: String = "",
) {
  val key: DidKey get() = DidKey(module, did)

  /**
   * Decodes a reply payload exactly like the probe: String for ascii / hex, otherwise Long values
   * when scale == 1 and offset == 0, else Double values rounded to 6 decimals; a scalar (count 1)
   * is the value or null when the reply is too short, anything else a List.
   */
  fun decode(data: ByteArray): Any? {
    if (type == "ascii") return asciiReplace(data.sliceFrom(start)).trim { it == '\u0000' || it == ' ' }
    if (type == "hex") return hexSpaced(data.sliceFrom(start))
    val (size, signed) = Profile.TYPES[type] ?: throw IllegalArgumentException("unknown type $type")
    val step = if (stride != 0) stride else size
    val n = if (count != 0) count else maxOf(0, Math.floorDiv(data.size - start - size, step) + 1)
    val values = ArrayList<Any>()
    for (i in 0 until n) {
      val lo = start + i * step
      if (lo < 0 || lo + size > data.size) break
      var raw = 0L
      for (j in 0 until size) raw = (raw shl 8) or u8(data[lo + j]).toLong()
      if (signed && raw >= (1L shl (8 * size - 1))) raw -= 1L shl (8 * size)
      values += if (scale == 1.0 && offset == 0.0) raw else Py.round(raw.toDouble() * scale + offset, 6)
    }
    if (count == 1) return values.firstOrNull()
    return values
  }
}

class Profile(val name: String, val modules: Map<String, Module>, val didFields: List<DidField>) {

  /** Fields grouped by (module, DID) so each DID is requested once per cycle (first-seen order). */
  fun reads(): Map<DidKey, List<DidField>> {
    val out = LinkedHashMap<DidKey, MutableList<DidField>>()
    for (f in didFields) out.getOrPut(f.key) { mutableListOf() } += f
    return out
  }

  companion object {
    /** Integer types: size in bytes, signed. */
    val TYPES: Map<String, Pair<Int, Boolean>> = mapOf(
      "u8" to (1 to false), "s8" to (1 to true),
      "u16" to (2 to false), "s16" to (2 to true),
      "u24" to (3 to false), "s24" to (3 to true),
      "u32" to (4 to false), "s32" to (4 to true),
    )
    val STATUSES: List<String> = listOf("unverified", "bolt-documented", "community-ultium", "confirmed")

    /**
     * Fields that are polled (as in the probe) but must not be shown: their community decoding is
     * wrong on this truck (40:434F reads 60 C with a 17 C pack; 4127 / 4124 meaning unclear).
     */
    val NOT_DISPLAYED: Set<String> = setOf("hv_battery_temp", "battery_temp_a", "battery_temp_b")

    /** Parses and validates the profile JSON (same checks as the probe's TOML loader, plus addresses). */
    fun fromJson(text: String, source: String = "profile.json"): Profile {
      val doc = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("$source: not a JSON object")
      val modulesJson = doc["modules"] as? Map<*, *> ?: emptyMap<String, Any?>()
      val modules = LinkedHashMap<String, Module>()
      for ((k, m) in modulesJson) {
        val key = k as String
        val mm = m as? Map<*, *> ?: throw IllegalArgumentException("$source: module $key is not an object")
        val (tx, rx) = if (mm["ecu"] != null) {
          Addressing.ids(int(mm["ecu"], "$key.ecu"), int(mm["pri"] ?: 0x14L, "$key.pri"))
        } else {
          long(mm["tx"], "$key.tx") to long(mm["rx"], "$key.rx")
        }
        try {
          Safety.checkAddress(tx, rx, false)
        } catch (e: UnsafeRequestError) {
          throw IllegalArgumentException("$source: module $key: ${e.message}")
        }
        modules[key] = Module(key, tx, rx, mm["description"] as? String ?: "")
      }
      val list = (doc["fields"] ?: doc["did"]) as? List<*> ?: emptyList<Any?>()
      val out = ArrayList<DidField>()
      for (item in list) {
        val d = item as? Map<*, *> ?: throw IllegalArgumentException("$source: field entry is not an object")
        val name = d["name"] as? String ?: throw IllegalArgumentException("$source: field without a name")
        val f = DidField(
          module = d["module"] as? String ?: throw IllegalArgumentException("$source: field $name has no module"),
          did = int(d["did"] ?: d["id"], "$name.did"),
          name = name,
          label = d["label"] as? String ?: name,
          unit = d["unit"] as? String ?: "",
          type = d["type"] as? String ?: "u16",
          start = int(d["start"] ?: 0L, "$name.start"),
          stride = int(d["stride"] ?: 0L, "$name.stride"),
          scale = double(d["scale"] ?: 1.0, "$name.scale"),
          offset = double(d["offset"] ?: 0.0, "$name.offset"),
          count = int(d["count"] ?: 1L, "$name.count"),
          period = double(d["period"] ?: 1.0, "$name.period"),
          status = d["status"] as? String ?: "unverified",
          source = d["source"] as? String ?: "",
          note = d["note"] as? String ?: "",
        )
        require(f.module in modules) { "$source: field ${f.name} uses unknown module '${f.module}'" }
        require(f.type in TYPES || f.type == "ascii" || f.type == "hex") { "$source: field ${f.name} has unknown type '${f.type}'" }
        require(f.period >= 0.5) { "$source: field ${f.name} period must be >= 0.5 s" }
        require(f.status in STATUSES) { "$source: field ${f.name} has unknown status '${f.status}'" }
        require(f.did in 0..0xFFFF) { "$source: field ${f.name} DID out of range" }
        require(f.start >= 0 && f.stride >= 0 && f.count >= 0) { "$source: field ${f.name} has a negative start/stride/count" }
        require(f.scale.isFinite() && f.offset.isFinite()) { "$source: field ${f.name} scale/offset must be finite" }
        out += f
      }
      val pairs = modules.values.map { it.tx to it.rx }
      require(pairs.size == pairs.toSet().size) { "$source: two modules share the same CAN IDs" }
      val dupes = out.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
      require(dupes.isEmpty()) { "$source: duplicate field names ${dupes.sorted()}" }
      require(out.isNotEmpty()) { "$source: no fields" }
      val meta = doc["meta"] as? Map<*, *>
      val name = doc["name"] as? String ?: meta?.get("name") as? String ?: source.substringAfterLast('/').substringBeforeLast('.')
      return Profile(name, modules, out)
    }

    private fun long(v: Any?, what: String): Long = when (v) {
      is Long -> v
      is Int -> v.toLong()
      else -> throw IllegalArgumentException("$what must be an integer, got $v")
    }

    private fun int(v: Any?, what: String): Int {
      val l = long(v, what)
      require(l in Int.MIN_VALUE..Int.MAX_VALUE) { "$what out of range" }
      return l.toInt()
    }

    private fun double(v: Any?, what: String): Double = when (v) {
      is Double -> v
      is Long -> v.toDouble()
      is Int -> v.toDouble()
      else -> throw IllegalArgumentException("$what must be a number, got $v")
    }
  }
}
