package dev.silveradostats.obd

/**
 * Values computed from several DIDs (port of cli.derive; all estimates).
 *
 * Input: field name -> decoded value (what [DidField.decode] returns). Output keys, in order:
 * power_kw, cell_min_v, cell_max_v, cell_spread_mv, series_groups, modules, bms_energy_at_100_kwh,
 * cell_groups_read, array_min_v, array_min_group, array_max_v, array_max_group, and (added in the
 * app) ah_nominal_est. Ints are Long/Int, floats Double, exactly as the probe computes them.
 */
object Derive {
  /** Nominal NMC cell-group voltage used only for the Ah estimate. */
  const val NOMINAL_CELL_V = 3.6

  /** key -> (label, unit) for display. */
  val LABELS: Map<String, Pair<String, String>> = linkedMapOf(
    "power_kw" to ("Battery power (+ discharge, - charge)" to "kW"),
    "cell_min_v" to ("Lowest cell group (half summaries)" to "V"),
    "cell_max_v" to ("Highest cell group (half summaries)" to "V"),
    "cell_spread_mv" to ("Cell group spread" to "mV"),
    "series_groups" to ("Series groups (pack V / average group V)" to ""),
    "modules" to ("Battery modules" to ""),
    "bms_energy_at_100_kwh" to ("BMS energy at 100 % (not usable capacity)" to "kWh"),
    "cell_groups_read" to ("Cell groups read" to ""),
    "array_min_v" to ("Lowest cell group (full array)" to "V"),
    "array_min_group" to ("Lowest cell group #" to ""),
    "array_max_v" to ("Highest cell group (full array)" to "V"),
    "array_max_group" to ("Highest cell group #" to ""),
    "ah_nominal_est" to ("Capacity, nominal estimate (BMS energy at 100 % / (series groups x 3.6 V))" to "Ah"),
  )

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
    // Added in the app: a nominal Ah figure, NOT a measured capacity (the coulomb counter comes later).
    val bms = d["bms_energy_at_100_kwh"]
    val groups = d["series_groups"]
    if (Py.truthy(bms) && Py.truthy(groups) && bms is Number && groups is Number) {
      d["ah_nominal_est"] = Py.round(bms.toDouble() * 1000 / (groups.toDouble() * NOMINAL_CELL_V), 1)
    }
    return d
  }

  /** v[k] for each key whose value is truthy (the probe's `if v.get(k)`), numbers only. */
  private fun numbers(v: Map<String, Any?>, vararg keys: String): List<Number> =
    keys.mapNotNull { k -> v[k]?.takeIf { Py.truthy(it) } as? Number }
}
