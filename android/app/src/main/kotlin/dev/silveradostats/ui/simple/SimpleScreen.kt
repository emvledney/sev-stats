package dev.silveradostats.ui.simple

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silveradostats.link.UiSettings
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitColumnScope
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.layout.Segmented
import dev.silveradostats.ui.layout.WindowTooSmall
import dev.silveradostats.ui.live.DistUnit
import dev.silveradostats.ui.live.Marker
import dev.silveradostats.ui.live.MxButton
import dev.silveradostats.ui.live.TempUnit
import dev.silveradostats.ui.theme.BatteryFill
import dev.silveradostats.ui.theme.EfficiencyLine
import dev.silveradostats.ui.theme.GridLine
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.SessionState
import kotlin.math.max
import kotlin.math.min

/**
 * The Simple tab: only the truck's built-in data (no OBD adapter), measured since the app was opened.
 * The battery ring, capacity and range, efficiency overall and over the last 15 minutes, and the
 * trip's distance and energy. One page in any landscape window: the ring and the sparkline take the
 * height the figures leave (see [DashLayout]).
 *
 * While driving ([DashLayout.restricted]): larger figures, no explanatory lines, the MX+ button as the
 * only control, and no way into a dialog.
 */
@Composable
fun SimpleScreen(
  session: SessionState,
  settings: UiSettings,
  layout: DashLayout,
  chrome: Chrome,
  onTempUnit: (TempUnit) -> Unit,
  onDistUnit: (DistUnit) -> Unit,
  onReset: () -> Unit,
  onMxOff: (Boolean) -> Unit,
  onRequestPermissions: () -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  val view = remember(session, settings.tempUnit, settings.distUnit) {
    SimpleModel.build(session, settings.tempUnit, settings.distUnit)
  }
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  // Shifting out of Park with the question open closes it: no dialog stays up while driving.
  LaunchedEffect(layout.restricted) { if (layout.restricted) confirmReset = false }
  if (confirmReset && layout.controls) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { FitText("Reset the session?", sp = layout.type.cardTitle, weight = FontWeight.SemiBold) },
      text = { FitText("Distance, energy used and efficiency start again from now.", sp = layout.type.body, maxLines = 3) },
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
        MxButton(settings, layout, onMxOff)
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
        Tiles(view, layout)
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

/** A tile's title and badge. The badge changes what the figure means: it has first claim. Then the label. */
@Composable
private fun StatHead(s: Stat, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  PriorityRow(modifier) {
    FitText(if (layout.shortTitles) s.shortTitle else s.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.title).dp), sp = type.title, color = TextDim)
    s.marker?.let { Marker(it, type.label, Modifier.slot(0)) }
  }
}

/** A big value with its unit. */
@Composable
private fun StatFigure(s: Stat, layout: DashLayout, sp: Int, modifier: Modifier) {
  Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FitText(
      s.value,
      Modifier.alignByBaseline(),
      sp = sp,
      lineFactor = DashLayout.VALUE_LINE_FACTOR,
      weight = FontWeight.Bold,
      color = if (s.hasValue) MaterialTheme.colorScheme.onSurface else TextDim,
    )
    val unit = if (layout.shortTitles) s.shortUnit else s.unit
    if (unit.isNotEmpty()) FitText(unit, Modifier.alignByBaseline().weight(1f, fill = false), sp = layout.type.unit, color = TextDim)
  }
}

private fun shownLines(s: Stat, layout: DashLayout): List<String> =
  if (s.linesWhileDriving || !layout.restricted) s.lines.take(layout.tileLines) else emptyList()

/** Head (title, marker), then a big value with its unit, then dim lines. */
@Composable
private fun StatTile(
  s: Stat,
  layout: DashLayout,
  modifier: Modifier,
  extra: (@Composable FitColumnScope.() -> Unit)? = null,
) {
  val type = layout.type
  Panel(modifier, layout) {
    StatHead(s, layout, Modifier.fit(1).fillMaxWidth())
    StatFigure(s, layout, type.value, Modifier.fit(0).fillMaxWidth())
    extra?.invoke(this)
    shownLines(s, layout).forEach { FitText(it, Modifier.fit(3), sp = type.body, color = TextDim) }
  }
}

/** A word in place of a figure ("Net regen") is drawn smaller: half a tile has no room for it at full size. */
private fun twinSp(s: Stat, layout: DashLayout): Int =
  if (s.value.any { it.isLetter() }) max(layout.type.title, (layout.twinValueSp * WORD_SCALE).toInt()) else layout.twinValueSp

private const val WORD_SCALE = 0.57f

/** Two figures side by side, as tall as a [StatTile]: each half has its own head and lines. */
@Composable
private fun TwinTile(
  a: Stat,
  b: Stat,
  layout: DashLayout,
  modifier: Modifier,
  extra: (@Composable FitColumnScope.() -> Unit)? = null,
) {
  val gap = Arrangement.spacedBy(layout.gapDp.dp)
  Panel(modifier, layout) {
    Row(Modifier.fit(1).fillMaxWidth(), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
      StatHead(a, layout, Modifier.weight(1f))
      StatHead(b, layout, Modifier.weight(1f))
    }
    Row(Modifier.fit(0).fillMaxWidth(), horizontalArrangement = gap, verticalAlignment = Alignment.Bottom) {
      StatFigure(a, layout, twinSp(a, layout), Modifier.weight(1f))
      StatFigure(b, layout, twinSp(b, layout), Modifier.weight(1f))
    }
    extra?.invoke(this)
    val left = shownLines(a, layout)
    val right = shownLines(b, layout)
    for (i in 0 until max(left.size, right.size)) {
      Row(Modifier.fit(3).fillMaxWidth(), horizontalArrangement = gap) {
        FitText(left.getOrElse(i) { "" }, Modifier.weight(1f), sp = layout.type.body, color = TextDim)
        // A line with nothing beside it has the width of the tile.
        right.getOrNull(i)?.let { FitText(it, Modifier.weight(1f), sp = layout.type.body, color = TextDim) }
      }
    }
  }
}

// --- The page ---------------------------------------------------------------------------------------

@Composable
private fun RowScope.Tiles(view: SimpleView, layout: DashLayout) {
  val gap = Arrangement.spacedBy(layout.gapDp.dp)
  RingCard(view.ring, layout, Modifier.weight(1f).fillMaxHeight())
  Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
    StatTile(view.capacity, layout, Modifier.weight(1f).fillMaxWidth())
    StatTile(view.range, layout, Modifier.weight(1f).fillMaxWidth())
  }
  Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
    val spark: @Composable FitColumnScope.() -> Unit = { Sparkline(view.spark, Modifier.fill(3, DashLayout.SPARK_MIN_DP.dp).fillMaxWidth()) }
    val recent = view.recentEfficiency
    if (recent == null) {
      StatTile(view.efficiency, layout, Modifier.weight(1f).fillMaxWidth(), spark)
    } else {
      TwinTile(view.efficiency, recent, layout, Modifier.weight(1f).fillMaxWidth(), spark)
    }
    TwinTile(view.trip, view.energyUsed, layout, Modifier.weight(1f).fillMaxWidth())
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
    if (layout.ringDetail) FitText(r.max, Modifier.fit(3), sp = type.body, color = TextDim)
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
