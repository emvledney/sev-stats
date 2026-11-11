package dev.silveradostats.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The driving state: the mapping and its fail-safe cases, the tracker, the update limit. */
class DrivingStateTest {
  private val park = DrivingPolicy.GEAR_PARK
  private val drive = 8
  private val reverse = 2
  private val neutral = 1

  private fun restricted(ux: Boolean?, gear: Int? = null, speed: Double? = null, car: Boolean = true) =
    DrivingPolicy.restricted(DrivingInputs(car, ux, gear, speed))

  @Test
  fun aNonCarDeviceIsNeverRestricted() {
    assertFalse(restricted(ux = null, car = false))
    assertFalse(restricted(ux = true, car = false))
    assertFalse(restricted(ux = null, gear = drive, speed = 30.0, car = false))
  }

  @Test
  fun anUnknownStateOnACarIsRestricted() {
    assertTrue("nothing read yet, service gone, or listener failed", restricted(ux = null))
    assertTrue("a Park reading does not make up for an unknown state", restricted(ux = null, gear = park, speed = 0.0))
  }

  @Test
  fun theServiceDecidesFirst() {
    assertTrue(restricted(ux = true))
    assertTrue("restricted even at a standstill in Park", restricted(ux = true, gear = park, speed = 0.0))
    assertFalse(restricted(ux = false))
    assertFalse(restricted(ux = false, gear = park, speed = 0.0))
  }

  @Test
  fun aGearOutOfParkOrAnySpeedAlsoRestricts() {
    assertTrue(restricted(ux = false, gear = drive))
    assertTrue(restricted(ux = false, gear = reverse))
    assertTrue(restricted(ux = false, gear = neutral))
    assertTrue(restricted(ux = false, speed = 15.0))
    assertTrue("with no gear to go by, any speed counts", restricted(ux = false, speed = 0.1))
    assertTrue(restricted(ux = false, gear = DrivingPolicy.GEAR_UNKNOWN, speed = 0.1))
    assertTrue(restricted(ux = false, gear = drive, speed = 0.0))
  }

  @Test
  fun aSpeedCountsInEitherDirection() {
    assertTrue("reversing, with no gear to go by", restricted(ux = false, speed = -1.5))
    assertTrue(restricted(ux = false, gear = DrivingPolicy.GEAR_UNKNOWN, speed = -0.1))
    assertTrue("too much to be noise, even in Park", restricted(ux = false, gear = park, speed = -1.5))
  }

  @Test
  fun aTinySpeedInParkIsNoise() {
    val noise = DrivingPolicy.PARK_SPEED_NOISE_MPS
    assertEquals("the speed the session itself calls moving", SessionTracker.MOVING_MPS, noise, 0.0)
    assertFalse(restricted(ux = false, gear = park, speed = 0.1))
    assertFalse(restricted(ux = false, gear = park, speed = -0.1))
    assertFalse(restricted(ux = false, gear = park, speed = noise))
    assertTrue(restricted(ux = false, gear = park, speed = noise + 0.01))
    assertTrue("only with the service's say-so", restricted(ux = true, gear = park, speed = 0.1))
    assertTrue(restricted(ux = null, gear = park, speed = 0.1))
  }

  @Test
  fun theReasonLineSaysWhatTheStateIsGoingBy() {
    assertEquals("Gear Park · speed 0.2 mph · restrictions off", DrivingPolicy.reason(DrivingInputs(true, false, park, 0.1)))
    assertEquals("Gear Drive · speed 33.6 mph · restrictions on", DrivingPolicy.reason(DrivingInputs(true, true, drive, 15.0)))
    assertEquals("Gear not read · speed not read · restrictions no answer", DrivingPolicy.reason(DrivingInputs(true)))
    assertEquals("Gear 777 · speed -3.4 mph · restrictions off", DrivingPolicy.reason(DrivingInputs(true, false, 777, -1.5)))
  }

  @Test
  fun whatCouldNotBeReadIsNoHint() {
    assertFalse("no gear, no speed (no permission, not offered)", restricted(ux = false, gear = null, speed = null))
    assertFalse("the property itself says it does not know", restricted(ux = false, gear = DrivingPolicy.GEAR_UNKNOWN))
    assertFalse(restricted(ux = false, gear = park, speed = Double.NaN))
    assertTrue("an unknown gear with a speed still restricts", restricted(ux = false, gear = DrivingPolicy.GEAR_UNKNOWN, speed = 3.0))
  }

  @Test
  fun aTrackerStartsFailSafeAndFollowsTheService() {
    val t = DrivingTracker(carDevice = true)
    assertTrue("before the first answer", t.restricted.value)
    assertFalse(t.known.value)
    t.onUx(false)
    assertFalse(t.restricted.value)
    assertTrue(t.known.value)
    t.onUx(true)
    assertTrue(t.restricted.value)
    t.onUx(false)
    assertFalse(t.restricted.value)
    t.onUx(null) // the listener errored, or the car service went away
    assertTrue(t.restricted.value)
    assertFalse(t.known.value)
  }

  @Test
  fun aTrackerUsesTheHint() {
    val t = DrivingTracker(carDevice = true)
    t.onUx(false)
    t.onHints(drive, 0.0)
    assertTrue(t.restricted.value)
    t.onHints(park, 15.0)
    assertTrue(t.restricted.value)
    assertEquals("what it read is there to explain it", DrivingInputs(true, false, park, 15.0), t.inputs.value)
    t.onHints(park, 0.0)
    assertFalse(t.restricted.value)
    t.onHints(null, null)
    assertFalse("a failed read says nothing", t.restricted.value)
  }

  @Test
  fun aTrackerOffACarIsNeverRestricted() {
    val t = DrivingTracker(carDevice = false)
    assertFalse(t.restricted.value)
    assertTrue(t.known.value)
    t.onUx(null)
    t.onHints(drive, 30.0)
    assertFalse(t.restricted.value)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun whileRestrictedValuesChangeAtMostOnceASecond() = runTest {
    val source = MutableStateFlow(0)
    val restricted = MutableStateFlow(true)
    val seen = mutableListOf<Pair<Long, Int>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      source.heldWhile(restricted).collect { seen += testScheduler.currentTime to it }
    }
    repeat(40) {
      advanceTimeBy(100)
      source.value = it + 1
      runCurrent()
    }
    assertTrue("values kept coming", seen.size >= 3)
    val gaps = seen.map { it.first }.zipWithNext { a, b -> b - a }
    assertTrue("gaps $gaps", gaps.all { it >= DRIVING_UPDATE_MS })

    // Back in Park: every value, at once.
    seen.clear()
    restricted.value = false
    runCurrent()
    source.value = 1000
    runCurrent()
    source.value = 1001
    runCurrent()
    assertEquals(listOf(1000, 1001), seen.map { it.second }.dropWhile { it < 1000 })
  }
}
