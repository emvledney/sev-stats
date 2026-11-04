package dev.silveradostats.link

/**
 * Fixed-size ring buffer of power samples covering the last [windowMs] (5 minutes by default).
 * Pure Kotlin; not thread-safe (the poller thread owns it and publishes [snapshot]s).
 */
class PowerHistory(val windowMs: Long = WINDOW_MS, val capacity: Int = CAPACITY) {
  private val at = LongArray(capacity)
  private val kw = DoubleArray(capacity)
  private var start = 0
  var size = 0
    private set

  init {
    require(capacity > 0 && windowMs > 0)
  }

  /** Appends one point; points must arrive in time order (older or equal ones are ignored). */
  fun add(atMs: Long, value: Double) {
    if (!value.isFinite()) return
    if (size > 0 && atMs < at[(start + size - 1) % capacity]) return
    if (size == capacity) {
      start = (start + 1) % capacity
      size -= 1
    }
    val i = (start + size) % capacity
    at[i] = atMs
    kw[i] = value
    size += 1
  }

  /** Drops points older than [windowMs] before [nowMs]. */
  fun prune(nowMs: Long) {
    while (size > 0 && nowMs - at[start] > windowMs) {
      start = (start + 1) % capacity
      size -= 1
    }
  }

  fun clear() {
    start = 0
    size = 0
  }

  /** The points of the last [windowMs] before [nowMs], oldest first. */
  fun snapshot(nowMs: Long): List<PowerPoint> {
    prune(nowMs)
    return List(size) { k ->
      val i = (start + k) % capacity
      PowerPoint(at[i], kw[i])
    }
  }

  companion object {
    const val WINDOW_MS = 5 * 60_000L

    /** 5 minutes at the 1 s current/voltage period, with room to spare. */
    const val CAPACITY = 600
  }
}
