package dev.silveradostats.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BtErrorsTest {
  @Test
  fun unreachableMessages() {
    val timeout = BtErrors.connectFailure(TimedConnect.Timeout(10_000), timedOut = true)
    assertTrue(timeout.retryable)
    assertTrue(timeout.message!!.startsWith(BtErrors.UNREACHABLE))
    assertTrue(timeout.message!!.contains("10 s"))
    for (m in listOf("read failed, socket might closed or timeout, read ret: -1", "Host is down", "Connection timed out", "socket closed", "Connection refused")) {
      val f = BtErrors.connectFailure(IOException(m), timedOut = false)
      assertTrue(m, f.retryable)
      assertTrue(m, f.message!!.startsWith("MX+ not reachable: is your phone connected to it? Turn the phone's Bluetooth off."))
    }
  }

  @Test
  fun actionableMessages() {
    val perm = BtErrors.connectFailure(SecurityException("Need android.permission.BLUETOOTH_CONNECT"), timedOut = false)
    assertFalse(perm.retryable)
    assertEquals(BtErrors.NO_CONNECT_PERMISSION, perm.message)
    val auth = BtErrors.connectFailure(IOException("Authentication failed"), timedOut = false)
    assertFalse(auth.retryable)
    assertEquals(BtErrors.PAIRING_STALE, auth.message)
    assertEquals(BtErrors.SERVICE_NOT_FOUND, BtErrors.connectFailure(IOException("Service discovery failed"), false).message)
    val other = BtErrors.connectFailure(IOException("weird"), false)
    assertTrue(other.retryable)
    assertTrue(other.message!!.contains("weird"))
  }

  @Test
  fun obdNameFilter() {
    assertTrue(BtErrors.isObdName("OBDLink MX+ 12345"))
    assertTrue(BtErrors.isObdName("obdlink cx"))
    assertTrue(BtErrors.isObdName("OBDII"))
    assertFalse(BtErrors.isObdName("Pixel 8"))
    assertFalse(BtErrors.isObdName(null))
    assertTrue(BtDevice("00:04:3E:00:00:01", "OBDLink MX+", bonded = true).obdLike)
    assertFalse(BtDevice("00:04:3E:00:00:01", null, bonded = true).obdLike)
  }

  @Test
  fun addressMasking() {
    assertEquals("00:04:3E:XX:XX:XX", BtErrors.maskAddress("00:04:3E:12:34:56"))
    assertEquals("XX:XX:XX:XX:XX:XX", BtErrors.maskAddress("garbage"))
    assertNull(BtErrors.maskAddress(null))
    assertEquals("OBDLink MX+ 00:04:3e:XX:XX:XX ok", BtErrors.maskAddressesIn("OBDLink MX+ 00:04:3e:a1:b2:c3 ok"))
    assertEquals("no address here", BtErrors.maskAddressesIn("no address here"))
  }

  @Test
  fun timedConnectAbortsAfterTheDeadline() {
    val aborted = CountDownLatch(1)
    val release = CountDownLatch(1)
    val t0 = System.nanoTime()
    try {
      TimedConnect.run(200, connect = { release.await(5, TimeUnit.SECONDS) }, abort = {
        aborted.countDown()
        release.countDown() // closing the socket makes connect() return
      })
      fail("must time out")
    } catch (e: TimedConnect.Timeout) {
      assertEquals(200, e.timeoutMs)
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue("took $ms ms", ms in 150..2_000)
    assertTrue(aborted.await(0, TimeUnit.MILLISECONDS))
  }

  @Test
  fun timedConnectPassesResultsThrough() {
    TimedConnect.run(1_000, connect = {}, abort = { fail("no abort on success") })
    val boom = IOException("read failed")
    try {
      TimedConnect.run(1_000, connect = { throw boom }, abort = { fail("no abort on failure") })
      fail("must rethrow")
    } catch (e: IOException) {
      assertSame(boom, e)
    }
  }
}
