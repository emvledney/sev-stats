package dev.silveradostats.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Only a visible tab that shows OBD data attaches the engine: Simple never, Diagnostics not as a panel. */
class AppTabTest {
  @Test
  fun obdIsWantedOnlyWhereItsDataIsOnScreen() {
    assertEquals(false, AppTab.SIMPLE.wantsObd(restricted = false))
    assertEquals(false, AppTab.SIMPLE.wantsObd(restricted = true))
    assertEquals(true, AppTab.LIVE.wantsObd(restricted = false))
    assertEquals("Live still shows it while driving", true, AppTab.LIVE.wantsObd(restricted = true))
    assertEquals(true, AppTab.DIAGNOSTICS.wantsObd(restricted = false))
    assertEquals("the Available in Park panel shows none", false, AppTab.DIAGNOSTICS.wantsObd(restricted = true))
  }
}
