package dev.silveradostats.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How the log is connected to the app, read from the sources (as AndroidWiringTest does for the rest). */
class LogWiringTest {
  private val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }

  private fun src(rel: String) = File(main, "dev/silveradostats/$rel").readText()

  private fun callers(call: Regex): Set<String> =
    main.walkTopDown().filter { it.isFile && it.extension == "kt" && call.containsMatchIn(it.readText()) }.map { it.name }.toSet()

  @Test
  fun theOnlyWayTheLogConnectsToTheMxIsTheGatedVisit() {
    val hub = src("log/LogHub.kt")
    assertEquals("one connection for the log", 1, Regex("""\bLinkConnection\(""").findAll(hub).count())
    val visit = hub.substring(hub.indexOf("suspend fun visit("), hub.indexOf("/** What Trends draws"))
    assertTrue(visit.indexOf("LogPolicy.visitAllowed(") in 0 until visit.indexOf("LinkConnection("))
    assertTrue("a visit always lets go", visit.contains("link.close()"))
    assertEquals(setOf("LogScheduler.kt"), callers(Regex("""\bhub\.visit\(""")))
    // It goes through the app's link service: no socket, transport or poller of its own.
    for (name in listOf("log/LogHub.kt", "log/LogScheduler.kt", "log/LogModel.kt", "log/TrendsModel.kt")) {
      assertFalse(name, Regex("""\b(BluetoothSocket|ElmTransport|ElmLink|Poller|LinkEngine|UdsClient)\b""").containsMatchIn(src(name)))
    }
  }

  @Test
  fun theMxRowsOnlyListen() {
    assertTrue(src("link/LinkService.kt").contains("engine.state.collect { logs.onLive(it) }"))
    val hub = src("log/LogHub.kt")
    val onLive = hub.substring(hub.indexOf("fun onLive("), hub.indexOf("suspend fun visit("))
    assertFalse(onLive.contains("LinkConnection"))
    assertFalse(onLive.contains(".attach("))
  }

  @Test
  fun builtInRowsOnScreenStopWithTheScreen() {
    assertEquals(setOf("MainActivity.kt"), callers(Regex("""\.whileOnScreen\(""")))
    assertTrue(src("MainActivity.kt").contains("repeatOnLifecycle(Lifecycle.State.STARTED) { logs.whileOnScreen() }"))
    // It reads the car service. Whatever reaches the truck's bus goes through the visit or the screens.
    val hub = src("log/LogHub.kt")
    val onScreen = hub.substring(hub.indexOf("suspend fun whileOnScreen("), hub.indexOf("/** Main thread. One read of the built-in data"))
    assertFalse(onScreen.contains("LinkConnection"))
    assertFalse(onScreen.contains("visit("))
  }

  @Test
  fun trendsIsParkedOnlyAndNeedsNoObd() {
    val shell = src("ui/AppShell.kt")
    val branch = shell.substring(shell.indexOf("AppTab.TRENDS -> if (layout.restricted) {"), shell.indexOf("AppTab.DIAGNOSTICS -> Column"))
    assertTrue(branch.indexOf("AvailableInParkPanel(") in 0 until branch.indexOf("} else {"))
    assertTrue(branch.indexOf("} else {") < branch.indexOf("TrendsScreen("))
    // Always offered in the rail (the owner's choice, 2026-11-11): restricted, selecting Trends leads only to the panel above.
    assertTrue(shell.contains("AppTab.entries.forEach { t -> NavButton("))
    assertEquals(setOf("AppShell.kt"), callers(Regex("""(?<!fun )\bTrendsScreen\(""")))
    assertTrue(src("link/AppSettings.kt").contains("TRENDS(\"Trends\", false)"))
    assertFalse("one page", Regex("""\bverticalScroll\b|\bLazyColumn\b|\bhorizontalScroll\b""").containsMatchIn(src("ui/trends/TrendsScreen.kt")))
  }

  @Test
  fun aPageIsWorkedOutOffTheMainThreadFromOneLoad() {
    val screen = src("ui/trends/TrendsScreen.kt")
    val load = screen.substring(screen.indexOf("val page by produceState"), screen.indexOf("val saver ="))
    assertTrue(load.contains("withContext(Dispatchers.IO)"))
    assertTrue(load.contains("TrendsModel.page(hub.trendData("))
    assertFalse("the only place rows are read", Regex("""\.trendData\(""").findAll(screen.replace(load, "")).any())
  }

  @Test
  fun demoDataStaysOutOfTheStore() {
    val hub = src("log/LogHub.kt")
    assertEquals("demo rows only for the chart", 1, Regex("""\bDemoLog\.rows\(""").findAll(hub).count())
    val export = hub.substring(hub.indexOf("suspend fun export("), hub.indexOf("private suspend fun save("))
    assertFalse(export.contains("DemoLog"))
    assertTrue(hub.contains("if (s.demo || s.status != LinkStatus.CONNECTED) return"))
  }
}
