package dev.silveradostats.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The module-temperature figures of a battery half: min, mean, median, max, gaps skipped. */
class PackTilesTest {
  @Test
  fun statsOfAnOddAndAnEvenCount() {
    assertEquals(listOf(20.0, 23.0, 22.0, 27.0), DashboardModel.minMeanMedianMax(listOf(27.0, 20.0, 22.0)))
    // Even count: the median is the mean of the middle two.
    assertEquals(listOf(20.0, 23.0, 23.0, 26.0), DashboardModel.minMeanMedianMax(listOf(26.0, 20.0, 22.0, 24.0)))
  }

  @Test
  fun gapsAreSkippedAndNothingGivesNothing() {
    assertEquals(listOf(21.0, 22.0, 22.0, 23.0), DashboardModel.minMeanMedianMax(listOf(null, 23.0, null, 21.0)))
    assertNull(DashboardModel.minMeanMedianMax(listOf(null, null)))
    assertNull(DashboardModel.minMeanMedianMax(emptyList()))
  }
}
