package dev.silveradostats.glance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GlanceRefresh: at most one invalidate() per second, only when the shown text would change, and
 * none while the host still owes the previous template.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlanceRefreshTest {

  private fun view(n: Int) = GlanceView(
    title = GlanceModel.TITLE,
    status = "s$n",
    canRetry = false,
    demo = true,
    rows = GlanceModel.ROW_TITLES.map { GlanceRow(it, listOf("v$n")) },
  )

  /**
   * A fake host: on invalidate() it asks for the template [hostDelayMs] later (0 = at once, null =
   * never), like the real host calling onGetTemplate. [data] is what the view is built from.
   */
  private inner class Rig(val scope: TestScope, val hostDelayMs: Long? = 0) {
    val data = MutableStateFlow(0)
    val host = HostTemplates()
    val invalidates = mutableListOf<Long>()
    val serves = mutableListOf<Long>()
    private var pending = false

    private fun serve() {
      host.onServed(view(data.value))
      serves += scope.testScheduler.currentTime
    }

    fun start() {
      // The first template: the host asks for it when the screen starts.
      serve()
      scope.backgroundScope.launch {
        GlanceRefresh.run(
          changes = data,
          current = { view(data.value) },
          host = host,
          invalidate = {
            invalidates += scope.testScheduler.currentTime
            val d = hostDelayMs
            when {
              d == null -> {}
              d == 0L -> serve()
              !pending -> {
                // Like the real host: requests while one is held back are folded into it.
                pending = true
                scope.backgroundScope.launch {
                  delay(d)
                  pending = false
                  serve()
                }
              }
            }
          },
          clock = { scope.testScheduler.currentTime },
        )
      }
      scope.runCurrent()
    }

    /** New data every [everyMs] for [forMs]. */
    fun stream(everyMs: Long, forMs: Long) {
      var t = 0L
      while (t < forMs) {
        scope.advanceTimeBy(everyMs)
        t += everyMs
        data.value += 1
        scope.runCurrent()
      }
    }
  }

  private fun assertSpacing(times: List<Long>) {
    for ((a, b) in times.zipWithNext()) assertTrue("invalidates at $a and $b ms: closer than 1 s", b - a >= 1_000)
  }

  @Test
  fun noChangeMeansNoInvalidate() = runTest {
    val rig = Rig(this)
    rig.start()
    advanceTimeBy(60_000)
    assertEquals(emptyList<Long>(), rig.invalidates)
  }

  @Test
  fun firstChangeGoesOutAtOnceAndABurstFoldsIntoOne() = runTest {
    val rig = Rig(this)
    rig.start()
    advanceTimeBy(5_000)
    rig.data.value = 1
    runCurrent()
    assertEquals(listOf(5_000L), rig.invalidates)
    // 9 more changes within 90 ms: one more invalidate, a second after the first, with the latest data.
    repeat(9) {
      advanceTimeBy(10)
      rig.data.value = it + 2
      runCurrent()
    }
    advanceTimeBy(2_000)
    assertEquals(listOf(5_000L, 6_000L), rig.invalidates)
    assertEquals(view(10), rig.host.shown)
  }

  @Test
  fun continuousChangesAreCappedAtOncePerSecond() = runTest {
    val rig = Rig(this)
    rig.start()
    rig.stream(everyMs = 100, forMs = 20_000)
    advanceTimeBy(1_500)
    assertSpacing(rig.invalidates)
    assertTrue("${rig.invalidates.size} invalidates", rig.invalidates.size in 19..21)
    assertEquals(view(200), rig.host.shown)
  }

  /**
   * The host holds refreshes back (10 s on the emulator's Templates Host): while it owes a
   * template nothing more is sent, and the template it finally takes has the newest data.
   */
  @Test
  fun aSlowHostIsNotAskedAgainWhileItOwesATemplate() = runTest {
    val rig = Rig(this, hostDelayMs = 10_000)
    rig.start()
    rig.stream(everyMs = 100, forMs = 35_000)
    // 35 s of new data every 100 ms: 4 requests (not 35), one per template the host took.
    assertEquals(rig.invalidates.toString(), 4, rig.invalidates.size)
    assertEquals(rig.serves.toString(), 4, rig.serves.size)
    for ((prev, next) in rig.invalidates.zipWithNext()) {
      assertTrue("request at $next ms before the host served the one from $prev ms", rig.serves.any { it in (prev + 1)..next })
      assertTrue(next - prev in 10_000L..10_200L)
    }
    advanceTimeBy(10_000)
    assertEquals(view(350), rig.host.shown)
  }

  /** A host that never answers is asked again only every RESEND_MS. */
  @Test
  fun aSilentHostIsAskedAgainOnlyAfterTheResendTime() = runTest {
    val rig = Rig(this, hostDelayMs = null)
    rig.start()
    rig.stream(everyMs = 500, forMs = 95_000)
    assertEquals(listOf(500L, 30_500L, 60_500L, 90_500L), rig.invalidates)
  }

  /** Text that changes with time alone (ages) is picked up by the 1 s tick, without new data. */
  @Test
  fun theTickCatchesTimeOnlyChanges() = runTest {
    var t = 0
    val host = HostTemplates().apply { onServed(view(0)) }
    val calls = mutableListOf<Long>()
    backgroundScope.launch {
      GlanceRefresh.run(
        changes = MutableStateFlow(Unit),
        current = { view(t) },
        host = host,
        invalidate = {
          calls += testScheduler.currentTime
          host.onServed(view(t))
        },
        clock = { testScheduler.currentTime },
      )
    }
    runCurrent()
    advanceTimeBy(2_500)
    assertEquals(emptyList<Long>(), calls)
    t = 1
    advanceTimeBy(1_100)
    assertEquals(listOf(3_000L), calls)
  }

  @Test
  fun refusesAFasterRate() = runTest {
    try {
      GlanceRefresh.run(MutableStateFlow(0), { view(0) }, HostTemplates(), {}, { 0L }, minIntervalMs = 500)
      fail("accepted a 500 ms interval")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("once per second"))
    }
  }
}
