package dev.silveradostats.ui.live

import java.util.Locale
import kotlin.math.abs

/*
 * Pure Kotlin (no android.* imports): unit conversion and number formatting for the dashboard.
 */

enum class TempUnit(val symbol: String) { C("°C"), F("°F") }

enum class DistUnit(val symbol: String) { MI("mi"), KM("km") }

object Units {
  /** What every screen shows, by the owner's choice (2026-11-06): no toggle, no locale, no saved setting. */
  val TEMP = TempUnit.F
  val DIST = DistUnit.MI

  const val KM_PER_MILE = 1.609344
  const val METERS_PER_MILE = 1609.344
  const val MINUS = "−"

  fun temp(celsius: Double, u: TempUnit): Double = if (u == TempUnit.C) celsius else celsius * 9.0 / 5.0 + 32.0

  /** "21.6 °C" / "70.9 °F"; "—" for null. */
  fun tempText(celsius: Double?, u: TempUnit, decimals: Int = 1): String =
    if (celsius == null || !celsius.isFinite()) DASH else "${num(temp(celsius, u), decimals)} ${u.symbol}"

  fun distFromMiles(miles: Double, u: DistUnit): Double = if (u == DistUnit.MI) miles else miles * KM_PER_MILE

  fun distFromMeters(meters: Double, u: DistUnit): Double =
    if (u == DistUnit.KM) meters / 1000.0 else meters / METERS_PER_MILE

  /** "187.6 mi" / "301.9 km" from miles. */
  fun distText(miles: Double?, u: DistUnit, decimals: Int = 0): String =
    if (miles == null || !miles.isFinite()) DASH else "${num(distFromMiles(miles, u), decimals)} ${u.symbol}"

  /** Fixed decimals, US digits, a real minus sign, and never "-0.0". */
  fun num(x: Double, decimals: Int): String {
    val s = String.format(Locale.US, "%.${decimals}f", abs(x))
    val zero = s.all { it == '0' || it == '.' }
    return if (x < 0 && !zero) MINUS + s else s
  }

  /** Like [num] with an explicit "+" for positive values ("+12.3", "−4.0", "0.0"). */
  fun signed(x: Double, decimals: Int): String {
    val s = num(x, decimals)
    return if (x > 0 && s.any { it in '1'..'9' }) "+$s" else s
  }

  /** "850 ms", "4.2 s", "37 s", "3 min", "2 h". */
  fun age(ms: Long): String = when {
    ms < 0 -> "0 ms"
    ms < 1_000 -> "$ms ms"
    ms < 10_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
    ms < 120_000 -> "${ms / 1000} s"
    ms < 2 * 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
  }

  const val DASH = "—"
}
