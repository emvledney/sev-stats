package dev.silveradostats.map

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Calibration knobs for the range outline.
const val RANGE_BEARINGS = 24               // directions sampled around the vehicle
private const val TYPICAL_DETOUR = 1.3      // road miles per straight-line mile; places the probes and fills gaps
private const val EARTH_RADIUS_M = 6_371_000.0

/** The point [meters] from (lat, lng) along [bearingDeg], clockwise from north. */
fun destination(lat: Double, lng: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
    val d = meters / EARTH_RADIUS_M
    val b = Math.toRadians(bearingDeg)
    val p1 = Math.toRadians(lat)
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
    val l2 = Math.toRadians(lng) + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return Math.toDegrees(p2) to (Math.toDegrees(l2) + 540) % 360 - 180
}

private fun probeMeters(rangeMeters: Double) = rangeMeters / TYPICAL_DETOUR

/** Where to ask for road distances: one point per direction, about as far as the range usually reaches. */
fun rangeProbes(lat: Double, lng: Double, rangeMeters: Double): List<Pair<Double, Double>> =
    (0 until RANGE_BEARINGS).map { destination(lat, lng, it * 360.0 / RANGE_BEARINGS, probeMeters(rangeMeters)) }

/**
 * Outline of how far the range reaches by road. [roadMeters] holds the driving distance to each probe
 * (null where there is no road answer). Each direction is pulled in by how indirect its roads are.
 */
// Known ceiling: assumes detour is the same all the way along a direction, and a probe that lands in water is
// measured to the nearest road, which flatters that direction; sample more points per direction if it misleads.
fun rangeOutline(lat: Double, lng: Double, rangeMeters: Double, roadMeters: List<Double?>): List<Pair<Double, Double>> {
    val probe = probeMeters(rangeMeters)
    // Straight-line metres covered per road metre, per direction.
    val directness = roadMeters.map { road -> road?.takeIf { it > 0 }?.let { probe / it } }
    val known = directness.filterNotNull()
    val fallback = if (known.isEmpty()) 1 / TYPICAL_DETOUR else known.average()
    return directness.mapIndexed { i, k ->
        destination(lat, lng, i * 360.0 / directness.size, rangeMeters * (k ?: fallback).coerceAtMost(1.0))
    }
}
