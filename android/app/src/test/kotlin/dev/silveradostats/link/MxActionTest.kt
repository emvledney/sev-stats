package dev.silveradostats.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the MX+ button offers in each link state. A real link's run: LinkEngineLifecycleTest. */
class MxActionTest {
  private fun of(status: LinkStatus, canRetry: Boolean = false, mxOff: Boolean = false) =
    MxAction.of(mxOff, LiveState(status = status, canRetry = canRetry))

  @Test
  fun disconnectOnlyWhileTheAppHoldsTheMx() {
    assertEquals(MxAction.DISCONNECT, of(LinkStatus.CONNECTED))
    assertEquals("the link stays open, silent, while the truck sleeps", MxAction.DISCONNECT, of(LinkStatus.TRUCK_ASLEEP))
  }

  @Test
  fun anAttemptInProgressCanBeCancelled() {
    assertEquals(MxAction.CANCEL, of(LinkStatus.CONNECTING))
  }

  @Test
  fun anAutomaticRetryKeepsOfferingConnect() {
    assertEquals(MxAction.CONNECT, of(LinkStatus.CONNECTING, canRetry = true))
  }

  @Test
  fun aLinkThatIsDownOffersConnect() {
    assertEquals(MxAction.CONNECT, of(LinkStatus.ADAPTER_UNREACHABLE, canRetry = true))
    assertEquals(MxAction.CONNECT, of(LinkStatus.STOPPED_ERROR, canRetry = true))
    assertEquals("polling gave up: the link is closed", MxAction.CONNECT, of(LinkStatus.TRUCK_ASLEEP, canRetry = true))
    assertEquals(MxAction.CONNECT, of(LinkStatus.DISCONNECTED))
    assertEquals(MxAction.CONNECT, of(LinkStatus.PAIRING))
    assertEquals("before the service has reported anything", MxAction.CONNECT, of(LinkStatus.NO_ADAPTER))
  }

  @Test
  fun aDisconnectTheUserAskedForWinsOverAnyStatus() {
    for (status in LinkStatus.entries) assertEquals(status.name, MxAction.CONNECT, of(status, mxOff = true))
  }

  /** The header's width budget (DashLayout: MX_CHARS, MX_SHORT_CHARS) must keep holding every label. */
  @Test
  fun labelsFitTheHeader() {
    for (a in MxAction.entries) {
      assertTrue(a.label, a.label.length <= 14)
      assertTrue(a.shortLabel, a.shortLabel.length <= 7)
    }
  }
}
