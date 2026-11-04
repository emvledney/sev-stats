package dev.silveradostats.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerHistoryTest {
  @Test
  fun keepsTheLastFiveMinutesInOrder() {
    val h = PowerHistory()
    for (s in 0 until 400) h.add(s * 1_000L, s.toDouble())
    val snap = h.snapshot(399_000L)
    assertEquals(301, snap.size) // 99 s … 399 s inclusive
    assertEquals(99_000L, snap.first().atMs)
    assertEquals(399.0, snap.last().kw, 0.0)
    assertTrue(snap.zipWithNext().all { (a, b) -> a.atMs < b.atMs })
  }

  @Test
  fun ringBufferDropsTheOldestWhenFull() {
    val h = PowerHistory(windowMs = 1_000_000, capacity = 5)
    for (i in 1..8) h.add(i.toLong(), i.toDouble())
    assertEquals(listOf(4.0, 5.0, 6.0, 7.0, 8.0), h.snapshot(8).map { it.kw })
  }

  @Test
  fun ignoresOutOfOrderAndNonFinitePoints() {
    val h = PowerHistory()
    h.add(10, 1.0)
    h.add(5, 2.0)
    h.add(11, Double.NaN)
    h.add(12, Double.POSITIVE_INFINITY)
    h.add(13, -3.5)
    assertEquals(listOf(1.0, -3.5), h.snapshot(13).map { it.kw })
    h.clear()
    assertEquals(0, h.snapshot(13).size)
  }

  @Test
  fun pruneEmptiesAfterTheWindow() {
    val h = PowerHistory()
    h.add(0, 5.0)
    assertEquals(1, h.snapshot(PowerHistory.WINDOW_MS).size)
    assertEquals(0, h.snapshot(PowerHistory.WINDOW_MS + 1).size)
  }
}
