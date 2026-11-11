package dev.silveradostats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android wiring that the JVM tests can't run (no Bluetooth, no activity), pinned in the source
 * like SourceSafetyTest does: review findings on Pair new and the permission grant.
 */
class AndroidWiringTest {
  private fun src(rel: String): String =
    listOf(File("src/main/kotlin/dev/silveradostats/$rel"), File("app/src/main/kotlin/dev/silveradostats/$rel"))
      .firstOrNull { it.isFile }?.readText() ?: error("cannot find $rel from ${File(".").absolutePath}")

  /** A device whose name only arrives after inquiry (ACTION_NAME_CHANGED) is still offered. */
  @Test
  fun discoveryAlsoUsesResolvedNames() {
    val t = src("bt/BtController.kt")
    assertTrue(Regex("""addAction\(BluetoothDevice\.ACTION_NAME_CHANGED\)""").containsMatchIn(t))
    assertTrue(Regex("""BluetoothDevice\.ACTION_FOUND, BluetoothDevice\.ACTION_NAME_CHANGED -> onFound\(intent\)""").containsMatchIn(t))
  }

  /**
   * The runtime-permission dialog only pauses MainActivity, so the grant must refresh the MX+ side
   * (Bluetooth state, and a link stopped for the missing permission) itself: in the dialog
   * callback, in the already-granted path and in onResume (grants made in Settings).
   */
  @Test
  fun permissionGrantRefreshesTheMxLink() {
    val t = src("MainActivity.kt")
    assertEquals(3, Regex("""liveVm\.onPermissionsChanged\(\)""").findAll(t).count())
    val launcher = Regex("""RequestMultiplePermissions\(\)\) \{ result ->(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1)
      ?: error("no launcher")
    assertTrue(launcher.contains("liveVm.onPermissionsChanged()"))
    val resume = Regex("""override fun onResume\(\) \{([^}]*)\}""").find(t)?.groupValues?.get(1) ?: error("no onResume")
    assertTrue(resume.contains("liveVm.onPermissionsChanged()"))
    val bt = src("bt/BtController.kt")
    assertTrue(
      Regex("""fun onPermissionsChanged\(\) \{\s*refresh\(\)\s*if \(_state\.value\.availability == BtAvailability\.READY\) engine\.retryIfStoppedBy\(BtErrors\.NO_CONNECT_PERMISSION\)""")
        .containsMatchIn(bt),
    )
  }

  /**
   * The Simple tab never opens the MX+ link: LinkConnection attaches the OBD engine only while a
   * screen is visible AND wants OBD, the ViewModel decides that from the remembered tab before the
   * first onStart, and only the Live and Diagnostics tabs want it. The built-in session is attached
   * for the whole binding instead (so it keeps sampling with another app in front).
   */
  @Test
  fun simpleTabNeverAttachesTheObdEngine() {
    val conn = src("link/LinkConnection.kt")
    assertTrue(Regex("""val want = visible && obd && !closed""").containsMatchIn(conn))
    assertEquals(1, Regex("""\.engine\.attach\(""").findAll(conn).count())
    assertTrue(Regex("""if \(want\) s\.engine\.attach\(token\) else s\.engine\.detach\(token\)""").containsMatchIn(conn))
    assertTrue(Regex("""s\.vehicle\.attach\(token\)""").containsMatchIn(conn))
    assertTrue(Regex("""it\.vehicle\.detach\(token\)""").containsMatchIn(conn))
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("LinkConnection(app, viewModelScope, obdWanted = store.state.value.tab.wantsObd(driving.restricted.value))"))
    assertTrue(Regex("""fun selectTab\(t: AppTab\) \{\s*if \(t == AppTab\.DIAGNOSTICS && restricted\.value\) return\s*store\.setTab\(t\)""").containsMatchIn(vm))
    // One place decides it, from the tab and the restriction: the Diagnostics panel polls nothing.
    assertTrue(Regex("""combine\(store\.state, restricted\) \{ s, r -> s\.tab\.wantsObd\(r\) \}\.distinctUntilChanged\(\)\.collect \{ conn\.setObdWanted\(it\) \}""").containsMatchIn(vm))
    assertEquals(1, Regex("""conn\.setObdWanted\(""").findAll(vm).count())
    val settings = src("link/AppSettings.kt")
    assertTrue(settings.contains("SIMPLE(\"Simple\", false)"))
    assertTrue(settings.contains("LIVE(\"Live\", true)"))
    assertTrue(settings.contains("DIAGNOSTICS(\"Diagnostics\", true)"))
    // Nothing else in the app attaches the engine.
    val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    val attaching = main.walkTopDown().filter { it.isFile && it.extension == "kt" && Regex("""engine\.attach\(""").containsMatchIn(it.readText()) }.map { it.name }.toList()
    assertEquals(listOf("LinkConnection.kt"), attaching)
  }

  // --- One page, and driving --------------------------------------------------------------------

  private fun dashboards(): List<Pair<String, String>> =
    listOf("ui/simple/SimpleScreen.kt", "ui/live/LiveScreen.kt", "ui/AppShell.kt").map { it to src(it) }

  /** A function's text, from `fun name(` to its matching closing brace (or the end of its expression). */
  private fun funBody(text: String, name: String): String {
    val start = Regex("""\bfun (\w+\.)?$name\(""").find(text)?.range?.first ?: error("no fun $name")
    var i = text.indexOf('(', start)
    var parens = 0
    do {
      if (text[i] == '(') parens += 1 else if (text[i] == ')') parens -= 1
      i += 1
    } while (parens > 0)
    while (text[i] != '{' && text[i] != '=') i += 1
    if (text[i] == '=') {
      val end = text.indexOf("\n\n", i)
      return if (end >= 0) text.substring(start, end) else text.substring(start)
    }
    var braces = 0
    do {
      if (text[i] == '{') braces += 1 else if (text[i] == '}') braces -= 1
      i += 1
    } while (braces > 0)
    return text.substring(start, i)
  }

  @Test
  fun simpleAndLiveNeverScroll() {
    val scrolling = Regex("""\bverticalScroll\b|\bhorizontalScroll\b|\bLazyColumn\b|\bLazyRow\b|\bLazyVerticalGrid\b|\bscrollable\(|rememberScrollState""")
    for ((name, text) in dashboards()) {
      assertEquals("$name scrolls", emptyList<String>(), scrolling.findAll(text).map { it.value }.toList())
    }
    // Everything in those two packages, helpers included.
    val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.let { p -> p.contains("/ui/simple/") || p.contains("/ui/live/") || p.contains("/ui/layout/") } }.toList()
    assertTrue(files.size >= 5)
    for (f in files) assertEquals("${f.name} scrolls", emptyList<String>(), scrolling.findAll(f.readText()).map { it.value }.toList())
    // Diagnostics stays scrollable.
    assertTrue(src("ui/DiagnosticsScreen.kt").contains("LazyColumn("))
  }

  /** All dashboard text goes through FitText, so none of it can wrap out of a card or be cut. */
  @Test
  fun dashboardTextIsAlwaysFitted() {
    val rawText = Regex("""(?<![\w.])Text\(""")
    for ((name, text) in dashboards()) assertEquals("raw Text( in $name", 0, rawText.findAll(text).count())
    val fit = funBody(src("ui/layout/DashWidgets.kt"), "FitText")
    assertTrue(fit.contains("overflow: TextOverflow = TextOverflow.Ellipsis"))
    assertTrue(fit.contains("softWrap = maxLines > 1"))
    // No window size is hard-coded back in as a layout decision.
    for ((name, text) in dashboards()) {
      assertEquals("$name decides from a size", emptyList<String>(), Regex("""\bmaxWidth >=|\bmaxHeight >=|2914|1134|2330""").findAll(text).map { it.value }.toList())
    }
  }

  /** The window's size, read after the insets, is the policy's only size input. */
  @Test
  fun theLayoutIsDerivedFromTheActualWindow() {
    val shell = src("ui/AppShell.kt")
    assertTrue(Regex("""BoxWithConstraints\(Modifier\.fillMaxSize\(\)\.windowInsetsPadding\(WindowInsets\.safeDrawing\)\)""").containsMatchIn(shell))
    assertTrue(Regex("""DashLayout\.of\(maxWidth\.value, maxHeight\.value, restricted, fontScale\)""").containsMatchIn(shell))
    assertEquals("one layout decision", 1, Regex("""DashLayout\.of\(""").findAll(shell).count())
  }

  /** While restricted the Diagnostics tab is one text panel, and nothing else can reach its parts. */
  @Test
  fun diagnosticsAndPairingAreReachableOnlyInPark() {
    val shell = src("ui/AppShell.kt")
    assertTrue(Regex("""if \(layout\.restricted\) \{\s*AvailableInParkPanel\(layout, stateKnown, liveVm\.drivingInputs\)\s*\} else \{\s*DiagnosticsScreen\(diagVm, liveVm,""").containsMatchIn(shell))
    assertTrue("the tab is not offered", shell.contains("it != AppTab.DIAGNOSTICS || layout.diagnosticsTab"))
    val panel = funBody(shell, "AvailableInParkPanel")
    assertEquals("the panel has nothing to tap", emptyList<String>(), Regex("""Button|clickable|selectable|TextField|Switch|onClick""").findAll(panel).map { it.value }.toList())
    val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    fun callers(call: Regex) = main.walkTopDown().filter { it.isFile && it.extension == "kt" && call.containsMatchIn(it.readText()) }.map { it.name }.toSet()
    assertEquals(setOf("AppShell.kt"), callers(Regex("""(?<!fun )\bDiagnosticsScreen\(""")))
    assertEquals(setOf("DiagnosticsScreen.kt"), callers(Regex("""(?<!fun )\bMxLinkSection\(""")))
    assertEquals(setOf("DiagnosticsScreen.kt", "MxLinkSection.kt"), callers(Regex("""\bOutlinedTextField\(|\bSwitch\(""")))
    // And the view models refuse it even if something stale called them.
    val vm = src("LiveViewModel.kt")
    for (action in listOf("resetSession", "retry", "setTempUnit", "setDistUnit", "setDemo", "choose", "forgetAdapter", "startPairing", "pair", "useUnpaired", "testLink")) {
      assertTrue("$action is parked-only", Regex("""fun $action\([^)]*\) = parkedOnly \{""").containsMatchIn(vm))
    }
    assertTrue(vm.contains("if (t == AppTab.DIAGNOSTICS && restricted.value) return"))
  }

  /** Discovery and link tests in progress end when the restriction begins; nothing new starts. */
  @Test
  fun aRestrictionEndsDiscoveryAndLinkTests() {
    val bt = src("bt/BtController.kt")
    assertTrue(funBody(bt, "setRestricted"), funBody(bt, "setRestricted").contains("if (on) endRunningWork("))
    assertTrue(funBody(bt, "onLeftScreen"), funBody(bt, "onLeftScreen").contains("endRunningWork("))
    val end = funBody(bt, "endRunningWork").replace(Regex("\\s+"), " ")
    assertTrue(end, end.contains("stopDiscovery()"))
    assertTrue(end, end.contains("engine.cancelChecks()"))
    assertTrue("a pairing already started may finish", !end.contains("removeBond") && !end.contains("cancelBond"))
    for (start in listOf("startPairing", "pair", "testLink")) {
      assertTrue("$start refuses", Regex("""fun $start\([^)]*\) \{\s*if \(restricted""").containsMatchIn(bt))
    }
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("s?.bt?.setRestricted(r)"))
    // The Diagnostics tab's own BLE scan and TCP test.
    val diag = src("DiagnosticsViewModel.kt")
    val off = funBody(diag, "stopRunningWork")
    assertTrue(off.contains("adapterTest?.cancel()"))
    assertTrue(off.contains("bleJob?.cancel()"))
    assertTrue(funBody(diag, "followRestriction").contains("state.collect { if (it) stopRunningWork() }"))
    assertTrue(src("MainActivity.kt").contains("vm.followRestriction(liveVm.restricted)"))
  }

  /** The listener stops at onStop, so what a screen left running behind another app ends right there. */
  @Test
  fun leavingTheScreenEndsWhatItHadRunning() {
    val a = src("MainActivity.kt")
    val stop = funBody(a, "onStop")
    assertTrue(stop, Regex("""if \(!isChangingConfigurations\) \{[^}]*liveVm\.setVisible\(false\)[^}]*vm\.stopRunningWork\(\)[^}]*liveVm\.onLeftScreen\(\)[^}]*\}""").containsMatchIn(stop))
    assertTrue(funBody(src("LiveViewModel.kt"), "onLeftScreen").contains("bt?.onLeftScreen()"))
  }

  /** A stale tap, handled before the screen redrew without its button, starts nothing either. */
  @Test
  fun diagnosticsStartersRefuseWhileRestricted() {
    val diag = src("DiagnosticsViewModel.kt")
    for (start in listOf("runAdapterTest", "runBleScan", "sendReport")) {
      assertTrue("$start refuses", Regex("""fun $start\([^)]*\) \{\s*if \(restricted\) return""").containsMatchIn(diag))
    }
    assertTrue(diag.contains("private val restricted: Boolean get() = restriction.value"))
  }

  /** The list's place and "show all" live above the restricted branch, so P to R to P keeps them. */
  @Test
  fun aTripThroughTheRestrictedPanelKeepsDiagnosticsInPlace() {
    val shell = funBody(src("ui/AppShell.kt"), "AppShell")
    val branch = shell.indexOf("if (layout.restricted)")
    assertTrue(branch > 0)
    assertTrue(shell.indexOf("rememberLazyListState()") in 0 until branch)
    assertTrue(shell.indexOf("diagnosticsShowAll by rememberSaveable") in 0 until branch)
    val screen = funBody(src("ui/DiagnosticsScreen.kt"), "DiagnosticsScreen")
    assertTrue(screen.contains("state = listState"))
    assertTrue("nothing of this kept inside the screen", !screen.contains("rememberLazyListState") && !screen.contains("rememberSaveable"))
  }

  /** No system dialog or Settings screen opens while restricted, and no in-app dialog stays up. */
  @Test
  fun noDialogOpensWhileRestricted() {
    val a = src("MainActivity.kt")
    assertTrue(Regex("""private fun requestRuntimePermissions\(carOnly: Boolean\) \{\s*if \(liveVm\.restricted\.value\) return""").containsMatchIn(a))
    assertTrue(Regex("""private fun openAppSettings\(\) \{\s*if \(liveVm\.restricted\.value\) return""").containsMatchIn(a))
    assertEquals("one permission dialog", 1, Regex("""permissionLauncher\.launch\(""").findAll(a).count())
    // One dialog per launch: the next is opened from the answer, and only if still not restricted.
    val ask = funBody(a, "askNextDialog").replace(Regex("\\s+"), " ")
    assertTrue(ask, ask.contains("if (next == null || liveVm.restricted.value) { pendingDialogs = emptyList()"))
    assertTrue(ask, ask.contains("permissionLauncher.launch("))
    assertTrue(funBody(a, "requestRuntimePermissions").contains("AppPermissions.dialogGroups(missing)"))
    val launcher = Regex("""RequestMultiplePermissions\(\)\) \{ result ->(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(a)?.groupValues?.get(1) ?: error("no launcher")
    assertTrue(launcher.contains("askNextDialog()"))
    assertEquals("one Settings screen", 1, Regex("""startActivity\(""").findAll(a).count())
    val simple = src("ui/simple/SimpleScreen.kt")
    assertEquals("one dialog on the dashboards", 1, Regex("""\bAlertDialog\(""").findAll(simple).count())
    assertEquals(0, Regex("""\bAlertDialog\(""").findAll(src("ui/live/LiveScreen.kt")).count())
    assertTrue(simple.contains("if (confirmReset && layout.controls) {"))
    assertTrue(simple.contains("LaunchedEffect(layout.restricted) { if (layout.restricted) confirmReset = false }"))
  }

  /** Every control but the tabs and the layout selector is behind `layout.controls`. */
  @Test
  fun controlsAreParkedOnly() {
    val simple = src("ui/simple/SimpleScreen.kt")
    val live = src("ui/live/LiveScreen.kt")
    // The unit toggles and Reset.
    assertTrue(Regex("""if \(layout\.controls\) \{\s*Segmented\(TempUnit""").containsMatchIn(simple))
    assertTrue(Regex("""if \(layout\.controls\) \{\s*if \(dash\.status\.canRetry\)""").containsMatchIn(live))
    assertEquals("one unit toggle per screen", 1, Regex("""Segmented\(TempUnit""").findAll(simple).count())
    assertEquals(1, Regex("""Segmented\(TempUnit""").findAll(live).count())
    // Allow access and Open Diagnostics: the notice's driving branch has no button.
    val notice = funBody(simple, "NoticeCard")
    val drivingBranch = notice.substring(notice.indexOf("if (layout.restricted) {"), notice.indexOf("} else {"))
    assertEquals(0, Regex("""Button""").findAll(drivingBranch).count())
    // The selector stays, as tall as the policy says: 76 dp or more while driving (DashLayoutTest).
    assertTrue(simple.contains("settings.simpleLayout.ordinal, layout.selectorDp"))
    assertTrue(funBody(src("ui/AppShell.kt"), "HeaderTab").contains(".fillMaxHeight()"))
  }

  /** The heatmap, the module strip and every tap gesture exist only on the parked path. */
  @Test
  fun tapToInspectAndTheGridsAreParkedOnly() {
    val live = src("ui/live/LiveScreen.kt")
    val driving = funBody(live, "LiveDriving")
    for (absent in listOf("HeatmapCard", "Heatmap(", "ModuleTempStrip", "NoCellDataCard", "selectedGroup", "selectedPowerAt")) {
      assertTrue("$absent in LiveDriving", !driving.contains(absent))
    }
    assertTrue("no gesture on its chart", driving.contains("PowerCard(dash.powerChart, null, null, layout"))
    assertTrue(Regex("""if \(layout\.restricted\) \{\s*LiveDriving\(""").containsMatchIn(live))
    assertEquals("one caller", 1, Regex("""(?<!fun )\bHeatmapCard\(""").findAll(live).count())
    assertTrue(funBody(live, "LiveParked").contains("HeatmapCard("))
    assertEquals("the cell tap", 1, Regex("""\.clickable\(""").findAll(live).count())
    assertTrue(funBody(live, "Heatmap").contains(".clickable("))
    // The one tap gesture of the dashboards, adding nothing at all when disabled.
    val widgets = src("ui/layout/DashWidgets.kt")
    val tap = funBody(widgets, "inspectTap")
    assertTrue(tap, Regex("""if \(!enabled\) \{\s*this\s*\}""").containsMatchIn(tap))
    for ((name, text) in dashboards()) {
      assertEquals("$name: raw gesture", 0, Regex("""\bdetectTapGestures\b|\bpointerInput\b""").findAll(text).count())
    }
    val simple = src("ui/simple/SimpleScreen.kt")
    assertEquals(2, Regex("""\.inspectTap\(interactive, Unit\)""").findAll(simple).count())
    assertEquals("no tooltip while driving", 2, Regex("""val sel = if \(interactive\)""").findAll(simple).count())
    assertEquals(1, Regex("""\.inspectTap\(onSelect != null, """).findAll(live).count())
    assertEquals(2, Regex("""val interactive = layout\.tapToInspect""").findAll(simple).count())
  }

  /** Driving-state wiring: its own connection, read-only, following onStart / onStop; fail safe. */
  @Test
  fun theDrivingStateFollowsTheLifecycleAndFailsSafe() {
    val vm = src("LiveViewModel.kt")
    assertTrue(Regex("""if \(visible\) driving\.start\(\) else driving\.stop\(\)""").containsMatchIn(vm))
    assertTrue(funBody(vm, "onCleared").contains("driving.close()"))
    val monitor = src("vehicle/DrivingMonitor.kt")
    assertTrue(funBody(monitor, "start").contains("reader.setUxWatching(true)"))
    assertTrue(funBody(monitor, "stop").contains("reader.setUxWatching(false)"))
    assertTrue(funBody(monitor, "start").contains("reader.refreshUx()"))
    // The only car access is through VehicleReader, and only these reads.
    assertTrue(!Regex("""android\.car|getCarManager|CarPropertyManager""").containsMatchIn(monitor))
    assertEquals(
      setOf("KnownProperties.GEAR_SELECTION", "KnownProperties.PERF_VEHICLE_SPEED"),
      Regex("""reader\.readRaw\(([^)]+)\)""").findAll(monitor).map { it.groupValues[1] }.toSet(),
    )
    // Every way the reader loses its answer leaves the state unknown, never a stale "not restricted".
    val reader = src("vehicle/VehicleReader.kt")
    assertTrue(Regex("""if \(!ready\) \{\s*propertyManager = null\s*uxManager = null\s*_ux\.value = null""").containsMatchIn(reader))
    assertTrue(funBody(reader, "disconnect").contains("_ux.value = null"))
    val register = funBody(reader, "registerUx")
    assertTrue(Regex("""catch \(e: Exception\) \{\s*_ux\.value = null""").containsMatchIn(register))
    assertTrue(Regex("""catch \(e: LinkageError\) \{\s*_ux\.value = null""").containsMatchIn(register))
    assertTrue(Regex("""_ux\.value = info""").containsMatchIn(funBody(reader, "refreshUx")))
  }

  /** The screens draw the held flows, so values change at most once a second while driving. */
  @Test
  fun theScreensDrawTheHeldFlows() {
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("conn.session.heldWhile(restricted)"))
    assertTrue(vm.contains("conn.live.heldWhile(restricted)"))
    val shell = src("ui/AppShell.kt")
    assertTrue(shell.contains("liveVm.shownSession.collectAsStateWithLifecycle()"))
    assertTrue(shell.contains("liveVm.shownLive.collectAsStateWithLifecycle()"))
    assertTrue(!Regex("""liveVm\.(session|live)\.""").containsMatchIn(shell))
    // Live has two sources: while driving only the clock's one-second tick rebuilds what it draws.
    assertTrue(src("ui/live/LiveScreen.kt").contains("remember(now, settings.tempUnit, settings.distUnit, if (layout.restricted) Unit else live, if (layout.restricted) Unit else vhal)"))
    // No animation on the dashboards: nothing to catch the eye while driving.
    val animated = Regex("""\banimate\w*\(|\brememberInfiniteTransition\b|\bCircularProgressIndicator\b|\bAnimatedVisibility\b|\bCrossfade\b""")
    for ((name, text) in dashboards()) assertEquals("$name animates", emptyList<String>(), animated.findAll(text).map { it.value }.toList())
  }

  /** No screen or comment still claims the app is for parked use only. */
  @Test
  fun nothingStillSaysParkedOnly() {
    val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    val claim = Regex("""parked[- ]only|parked use|Parked use only|Park-only|keep Battery Glance|Android blocks|blocked in Drive""", RegexOption.IGNORE_CASE)
    val hits = main.walkTopDown()
      .filter { it.isFile && it.extension in setOf("kt", "xml") }
      .flatMap { f -> claim.findAll(f.readText()).map { "${f.name}: ${it.value}" } }
      .toList()
    assertEquals(emptyList<String>(), hits)
  }
}
