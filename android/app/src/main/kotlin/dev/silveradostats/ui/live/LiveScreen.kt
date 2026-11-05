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
import dev.silveradostats.link.UiSettings
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitColumnScope
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.layout.Segmented
import dev.silveradostats.ui.layout.SizeTier
import dev.silveradostats.ui.layout.WindowTooSmall
import dev.silveradostats.ui.theme.DivergingHigh
import dev.silveradostats.ui.theme.DivergingLow
import dev.silveradostats.ui.theme.DivergingMid
import dev.silveradostats.ui.theme.GridLine
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
 * Live dashboard, on one page in any landscape window: KPI tiles of a fixed height, then cards that
 * share what is left. Parked: pack and temperatures, cell-group heatmap with its module strip.
 * Driving ([DashLayout.restricted]): larger tiles, pack, a temperature summary; the heatmap and the
 * strip are not composed, there is nothing to tap, and cell min / max stays in its tile.
 */
@Composable
fun LiveScreen(
  live: LiveState,
  vhal: VhalFigures,
  /** The session's own range, m (the truck's is in [live] and [vhal]). */
  ownRangeM: Double?,
  settings: UiSettings,
  layout: DashLayout,
  chrome: Chrome,
  onRetry: () -> Unit,
  onMxOff: (Boolean) -> Unit,
  onTempUnit: (TempUnit) -> Unit,
  onDistUnit: (DistUnit) -> Unit,
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
  val dash = remember(now, settings.tempUnit, settings.distUnit, if (layout.restricted) Unit else live, if (layout.restricted) Unit else vhal) {
    DashboardModel.build(live, vhal, settings.tempUnit, settings.distUnit, now, settings.savedCapacity, System.currentTimeMillis(), ownRangeM)
  }

  Column(Modifier.fillMaxSize()) {
    chrome(
      { LiveSummary(dash.status, layout) },
      {
        MxButton(settings, layout, onMxOff)
        if (layout.controls) {
          if (dash.status.canRetry) {
            Button(
              onClick = onRetry,
              modifier = Modifier.heightIn(min = layout.controlDp.dp),
              contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
            ) { FitText("Retry", sp = layout.type.tab - 2) }
          }
          Segmented(TempUnit.entries.map { it.symbol }, settings.tempUnit.ordinal, layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) { onTempUnit(TempUnit.entries[it]) }
          Segmented(DistUnit.entries.map { it.symbol }, settings.distUnit.ordinal, layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) { onDistUnit(DistUnit.entries[it]) }
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
        if (layout.restricted) {
          LiveDriving(dash, layout, Modifier.weight(1f))
        } else {
          LiveParked(dash, live.status == LinkStatus.NO_ADAPTER, settings.tempUnit, layout, onOpenDiagnostics, Modifier.weight(1f))
        }
      }
    }
  }
}

/** Driving: the pack and a temperature summary, nothing to tap, no cell or module grid. */
@Composable
private fun LiveDriving(dash: Dashboard, layout: DashLayout, modifier: Modifier) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    TempsCard("Pack", dash.pack, layout, Modifier.weight(1f).fillMaxHeight())
    TempsCard("Temperatures", dash.tempSummary, layout, Modifier.weight(1f).fillMaxHeight())
  }
}

/** Parked: everything, and only here the tap-to-inspect details and the grids that need them. */
@Composable
private fun LiveParked(dash: Dashboard, noAdapter: Boolean, unit: TempUnit, layout: DashLayout, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
  var selectedGroup by rememberSaveable { mutableStateOf<Int?>(null) }
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    TempsCard(if (layout.liveShort) "Pack and temps" else "Pack, temperatures and 12 V", dash.pack + dash.temps, layout, Modifier.weight(1f).fillMaxHeight())
    if (dash.heatmap == null) {
      NoCellDataCard(noAdapter, dash.obdHint, dash.moduleTemps, unit, layout, onOpenDiagnostics, Modifier.weight(2.4f).fillMaxHeight())
    } else {
      HeatmapCard(dash.heatmap, dash.moduleTemps, unit, selectedGroup, { selectedGroup = it }, layout, Modifier.weight(2.4f).fillMaxHeight())
    }
  }
}

/**
 * Disconnect / Connect for the MX+, on every Simple and Live state, driving too: one tap, as big as
 * the other driving targets, so the phone can have Bluetooth back without pulling over.
 */
@Composable
internal fun MxButton(settings: UiSettings, layout: DashLayout, onMxOff: (Boolean) -> Unit) {
  if (settings.demo || settings.mxAddress == null) return
  val word = if (settings.mxOff) "Connect" else "Disconnect"
  OutlinedButton(
    onClick = { onMxOff(!settings.mxOff) },
    modifier = Modifier.heightIn(min = layout.mxButtonDp.dp),
    contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
  ) { FitText(if (!layout.shortChrome) "$word MX+" else if (settings.mxOff) "MX+ on" else "MX+ off", sp = layout.type.tab - 2) }
}

internal fun toneColor(t: Tone): Color = when (t) {
  Tone.GOOD -> StatusOk
  Tone.WARN -> StatusWarn
  Tone.ERROR -> StatusError
  Tone.DIM -> TextDim
  Tone.NORMAL -> Color(0xFFE8EDF2)
}

@Composable
private fun DashCard(modifier: Modifier, layout: DashLayout, content: @Composable FitColumnScope.() -> Unit) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
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
 * The link state, in the header row. In demo mode the app-wide DEMO chip says it already. While
 * driving: the chip alone, plus the data's age only once it is stale, because that changes what the
 * figures mean.
 */
@Composable
private fun RowScope.LiveSummary(s: StatusView, layout: DashLayout) {
  if (!s.demo) {
    Surface(
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
  }
  if (layout.restricted) {
    if (s.dataStale) s.dataAge?.let { FitText(it, Modifier.weight(1f, fill = false), sp = layout.type.body, color = StatusWarn) }
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
  val tiles = listOf(dash.soc, dash.capacity, dash.capacityAh, dash.capacityDeviation)
  val rows = tiles.chunked(layout.liveTileCols)
  val size = if (layout.liveCards) Modifier.height(layout.liveTilesDp.dp) else Modifier.weight(1f)
  Column(Modifier.fillMaxWidth().then(size), verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    rows.forEach { row ->
      Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
        row.forEach { KpiTile(it, layout, Modifier.weight(1f).fillMaxHeight()) }
      }
    }
  }
}

@Composable
private fun KpiTile(k: Kpi, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  val short = layout.liveShort
  DashCard(modifier, layout) {
    // Who gives way first: an age that is not a warning, then the label.
    PriorityRow(Modifier.fit(1).fillMaxWidth()) {
      FitText(if (short) k.shortTitle else k.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.title).dp), sp = type.title, color = TextDim)
      k.marker?.let { Marker(it, type.label, Modifier.slot(0)) }
      k.age?.takeIf { layout.ages || k.stale }?.let {
        FitText(it, Modifier.slot(if (k.stale) 2 else 5, end = true), sp = type.label, color = if (k.stale) StatusWarn else TextDim)
      }
    }
    Row(Modifier.fit(0).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FitText(
        k.value,
        Modifier.alignByBaseline(),
        sp = layout.liveValueSp,
        lineFactor = DashLayout.VALUE_LINE_FACTOR,
        weight = FontWeight.Bold,
        color = if (k.hasValue) MaterialTheme.colorScheme.onSurface else TextDim,
      )
      FitText(k.unit, Modifier.alignByBaseline().weight(1f, fill = false), sp = type.unit, color = TextDim)
    }
    if (layout.restricted) {
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
        l.marker?.let { Marker(it, type.label, Modifier.slot(1, end = true)) }
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
        "Pair and choose your OBDLink MX+ under Diagnostics › OBDLink MX+ link (or turn on demo data there)."
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
      ) { FitText("Open Diagnostics", sp = type.tab - 2) }
    }
    hint?.let { FitText(it, Modifier.fit(4), sp = type.label, color = TextDim, maxLines = 4) }
    if (temps != null) {
      ModuleTempLabel(temps, unit, layout, Modifier.fit(3))
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
    val info = if (layout.liveShort) {
      "${heat.read} groups · average ${Units.num(heat.avgV, 4)} V"
    } else {
      "${heat.read} groups · pack average ${Units.num(heat.avgV, 4)} V" + (heat.age?.let { a -> " · $a old" } ?: "")
    } +
      // Shading mixes reads ~2 s apart while current flows: the spread tile uses the BMS halves.
      (if (heat.underLoad) " · read ${DashboardModel.UNDER_LOAD}" else "")
    val infoColor = if (heat.stale || heat.underLoad) StatusWarn else TextDim
    val sel = selectedGroup?.let { g -> heat.cells.getOrNull(g - 1) }
    // The tapped cell's details replace the summary line (no extra row).
    Row(Modifier.fit(2).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
      FitText(if (layout.liveShort) "Cell groups" else "Cell groups vs pack average", sp = type.cardTitle, weight = FontWeight.SemiBold)
      FitText(
        sel?.let { DashboardModel.cellDetail(it) } ?: if (layout.liveShort) info else "$info · tap a cell for details",
        Modifier.weight(1f),
        sp = if (sel != null) type.title else type.body,
        color = if (sel != null) MaterialTheme.colorScheme.onSurface else infoColor,
      )
    }
    Heatmap(heat, selectedGroup, onSelect, layout, Modifier.fill(0, (layout.heatHeadDp + DashLayout.HEAT_ROWS * DashLayout.HEAT_ROW_MIN_DP).dp).fillMaxWidth())
    if (temps != null) {
      ModuleTempStrip(temps, heat.modules, heat.halfAfter, unit, layout, Modifier.fit(1).fillMaxWidth().height(layout.heatStripDp.dp))
    }
    Row(Modifier.fit(3).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Legend(heat, layout, Modifier.weight(1f))
      temps?.let { ModuleTempLabel(it, unit, layout) }
    }
  }
}

@Composable
private fun ModuleTempLabel(temps: ModuleTemps, unit: TempUnit, layout: DashLayout, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!layout.liveShort) FitText("Bottom row: module ${unit.symbol}", sp = layout.type.label, color = TextDim)
    temps.marker?.let { Marker(it, layout.type.label) }
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
            color = if (bg.luminance() > 0.4f) Color(0xFF14110A) else Color(0xFFE8EDF2),
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
    FitText("gray = average", Modifier.weight(1f, fill = false), sp = sp, color = TextDim)
  }
}
