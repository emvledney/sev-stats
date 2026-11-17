package dev.silveradostats.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** A visible tab that shows OBD data attaches the engine; Simple only with an MX+ chosen, Diagnostics not as a panel. */
class AppTabTest {
  @Test
  fun obdIsWantedOnlyWhereItsDataIsOnScreen() {
    assertEquals(false, AppTab.SIMPLE.wantsObd(restricted = false))
    assertEquals(false, AppTab.SIMPLE.wantsObd(restricted = true))
    assertEquals(true, AppTab.LIVE.wantsObd(restricted = false))
    assertEquals("Live still shows it while driving", true, AppTab.LIVE.wantsObd(restricted = true))
    assertEquals(true, AppTab.DIAGNOSTICS.wantsObd(restricted = false))
    assertEquals("the Available in Park panel shows none", false, AppTab.DIAGNOSTICS.wantsObd(restricted = true))
    // Simple uses a chosen MX+ too, for the session's energy, unless it is disconnected.
    fun simple(address: String?, off: Boolean = false, demo: Boolean = false) =
      UiSettings(demo, address, null, AppTab.SIMPLE, mxOff = off).wantsObd(restricted = true)
    assertEquals(true, simple("00:04:3E:00:00:09"))
    assertEquals(false, simple(null))
    assertEquals(false, simple("00:04:3E:00:00:09", off = true))
    assertEquals(false, simple("00:04:3E:00:00:09", demo = true))
  }
}
