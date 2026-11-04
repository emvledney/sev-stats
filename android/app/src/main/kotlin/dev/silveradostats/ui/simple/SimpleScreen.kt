package dev.silveradostats.ui.simple

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silveradostats.link.SimpleLayout
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
import dev.silveradostats.ui.layout.inspectTap
import dev.silveradostats.ui.live.Direction
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.Dot
import dev.silveradostats.ui.live.Marker
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.live.Tone
import dev.silveradostats.ui.live.Units
import dev.silveradostats.ui.live.toneColor
import dev.silveradostats.ui.theme.BatteryFill
import dev.silveradostats.ui.theme.CardSurface
import dev.silveradostats.ui.theme.ChargeRegen
import dev.silveradostats.ui.theme.Discharge
import dev.silveradostats.ui.theme.EfficiencyBars
import dev.silveradostats.ui.theme.EfficiencyLine
import dev.silveradostats.ui.theme.GridLine
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.SessionState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The Simple tab: only the truck's built-in data (no OBD adapter), measured since the app was
 * opened. Two layouts, chosen with the segmented control and remembered: A "Efficiency" (trip
 * efficiency first) and B "Battery" (battery first). One page in any landscape window: charts take
 * the height the figures leave (see [DashLayout]).
 *
 * While driving ([DashLayout.restricted]): larger figures, no explanatory lines, the layout selector
 * as the only control, and no way into a dialog or a chart's details.
 */
@Composable
fun SimpleScreen(
  session: SessionState,
  settings: UiSettings,
  layout: DashLayout,
  chrome: Chrome,
  onLayout: (SimpleLayout) -> Unit,
  onTempUnit: (TempUnit) -> Unit,
  onDistUnit: (DistUnit) -> Unit,
  onReset: () -> Unit,
  onRequestPermissions: () -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  val view = remember(session, settings.tempUnit, settings.distUnit) {
    SimpleModel.build(session, settings.tempUnit, settings.distUnit, session.lastSampleAtMs ?: 0L)
  }
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  // Shifting out of Park with the question open closes it: no dialog stays up while driving.
  LaunchedEffect(layout.restricted) { if (layout.restricted) confirmReset = false }
  if (confirmReset && layout.controls) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { FitText("Reset the session?", sp = layout.type.cardTitle, weight = FontWeight.SemiBold) },
      text = { FitText("Distance, energy used, efficiency and the charts start again from now.", sp = layout.type.body, maxLines = 3) },
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
    chrome(
      { SessionSummary(view, layout) },
      {
        Segmented(SimpleLayout.entries.map { it.label }, settings.simpleLayout.ordinal, layout.selectorDp, layout.type.tab - 2, layout.segmentPadDp) { onLayout(SimpleLayout.entries[it]) }
        if (layout.controls) {
          Segmented(TempUnit.entries.map { it.symbol }, settings.tempUnit.ordinal, layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) { onTempUnit(TempUnit.entries[it]) }
          Segmented(DistUnit.entries.map { it.symbol }, settings.distUnit.ordinal, layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) { onDistUnit(DistUnit.entries[it]) }
          OutlinedButton(
            onClick = { confirmReset = true },
            modifier = Modifier.heightIn(min = layout.controlDp.dp),
            contentPadding = PaddingValues(horizontal = layout.segmentPadDp.dp),
          ) { FitText(if (layout.shortChrome) "Reset" else "Reset session", sp = layout.type.tab - 2) }
        }
      },
    )
    if (layout.strip) {
      listOfNotNull(view.note, view.hint, DEMO_OFF.takeIf { settings.demo }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
        FitText(it, Modifier.fillMaxWidth().padding(horizontal = layout.pagePadHDp.dp), sp = layout.type.body, color = if (view.hintWarn) StatusWarn else TextDim)
      }
    }
    if (layout.tooSmall(view.notice != null)) {
      WindowTooSmall(layout)
      return@Column
    }
    Column(
      Modifier.fillMaxWidth().weight(1f).padding(horizontal = layout.pagePadHDp.dp, vertical = layout.pagePadVDp.dp),
      verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
    ) {
      view.notice?.let { NoticeCard(it, layout, onRequestPermissions, onOpenDiagnostics) }
      Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
        if (settings.simpleLayout == SimpleLayout.EFFICIENCY) LayoutA(view, layout) else LayoutB(view, layout)
      }
    }
  }
}

private const val DEMO_OFF = "turn it off under Diagnostics › OBDLink MX+ link"

// --- Building blocks --------------------------------------------------------------------------------

@Composable
private fun Panel(modifier: Modifier, layout: DashLayout, content: @Composable FitColumnScope.() -> Unit) {
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

/**
 * The header's session figures, whole before anything else. "2 pauses" stays while driving, since it
 * qualifies every figure, as long as the figures leave it the room.
 */
@Composable
private fun RowScope.SessionSummary(view: SimpleView, layout: DashLayout) {
  PriorityRow(Modifier.weight(1f), gap = layout.headerGapDp.dp) {
    FitText(
      if (layout.longSummary) view.header else view.summary,
      Modifier.slot(0, minWidth = 1.dp),
      sp = layout.type.title,
      weight = FontWeight.Bold,
    )
    view.pauses?.let { FitText(it, Modifier.slot(1), sp = layout.type.body, color = StatusWarn) }
  }
}

@Composable
private fun NoticeCard(n: Notice, layout: DashLayout, onGrant: () -> Unit, onOpenDiagnostics: () -> Unit) {
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
      if (layout.restricted) {
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

/** Head (title, marker, direction), then a big value with its unit, then dim lines. */
@Composable
private fun StatTile(
  s: Stat,
  layout: DashLayout,
  modifier: Modifier,
  extra: (@Composable FitColumnScope.() -> Unit)? = null,
) {
  val type = layout.type
  Panel(modifier, layout) {
    // The badge changes what the figure means: it has first claim. Then the label; the pill's words last.
    PriorityRow(Modifier.fit(1).fillMaxWidth()) {
      FitText(if (layout.shortTitles) s.shortTitle else s.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.title).dp), sp = type.title, color = TextDim)
      s.marker?.let { Marker(it, type.label, Modifier.slot(0)) }
      s.pill?.let { Direction(it, s.pillTone, type.label, priority = 2) }
    }
    Row(Modifier.fit(0).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FitText(
        s.value,
        Modifier.alignByBaseline(),
        sp = type.value,
        lineFactor = DashLayout.VALUE_LINE_FACTOR,
        weight = FontWeight.Bold,
        color = if (s.hasValue) MaterialTheme.colorScheme.onSurface else TextDim,
      )
      val unit = if (layout.shortTitles) s.shortUnit else s.unit
      if (unit.isNotEmpty()) FitText(unit, Modifier.alignByBaseline().weight(1f, fill = false), sp = type.unit, color = TextDim)
    }
    extra?.invoke(this)
    if (s.linesWhileDriving || !layout.restricted) {
      s.lines.take(layout.tileLines).forEach { FitText(it, Modifier.fit(3), sp = type.body, color = TextDim) }
    }
  }
}

/** A horizontal fill bar (battery remaining). */
@Composable
private fun FillBar(fraction: Double?, heightDp: Int, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape((heightDp / 2).dp)
  Box(modifier.fillMaxWidth().height(heightDp.dp).clip(shape).background(GridLine)) {
    if (fraction != null && fraction > 0) {
      Box(Modifier.fillMaxWidth(fraction.toFloat()).fillMaxHeight().clip(shape).background(BatteryFill))
    }
  }
}

// --- Layout A: trip efficiency first --------------------------------------------------------------

@Composable
private fun RowScope.LayoutA(view: SimpleView, layout: DashLayout) {
  val gap = Arrangement.spacedBy(layout.gapDp.dp)
  EfficiencyCard(view.hero, view.chart, layout, Modifier.weight(1.12f).fillMaxHeight())
  Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = gap) {
      StatTile(view.battery, layout, Modifier.weight(1f).fillMaxHeight()) { FillBar(view.batteryFraction, layout.barDp, Modifier.fit(2)) }
      StatTile(view.power, layout, Modifier.weight(1f).fillMaxHeight())
    }
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = gap) {
      StatTile(view.range, layout, Modifier.weight(1f).fillMaxHeight())
      StatTile(view.session, layout, Modifier.weight(1f).fillMaxHeight())
    }
  }
}

@Composable
private fun EfficiencyCard(hero: HeroView, chart: EffChart, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  Panel(modifier, layout) {
    PriorityRow(Modifier.fit(2).fillMaxWidth()) {
      FitText(
        if (layout.shortTitles) "Efficiency" else "Measured efficiency since app opened",
        Modifier.slot(1, minWidth = layout.labelMinDp(type.cardTitle).dp),
        sp = type.cardTitle,
        weight = FontWeight.SemiBold,
      )
      if (layout.legends) {
        Row(Modifier.slot(0, end = true), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Dot(EfficiencyLine)
          FitText("Cumulative", sp = type.body)
          Dot(EfficiencyBars)
          FitText(if (chart.distUnit == "mi") "Each mile" else "Each km", sp = type.body)
        }
      }
    }
    Row(
      Modifier.fit(0).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy((layout.gapDp * 2).dp),
    ) {
      if (hero.kind != HeroKind.VALUE) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          val color = when (hero.kind) {
            HeroKind.NET_REGEN -> ChargeRegen
            HeroKind.BLOCKED -> TextDim
            else -> StatusWarn
          }
          FitText(hero.value, sp = (type.hero * 0.57f).toInt(), lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold, color = color)
          if (hero.warming) {
            LinearProgressIndicator(
              progress = { hero.progress.toFloat() },
              modifier = Modifier.fillMaxWidth().height(layout.barDp.dp).clip(RoundedCornerShape(5.dp)),
              color = StatusWarn,
              trackColor = GridLine,
              drawStopIndicator = {},
            )
          }
        }
      } else {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FitText(hero.value, Modifier.alignByBaseline(), sp = type.hero, lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold)
          FitText(hero.unit, Modifier.alignByBaseline(), sp = type.unit + 4, color = TextDim)
          if (hero.early) Box(Modifier.alignByBaseline()) { Marker(SimpleModel.EARLY, type.label) }
        }
      }
      if (layout.heroDetail) {
        // Two short lines beside the figure: the row keeps the height of the figure alone.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          FitText(hero.secondary, sp = type.body, maxLines = if (hero.truck != null && layout.tier == SizeTier.COMPACT) 1 else 2, weight = FontWeight.Medium)
          hero.truck?.let { FitText(it, sp = type.body, color = TextDim) }
        }
      } else {
        hero.consumption?.let { FitText(it, Modifier.weight(1f), sp = type.title, color = TextDim) }
      }
    }
    EfficiencyChart(chart, layout, Modifier.fill(1, layout.chartMinDp.dp).fillMaxWidth())
  }
}

/** The smallest "draw every n-th label" that keeps labels [needPx] apart when ticks are [spacingPx] apart. */
private fun everyNth(spacingPx: Float, needPx: Float): Int = if (spacingPx <= 0f) 1 else max(1, ceil(needPx / spacingPx).toInt())

@Composable
private fun EfficiencyChart(c: EffChart, layout: DashLayout, modifier: Modifier) {
  val measurer = rememberTextMeasurer()
  val axisLeft = layout.axisLeftDp.dp
  val axisBottom = layout.axisBottomDp.dp
  val labelStyle = TextStyle(color = TextDim, fontSize = layout.type.axis.sp)
  val valueStyle = TextStyle(color = Color(0xFFE8EDF2), fontSize = (layout.type.axis + 4).sp, fontWeight = FontWeight.SemiBold)
  val hintStyle = TextStyle(color = TextDim, fontSize = layout.type.body.sp)
  val interactive = layout.tapToInspect
  // Tap a bar for its figure (tap again, or outside the bars, to clear). Not while driving.
  var selected by remember { mutableStateOf<Int?>(null) }
  val sel = if (interactive) selected?.let { i -> c.bars.firstOrNull { it.index == i } } else null
  // Data arrives twice a second: the gesture must outlive it, or taps made across a tick are lost.
  val latest by rememberUpdatedState(c)
  Canvas(
    modifier.inspectTap(interactive, Unit) { o ->
      val left = axisLeft.toPx()
      val w = size.width - left - 16.dp.toPx()
      val bar = if (w > 0) SimpleModel.barAt(latest, (o.x - left) / w * latest.xMax) else null
      selected = if (bar == null || bar.index == selected) null else bar.index
    },
  ) {
    val left = axisLeft.toPx()
    val bottom = axisBottom.toPx()
    val top = 12.dp.toPx()
    val right = 16.dp.toPx()
    val w = size.width - left - right
    val h = size.height - top - bottom
    if (w <= 0 || h <= 0) return@Canvas
    fun px(x: Double) = left + (x / c.xMax * w).toFloat()
    fun py(y: Double) = top + h - (min(y, c.yMax) / c.yMax * h).toFloat()

    // Recessive grid and y labels (fewer labels on a short chart, so they never touch).
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val yDecimals = if (c.yStep < 0.5) 2 else if (c.yStep < 1) 1 else 0
    val yEvery = everyNth((c.yStep / c.yMax * h).toFloat(), measurer.measure("0", labelStyle).size.height * 1.1f)
    var y = 0.0
    var yIndex = 0
    while (y <= c.yMax + 1e-9) {
      val yy = py(y)
      drawLine(if (y == 0.0) TextDim else GridLine, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1.dp.toPx(), pathEffect = if (y == 0.0) null else dash)
      if (yIndex % yEvery == 0) {
        val r = measurer.measure(Units.num(y, yDecimals), labelStyle)
        drawText(r, topLeft = Offset(left - r.size.width - 10.dp.toPx(), yy - r.size.height / 2f))
      }
      y += c.yStep
      yIndex += 1
    }
    // x labels.
    val xDecimals = if (c.xStep < 1) 1 else 0
    val xWidest = measurer.measure("${Units.num(c.xMax, xDecimals)} ${c.distUnit}", labelStyle).size.width
    val xEvery = everyNth((c.xStep / c.xMax * w).toFloat(), xWidest + 8.dp.toPx())
    var x = 0.0
    var xIndex = 0
    while (x <= c.xMax + 1e-9) {
      if (xIndex % xEvery == 0) {
        val label = if (x == 0.0) "0" else "${Units.num(x, xDecimals)} ${c.distUnit}"
        val r = measurer.measure(label, labelStyle)
        val xx = px(x)
        drawText(r, topLeft = Offset((xx - r.size.width / 2f).coerceIn(left - r.size.width / 2f, size.width - r.size.width), top + h + 4.dp.toPx()))
      }
      x += c.xStep
      xIndex += 1
    }

    // Per-mile bars: 2 dp of surface between neighbours, rounded tops, anchored to the baseline.
    val gap = 2.dp.toPx()
    for (b in c.bars) {
      val x0 = px(b.x0) + gap / 2
      val x1 = px(b.x1) - gap / 2
      if (x1 <= x0) continue
      val v = b.y
      val picked = b.index == sel?.index
      when {
        v != null -> {
          val t = py(v)
          drawBar(EfficiencyBars.copy(alpha = if (picked) 1f else 0.75f), x0, x1, t, top + h)
          if (v > c.yMax) drawCapMark(x0, x1, top)
        }
        b.regen -> {
          // Net regen over this mile: no figure; a full-height green bar says "more than the chart".
          drawBar(ChargeRegen.copy(alpha = if (picked) 0.9f else 0.55f), x0, x1, top, top + h)
        }
      }
      if (picked) drawRect(Color.White, Offset(x0, top), Size(x1 - x0, h), style = Stroke(2.dp.toPx()))
    }
    sel?.let { b ->
      val r = measurer.measure(SimpleModel.barDetail(c, b), valueStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (w - 16.dp.toPx()).toInt().coerceAtLeast(1)))
      val bx = left + 8.dp.toPx()
      val by = top + 4.dp.toPx()
      drawRoundRect(CardSurface.copy(alpha = 0.92f), Offset(bx - 6.dp.toPx(), by - 2.dp.toPx()), Size(r.size.width + 12.dp.toPx(), r.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
      drawText(r, topLeft = Offset(bx, by))
    }

    if (c.warming || c.line.isEmpty()) {
      // While driving the hero above already says "Warming up": no sentence in the chart.
      if (interactive) {
        val r = measurer.measure(c.warmupText, hintStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = w.toInt().coerceAtLeast(1)))
        drawText(r, topLeft = Offset(left + (w - r.size.width) / 2f, top + h / 2f - r.size.height / 2f))
      }
      return@Canvas
    }
    // Cumulative line with a surface ring under it, so it stays readable over the bars.
    val path = Path()
    c.line.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y)) }
    drawPath(path, CardSurface, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, EfficiencyLine, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val last = c.line.last()
    val end = Offset(px(last.x), py(last.y))
    drawCircle(CardSurface, radius = 9.dp.toPx(), center = end)
    drawCircle(EfficiencyLine, radius = 6.dp.toPx(), center = end)
    val r = measurer.measure(Units.num(last.y, 2), valueStyle)
    val lx = (end.x - r.size.width - 12.dp.toPx()).coerceAtLeast(left)
    // Above the end of the line; below it at the top of the chart (a figure drawn capped).
    val above = end.y - r.size.height - 10.dp.toPx()
    val ly = if (above >= top) above else end.y + 10.dp.toPx()
    drawText(r, topLeft = Offset(lx, ly))
  }
}

private fun DrawScope.drawBar(color: Color, x0: Float, x1: Float, top: Float, base: Float) {
  val h = base - top
  if (h <= 0.5f) return
  val radius = min(4.dp.toPx(), (x1 - x0) / 2)
  drawRoundRect(color, Offset(x0, top), Size(x1 - x0, h), CornerRadius(radius, radius))
  // Square the bottom corners: rounded data end only.
  if (h > radius) drawRect(color, Offset(x0, base - radius), Size(x1 - x0, radius))
}

/** A small chevron at the top of a bar taller than the chart. */
private fun DrawScope.drawCapMark(x0: Float, x1: Float, top: Float) {
  val cx = (x0 + x1) / 2
  val s = min(8.dp.toPx(), (x1 - x0) / 2)
  val p = Path().apply {
    moveTo(cx - s, top + s * 1.4f)
    lineTo(cx, top + s * 0.4f)
    lineTo(cx + s, top + s * 1.4f)
  }
  drawPath(p, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
}

// --- Layout B: battery first ----------------------------------------------------------------------

@Composable
private fun RowScope.LayoutB(view: SimpleView, layout: DashLayout) {
  RingCard(view.ring, layout, Modifier.weight(0.82f).fillMaxHeight())
  PowerCard(view.powerChart, layout, Modifier.weight(1.5f).fillMaxHeight())
  Column(Modifier.weight(0.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
    StatTile(view.efficiencyMini, layout, Modifier.weight(1f).fillMaxWidth()) {
      Sparkline(view.spark, Modifier.fill(3, DashLayout.SPARK_MIN_DP.dp).fillMaxWidth())
    }
    StatTile(view.sessionMini, layout, Modifier.weight(1f).fillMaxWidth())
  }
}

@Composable
private fun RingCard(r: RingView, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  val fontScale = LocalDensity.current.fontScale
  Panel(modifier, layout) {
    FitText("Battery", Modifier.fit(2), sp = type.cardTitle, weight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fill(0, layout.chartMinDp.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
      // The figure scales with the ring, which scales with whatever box the card got.
      val d = min(maxWidth.value, maxHeight.value)
      BatteryRing(r.fraction, Modifier.size(d.dp))
      val socSp = (d * 0.24f / fontScale).toInt().coerceIn(MIN_RING_SP, type.hero)
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
          FitText(r.soc, Modifier.alignByBaseline(), sp = socSp, lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold)
          Spacer(Modifier.width(4.dp))
          FitText("%", Modifier.alignByBaseline(), sp = (socSp * 0.4f).toInt(), color = TextDim)
        }
        FitText(r.remaining, sp = (socSp * 0.36f).toInt().coerceAtLeast(type.label), weight = FontWeight.SemiBold)
      }
    }
    if (layout.heroDetail) FitText(r.max, Modifier.fit(3), sp = type.body, color = TextDim)
    FitText(r.truckRange, Modifier.fit(3), sp = type.body, color = TextDim)
  }
}

private const val MIN_RING_SP = 16

@Composable
private fun BatteryRing(fraction: Double?, modifier: Modifier) {
  Canvas(modifier.padding(4.dp)) {
    val stroke = min(size.width, size.height) * 0.08f
    val d = min(size.width, size.height) - stroke
    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
    drawArc(GridLine, 0f, 360f, useCenter = false, topLeft = tl, size = Size(d, d), style = Stroke(stroke))
    if (fraction != null && fraction > 0) {
      drawArc(BatteryFill, -90f, (fraction * 360).toFloat(), useCenter = false, topLeft = tl, size = Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
  }
}

@Composable
private fun PowerCard(v: PowerChartView, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  Panel(modifier, layout) {
    PriorityRow(Modifier.fit(2).fillMaxWidth()) {
      FitText(if (layout.shortTitles) "Power, 10 min" else "Power, last 10 min", Modifier.slot(1, minWidth = layout.labelMinDp(type.cardTitle).dp), sp = type.cardTitle, weight = FontWeight.SemiBold)
      if (v.estimated) Marker("est.", type.label, Modifier.slot(0))
      if (layout.legends) {
        Row(Modifier.slot(2, end = true), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Dot(Discharge)
          FitText("Drive (+)", sp = type.body)
          Dot(ChargeRegen)
          FitText(if (layout.liveShort) "Regen (${Units.MINUS})" else "Regen / charge (${Units.MINUS})", sp = type.body)
        }
      }
    }
    Row(Modifier.fit(0).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp), verticalAlignment = Alignment.Bottom) {
      PowerStat("Now", v.now, v.nowLabel, v.nowTone, layout, Modifier.weight(1.4f))
      if (layout.peakStats) {
        PowerStat(v.peakTitle, v.peak, null, Tone.DISCHARGE, layout, Modifier.weight(1f))
        PowerStat(if (layout.liveShort) v.regenTitle.replace(" / charge", "") else v.regenTitle, v.regen, null, Tone.CHARGE, layout, Modifier.weight(1f))
      }
    }
    PowerBars(v, layout, Modifier.fill(1, layout.chartMinDp.dp).fillMaxWidth())
    if (v.estimated && layout.heroDetail) {
      FitText("est.: from the battery level over 30 s (the truck's charge rate is not offered, stuck, or at odds with the level)", Modifier.fit(4), sp = type.label, color = TextDim)
    }
  }
}

@Composable
private fun PowerStat(title: String, value: String, pill: String?, tone: Tone, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  Column(modifier) {
    if (layout.peakStats) {
      PriorityRow(Modifier.fillMaxWidth(), gap = 6.dp) {
        FitText(title, Modifier.slot(0, minWidth = layout.labelMinDp(type.body).dp), sp = type.body, color = TextDim)
        pill?.let { Direction(it, tone, type.label, priority = 1, end = false) }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // The sign says the direction too: the dot gives way on a narrow card, not the figure.
        if (layout.tier != SizeTier.COMPACT) Dot(toneColor(tone), 12)
        FitText(value, sp = type.stat, lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold)
      }
    } else {
      // Driving: the figure and which way the power flows, on one line.
      PriorityRow(Modifier.fillMaxWidth(), gap = layout.gapDp.dp) {
        FitText(value, Modifier.slot(0, minWidth = 1.dp), sp = type.stat, lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold)
        pill?.let { Direction(it, tone, type.label, priority = 1, end = false) }
      }
    }
  }
}

@Composable
private fun PowerBars(v: PowerChartView, layout: DashLayout, modifier: Modifier) {
  val measurer = rememberTextMeasurer()
  val axisLeft = layout.axisLeftDp.dp
  val axisBottom = layout.axisBottomDp.dp
  val labelStyle = TextStyle(color = TextDim, fontSize = layout.type.axis.sp)
  val valueStyle = TextStyle(color = Color(0xFFE8EDF2), fontSize = (layout.type.axis + 4).sp, fontWeight = FontWeight.SemiBold)
  val interactive = layout.tapToInspect
  // Tap for the value at that moment (tap again to clear). Not while driving.
  var selectedAt by remember { mutableStateOf<Long?>(null) }
  val sel = if (interactive) selectedAt?.let { at -> v.points.firstOrNull { it.atMs == at } } else null
  // Data arrives twice a second: the gesture must outlive it, or taps made across a tick are lost.
  val latest by rememberUpdatedState(v)
  Canvas(
    modifier.inspectTap(interactive, Unit) { o ->
      val left = axisLeft.toPx()
      val w = size.width - left
      val now = latest
      val p = if (w > 0) SimpleModel.powerAt(now, now.nowMs - now.windowMs + ((o.x - left) / w * now.windowMs).toLong()) else null
      selectedAt = if (p == null || p.atMs == selectedAt) null else p.atMs
    },
  ) {
    val left = axisLeft.toPx()
    val top = 12.dp.toPx()
    val w = size.width - left
    // The plot keeps clear of the x labels below it, so the -scale label never meets "-10 min".
    val plotBottom = size.height - axisBottom.toPx() - 6.dp.toPx()
    val half = (plotBottom - top) / 2f
    if (w <= 0 || half <= 0) return@Canvas
    val mid = top + half
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    for (f in listOf(-1f, -0.5f, 0.5f, 1f)) {
      val yy = mid - f * half
      drawLine(GridLine, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx(), pathEffect = dash)
    }
    drawLine(TextDim, Offset(left, mid), Offset(size.width, mid), strokeWidth = 1.5.dp.toPx())
    val s = Units.num(v.scaleKw, 0)
    for ((label, yy) in listOf("+$s kW" to top, "0" to mid, "${Units.MINUS}$s kW" to plotBottom)) {
      val r = measurer.measure(label, labelStyle)
      drawText(r, topLeft = Offset(left - r.size.width - 8.dp.toPx(), (yy - r.size.height / 2f).coerceAtLeast(0f)))
    }
    val minutes = (v.windowMs / 60_000).toInt()
    val widest = measurer.measure("${Units.MINUS}$minutes min", labelStyle).size.width
    val every = everyNth(w * 2 / minutes.toFloat(), widest + 8.dp.toPx())
    for (m in 0..minutes step 2) {
      // "now" always shows: the rest thin out from the old end when the chart is narrow.
      if (m == minutes || (minutes - m) / 2 % every == 0) {
        val label = if (m == minutes) "now" else "${Units.MINUS}${minutes - m} min"
        val r = measurer.measure(label, labelStyle)
        val xx = left + w * m / minutes.toFloat()
        drawText(r, topLeft = Offset((xx - r.size.width / 2f).coerceIn(left - r.size.width / 2f, size.width - r.size.width), size.height - r.size.height))
      }
    }
    if (v.points.isEmpty()) return@Canvas
    val start = v.nowMs - v.windowMs
    val barW = max(1.5.dp.toPx(), w / (v.windowMs / 1000f) * 0.85f)
    for (p in v.points) {
      val xx = left + w * ((p.atMs - start).toFloat() / v.windowMs)
      if (xx < left) continue
      val hh = (p.kw / v.scaleKw).coerceIn(-1.0, 1.0).toFloat() * half
      if (abs(hh) < 0.5f) continue
      val color = if (p.kw >= 0) Discharge else ChargeRegen
      drawRect(color, Offset(xx - barW / 2f, if (hh > 0) mid - hh else mid), Size(barW, abs(hh)))
    }
    sel?.let { p ->
      val xx = left + w * ((p.atMs - start).toFloat() / v.windowMs)
      drawLine(Color.White, Offset(xx, top), Offset(xx, plotBottom), strokeWidth = 2.dp.toPx())
      val r = measurer.measure(SimpleModel.powerDetail(v, p), valueStyle)
      val bx = left + 8.dp.toPx()
      drawRoundRect(CardSurface.copy(alpha = 0.92f), Offset(bx - 6.dp.toPx(), top - 2.dp.toPx()), Size(r.size.width + 12.dp.toPx(), r.size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
      drawText(r, topLeft = Offset(bx, top))
    }
  }
}

@Composable
private fun Sparkline(points: List<ChartPoint>, modifier: Modifier) {
  Canvas(modifier.padding(vertical = 4.dp)) {
    if (points.size < 2) {
      drawLine(GridLine, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
      return@Canvas
    }
    val x0 = points.first().x
    val x1 = points.last().x
    val lo = points.minOf { it.y }
    val hi = points.maxOf { it.y }
    // Keep at least ±5 % of the value in view, so a flat session reads as flat.
    val pad = max((hi - lo) * 0.1, (hi + lo) / 2 * 0.05)
    val yLo = lo - pad
    val yHi = hi + pad
    fun px(x: Double) = if (x1 > x0) ((x - x0) / (x1 - x0) * size.width).toFloat() else 0f
    fun py(y: Double) = (size.height - (y - yLo) / (yHi - yLo) * size.height).toFloat()
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y)) }
    drawPath(path, EfficiencyLine, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val last = points.last()
    drawCircle(EfficiencyLine, radius = 5.dp.toPx(), center = Offset(px(last.x), py(last.y)))
  }
}
