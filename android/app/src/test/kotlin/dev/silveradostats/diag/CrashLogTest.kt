package dev.silveradostats.diag

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
  @Test
  fun formatHasContextAndFullStackTraceWithCause() {
    val e = IllegalStateException("outer", IllegalArgumentException("root cause"))
    val text = CrashLog.format(0L, "0.1.0 (1)", "UQBM.123 (SDK 34)", "main", e)
    assertTrue(text.contains("app: 0.1.0 (1)"))
    assertTrue(text.contains("build: UQBM.123 (SDK 34)"))
    assertTrue(text.contains("thread: main"))
    assertTrue(text.contains("java.lang.IllegalStateException: outer"))
    assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
    assertTrue(text.contains("CrashLogTest"))
  }

  @Test
  fun formatIsBounded() {
    val text = CrashLog.format(0L, "a", "b", "t", RuntimeException("x".repeat(200_000)))
    assertTrue(text.length <= CrashLog.MAX_CHARS + 20)
    assertTrue(text.endsWith("(truncated)"))
  }
}
