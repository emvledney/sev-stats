package dev.silveradostats.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.MonoClock
import dev.silveradostats.link.MxAction
import dev.silveradostats.link.UiSettings
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitColumnScope
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.layout.SizeTier
import dev.silveradostats.ui.layout.WindowTooSmall
import dev.silveradostats.ui.theme.CardEdge
import dev.silveradostats.ui.theme.CardShape
import dev.silveradostats.ui.theme.DivergingHigh
import dev.silveradostats.ui.theme.DivergingLow
import dev.silveradostats.ui.theme.DivergingMid
import dev.silveradostats.ui.theme.GridLine
import dev.silveradostats.ui.theme.Ink
import dev.silveradostats.ui.theme.StatusError
import dev.silveradostats.ui.theme.StatusOk
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TempRampHigh
import dev.silveradostats.ui.theme.TempRampLow
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.Fmt
import dev.silveradostats.vehicle.KnownProperties
import dev.silveradostats.vehicle.PropertyRow
import dev.silveradostats.vehicle.RowStatus
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** VHAL figures from the Diagnostics live rows (refreshed every second while visible). */
fun vhalFigures(rows: List<PropertyRow>): VhalFigures {
  fun v(id: Int) = rows.firstOrNull { it.id == id && it.status == RowStatus.AVAILABLE }?.rawValue?.let { Fmt.num(it) }
  return VhalFigures(
    energyWh = v(KnownProperties.EV_BATTERY_LEVEL),
    usableWh = v(KnownProperties.EV_CURRENT_BATTERY_CAPACITY),
    nominalWh = v(KnownProperties.INFO_EV_BATTERY_CAPACITY),
    rangeM = v(KnownProperties.RANGE_REMAINING),
    outsideC = v(KnownProperties.ENV_OUTSIDE_TEMPERATURE),
  )
}

/**
 * The OBD dashboard, on one page in any landscape window: KPI tiles of a fixed height, then the grid of pack
 * and temperature tiles beside the cell-group heatmap with its module strip.
 *
 * The page is the same in every gear, by the owner's choice (2026-11-11): the shell gives it the parked layout
 * ([DashLayout.ofLive]) whatever the truck is doing, heatmap and its tap-to-inspect included. [driving] only
 * slows the figures to one change a second.
 */
@Composable
fun LiveScreen(
  live: LiveState,
  vhal: VhalFigures,
  /** The session's own range, m (the truck's is in [live] and [vhal]). */
  ownRangeM: Double?,
  settings: UiSettings,
  layout: DashLayout,
  /** The truck is out of Park: the figures then change together, once a second. */
  driving: Boolean,
  chrome: Chrome,
  onRetry: () -> Unit,
  mxAction: MxAction,
  onMxAction: (MxAction) -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  val now by produceState(MonoClock.ms()) {
    while (true) {
      value = MonoClock.ms()
      delay(1_000)
    }
  }
  // While driving the figures all change together on the clock's tick, once a second, whatever
  // the two sources do between ticks.
  val dash = remember(now, if (driving) Unit else live, if (driving) Unit else vhal) {
    DashboardModel.build(live, vhal, Units.TEMP, Units.DIST, now, settings.savedCapacity, System.currentTimeMillis(), ownRangeM)
  }

  Column(Modifier.fillMaxSize()) {
    chrome(
      { LiveSummary(dash.status, layout) },
      {
        val wide = if (layout.inRail) Modifier.fillMaxWidth() else Modifier
        MxButton(settings, mxAction, layout, onMxAction, wide)
        // With an MX+ that button reconnects; the simulator has no such button, only Retry.
        if (layout.controls) {
          if (dash.status.canRetry && settings.demo) {
            Button(
              onClick = onRetry,
              modifier = wide.heightIn(min = layout.controlDp.dp),
              contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
            ) { FitText("Retry", sp = layout.type.tab - 2) }
          }
        }
      },
    )
    if (layout.tooSmall()) {
      WindowTooSmall(layout)
      return@Column
    }
    Column(
      Modifier.fillMaxWidth().weight(1f).padding(horizontal = layout.pagePadHDp.dp, vertical = layout.pagePadVDp.dp),
      verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
    ) {
      KpiTiles(dash, layout)
      // Only what has its minimum height: a window too short for the cards shows no empty ones.
      if (layout.liveCards) {
        LiveParked(dash, live.status == LinkStatus.NO_ADAPTER, Units.TEMP, layout, onOpenDiagnostics, Modifier.weight(1f))
      }
    }
  }
}

/** The cards under the tiles: the grid of pack and temperature tiles, and the cell-group heatmap with its tap-to-inspect. */
@Composable
private fun LiveParked(dash: Dashboard, noAdapter: Boolean, unit: TempUnit, layout: DashLayout, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
  var selectedGroup by rememberSaveable { mutableStateOf<Int?>(null) }
  // The cell grid is a quarter narrower than it was (2.4 of 3.4 shares), by the owner's request (2026-11-13): the tiles have the rest.
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    PackTiles(dash.packTiles, layout, Modifier.weight(DashLayout.PACK_SHARE).fillMaxHeight())
    if (dash.heatmap == null) {
      NoCellDataCard(noAdapter, dash.obdHint, dash.moduleTemps, unit, layout, onOpenDiagnostics, Modifier.weight(DashLayout.HEAT_SHARE).fillMaxHeight())
    } else {
      HeatmapCard(dash.heatmap, dash.moduleTemps, unit, selectedGroup, { selectedGroup = it }, layout, Modifier.weight(DashLayout.HEAT_SHARE).fillMaxHeight())
    }
  }
}

/**
 * Connect / Disconnect for the MX+ ([MxAction]), on every Simple and Live state, driving too: one tap,
 * as big as the other driving targets, so the phone can have Bluetooth back, or a dropped link can be
 * brought back, without pulling over.
 */
@Composable
internal fun MxButton(settings: UiSettings, action: MxAction, layout: DashLayout, onMxAction: (MxAction) -> Unit, modifier: Modifier = Modifier) {
  if (settings.demo || settings.mxAddress == null) return
  OutlinedButton(
    onClick = { onMxAction(action) },
    modifier = modifier.heightIn(min = layout.mxButtonDp.dp),
    contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
  ) { FitText(if (layout.shortChrome) action.shortLabel else action.label, sp = layout.type.tab - 2) }
}

internal fun toneColor(t: Tone): Color = when (t) {
  Tone.GOOD -> StatusOk
  Tone.WARN -> StatusWarn
  Tone.ERROR -> StatusError
  Tone.DIM -> TextDim
  Tone.NORMAL -> Ink
}

@Composable
private fun DashCard(modifier: Modifier, layout: DashLayout, content: @Composable FitColumnScope.() -> Unit) {
  Card(
    modifier = modifier,
    shape = CardShape,
    border = CardEdge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    FitColumn(
      Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp),
      spacing = layout.cardGapDp.dp,
      content = content,
    )
  }
}

/** Small marker beside a title: "unverified" / "likely" (warning tint), "VHAL" / "est." (neutral). */
@Composable
internal fun Marker(text: String, sp: Int, modifier: Modifier = Modifier) {
  val c = if (text == "unverified" || text == "likely" || text == "sign unverified" || text == "unknown") StatusWarn else MaterialTheme.colorScheme.primary
  Surface(modifier, color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
    FitText(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), sp = sp, color = c)
  }
}

@Composable
internal fun Dot(color: Color, size: Int = 14, modifier: Modifier = Modifier) {
  Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

// --- Header summary -------------------------------------------------------------------------------

/**
 * The link state, in the header row, demo mode included. While driving: the chip alone, plus the data's
 * age only once it is stale, because that changes what the figures mean.
 */
@Composable
private fun RowScope.LiveSummary(s: StatusView, layout: DashLayout) {
  val wide = if (layout.inRail) Modifier.fillMaxWidth() else Modifier
  Surface(
    modifier = wide,
    shape = RoundedCornerShape(24.dp),
    color = toneColor(s.tone).copy(alpha = 0.14f),
    border = BorderStroke(2.dp, toneColor(s.tone)),
  ) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Dot(toneColor(s.tone), 12)
      Spacer(Modifier.width(8.dp))
      FitText(s.chip, sp = layout.type.body, weight = FontWeight.SemiBold)
    }
  }
  if (layout.restricted) {
    if (s.dataStale) s.dataAge?.let { FitText(it, Modifier.weight(1f, fill = false), sp = layout.type.body, color = StatusWarn) }
  } else if (layout.inRail) {
    // In the rail: narrow, so the message wraps and each detail has its own line.
    Column(Modifier.fillMaxWidth()) {
      FitText(s.message, sp = layout.type.label, maxLines = 3)
      listOfNotNull(s.dataAge, s.retryIn).forEach { FitText(it, sp = layout.type.label, color = if (s.dataStale) StatusWarn else TextDim) }
    }
  } else {
    Column(Modifier.weight(1f)) {
      FitText(s.message, sp = layout.type.body)
      val meta = listOfNotNull(s.dataAge, s.adapterVolts, s.retryIn).joinToString("  ·  ")
      if (meta.isNotEmpty()) FitText(meta, sp = layout.type.label, color = if (s.dataStale) StatusWarn else TextDim)
    }
  }
}

// --- KPI tiles --------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.KpiTiles(dash: Dashboard, layout: DashLayout) {
  val tiles = listOf(dash.soc, dash.remaining, dash.capacity, dash.capacityAh, dash.capacityDeviation)
  // One row of all five where the page has its tiles in a row; two to a row on a window too narrow for that.
  val across = if (layout.liveTileCols >= DashLayout.LIVE_TILES) tiles.size else 2
  val rows = tiles.chunked(across)
  // The capacity tiles carry no description of what they are, by the owner's choice (2026-11-11): only
  // the marker beside the title says read, estimated or last read. Their lines stay where they are
  // news: a figure that is a saved one (how old it is), or missing because there is no adapter.
  // The charge is alone in its tile: what is left has the tile beside it, and this page shows no range.
  fun hasLines(k: Kpi) = k !== dash.soc && k !== dash.remaining && (k.marker == DashboardModel.LAST_READ || (!k.hasValue && k !== dash.capacityDeviation))
  // All five in a row: the figures fill their tiles, and the row is no taller than they need.
  val fill = across == tiles.size && layout.liveCards
  val anyLines = tiles.any(::hasLines)
  val figureSp = if (fill) layout.obdFigureSp(anyLines) else layout.liveFigureSp(across)
  val size = when {
    !layout.liveCards -> Modifier.weight(1f)
    fill -> Modifier.height(layout.obdTilesDp(figureSp, anyLines).dp)
    else -> Modifier.height(layout.liveTilesDp.dp)
  }
  Column(Modifier.fillMaxWidth().then(size), verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    rows.forEach { row ->
      Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
        row.forEach { k ->
          KpiTile(k, hasLines(k), figureSp, layout, Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
  }
}

@Composable
private fun KpiTile(k: Kpi, lines: Boolean, figureSp: Int, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  val short = layout.liveShort
  DashCard(modifier, layout) {
    // Who gives way first: an age that is not a warning, then the label.
    PriorityRow(Modifier.fit(1).fillMaxWidth()) {
      FitText(if (short) k.shortTitle else k.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.title).dp), sp = type.title, color = TextDim)
      tag(k.marker)?.let { Marker(it, type.label, Modifier.slot(0)) }
      k.age?.takeIf { layout.ages || k.stale }?.let {
        FitText(it, Modifier.slot(if (k.stale) 2 else 5, end = true), sp = type.label, color = if (k.stale) StatusWarn else TextDim)
      }
    }
    Row(Modifier.fit(0).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FitText(
        k.value,
        Modifier.alignByBaseline(),
        sp = figureSp,
        lineFactor = DashLayout.VALUE_LINE_FACTOR,
        weight = FontWeight.Bold,
        color = if (k.hasValue) MaterialTheme.colorScheme.onSurface else TextDim,
      )
      FitText(k.unit, Modifier.alignByBaseline().weight(1f, fill = false), sp = type.unit, color = TextDim)
    }
    if (!lines) {
      // No description under this figure.
    } else if (layout.restricted) {
      k.drivingLines.take(layout.liveTileLines).forEach { FitText(it, Modifier.fit(3), sp = type.body, color = TextDim) }
    } else {
      k.lines.take(layout.liveTileLines).forEachIndexed { i, line ->
        FitText(if (i == 0 && short) k.shortLine ?: line else line, Modifier.fit(3), sp = type.body, color = TextDim)
      }
    }
  }
}

// --- Temperatures -----------------------------------------------------------------------------------

@Composable
private fun TempsCard(title: String, lines: List<TempLine>, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  val short = layout.liveShort
  DashCard(modifier, layout) {
    FitText(title, Modifier.fit(2), sp = type.cardTitle, weight = FontWeight.SemiBold)
    // In order of importance, so a short card keeps the pack's own figures.
    lines.forEachIndexed { i, l ->
      PriorityRow(Modifier.fit(3 + i).fillMaxWidth()) {
        FitText(if (short) l.shortLabel else l.label, Modifier.slot(2, minWidth = layout.labelMinDp(type.body).dp), sp = type.body, color = TextDim)
        tag(l.marker)?.let { Marker(it, type.label, Modifier.slot(1, end = true)) }
        FitText(
          if (short) l.shortValue else l.value,
          Modifier.slot(0, minWidth = 1.dp, end = true),
          sp = if (layout.restricted) type.title else type.body + 1,
          weight = FontWeight.SemiBold,
          color = if (l.stale) StatusWarn else MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

/** The tag beside a title, except "unverified": the owner does not want that one shown (2026-11-11). */
private fun tag(marker: String?): String? = marker?.takeUnless { it == "unverified" }

/**
 * The parked page's grid of small tiles ([Dashboard.packTiles]), row by row as the model gives them: up to three
 * small tiles, each a label over its value, or one tile with parts (a battery's temperatures), its figures side
 * by side over their captions. A list of rows ran out of height and read as a table, not as figures.
 */
@Composable
private fun PackTiles(rows: List<List<PackTile>>, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  // Six rows share the height: the gaps between them stay small, so the text has it.
  val gapDp = minOf(layout.gapDp, 8)
  val gap = Arrangement.spacedBy(gapDp.dp)
  BoxWithConstraints(modifier) {
    val rowDp = (maxHeight.value - (rows.size - 1) * gapDp) / rows.size.coerceAtLeast(1)
    val inner = maxWidth.value - 2 * layout.cardPadHDp
    // One size for every small tile, from the narrowest (three to a row), and one for every wide tile's five columns.
    val across = rows.filter { r -> r.all { it.parts.isEmpty() } }.maxOfOrNull { it.size } ?: 1
    val (valueSp, labelSp) = layout.packSp(rowDp, (inner - (across - 1) * gapDp) / across - layout.cardPadHDp, DashLayout.PACK_VALUE_CHARS)
    val (partSp, captionSp) = layout.packSp(rowDp, inner / 5 - 4, DashLayout.PACK_PART_CHARS)
    Column(Modifier.fillMaxSize(), verticalArrangement = gap) {
      rows.forEach { row ->
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = gap) {
          row.forEach { t ->
            Card(
              modifier = Modifier.weight(1f).fillMaxHeight(),
              shape = CardShape,
              border = CardEdge,
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
              val ink = if (t.stale) StatusWarn else MaterialTheme.colorScheme.onSurface
              val pad = Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp, vertical = DashLayout.PACK_PAD_V_DP.dp)
              if (t.parts.isEmpty()) {
                FitColumn(pad, spacing = DashLayout.PACK_SPACING_DP.dp) {
                  PriorityRow(Modifier.fit(1).fillMaxWidth()) {
                    FitText(t.label, Modifier.slot(1, minWidth = layout.labelMinDp(labelSp).dp), sp = labelSp, color = TextDim)
                    tag(t.marker)?.let { Marker(it, type.label, Modifier.slot(0, end = true)) }
                  }
                  FitText(t.value, Modifier.fit(0).fillMaxWidth(), sp = valueSp, weight = FontWeight.Bold, color = ink)
                }
              } else {
                // A wide tile: its name and unit lead the row, then the figures side by side over their captions.
                Row(pad, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Column(Modifier.weight(1f)) {
                    FitText(t.label, sp = captionSp + 2, weight = FontWeight.SemiBold)
                    // Under the name: the unit only. There is no room here for a tag such as "likely".
                    FitText(t.value, sp = captionSp, color = TextDim)
                  }
                  t.parts.forEach { (caption, figure) ->
                    Column(Modifier.weight(1f)) {
                      FitText(figure, sp = partSp, weight = FontWeight.Bold, color = ink)
                      FitText(caption, sp = captionSp, color = TextDim)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

// --- Cell-group heatmap (never composed while driving) --------------------------------------------

private fun heatColor(devMv: Double?, scaleMv: Double): Color {
  if (devMv == null) return Color.Transparent
  val p = DashboardModel.colourPosition(devMv, scaleMv).toFloat()
  return if (p < 0) lerp(DivergingMid, DivergingLow, -p) else lerp(DivergingMid, DivergingHigh, p)
}

/** One amber hue, dark -> bright over 0 … 50 °C. */
private fun tempColor(celsius: Double): Color = lerp(TempRampLow, TempRampHigh, (celsius / 50.0).coerceIn(0.0, 1.0).toFloat())

/** Where the cell grid would be when there are no cell voltages to show: what it takes to get them. */
@Composable
private fun NoCellDataCard(noAdapter: Boolean, hint: String?, temps: ModuleTemps?, unit: TempUnit, layout: DashLayout, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
  val type = layout.type
  DashCard(modifier, layout) {
    FitText(if (noAdapter) "No OBD adapter chosen" else "Cell groups vs pack average", Modifier.fit(0), sp = type.cardTitle, weight = FontWeight.SemiBold)
    FitText(
      if (noAdapter) {
        "Pair and choose your OBDLink MX+ under Settings › OBDLink MX+ link (or turn on demo data there)."
      } else {
        "Cell-group voltages (192 on this truck: 24 modules × 8 groups) need the OBDLink MX+."
      },
      Modifier.fit(1),
      sp = type.body,
      color = TextDim,
      maxLines = 3,
    )
    if (noAdapter) {
      OutlinedButton(
        onClick = onOpenDiagnostics,
        modifier = Modifier.fit(1).heightIn(min = layout.controlDp.dp),
        contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
      ) { FitText("Open Settings", sp = type.tab - 2) }
    }
    hint?.let { FitText(it, Modifier.fit(4), sp = type.label, color = TextDim, maxLines = 4) }
    if (temps != null) {
      ModuleTempStrip(temps, temps.celsius.size, null, unit, layout, Modifier.fit(2).fillMaxWidth().height(layout.heatStripDp.dp))
    }
  }
}

@Composable
private fun HeatmapCard(
  heat: HeatmapView,
  temps: ModuleTemps?,
  unit: TempUnit,
  selectedGroup: Int?,
  onSelect: (Int?) -> Unit,
  layout: DashLayout,
  modifier: Modifier,
) {
  val type = layout.type
  DashCard(modifier, layout) {
    val sel = selectedGroup?.let { g -> heat.cells.getOrNull(g - 1) }
    // Owner's request (2026-11-12): flip the grid between cell voltages and temperatures. The truck reports
    // temperatures per module (two sensors each), not per cell, so that view is one tall cell per module.
    var showTemps by rememberSaveable { mutableStateOf(false) }
    val tempView = showTemps && temps != null
    // The summary caption ("192 groups · average … · read under load") was removed on the owner's request
    // (2026-11-12); only a tapped cell's details show here.
    Row(Modifier.fit(2).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
      FitText(
        if (tempView) "Module temperatures" else if (layout.liveShort) "Cell groups" else "Cell groups vs pack average",
        sp = type.cardTitle,
        weight = FontWeight.SemiBold,
      )
      FitText(
        if (tempView) "" else sel?.let { DashboardModel.cellDetail(it) } ?: "",
        Modifier.weight(1f),
        sp = type.title,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (temps != null) {
        OutlinedButton(
          onClick = { showTemps = !showTemps },
          contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
        ) { FitText(if (tempView) "Voltage" else "Temperature", sp = type.tab - 2) }
      }
    }
    val gridMod = Modifier.fill(0, (layout.heatHeadDp + DashLayout.HEAT_ROWS * DashLayout.HEAT_ROW_MIN_DP).dp).fillMaxWidth()
    if (tempView) {
      ModuleTempBlocks(temps!!, heat.modules, heat.halfAfter, unit, layout, gridMod)
    } else {
      // No module temperatures under the voltages, and no "likely" tag (owner, 2026-11-13): the Temperature view has them.
      Heatmap(heat, selectedGroup, onSelect, layout, gridMod)
    }
    if (!tempView) Legend(heat, layout, Modifier.fit(3).fillMaxWidth())
  }
}

/** A row of [columns] equal cells with a gap between the two pack halves, and a left label. */
@Composable
private fun ColumnsRow(label: String?, columns: Int, halfAfter: Int?, layout: DashLayout, modifier: Modifier, cell: @Composable (module: Int, Modifier) -> Unit) {
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.width(layout.heatLabelDp.dp)) {
      if (label != null) FitText(label, sp = layout.type.axis, color = TextDim)
    }
    for (m in 1..columns) {
      cell(m, Modifier.weight(1f).fillMaxSize())
      if (m == halfAfter) Spacer(Modifier.width(layout.gapDp.dp))
    }
  }
}

@Composable
private fun Heatmap(heat: HeatmapView, selectedGroup: Int?, onSelect: (Int?) -> Unit, layout: DashLayout, modifier: Modifier) {
  BoxWithConstraints(modifier) {
    val rowDp = (maxHeight.value - layout.heatHeadDp) / heat.groupsPerModule
    // A row label only where the row is tall enough to hold one; never a cut one.
    val rowLabels = rowDp >= layout.lineDp(layout.type.axis)
    val halfGap = if (heat.halfAfter != null) layout.gapDp else 0
    val cellDp = (maxWidth.value - layout.heatLabelDp - halfGap) / heat.modules
    // Two-digit module numbers on every column only if they fit one column's width.
    val everyModule = cellDp >= 2 * layout.type.axis * 0.62f * layout.fontScale
    Column(Modifier.fillMaxSize()) {
      ColumnsRow("mod", heat.modules, heat.halfAfter, layout, Modifier.height(layout.heatHeadDp.dp)) { m, mod ->
        if (everyModule || m % 2 == 1) {
          Box(mod, contentAlignment = Alignment.Center) {
            FitText("$m", Modifier.wrapContentWidth(unbounded = true), sp = layout.type.axis, color = TextDim, align = TextAlign.Center, overflow = TextOverflow.Visible)
          }
        }
      }
      for (row in 0 until heat.groupsPerModule) {
        ColumnsRow(
          if (!rowLabels) null else if (row == 0) "g 1" else "${row + 1}",
          heat.modules,
          heat.halfAfter,
          layout,
          Modifier.weight(1f),
        ) { m, mod ->
          val c = heat.cell(m, row)
          val selected = c != null && c.group == selectedGroup
          Box(
            mod
              .padding(1.dp)
              .clip(RoundedCornerShape(3.dp))
              .background(heatColor(c?.devMv, heat.scaleMv))
              .then(
                when {
                  selected -> Modifier.border(3.dp, Color.White, RoundedCornerShape(3.dp))
                  c?.volts == null -> Modifier.border(1.dp, GridLine, RoundedCornerShape(3.dp))
                  else -> Modifier
                },
              )
              .clickable(enabled = c != null) { onSelect(if (selected) null else c?.group) },
          )
        }
      }
    }
  }
}

/** Temperature view: a block per module, one row per battery half, the module's two sensors stacked with their figures. */
@Composable
private fun ModuleTempBlocks(temps: ModuleTemps, modules: Int, halfAfter: Int?, unit: TempUnit, layout: DashLayout, modifier: Modifier) {
  val rows = (1..modules).chunked(halfAfter ?: modules)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    for (row in rows) {
      Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (m in row) {
          Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FitText("$m", Modifier.fillMaxWidth(), sp = layout.type.axis, color = TextDim, align = TextAlign.Center)
            for (c in temps.pairs.getOrNull(m - 1) ?: listOf(null, null)) {
              val bg = if (c != null) tempColor(c) else Color.Transparent
              Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(bg).then(if (c == null) Modifier.border(1.dp, GridLine, RoundedCornerShape(4.dp)) else Modifier),
                contentAlignment = Alignment.Center,
              ) {
                if (c != null) {
                  FitText(
                    Units.num(Units.temp(c, unit), 1),
                    sp = layout.type.title,
                    color = if (bg.luminance() > 0.4f) Color(0xFF14110A) else Ink,
                    weight = FontWeight.SemiBold,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ModuleTempStrip(temps: ModuleTemps, columns: Int, halfAfter: Int?, unit: TempUnit, layout: DashLayout, modifier: Modifier) {
  BoxWithConstraints(modifier) {
    val halfGap = if (halfAfter != null) layout.gapDp else 0
    val cellDp = (maxWidth.value - layout.heatLabelDp - halfGap) / columns.coerceAtLeast(1)
    val figures = temps.celsius.filterNotNull().map { Units.temp(it, unit).roundToInt().toString().length }
    // Colour alone when the figures do not fit the column; never an ellipsis in a one-number cell.
    val showFigures = cellDp >= (figures.maxOrNull() ?: 2) * layout.type.axis * 0.62f * layout.fontScale + 2
    ColumnsRow(unit.symbol, columns, halfAfter, layout, Modifier.fillMaxSize()) { m, mod ->
      val c = temps.celsius.getOrNull(m - 1)
      Box(
        mod
          .padding(1.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(if (c != null) tempColor(c) else Color.Transparent),
        contentAlignment = Alignment.Center,
      ) {
        if (c != null && showFigures) {
          val bg = tempColor(c)
          FitText(
            Units.temp(c, unit).roundToInt().toString(),
            sp = layout.type.axis,
            color = if (bg.luminance() > 0.4f) Color(0xFF14110A) else Ink,
            weight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun Legend(heat: HeatmapView, layout: DashLayout, modifier: Modifier) {
  val sp = layout.type.label
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FitText("${Units.MINUS}${Units.num(heat.scaleMv, 1)} mV", sp = sp, color = TextDim)
    Box(
      Modifier
        .width(if (layout.tier == SizeTier.COMPACT) 80.dp else 160.dp)
        .height(12.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(Brush.horizontalGradient(listOf(DivergingLow, DivergingMid, DivergingHigh))),
    )
    FitText("+${Units.num(heat.scaleMv, 1)} mV", sp = sp, color = TextDim)
  }
}
