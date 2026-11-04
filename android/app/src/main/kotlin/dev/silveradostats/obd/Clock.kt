package dev.silveradostats.obd

/** Monotonic seconds since this class was loaded (never negative), like Python's time.monotonic(). */
object Monotonic {
  private val origin = System.nanoTime()

  fun seconds(): Double = (System.nanoTime() - origin) / 1e9
}

/** Wall-clock seconds since the epoch, like Python's time.time(). */
fun wallSeconds(): Double = System.currentTimeMillis() / 1000.0

/** Blocks the calling thread for [s] seconds (nothing for s <= 0), like Python's time.sleep(). */
fun sleepSeconds(s: Double) {
  if (s <= 0.0 || s.isNaN()) return
  val nanos = (s * 1e9).toLong()
  Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
}
