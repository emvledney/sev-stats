package dev.silveradostats.ui

import dev.silveradostats.R
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silveradostats.DiagnosticsViewModel
import dev.silveradostats.LiveViewModel
import dev.silveradostats.link.AppTab
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.NavRail
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.live.LiveScreen
import dev.silveradostats.ui.live.vhalFigures
import dev.silveradostats.ui.map.MapRangeSummary
import dev.silveradostats.ui.map.MapScreen
import dev.silveradostats.ui.simple.SimpleModel
import dev.silveradostats.ui.simple.SimpleScreen
import dev.silveradostats.ui.trends.TrendsScreen
import dev.silveradostats.ui.theme.CardEdge
import dev.silveradostats.ui.theme.CardShape
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.DrivingInputs
import dev.silveradostats.vehicle.DrivingPolicy
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The one header row the selected screen draws above itself: its status in [summary], then its buttons.
 * Simple has no status, by the owner's choice (2026-11-06). Height is what the dashboards are short of,
 * so nothing else sits above them; the tabs are in the rail on the left.
 */
typealias Chrome = @Composable (summary: @Composable RowScope.() -> Unit, controls: @Composable RowScope.() -> Unit) -> Unit

/**
 * Top-level layout: a rail of five buttons down the left edge (API | OBD | Trends | Map | Diagnostics, the
 * last one remembered) and the selected screen beside it, sized from the room this composition actually
 * got after insets and the rail, never from a known panel. Which tabs use the OBD link: UiSettings.wantsObd.
 *
 * The rail always shows all five, parked or driving, by the owner's choice (2026-11-11). While the truck
 * is out of Park (`layout.restricted`) Diagnostics and Trends can be selected but show only
 * [AvailableInParkPanel]; none of their tables, charts, pairing, fields or buttons compose.
 * Simple is the one page that does not change with the gear ([DashLayout.ofSimple]).
 */
@Composable
fun AppShell(
  liveVm: LiveViewModel,
  diagVm: DiagnosticsViewModel,
  onRequestPermissions: () -> Unit,
  onRequestCarPermissions: () -> Unit,
  onOpenAppSettings: () -> Unit,
) {
  val settings by liveVm.settings.collectAsStateWithLifecycle()
  val mxAction by liveVm.mxAction.collectAsStateWithLifecycle()
  val restricted by liveVm.restricted.collectAsStateWithLifecycle()
  val stateKnown by liveVm.drivingStateKnown.collectAsStateWithLifecycle()
  val tab = settings.tab
  // Out here, so a trip through the restricted panel (P to R to P) keeps the user's place.
  val diagnosticsList = rememberLazyListState()
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      val fontScale = LocalDensity.current.fontScale
      val rail = remember(maxWidth, maxHeight, fontScale) { DashLayout.navRail(maxWidth.value, maxHeight.value, fontScale) }
      // The selected screen's own status and buttons, when it hands them to the rail instead of a header row (API, OBD).
      val railSummary = remember(tab) { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
      val railControls = remember(tab) { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
      val toRail: Chrome = { summary, controls ->
        SideEffect {
          railSummary.value = summary
          railControls.value = controls
        }
      }
      Row(Modifier.fillMaxSize()) {
      NavRailColumn(rail, tab, liveVm::selectTab) {
        // The Map tab has no header row: its range reads here, under the buttons.
        if (tab == AppTab.MAP) {
          val session by liveVm.shownSession.collectAsStateWithLifecycle()
          MapRangeSummary(session, rail.textSp, rail.footSp)
        }
        for (part in listOf(railSummary.value, railControls.value)) {
          if (part == null) continue
          FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DashLayout.TAB_GAP_DP.dp), verticalArrangement = Arrangement.spacedBy(DashLayout.TAB_GAP_DP.dp)) { part() }
        }
      }
      // The screens are laid out in the room beside the rail, measured here, never assumed.
      BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
      val layout = remember(maxWidth, maxHeight, restricted, fontScale) {
        DashLayout.of(maxWidth.value, maxHeight.value, restricted, fontScale)
      }
      val chrome: Chrome = { summary, controls ->
        ShellHeader(layout, summary, controls)
      }
      when (tab) {
        AppTab.SIMPLE -> {
          val session by liveVm.shownSession.collectAsStateWithLifecycle()
          val live by liveVm.shownLive.collectAsStateWithLifecycle()
          val lastCharge by liveVm.lastCharge.collectAsStateWithLifecycle()
          // No gear in this one: shifting moves nothing on the page.
          // Its buttons (MX+, Reset) go to the foot of the rail, so the page has no header row.
          val simpleLayout = remember(maxWidth, maxHeight, fontScale) { DashLayout.ofSimple(maxWidth.value, maxHeight.value, fontScale).withoutHeader() }
          SimpleScreen(
            session = session,
            mxCapacityKwh = SimpleModel.mxCapacityKwh(live),
            lastCharge = lastCharge,
            settings = settings,
            layout = simpleLayout,
            driving = restricted,
            chrome = toRail,
            onReset = liveVm::resetSession,
            mxAction = mxAction,
            onMxAction = liveVm::onMxAction,
            onRequestPermissions = onRequestCarPermissions,
            onOpenDiagnostics = { liveVm.selectTab(AppTab.DIAGNOSTICS) },
          )
        }
        AppTab.LIVE -> {
          val live by liveVm.shownLive.collectAsStateWithLifecycle()
          val diag by diagVm.state.collectAsStateWithLifecycle()
          val session by liveVm.shownSession.collectAsStateWithLifecycle()
          LiveScreen(
            live = live,
            vhal = vhalFigures(diag.live),
            ownRangeM = session.rangeEstimate?.m,
            settings = settings,
            // The same page in every gear, its link status and buttons at the foot of the rail.
            layout = remember(maxWidth, maxHeight, fontScale) { DashLayout.ofLive(maxWidth.value, maxHeight.value, fontScale) },
            driving = restricted,
            chrome = toRail,
            onRetry = liveVm::retry,
            mxAction = mxAction,
            onMxAction = liveVm::onMxAction,
            onOpenDiagnostics = { liveVm.selectTab(AppTab.DIAGNOSTICS) },
          )
        }
        AppTab.TRENDS -> if (layout.restricted) {
          AvailableInParkPanel(layout, stateKnown, liveVm.drivingInputs, "Trends")
        } else {
          val live by liveVm.shownLive.collectAsStateWithLifecycle()
          TrendsScreen(settings, live.status, layout, chrome)
        }
        AppTab.MAP -> {
          val session by liveVm.shownSession.collectAsStateWithLifecycle()
          MapScreen(session = session, layout = layout, onRequestPermissions = onRequestPermissions)
        }
        AppTab.DIAGNOSTICS -> Column(Modifier.fillMaxSize()) {
          if (layout.restricted) {
            AvailableInParkPanel(layout, stateKnown, liveVm.drivingInputs)
          } else {
            DiagnosticsScreen(diagVm, liveVm, diagnosticsList, onRequestPermissions, onOpenAppSettings)
          }
        }
      }
      }
      }
    }
  }
}

@Composable
private fun ShellHeader(
  layout: DashLayout,
  summary: @Composable RowScope.() -> Unit,
  controls: @Composable RowScope.() -> Unit,
) {
  val gap = layout.headerGapDp.dp
  Row(
    Modifier.fillMaxWidth().height(layout.headerDp.dp).padding(horizontal = layout.pagePadHDp.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(gap),
  ) {
    // Takes what is left: every other child in this row has already been measured when it does.
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) { summary() }
    controls()
  }
}

private val TAB_BUTTON_SHAPE = RoundedCornerShape(10.dp)

/**
 * The rail: every tab, always, in the same place, whichever is selected and whatever the gear. What a
 * screen wants under them ([foot]) sits at the bottom.
 */
@Composable
private fun NavRailColumn(rail: NavRail, tab: AppTab, onSelect: (AppTab) -> Unit, foot: @Composable ColumnScope.() -> Unit) {
  Column(
    Modifier.width(rail.widthDp.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background).padding(rail.padDp.dp),
    verticalArrangement = Arrangement.spacedBy(DashLayout.TAB_GAP_DP.dp),
  ) {
    AppTab.entries.filter { it != AppTab.DIAGNOSTICS }.forEach { t -> NavButton(t.label, tab == t, rail) { onSelect(t) } }
    Spacer(Modifier.weight(1f))
    foot()
    GearButton(tab == AppTab.DIAGNOSTICS, rail) { onSelect(AppTab.DIAGNOSTICS) }
  }
}

/** The settings page's button: a gear at the foot of the rail, as tall as the other rail buttons. */
@Composable
private fun GearButton(selected: Boolean, rail: NavRail, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Box(
    Modifier
      .fillMaxWidth()
      .height(rail.buttonDp.dp)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface, TAB_BUTTON_SHAPE)
      .border(1.dp, if (selected) colors.primary.copy(alpha = 0.45f) else colors.surfaceVariant, TAB_BUTTON_SHAPE),
    contentAlignment = Alignment.Center,
  ) {
    Icon(painterResource(R.drawable.ic_settings), "Settings", Modifier.size(32.dp), tint = if (selected) colors.primary else TextDim)
  }
}

/**
 * One rail button, Carbon's: upper-case text, and for the selected one a green bar at its left edge over a
 * faint green wash. Same size and touch target as before: [NavRail.buttonDp] tall (76 dp or more while driving).
 */
@Composable
private fun NavButton(label: String, selected: Boolean, rail: NavRail, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Box(
    Modifier
      .fillMaxWidth()
      .height(rail.buttonDp.dp)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface, TAB_BUTTON_SHAPE)
      .border(1.dp, if (selected) colors.primary.copy(alpha = 0.45f) else colors.surfaceVariant, TAB_BUTTON_SHAPE)
      .drawBehind { if (selected) drawRoundRect(colors.primary, topLeft = Offset(0f, size.height * 0.2f), size = Size(5.dp.toPx(), size.height * 0.6f), cornerRadius = CornerRadius(3.dp.toPx())) }
      .padding(horizontal = DashLayout.NAV_BUTTON_PAD_DP.dp),
    contentAlignment = Alignment.Center,
  ) {
    // "DIAGNOSTICS" in capitals does not fit the rail: Carbon's mockup reads "DIAG".
    FitText(label.uppercase().let { if (it.length > 8) it.take(4) else it }, sp = rail.textSp, weight = FontWeight.Bold, color = if (selected) colors.onSurface else TextDim)
  }
}

/**
 * All the Diagnostics tab is while restricted: text only, nothing to tap. When the state is merely
 * unknown it says so, or a truck really in Park would show an unexplained message. The last line says
 * what the app is going by, because this is the one place the driver can read it when the app stays
 * in driving mode in Park (and Diagnostics, the usual way to find out, is the part that is locked).
 */
@Composable
private fun AvailableInParkPanel(layout: DashLayout, stateKnown: Boolean, inputs: StateFlow<DrivingInputs>, what: String = "Settings") {
  val reading by inputs.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize().padding(layout.gapDp.dp), contentAlignment = Alignment.Center) {
    Card(Modifier.fillMaxWidth(), shape = CardShape, border = CardEdge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      FitColumn(Modifier.fillMaxWidth().padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp), spacing = layout.gapDp.dp) {
        FitText("Available in Park", Modifier.fit(0).fillMaxWidth(), sp = layout.type.value, weight = FontWeight.Bold, lineFactor = DashLayout.VALUE_LINE_FACTOR, align = TextAlign.Center)
        FitText(
          if (stateKnown) "$what returns when the truck is in Park." else "The driving state cannot be read right now, so the app stays in driving mode.",
          Modifier.fit(1).fillMaxWidth(),
          sp = layout.type.title,
          color = TextDim,
          maxLines = 2,
          align = TextAlign.Center,
        )
        FitText(DrivingPolicy.reason(reading), Modifier.fit(2).fillMaxWidth(), sp = layout.type.body, color = TextDim, align = TextAlign.Center)
      }
    }
  }
}
