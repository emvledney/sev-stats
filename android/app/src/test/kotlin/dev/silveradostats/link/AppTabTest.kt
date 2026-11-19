package dev.silveradostats.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** A visible tab that shows OBD data attaches the engine; Map (the default, 2026-11-18) only with an MX+ chosen, Diagnostics not as a panel. */
class AppTabTest {
  @Test
  fun obdIsWantedOnlyWhereItsDataIsOnScreen() {
    assertEquals(false, AppTab.MAP.wantsObd(restricted = false))
    assertEquals(false, AppTab.MAP.wantsObd(restricted = true))
    assertEquals(true, AppTab.LIVE.wantsObd(restricted = false))
    assertEquals("Live still shows it while driving", true, AppTab.LIVE.wantsObd(restricted = true))
    assertEquals("Cells, in every gear", true, AppTab.CELLS.wantsObd(restricted = true))
    assertEquals(true, AppTab.DIAGNOSTICS.wantsObd(restricted = false))
    assertEquals("the Available in Park panel shows none", false, AppTab.DIAGNOSTICS.wantsObd(restricted = true))
    // Map uses a chosen MX+ too, as the API tab did, for the log and the session's energy, unless it is disconnected.
    fun simple(address: String?, off: Boolean = false, demo: Boolean = false) =
      UiSettings(demo, address, null, AppTab.MAP, mxOff = off).wantsObd(restricted = true)
    assertEquals(true, simple("00:04:3E:00:00:09"))
    assertEquals(false, simple(null))
    assertEquals(false, simple("00:04:3E:00:00:09", off = true))
    assertEquals(false, simple("00:04:3E:00:00:09", demo = true))
    // The app opens on Map; the API tab is gone.
    assertEquals(AppTab.MAP, UiSettings(false, null, null).tab)
    assertEquals(listOf("MAP", "LIVE", "CELLS", "DIAGNOSTICS"), AppTab.entries.map { it.name })
  }
}
