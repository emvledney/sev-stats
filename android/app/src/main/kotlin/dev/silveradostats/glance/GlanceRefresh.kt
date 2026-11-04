package dev.silveradostats.glance

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/*
 * Pure Kotlin (no android.* imports): when Battery Glance asks the host for a new template.
 * Unit tested on the JVM with virtual time.
 */

/** What the host last got. GlanceScreen.onGetTemplate records every template it hands out. Main thread only. */
class HostTemplates {
  /** The view of the last template the host got, or null before the first one. */
  var shown: GlanceView? = null
    private set

  /** How many templates the host has asked for so far. */
  var served: Long = 0
    private set

  fun onServed(view: GlanceView) {
    shown = view
    served += 1
  }
}

object GlanceRefresh {
  /** Never more than one Screen.invalidate() per second (the host may throttle further). */
  const val MIN_INTERVAL_MS = 1_000L

  /** Re-checks the view this often without new data, so ages and staleness keep up. */
  const val TICK_MS = 1_000L

  /**
   * After an invalidate() the host owes a template, and it gets the newest view when it asks for
   * it, so no further invalidate() is sent until then. Only if it hasn't asked within this long is
   * the request repeated. (Hosts hold refreshes back: about 10 s on the API 34 emulator's
   * Templates Host, 7–8 s reported on Polestar.)
   */
  const val RESEND_MS = 30_000L

  /**
   * Runs until cancelled (GlanceScreen runs it while the screen is started). Wakes on every
   * [changes] emission and every [tickMs], and calls [invalidate] only when
   *  - the [current] view differs from what the host last got ([host]),
   *  - the host does not still owe the template of the previous [invalidate] (or has owed it for
   *    [RESEND_MS] already), and
   *  - at least [minIntervalMs] have passed since the previous [invalidate].
   * Call on the main thread.
   */
  suspend fun run(
    changes: Flow<Any?>,
    current: () -> GlanceView,
    host: HostTemplates,
    invalidate: () -> Unit,
    clock: () -> Long,
    minIntervalMs: Long = MIN_INTERVAL_MS,
    tickMs: Long = TICK_MS,
  ): Unit = coroutineScope {
    require(minIntervalMs >= MIN_INTERVAL_MS) { "refusing to refresh more often than once per second" }
    require(tickMs > 0)
    val wake = Channel<Unit>(Channel.CONFLATED)
    launch { changes.collect { wake.trySend(Unit) } }
    launch {
      while (true) {
        delay(tickMs)
        wake.trySend(Unit)
      }
    }
    var lastAt: Long? = null
    // host.served when the last invalidate() went out: the host owes a template while it is unchanged.
    var owedAt: Long? = null

    fun needed(): Boolean {
      if (current() == host.shown) return false
      val at = lastAt ?: return true
      val owing = owedAt == host.served && clock() - at < RESEND_MS
      return !owing
    }

    while (true) {
      wake.receive()
      if (!needed()) continue
      val wait = lastAt?.let { it + minIntervalMs - clock() } ?: 0L
      if (wait > 0) delay(minOf(wait, minIntervalMs))
      if (!needed()) continue
      lastAt = clock()
      owedAt = host.served
      invalidate()
    }
  }
}
