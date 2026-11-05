package dev.silveradostats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.live.LiveScreen
import dev.silveradostats.ui.live.vhalFigures
import dev.silveradostats.ui.simple.SimpleScreen
import dev.silveradostats.ui.trends.TrendsScreen
import dev.silveradostats.ui.theme.DemoBannerColor
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.DrivingInputs
import dev.silveradostats.vehicle.DrivingPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * The one header row the selected screen draws above itself: its summary goes between the tabs and
 * its controls. Height is what the dashboards are short of, so nothing else sits above them.
 */
typealias Chrome = @Composable (summary: @Composable RowScope.() -> Unit, controls: @Composable RowScope.() -> Unit) -> Unit

/**
 * Top-level layout: the header row (tabs Simple | Live | Trends | Diagnostics, the last one remembered) and
 * the selected screen, sized from the window this composition actually got after insets, never
 * from a known panel. Which tabs use the OBD link: UiSettings.wantsObd.
 *
 * While the truck is out of Park (`layout.restricted`) the Diagnostics and Trends tabs are not offered
 * and show only [AvailableInParkPanel]; none of their tables, charts, pairing, fields or buttons compose.
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
  val restricted by liveVm.restricted.collectAsStateWithLifecycle()
  val stateKnown by liveVm.drivingStateKnown.collectAsStateWithLifecycle()
  val tab = settings.tab
  // Out here, so a trip through the restricted panel (P to R to P) keeps the user's place.
  val diagnosticsList = rememberLazyListState()
  var diagnosticsShowAll by rememberSaveable { mutableStateOf(false) }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      val fontScale = LocalDensity.current.fontScale
      val layout = remember(maxWidth, maxHeight, restricted, fontScale) {
        DashLayout.of(maxWidth.value, maxHeight.value, restricted, fontScale)
      }
      val chrome: Chrome = { summary, controls ->
        ShellHeader(layout, tab, settings.demo, liveVm::selectTab, summary, controls)
      }
      when (tab) {
        AppTab.SIMPLE -> {
          val session by liveVm.shownSession.collectAsStateWithLifecycle()
          SimpleScreen(
            session = session,
            settings = settings,
            layout = layout,
            chrome = chrome,
            onTempUnit = liveVm::setTempUnit,
            onDistUnit = liveVm::setDistUnit,
            onReset = liveVm::resetSession,
            onMxOff = liveVm::setMxOff,
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
            layout = layout,
            chrome = chrome,
            onRetry = liveVm::retry,
            onMxOff = liveVm::setMxOff,
            onTempUnit = liveVm::setTempUnit,
            onDistUnit = liveVm::setDistUnit,
            onOpenDiagnostics = { liveVm.selectTab(AppTab.DIAGNOSTICS) },
          )
        }
        AppTab.TRENDS -> if (layout.restricted) {
          Column(Modifier.fillMaxSize()) {
            chrome({}, {})
            AvailableInParkPanel(layout, stateKnown, liveVm.drivingInputs, "Trends")
          }
        } else {
          val live by liveVm.shownLive.collectAsStateWithLifecycle()
          TrendsScreen(settings, live.status, layout, chrome)
        }
        AppTab.DIAGNOSTICS -> Column(Modifier.fillMaxSize()) {
          chrome({}, {})
          if (layout.restricted) {
            AvailableInParkPanel(layout, stateKnown, liveVm.drivingInputs)
          } else {
            DiagnosticsScreen(diagVm, liveVm, diagnosticsList, diagnosticsShowAll, { diagnosticsShowAll = it }, onRequestPermissions, onOpenAppSettings)
          }
        }
      }
    }
  }
}

@Composable
private fun ShellHeader(
  layout: DashLayout,
  tab: AppTab,
  demo: Boolean,
  onSelect: (AppTab) -> Unit,
  summary: @Composable RowScope.() -> Unit,
  controls: @Composable RowScope.() -> Unit,
) {
  val gap = layout.headerGapDp.dp
  Row(
    Modifier.fillMaxWidth().height(layout.headerDp.dp).padding(horizontal = layout.pagePadHDp.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(gap),
  ) {
    if (layout.appTitle) FitText("Silverado Stats", sp = layout.type.tab, weight = FontWeight.Bold)
    Row(Modifier.fillMaxHeight()) {
      AppTab.entries.filter { it != AppTab.DIAGNOSTICS || layout.diagnosticsTab }.filter { it != AppTab.TRENDS || layout.trendsTab }.forEach { t ->
        HeaderTab(t.label, tab == t, layout) { onSelect(t) }
      }
    }
    if (demo) DemoChip(layout)
    // Takes what is left: every other child in this row has already been measured when it does.
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) { summary() }
    controls()
    if (layout.readOnlyNote) FitText("Read-only", sp = layout.type.body, color = TextDim)
  }
}

@Composable
private fun HeaderTab(label: String, selected: Boolean, layout: DashLayout, onClick: () -> Unit) {
  val accent = MaterialTheme.colorScheme.primary
  // As tall as the header: at least 76 dp while driving.
  Box(
    Modifier
      .fillMaxHeight()
      .widthIn(min = layout.tabMinWidthDp.dp)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .drawBehind {
        if (selected) {
          val line = 4.dp.toPx()
          drawRect(accent, Offset(0f, size.height - line), Size(size.width, line))
        }
      }
      .padding(horizontal = layout.segmentPadDp.dp),
    contentAlignment = Alignment.Center,
  ) {
    FitText(label, sp = layout.type.tab, weight = FontWeight.SemiBold, color = if (selected) accent else MaterialTheme.colorScheme.onSurface)
  }
}

/** Stays on screen in every state: it changes what every number means. */
@Composable
private fun DemoChip(layout: DashLayout) {
  Surface(color = DemoBannerColor, shape = RoundedCornerShape(8.dp)) {
    FitText(
      if (layout.shortChrome) "DEMO" else "DEMO DATA",
      Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      sp = layout.type.body,
      color = Color(0xFF1A1400),
      weight = FontWeight.Bold,
    )
  }
}

/**
 * All the Diagnostics tab is while restricted: text only, nothing to tap. When the state is merely
 * unknown it says so, or a truck really in Park would show an unexplained message. The last line says
 * what the app is going by, because this is the one place the driver can read it when the app stays
 * in driving mode in Park (and Diagnostics, the usual way to find out, is the part that is locked).
 */
@Composable
private fun AvailableInParkPanel(layout: DashLayout, stateKnown: Boolean, inputs: StateFlow<DrivingInputs>, what: String = "Diagnostics") {
  val reading by inputs.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize().padding(layout.gapDp.dp), contentAlignment = Alignment.Center) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
