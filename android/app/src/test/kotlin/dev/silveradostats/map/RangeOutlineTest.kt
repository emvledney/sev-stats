package dev.silveradostats.map

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class RangeTest {
    private val range = 160_000.0
    private val probe = range / 1.3

    /** Rough straight-line metres between two nearby points. */
    private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dy = (b.first - a.first) * 111_195
        val dx = (b.second - a.second) * 111_195 * cos(Math.toRadians((a.first + b.first) / 2))
        return Math.hypot(dx, dy)
    }

    @Test
    fun destinationGoesTheRightWay() {
        val north = destination(40.0, -83.0, 0.0, 111_195.0)
        assertEquals(41.0, north.first, 0.01)
        assertEquals(-83.0, north.second, 0.01)
        val east = destination(0.0, 179.5, 90.0, 111_195.0)
        assertEquals(-179.5, east.second, 0.01) // wraps across the date line
    }

    @Test
    fun outlineFollowsRoadDirectness() {
        val here = 40.0 to -83.0
        val road = MutableList<Double?>(RANGE_BEARINGS) { probe * 1.3 } // typical roads: range ends at the probe
        road[0] = probe            // dead-straight road north: reaches the full range, never beyond it
        road[6] = probe * 2.6      // very indirect roads east: reaches half as far
        road[12] = null            // no road answer south: uses the average of the others
        val outline = rangeOutline(here.first, here.second, range, road)
        assertEquals(RANGE_BEARINGS, outline.size)
        assertEquals(range, meters(here, outline[0]), range * 0.02)
        assertEquals(probe / 2, meters(here, outline[6]), range * 0.02)
        assertEquals(probe, meters(here, outline[3]), range * 0.02)
        val average = road.filterNotNull().map { probe / it }.average() * range
        assertEquals(average, meters(here, outline[12]), range * 0.02)
    }

    @Test
    fun outlineWithNoRoadDataIsATypicalDetourCircle() {
        val outline = rangeOutline(40.0, -83.0, range, List(RANGE_BEARINGS) { null })
        outline.forEach { assertEquals(probe, meters(40.0 to -83.0, it), range * 0.02) }
    }
}
