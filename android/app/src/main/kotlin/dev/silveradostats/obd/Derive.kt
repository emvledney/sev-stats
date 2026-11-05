package dev.silveradostats.obd

import kotlin.math.abs

/**
 * Values computed from several DIDs (port of cli.derive; all estimates).
 *
 * Input: field name -> decoded value (what [DidField.decode] returns). Output keys, in order:
 * power_kw, cell_min_v, cell_max_v, cell_spread_mv, series_groups, modules, bms_energy_at_100_kwh,
 * cell_groups_read, array_min_v, array_min_group, array_max_v, array_max_group, and (added in the
 * app) ah_nominal_est, capacity_kwh, capacity_ah. Ints are Long/Int, floats Double, exactly as the probe computes them.
 */
object Derive {
  /** Nominal NMC cell-group voltage used only for the Ah estimate. */
  const val NOMINAL_CELL_V = 3.6

  /** How far 27AE may be from energy / SoC and still be taken for the pack's capacity. */
  const val CAPACITY_TOLERANCE = 0.15

  /** kWh / Ah / series groups must land here (per group, or half of it with the halves in series for 800 V). */
  val CELL_V_RANGE = 3.4..3.9

  fun derive(v: Map<String, Any?>): LinkedHashMap<String, Any> {
    val d = LinkedHashMap<String, Any>()
    val pv = v["pack_voltage"]
    val pc = v["pack_current"]
    if (pv is Double && pc is Double) d["power_kw"] = Py.round(pv * pc / 1000, 2)
    // BT1 packs report two 400 V halves: 2AF6 = modules 1-12 (_a), 2AF5 = modules 13-24 (_b).
    val mins = numbers(v, "cell_min_a", "cell_min_b", "cell_min")
    val maxs = numbers(v, "cell_max_a", "cell_max_b", "cell_max")
    val avgs = numbers(v, "cell_avg_a", "cell_avg_b", "cell_avg")
    if (mins.isNotEmpty() && maxs.isNotEmpty()) {
      val lo = Py.min(mins)
      val hi = Py.max(maxs)
      d["cell_min_v"] = lo
      d["cell_max_v"] = hi
      d["cell_spread_mv"] = Py.round(Py.mul(Py.sub(hi, lo), 1000), 1)
    }
    if (Py.truthy(pv) && pv is Number && avgs.isNotEmpty()) {
      val avg = Py.truediv(Py.sum(avgs), avgs.size)
      if (avg != 0.0) d["series_groups"] = Py.round(Py.truediv(pv, avg), 1)
    }
    val moduleSoc = v["module_soc_raw"]
    if (moduleSoc is List<*>) d["modules"] = moduleSoc.count { Py.truthy(it) }
    val energy = v["energy_remaining"]
    val soc = v["soc"]
    if (Py.truthy(energy) && Py.truthy(soc) && energy is Number && soc is Number) {
      d["bms_energy_at_100_kwh"] = Py.round(Py.truediv(energy, Py.truediv(soc, 100)), 1)
    }
    val cells = v.keys.sorted().filter { it.startsWith("cells_") && v[it] is List<*> }.flatMap { v[it] as List<*> }
    // 0 = padding on smaller packs; group numbers count padded positions too, like the probe.
    val real = cells.withIndex().mapNotNull { (i, c) -> if (c is Number && c.toDouble() > 0.5) (i + 1) to c else null }
    if (real.isNotEmpty()) {
      var lo = real[0]
      var hi = real[0]
      for (x in real) {
        if (x.second.toDouble() < lo.second.toDouble()) lo = x
        if (x.second.toDouble() > hi.second.toDouble()) hi = x
      }
      d["cell_groups_read"] = real.size
      d["array_min_v"] = lo.second
      d["array_min_group"] = lo.first
      d["array_max_v"] = hi.second
      d["array_max_group"] = hi.first
    }
    // Added in the app: a nominal Ah figure, NOT a measured capacity (the coulomb counter is not built).
    val bms = d["bms_energy_at_100_kwh"]
    val groups = d["series_groups"]
    if (Py.truthy(bms) && Py.truthy(groups) && bms is Number && groups is Number) {
      d["ah_nominal_est"] = Py.round(bms.toDouble() * 1000 / (groups.toDouble() * NOMINAL_CELL_V), 1)
    }
    // Added in the app: the capacities the BECM reports itself, only while they fit the rest of the pack.
    val implied = (bms as? Number)?.toDouble()
    val capability = (v["battery_capability"] as? Number)?.toDouble()?.takeIf { it > 0 }
    if (capability != null && (implied == null || abs(capability / implied - 1) <= CAPACITY_TOLERANCE)) d["capacity_kwh"] = capability
    val ah = (v["pack_capacity_ah"] as? Number)?.toDouble()?.takeIf { it > 0 }
    val kwh = (d["capacity_kwh"] as? Number)?.toDouble() ?: implied
    if (ah != null && kwh != null && groups is Number && groups.toDouble() > 0) {
      val perGroup = kwh * 1000 / ah / groups.toDouble()
      if (perGroup in CELL_V_RANGE || perGroup * 2 in CELL_V_RANGE) d["capacity_ah"] = ah
    }
    return d
  }

  /** v[k] for each key whose value is truthy (the probe's `if v.get(k)`), numbers only. */
  private fun numbers(v: Map<String, Any?>, vararg keys: String): List<Number> =
    keys.mapNotNull { k -> v[k]?.takeIf { Py.truthy(it) } as? Number }
}
