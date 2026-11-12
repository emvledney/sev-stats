package dev.silveradostats

import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
   * LinkConnection attaches the OBD engine only while a screen is visible AND wants OBD, and the
   * ViewModel decides that from the remembered tab before the first onStart. Simple and Trends want it
   * only with an MX+ chosen and not disconnected (UiSettings.wantsObd). The built-in session is attached
   * for the whole binding instead (so it keeps sampling with another app in front).
   */
  @Test
  fun theObdEngineAttachesOnlyWhenWanted() {
    val conn = src("link/LinkConnection.kt")
    assertTrue(Regex("""val want = visible && obd && !closed""").containsMatchIn(conn))
    assertEquals(1, Regex("""\.engine\.attach\(""").findAll(conn).count())
    assertTrue(Regex("""if \(want\) s\.engine\.attach\(token\) else s\.engine\.detach\(token\)""").containsMatchIn(conn))
    assertTrue(Regex("""s\.vehicle\.attach\(token\)""").containsMatchIn(conn))
    assertTrue(Regex("""it\.vehicle\.detach\(token\)""").containsMatchIn(conn))
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("LinkConnection(app, viewModelScope, obdWanted = store.state.value.wantsObd(driving.restricted.value))"))
    assertTrue(Regex("""fun selectTab\(t: AppTab\) \{\s*store\.setTab\(t\)""").containsMatchIn(vm))
    // One place decides it, from the tab and the restriction: the Diagnostics panel polls nothing.
    assertTrue(Regex("""combine\(store\.state, restricted\) \{ s, r -> s\.wantsObd\(r\) \}\.distinctUntilChanged\(\)\.collect \{ conn\.setObdWanted\(it\) \}""").containsMatchIn(vm))
    assertEquals(1, Regex("""conn\.setObdWanted\(""").findAll(vm).count())
    val settings = src("link/AppSettings.kt")
    // Owner, 2026-11-11: the rail reads "Simple" and "Expert" (it read "API" and "OBD" in 0.19.0).
    assertTrue(settings.contains("SIMPLE(\"Simple\", false)"))
    assertTrue(settings.contains("LIVE(\"Expert\", true)"))
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
    assertEquals("one layout decision, and Simple's", 1, Regex("""DashLayout\.of\(""").findAll(shell).count())
    assertTrue(Regex("""DashLayout\.ofSimple\(maxWidth\.value, maxHeight\.value, fontScale\)\.withoutHeader\(\)""").containsMatchIn(shell))
  }

  /**
   * Simple is one page whatever the gear and its controls work in every gear, by the owner's choice
   * (2026-11-06). Everything else the lockdown covers stays locked: the pages behind the Trends and
   * Diagnostics tabs (diagnosticsAndPairingAreReachableOnlyInPark), system dialogs, Live's controls.
   */
  @Test
  fun simpleDoesNotChangeWithTheGearAndItsControlsWorkInDrive() {
    val simple = src("ui/simple/SimpleScreen.kt")
    val shell = src("ui/AppShell.kt")
    assertEquals("the page takes nothing from the layout's gear", emptyList<String>(), Regex("""layout\.(restricted|controls)""").findAll(simple).map { it.value }.toList())
    assertTrue(shell.contains("layout = simpleLayout,"))
    assertTrue(shell.contains("driving = restricted,"))
    // Its buttons are drawn at the foot of the rail, and the page has no header row.
    assertTrue(shell.contains("chrome = toRail,"))
    // Nothing on the page depends on the gear but a notice's button.
    assertEquals(listOf("driving"), Regex("""\bif \((driving)\)""").findAll(simple).map { it.groupValues[1] }.toList())
    assertFalse(simple.contains("!driving"))
    // The view model does not refuse them either.
    val vm = src("LiveViewModel.kt")
    assertFalse("Reset works in Drive", Regex("""fun resetSession\([^)]*\) = parkedOnly""").containsMatchIn(vm))
    // A reset still asks first, in every gear.
    assertTrue(simple.contains("if (confirmReset) {"))
    assertTrue(simple.contains("onClick = { confirmReset = true }"))
    assertTrue(Regex("""confirmReset = false\s*onReset\(\)""").containsMatchIn(simple))
    // Live's are still behind the driving state.
    assertTrue(Regex("""if \(layout\.controls\) \{\s*if \(dash\.status\.canRetry && settings\.demo\)""").containsMatchIn(src("ui/live/LiveScreen.kt")))
    // And the pages behind the two tabs are the panel out of Park.
    assertTrue(Regex("""AppTab\.TRENDS -> if \(layout\.restricted\) \{""").containsMatchIn(shell))
  }

  /** Started from a visible screen and at boot, where Android allows it; demo data has nothing to measure. */
  @Test
  fun theBackgroundServiceStartsFromAScreenOrAtBoot() {
    val activity = src("MainActivity.kt")
    assertTrue(Regex("""repeatOnLifecycle\(Lifecycle\.State\.STARTED\) \{\s*AppSettings\.get\(this@MainActivity\)\.state\.map \{ it\.demo \}\.distinctUntilChanged\(\)\.collect \{ VehicleWatch\.start\(this@MainActivity\) \}""").containsMatchIn(activity))
    val watch = src("vehicle/VehicleWatchService.kt")
    assertTrue(watch.contains("!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) || AppSettings.get(app).state.value.demo) return"))
    assertTrue(watch.contains("collect { demo -> if (demo) stopSelf() }"))
    // A refusal is no crash, on either side.
    assertEquals(2, Regex("""catch \(_: IllegalStateException\)""").findAll(watch).count())
    assertTrue(watch.contains("intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) VehicleWatch.start(context)"))
    // Nothing else starts it.
    val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("VehicleWatch.start(") }.map { it.name }.toSet()
    assertEquals(setOf("MainActivity.kt", "VehicleWatchService.kt"), callers)
  }

  /** The owner's choice (2026-11-06): the tabs and the buttons, no words. A tab's own status goes in its summary slot; Simple has none. */
  @Test
  fun theHeaderIsOnlyTheTabsAndTheButtons() {
    val header = funBody(src("ui/AppShell.kt"), "ShellHeader")
    for (word in listOf("Silverado Stats", "Read-only", "Demo", "demo")) assertFalse(word, header.contains(word))
    val simple = src("ui/simple/SimpleScreen.kt")
    assertTrue(Regex("""chrome\(\s*\{\},\s*\{\s*MxButton\(""").containsMatchIn(simple))
    // Nothing between the header and the page but a notice about the data feed.
    assertTrue(Regex("""\n\s*\)\n\s*if \(layout\.tooSmall\(view\.notice != null\)\)""").containsMatchIn(simple))
  }

  /** °F and miles, the owner's choice (2026-11-06): nothing on screen or saved can change them. */
  @Test
  fun theUnitsCannotChange() {
    assertEquals(TempUnit.F, Units.TEMP)
    assertEquals(DistUnit.MI, Units.DIST)
    val choice = Regex("""Segmented\((Temp|Dist)Unit|set(Temp|Dist)Unit|tempUnit|distUnit|temp_unit|distance_unit""")
    for (f in listOf("ui/AppShell.kt", "ui/simple/SimpleScreen.kt", "ui/live/LiveScreen.kt", "ui/trends/TrendsScreen.kt", "LiveViewModel.kt", "link/AppSettings.kt")) {
      assertFalse(f, choice.containsMatchIn(src(f)))
    }
  }

  /** The last charge is fed by every reading of the truck, the hourly job's too, never by a demo, and kept. */
  @Test
  fun theLastChargeIsFedByTheTrucksReadingsOnly() {
    val hub = src("log/LogHub.kt")
    // From the one session itself, so that whichever service asked for a reading, it counts, once.
    assertTrue(src("vehicle/CarVehicleSource.kt").contains("session.state.collect { LogHub.get(app).onSession(it) }"))
    assertFalse(src("link/LinkService.kt").contains("onSession"))
    assertFalse(src("vehicle/VehicleWatchService.kt").contains("onSession"))
    assertTrue(funBody(hub, "onSession"), funBody(hub, "onSession").contains("s.feed.kind == FeedKind.VEHICLE"))
    assertTrue(funBody(hub, "logCar"), funBody(hub, "logCar").contains("noteCharge(ChargeReading.fromCar("))
    assertTrue(hub.contains("store.rows(0L, LogSource.CAR, LogRows.CHARGE_COLS)"))
    assertTrue(funBody(hub, "publishCharge").contains("ChargeWatch.saveDue(chargeSaved, now)"))
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("if (s.demo) DemoDrive.lastCharge(session.distanceM) else c"))
    assertTrue(src("ui/AppShell.kt").contains("liveVm.lastCharge.collectAsStateWithLifecycle()"))
    // A reset of the session leaves it alone.
    assertFalse(funBody(vm, "resetSession").contains("lastCharge"))
  }

  /** While restricted the Diagnostics tab is one text panel, and nothing else can reach its parts. */
  @Test
  fun diagnosticsAndPairingAreReachableOnlyInPark() {
    val shell = src("ui/AppShell.kt")
    assertTrue(Regex("""if \(layout\.restricted\) \{\s*AvailableInParkPanel\(layout, stateKnown, liveVm\.drivingInputs\)\s*\} else \{\s*DiagnosticsScreen\(diagVm, liveVm,""").containsMatchIn(shell))
    // The rail offers every tab in every gear (the owner's choice, 2026-11-11): restricted, selecting Diagnostics leads only to the panel above.
    assertTrue(funBody(shell, "NavRailColumn").contains("AppTab.entries.filter { it != AppTab.DIAGNOSTICS }.forEach { t -> NavButton(t.label, tab == t, rail) { onSelect(t) } }"))
    // The Diagnostics page became the Settings page, a gear at the foot of the rail (owner, 2026-11-12): permissions, MX+ link and report only.
    assertTrue(funBody(shell, "NavRailColumn").contains("GearButton(tab == AppTab.DIAGNOSTICS, rail)"))
    assertTrue(funBody(shell, "GearButton").contains(".height(rail.buttonDp.dp)"))
    val panel = funBody(shell, "AvailableInParkPanel")
    assertEquals("the panel has nothing to tap", emptyList<String>(), Regex("""Button|clickable|selectable|TextField|Switch|onClick""").findAll(panel).map { it.value }.toList())
    val main = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    fun callers(call: Regex) = main.walkTopDown().filter { it.isFile && it.extension == "kt" && call.containsMatchIn(it.readText()) }.map { it.name }.toSet()
    assertEquals(setOf("AppShell.kt"), callers(Regex("""(?<!fun )\bDiagnosticsScreen\(""")))
    assertEquals(setOf("DiagnosticsScreen.kt"), callers(Regex("""(?<!fun )\bMxLinkSection\(""")))
    assertEquals(setOf("DiagnosticsScreen.kt", "MxLinkSection.kt"), callers(Regex("""\bOutlinedTextField\(|\bSwitch\(""")))
    // And the view models refuse it even if something stale called them.
    val vm = src("LiveViewModel.kt")
    for (action in listOf("retry", "setDemo", "choose", "forgetAdapter", "startPairing", "pair", "useUnpaired", "testLink")) {
      assertTrue("$action is parked-only", Regex("""fun $action\([^)]*\) = parkedOnly \{""").containsMatchIn(vm))
    }
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
    // Send to laptop refuses by reading what followRestriction stores: emptied, it would never refuse.
    val diag = src("DiagnosticsViewModel.kt")
    assertTrue(funBody(diag, "followRestriction").contains("restriction = state"))
    assertTrue(src("MainActivity.kt").contains("vm.followRestriction(liveVm.restricted)"))
  }

  /** The listener stops at onStop, so what a screen left running behind another app ends right there. */
  @Test
  fun leavingTheScreenEndsWhatItHadRunning() {
    val a = src("MainActivity.kt")
    val stop = funBody(a, "onStop")
    assertTrue(stop, Regex("""if \(!isChangingConfigurations\) \{[^}]*liveVm\.setVisible\(false\)[^}]*liveVm\.onLeftScreen\(\)[^}]*\}""").containsMatchIn(stop))
    assertTrue(funBody(src("LiveViewModel.kt"), "onLeftScreen").contains("bt?.onLeftScreen()"))
  }

  /** A stale tap, handled before the screen redrew without its button, starts nothing either. */
  @Test
  fun diagnosticsStartersRefuseWhileRestricted() {
    val diag = src("DiagnosticsViewModel.kt")
    for (start in listOf("sendReport")) {
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
    val screen = funBody(src("ui/DiagnosticsScreen.kt"), "DiagnosticsScreen")
    assertTrue(screen.contains("state = listState"))
    assertTrue("nothing of this kept inside the screen", !screen.contains("rememberLazyListState") && !screen.contains("rememberSaveable"))
  }

  /** No system dialog or Settings screen opens while restricted. Simple's reset question is the dashboards' one dialog. */
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
  }

  /** On Live every control but the tabs and the MX+ button is behind `layout.controls`. Simple: simpleDoesNotChangeWithTheGearAndItsControlsWorkInDrive. */
  @Test
  fun controlsAreParkedOnly() {
    val simple = src("ui/simple/SimpleScreen.kt")
    val live = src("ui/live/LiveScreen.kt")
    // Live's Retry.
    assertTrue(Regex("""if \(layout\.controls\) \{\s*if \(dash\.status\.canRetry && settings\.demo\)""").containsMatchIn(live))
    // Allow access and Open Diagnostics: the notice's driving branch has no button.
    val notice = funBody(simple, "NoticeCard")
    val drivingBranch = notice.substring(notice.indexOf("if (driving) {"), notice.indexOf("} else {"))
    assertEquals(0, Regex("""Button""").findAll(drivingBranch).count())
    // The MX+ button stays, as tall as the policy says: 76 dp or more while driving (DashLayoutTest).
    assertTrue(funBody(live, "MxButton").contains("layout.mxButtonDp"))
    // And it works while driving, Connect included, doing what the label it drew said.
    assertTrue(funBody(live, "MxButton").contains("onMxAction(action)"))
    val vm = src("LiveViewModel.kt")
    assertTrue(vm.contains("fun onMxAction(action: MxAction) {"))
    assertTrue(funBody(vm, "onMxAction"), !funBody(vm, "onMxAction").contains("parkedOnly"))
    // The rail buttons are as tall as the policy says: 76 dp or more while driving (DashLayoutTest).
    assertTrue(funBody(src("ui/AppShell.kt"), "NavButton").contains(".height(rail.buttonDp.dp)"))
  }

  /**
   * The OBD page is the same in every gear (the owner's choice, 2026-11-11): the shell gives it the parked
   * layout always, so the heatmap and its tap-to-inspect are there while driving too. One card function draws
   * it, and the heatmap still has exactly one tap gesture.
   */
  @Test
  fun theObdPageIsTheSameInEveryGear() {
    val live = src("ui/live/LiveScreen.kt")
    val shell = src("ui/AppShell.kt")
    assertTrue(shell.contains("DashLayout.ofLive(maxWidth.value, maxHeight.value, fontScale)"))
    assertTrue(src("ui/layout/DashLayout.kt").contains("fun ofLive(widthDp: Float, heightDp: Float, fontScale: Float = 1f): DashLayout = of(widthDp, heightDp, false, fontScale).withoutHeader()"))
    assertTrue("no driving variant of the page", !live.contains("LiveDriving"))
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
    // Only Trends takes it, and that tab is not offered while driving.
    assertEquals(0, Regex("""\.inspectTap\(""").findAll(src("ui/simple/SimpleScreen.kt")).count())
    assertEquals(0, Regex("""\.inspectTap\(""").findAll(live).count())
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

  /** Each drive starts fresh: nothing of an earlier one is kept, Live shows the same range, and Simple gets the MX+'s capacity. */
  @Test
  fun liveShowsTheOwnRangeAndSimpleTheMxCapacity() {
    assertFalse(src("vehicle/CarVehicleSource.kt").contains("lastEfficiency"))
    val shell = src("ui/AppShell.kt")
    assertTrue(shell.contains("ownRangeM = session.rangeEstimate?.m"))
    assertTrue(shell.contains("mxCapacityKwh = SimpleModel.mxCapacityKwh(live)"))
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
    assertTrue(src("ui/live/LiveScreen.kt").contains("remember(now, if (driving) Unit else live, if (driving) Unit else vhal)"))
    // No animation on the dashboards: nothing to catch the eye while driving.
    val animated = Regex("""\banimate\w*\(|\brememberInfiniteTransition\b|\bCircularProgressIndicator\b|\bAnimatedVisibility\b|\bCrossfade\b""")
    for ((name, text) in dashboards()) assertEquals("$name animates", emptyList<String>(), animated.findAll(text).map { it.value }.toList())
  }

  /** No screen or comment still claims the app is for parked use only. */
  @Test
  fun nothingStillSaysParkedOnly() {
    val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    val claim = Regex("""parked[- ]only|parked use|Parked use only|Park-only|Android blocks|blocked in Drive""", RegexOption.IGNORE_CASE)
    val hits = main.walkTopDown()
      .filter { it.isFile && it.extension in setOf("kt", "xml") }
      .flatMap { f -> claim.findAll(f.readText()).map { "${f.name}: ${it.value}" } }
      .toList()
    assertEquals(emptyList<String>(), hits)
  }
}
