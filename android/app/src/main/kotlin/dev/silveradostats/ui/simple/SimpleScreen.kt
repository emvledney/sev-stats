package dev.silveradostats.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silveradostats.link.MxAction
import dev.silveradostats.link.UiSettings
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitColumnScope
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.layout.WindowTooSmall
import dev.silveradostats.ui.live.Marker
import dev.silveradostats.ui.live.MxButton
import dev.silveradostats.ui.live.Units
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.ui.theme.ToneEfficiency
import dev.silveradostats.ui.theme.ToneHave
import dev.silveradostats.ui.theme.ToneSpent
import dev.silveradostats.vehicle.LastCharge
import dev.silveradostats.vehicle.SessionState

/**
 * The Simple tab: four columns of two cells alike, by the owner's choice (2026-11-07, when the battery ring went):
 * the energy left over the state of charge, the capacity over the range, this drive's distance over its energy, and
 * its mi/kWh over the last 15 minutes'. Under all four, the last charge's row, a part under each column: what the
 * charge reached, what has been used since, the mi/kWh since and how far the level has drifted.
 * One page in any landscape window: the figures are as large as their tiles allow (see [DashLayout]).
 *
 * The same page in every gear, controls included: [layout] has no driving state ([DashLayout.ofSimple]),
 * so shifting moves nothing. [driving] only takes the button off a notice, whose system dialog and
 * Diagnostics page are for Park. Above the page: the tabs and the buttons, no words of its own, by the
 * owner's choice (2026-11-06).
 */
@Composable
fun SimpleScreen(
  session: SessionState,
  mxCapacityKwh: Double?,
  lastCharge: LastCharge?,
  settings: UiSettings,
  layout: DashLayout,
  driving: Boolean,
  chrome: Chrome,
  onReset: () -> Unit,
  mxAction: MxAction,
  onMxAction: (MxAction) -> Unit,
  onRequestPermissions: () -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  val view = remember(session, mxCapacityKwh, lastCharge) {
    SimpleModel.build(session, Units.DIST, mxCapacityKwh, lastCharge)
  }
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  // Asked in every gear: one stray tap must not cost a drive's figures.
  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { FitText("Reset the session?", sp = layout.type.cardTitle, weight = FontWeight.SemiBold) },
      text = { FitText("Distance, energy used and range start again from now.", sp = layout.type.body, maxLines = 3) },
      confirmButton = {
        TextButton(onClick = {
          confirmReset = false
          onReset()
        }) { FitText("Reset", sp = layout.type.tab - 2) }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { FitText("Cancel", sp = layout.type.tab - 2) } },
    )
  }

  Column(Modifier.fillMaxSize()) {
    // The two buttons are drawn at the foot of the navigation rail, each as wide as the rail.
    chrome(
      {},
      {
        MxButton(settings, mxAction, layout, onMxAction, Modifier.fillMaxWidth())
        OutlinedButton(
          onClick = { confirmReset = true },
          modifier = Modifier.fillMaxWidth().heightIn(min = layout.controlDp.dp),
          contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
        ) { FitText(if (layout.shortChrome) "Reset" else "Reset session", sp = layout.type.tab - 2) }
      },
    )
    if (layout.tooSmall(view.notice != null)) {
      WindowTooSmall(layout)
      return@Column
    }
    Column(
      Modifier.fillMaxWidth().weight(1f).padding(horizontal = layout.pagePadHDp.dp, vertical = layout.pagePadVDp.dp),
      verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
    ) {
      view.notice?.let { NoticeCard(it, layout, driving, onRequestPermissions, onOpenDiagnostics) }
      Tiles(view, layout, Modifier.fillMaxWidth().weight(1f))
    }
  }
}

// --- Building blocks --------------------------------------------------------------------------------

/** What a tile is about: a wash of [color] from its corner, and an edge of it. */
private class TileTone(val color: Color, val wash: Float)

private val TONE_HAVE = TileTone(ToneHave, 0.30f)
private val TONE_SPENT = TileTone(ToneSpent, 0.22f)
private val TONE_EFFICIENCY = TileTone(ToneEfficiency, 0.22f)
private val TONE_CHARGE = TileTone(ToneSpent, 0.14f)

private const val TONE_WASH_ENDS = 0.62f
private const val TONE_EDGE_DP = 8

private fun Modifier.toned(tone: TileTone, surface: Color): Modifier = drawBehind {
  drawRect(Brush.linearGradient(0f to lerp(surface, tone.color, tone.wash), TONE_WASH_ENDS to surface, start = Offset.Zero, end = Offset(size.width, size.height)))
  drawRect(tone.color, size = Size(TONE_EDGE_DP.dp.toPx(), size.height))
}

@Composable
private fun Panel(modifier: Modifier, layout: DashLayout, tone: TileTone, content: @Composable FitColumnScope.() -> Unit) {
  val surface = MaterialTheme.colorScheme.surface
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = surface),
  ) {
    FitColumn(
      Modifier.fillMaxSize().toned(tone, surface).padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp),
      spacing = layout.cardGapDp.dp,
      content = content,
    )
  }
}

@Composable
private fun NoticeCard(n: Notice, layout: DashLayout, driving: Boolean, onGrant: () -> Unit, onOpenDiagnostics: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().height(layout.noticeDp.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp),
      horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (driving) {
        // Nothing to do about it now: what is wrong, in the fewest words, and no button.
        FitText(if (n.action == NoticeAction.GRANT) "${SimpleModel.NEEDS_ACCESS}: allow it in Park" else n.title, Modifier.weight(1f), sp = layout.type.title, weight = FontWeight.SemiBold)
      } else {
        FitText(n.title, sp = layout.type.title, weight = FontWeight.SemiBold)
        FitText(n.text, Modifier.weight(1f), sp = layout.type.body, color = TextDim)
        val controlMin = Modifier.heightIn(min = minOf(layout.controlDp, layout.noticeDp - 4).dp)
        when (n.action) {
          NoticeAction.GRANT -> Button(onClick = onGrant, modifier = controlMin) { FitText("Allow access", sp = layout.type.tab - 2) }
          NoticeAction.OPEN_DIAGNOSTICS -> OutlinedButton(onClick = onOpenDiagnostics, modifier = controlMin) { FitText("Open Diagnostics", sp = layout.type.tab - 2) }
          null -> {}
        }
      }
    }
  }
}

/** A tile's title and badge. The badge changes what the figure means: it has first claim. Then the label. */
@Composable
private fun StatHead(s: Stat, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  PriorityRow(modifier) {
    FitText(s.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.title).dp), sp = type.title, color = TextDim)
    s.marker?.let { Marker(it, type.label, Modifier.slot(0)) }
  }
}

/** A big value with its unit. */
@Composable
private fun StatFigure(s: Stat, layout: DashLayout, sp: Int, modifier: Modifier = Modifier, unitSp: Int = layout.simpleUnitSp(sp)) {
  Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FitText(
      s.value,
      Modifier.alignByBaseline(),
      sp = sp,
      lineFactor = DashLayout.VALUE_LINE_FACTOR,
      weight = FontWeight.Bold,
      color = if (s.hasValue) MaterialTheme.colorScheme.onSurface else TextDim,
    )
    if (s.unit.isNotEmpty()) FitText(s.unit, Modifier.alignByBaseline().weight(1f, fill = false), sp = unitSp, color = TextDim)
  }
}

/**
 * Head (title, marker), then a big value with its unit, centred over the room for one line. The same in every
 * cell, with or without the line, so that the figures of a row line up; the line goes first when it is short of room.
 */
@Composable
private fun StatTile(s: Stat, figureSp: Int, layout: DashLayout, tone: TileTone, modifier: Modifier) {
  val figureDp = layout.lineDp(figureSp, DashLayout.VALUE_LINE_FACTOR)
  Panel(modifier, layout, tone) {
    StatHead(s, layout, Modifier.fit(1).fillMaxWidth())
    BoxWithConstraints(Modifier.fill(0, figureDp.dp).fillMaxWidth()) {
      val line = maxHeight.value >= figureDp + layout.simpleLineDp
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) { StatFigure(s, layout, figureSp) }
        if (line) {
          Box(Modifier.height(layout.simpleLineDp.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            s.lines.firstOrNull()?.let { FitText(it, sp = layout.type.body, color = TextDim) }
          }
        }
      }
    }
  }
}

// --- The page ---------------------------------------------------------------------------------------

@Composable
private fun Tiles(view: SimpleView, layout: DashLayout, modifier: Modifier) {
  val notice = view.notice != null
  val gap = Arrangement.spacedBy(layout.gapDp.dp)
  val figureSp = layout.simpleFigureSp(notice)
  Column(modifier, verticalArrangement = gap) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = gap) {
      listOf(
        Triple(view.remaining, view.soc, TONE_HAVE),
        Triple(view.capacity, view.range, TONE_HAVE),
        Triple(view.trip, view.energyUsed, TONE_SPENT),
        Triple(view.efficiency, view.efficiencyRecent, TONE_EFFICIENCY),
      ).forEach { (top, bottom, tone) ->
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
          StatTile(top, figureSp, layout, tone, Modifier.weight(1f).fillMaxWidth())
          StatTile(bottom, figureSp, layout, tone, Modifier.weight(1f).fillMaxWidth())
        }
      }
    }
    ChargeTile(view.charge, notice, layout, Modifier.fillMaxWidth().height(layout.simpleChargeDp(notice).dp))
  }
}

/**
 * The last charge, as a row of four tiles in the style of the ones above, each under a column above and spaced as
 * those are, so that its figures start where theirs do: what the charge reached in kWh and %, then what has been
 * used since, the mi/kWh since and the drift.
 */
@Composable
private fun ChargeTile(c: ChargeView, notice: Boolean, layout: DashLayout, modifier: Modifier) {
  val sp = layout.simpleChargeSp(notice)
  val unitSp = layout.simpleChargeUnitSp(sp)
  val reached = layout.simpleChargeReachedWeights(notice)
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    ChargePart(layout) {
      ChargeFigure(c.to, layout, sp, unitSp, Modifier.weight(reached[0]))
      ChargeFigure(c.toPct, layout, sp, unitSp, Modifier.weight(reached[1]))
    }
    listOf(c.used, c.efficiency, c.drift).forEach { s ->
      ChargePart(layout) { ChargeFigure(s, layout, sp, unitSp, Modifier.weight(1f)) }
    }
  }
}

/** One tile of the last charge's row: as wide as a column above, its figures as far in, and toned like the tiles above. */
@Composable
private fun RowScope.ChargePart(layout: DashLayout, content: @Composable RowScope.() -> Unit) {
  val surface = MaterialTheme.colorScheme.surface
  Card(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = surface)) {
    Row(Modifier.fillMaxSize().toned(TONE_CHARGE, surface).padding(horizontal = layout.cardPadHDp.dp), content = content)
  }
}

/** A figure of the last charge's row, with its words under it. */
@Composable
private fun ChargeFigure(s: Stat, layout: DashLayout, sp: Int, unitSp: Int, modifier: Modifier) {
  Column(
    modifier.fillMaxHeight(),
    verticalArrangement = Arrangement.spacedBy(layout.cardGapDp.dp, Alignment.CenterVertically),
  ) {
    StatFigure(s, layout, sp, unitSp = unitSp)
    FitText(s.title, sp = layout.simpleChargeLabelSp, color = TextDim)
  }
}
