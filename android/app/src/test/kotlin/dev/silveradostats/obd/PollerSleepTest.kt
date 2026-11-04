package dev.silveradostats.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of PollerSleepTests: the poller must let the truck sleep and stay bus-silent while it does. */
class PollerSleepTest {

  /** Sleepy simulator + poller on one simulated clock (the probe's SleepyTransport / make()). */
  private class Rig(volts: Double? = 12.6, stopWhenAsleep: Boolean = false) {
    var now = 1000.0
    val t = SimTransport(clock = { now }, sleeper = {}).also { it.volts = volts }
    val poller = Poller(
      TestSupport.profile, t, stopWhenAsleep = stopWhenAsleep, clock = { now }, wallClock = { 1.7e9 + now },
      clientFactory = { UdsClient(it, clock = { now }, sleeper = {}) },
    )

    fun runUntil(state: PollerState, step: Double = 1.0, limit: Int = 10_000) {
      repeat(limit) {
        if (poller.state == state) return
        now += step
        poller.cycle()
      }
      fail("never reached $state")
    }
  }

  @Test
  fun fallingAsleepCostsFewUnansweredRequests() {
    val r = Rig()
    repeat(300) { // five minutes of normal polling
      r.now += 1
      r.poller.cycle()
    }
    r.t.silent = true
    val before = r.t.sent
    r.runUntil(PollerState.ASLEEP, step = 0.5)
    assertTrue("too many requests into a sleeping truck: ${r.t.sent - before}", r.t.sent - before <= 5)
  }

  @Test
  fun fallingAsleepProbesAreSpacedOut() {
    val r = Rig()
    repeat(10) {
      r.now += 1
      r.poller.cycle()
    }
    r.t.silent = true
    val sendTimes = mutableListOf<Double>()
    repeat(200) {
      if (r.poller.state != PollerState.AWAKE) return@repeat
      r.now += 0.1
      val n = r.t.sent
      r.poller.cycle()
      repeat(r.t.sent - n) { sendTimes += r.now }
    }
    assertEquals(PollerState.ASLEEP, r.poller.state)
    // The first silent read (plus the probe, unless it was the probe) in one cycle, then one probe
    // per cycle, at least PROBE_GAP_S apart, until SILENT_PROBES have gone unanswered.
    val cycles = sendTimes.groupBy { it }
    assertEquals(Poller.SILENT_PROBES, cycles.size)
    assertTrue(cycles.values.first().size in 1..2)
    assertTrue(cycles.values.drop(1).all { it.size == 1 })
    for ((a, b) in cycles.keys.zipWithNext()) {
      assertTrue("probe ${b - a} s after the previous one", b - a >= Poller.PROBE_GAP_S - 1e-9)
    }
    assertEquals(Poller.WATCH_S, r.poller.nextWait()!!, 1e-9)
  }

  @Test
  fun quietWhileAsleepThenSingleProbeOnWake() {
    val r = Rig()
    r.poller.cycle()
    assertTrue(r.t.sent > 30)
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    val before = r.t.sent
    repeat(30) { // 30 minutes asleep at flat voltage
      r.now += Poller.WATCH_S
      val s = r.poller.cycle()
      assertTrue(s.busSilent)
      assertTrue(s.asleep)
    }
    assertEquals("nothing may be sent while asleep", before, r.t.sent)
    r.t.volts = 13.9 // DC-DC came on
    r.t.silent = false
    r.now += Poller.WATCH_S
    r.poller.cycle()
    assertEquals("exactly one probe on wake", before + 1, r.t.sent)
    assertEquals(PollerState.AWAKE, r.poller.state)
  }

  @Test
  fun highRestingVoltageDoesNotProbe() {
    val r = Rig(volts = 13.2)
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    val before = r.t.sent
    repeat(20) {
      r.now += Poller.WATCH_S
      r.poller.cycle()
    }
    assertEquals(before, r.t.sent)
  }

  @Test
  fun failedVoltageReadDoesNotProbe() {
    val r = Rig()
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    val before = r.t.sent
    r.t.volts = null // one ATRV hiccup
    r.now += Poller.WATCH_S
    r.poller.cycle()
    assertEquals(before, r.t.sent)
    repeat(Poller.VOLTAGE_FAILURES_LIMIT) {
      r.now += Poller.WATCH_S
      r.poller.cycle()
    }
    assertEquals(PollerState.STOPPED, r.poller.state)
    assertEquals(before, r.t.sent)
  }

  @Test
  fun failedProbeNeedsAFreshRise() {
    val r = Rig()
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    val before = r.t.sent
    r.t.volts = 13.9 // rise, but modules still silent
    repeat(10) {
      r.now += Poller.WATCH_S
      r.poller.cycle()
    }
    assertEquals("one probe per voltage rise, not one per minute", before + 1, r.t.sent)
    r.t.volts = 12.5 // back to rest...
    r.now += Poller.WATCH_S
    r.poller.cycle()
    r.t.volts = 13.9 // ...then a real wake
    r.t.silent = false
    r.now += Poller.WATCH_S
    r.poller.cycle()
    assertEquals(PollerState.AWAKE, r.poller.state)
    assertEquals(before + 2, r.t.sent)
  }

  @Test
  fun noVoltageAdapterProbesOnScheduleThenStops() {
    val r = Rig(volts = null)
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    val before = r.t.sent
    val probeTimes = mutableListOf<Double>()
    repeat(10_000) {
      if (r.poller.state == PollerState.STOPPED) return@repeat
      r.now += 10.0
      val n = r.t.sent
      val s = r.poller.cycle()
      assertFalse("an adapter without voltage is not bus-silent", s.busSilent)
      if (r.t.sent > n) probeTimes += r.now
    }
    assertEquals(PollerState.STOPPED, r.poller.state)
    assertEquals(Poller.NO_VOLTAGE_PROBES_S.size, r.t.sent - before)
    // 1, 5 and 30 minutes apart (10 s simulation steps)
    val gaps = probeTimes.zipWithNext { a, b -> b - a }
    assertEquals(listOf(300.0, 1800.0), gaps.map { Math.round(it / 10.0) * 10.0 })
    assertNull(r.poller.nextWait())
  }

  @Test
  fun oneSilentDidDoesNotLookLikeSleep() {
    val r = Rig()
    val silentKey = DidKey("bcm", 0x4149)
    val orig = r.poller.clients.getValue("bcm")
    r.poller.clients["bcm"] = DidReader { did ->
      if (did == silentKey.did) throw NoResponse("silent DID")
      orig.readDid(did)
    }
    repeat(120) {
      r.now += 1
      r.poller.cycle()
    }
    assertEquals(PollerState.AWAKE, r.poller.state)
    assertTrue(r.poller.period.getValue(silentKey) > 10)
  }

  @Test
  fun pollStopsWhenAsleepByDefault() {
    val r = Rig(stopWhenAsleep = true)
    r.t.silent = true
    r.runUntil(PollerState.STOPPED)
    assertNull(r.poller.nextWait())
    val before = r.t.sent
    repeat(100) {
      r.now += Poller.WATCH_S
      r.poller.cycle()
    }
    assertEquals(before, r.t.sent)
  }

  @Test
  fun tieredPeriodsAndStaggeredWake() {
    val r = Rig()
    val first = r.poller.cycle()
    assertEquals(TestSupport.profile.didFields.size, first.values.size)
    assertTrue(first.adapterVoltsRead)
    assertEquals(12.6, first.adapterVolts!!, 0.0)
    assertEquals(24, first.derived["modules"])
    val afterFirst = r.t.sent
    r.now += 1.0
    val second = r.poller.cycle()
    // 1 s tier: dmcm pack voltage and current only
    assertEquals(setOf("pack_voltage", "pack_current"), second.values.keys)
    assertEquals(afterFirst + 2, r.t.sent)
    assertFalse(second.adapterVoltsRead) // adapter voltage every 30 s while awake
    assertEquals(1.0, r.poller.nextWait()!!, 1e-9) // next: the 1 s and 2 s tiers at +1 s
    // VIN-shaped data never reaches a Reading
    assertTrue(r.poller.latest.values.none { it.v is String && (it.v as String).contains("SIMULATED") })
    // fall asleep, then wake: DIDs come back 0.2 s apart, not all at once
    r.t.silent = true
    r.runUntil(PollerState.ASLEEP)
    r.t.silent = false
    r.t.volts = 13.9
    r.now += Poller.WATCH_S
    r.poller.cycle()
    assertEquals(PollerState.AWAKE, r.poller.state)
    val woke = r.t.sent
    r.now += 0.0
    r.poller.cycle()
    assertEquals(woke + 1, r.t.sent)
    r.now += 0.2
    r.poller.cycle()
    assertEquals(woke + 2, r.t.sent)
  }

  @Test
  fun transportErrorsAreNotSilence() {
    val r = Rig()
    r.poller.clients["becm"] = DidReader { throw FatalTransportError("adapter reported 'LV RESET'") }
    try {
      r.poller.cycle()
      fail("a transport error must end the run")
    } catch (e: FatalTransportError) {
      assertEquals(PollerState.AWAKE, r.poller.state)
    }
  }
}
