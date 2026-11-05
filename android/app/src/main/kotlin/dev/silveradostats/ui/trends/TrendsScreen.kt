package dev.silveradostats.ui.trends

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silveradostats.link.LinkStatus
import dev.silveradostats.link.UiSettings
import dev.silveradostats.log.ChartPick
import dev.silveradostats.log.LogHub
import dev.silveradostats.log.LogSource
import dev.silveradostats.log.PidRow
import dev.silveradostats.log.TrendAxis
import dev.silveradostats.log.TrendChart
import dev.silveradostats.log.TrendGroup
import dev.silveradostats.log.TrendMeasure
import dev.silveradostats.log.TrendPage
import dev.silveradostats.log.TrendPoint
import dev.silveradostats.log.TrendRange
import dev.silveradostats.log.TrendSeries
import dev.silveradostats.log.TrendTile
import dev.silveradostats.log.TrendsModel
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.layout.Segmented
import dev.silveradostats.ui.layout.inspectTap
import dev.silveradostats.ui.live.Marker
import dev.silveradostats.ui.live.Units
import dev.silveradostats.ui.theme.GridLine
import dev.silveradostats.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Solid with round marks on the left axis, dashed with square marks on the right: never colour alone.
private val LeftColor = Color(0xFF3987E5)
private val RightColor = Color(0xFFD95926)
private val ReadoutColor = Color(0xFFE8EDF2)

/** Above this many points on a chart the marks would merge; the line says it all. */
private const val MAX_MARKED_POINTS = 400

private val SOURCES = listOf(LogSource.CAR, LogSource.MX)

/**
 * Trends: the battery log of one source at a time, Truck API or MX+: its capacity tiles, a chart with a
 * measure and scale on either side, and every PID it logs. Only in Park (AppShell shows the Available in
 * Park panel while driving), one page, no scrolling: the PID list comes in groups instead.
 */
@Composable
fun TrendsScreen(settings: UiSettings, mxStatus: LinkStatus, layout: DashLayout, chrome: Chrome) {
  val context = LocalContext.current
  val hub = remember { LogHub.get(context) }
  val prefs = remember { TrendPrefs(context) }
  val scope = rememberCoroutineScope()
  var source by remember { mutableStateOf(prefs.source()) }
  var range by remember { mutableStateOf(prefs.range()) }
  var picks by remember { mutableStateOf(SOURCES.associateWith(prefs::pick)) }
  var groups by remember { mutableStateOf(SOURCES.associateWith(prefs::group)) }
  var note by remember { mutableStateOf<String?>(null) }
  val revision by hub.revision.collectAsStateWithLifecycle()
  val pick = picks.getValue(source)
  val page by produceState<TrendPage?>(null, range, revision, settings.demo, source, pick, settings.tempUnit, settings.distUnit) {
    value = withContext(Dispatchers.IO) {
      TrendsModel.page(hub.trendData(range, settings.demo, source, pick), range, settings.tempUnit, settings.distUnit)
    }
  }
  val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri != null) scope.launch { note = hub.export(uri) }
  }
  Column(Modifier.fillMaxSize()) {
    chrome(
      {
        val shown = page?.data
        val summary = if (shown == null) "" else TrendsModel.summary(shown.totalRows, shown.lastAtMs, shown.nowMs) + " · " + TrendsModel.sourceText(shown.source, if (shown.demo) LinkStatus.DEMO else mxStatus)
        FitText(note ?: summary, Modifier.weight(1f), sp = layout.type.body, color = TextDim)
      },
      {
        Segmented(SOURCES.map { it.label }, SOURCES.indexOf(source), layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) {
          source = SOURCES[it]
          prefs.setSource(source)
        }
        Segmented(TrendRange.entries.map { it.label }, range.ordinal, layout.controlDp, layout.type.tab - 2, layout.segmentPadDp) {
          range = TrendRange.entries[it]
          prefs.setRange(range)
        }
        OutlinedButton(
          onClick = {
            try {
              saver.launch("silverado-battery-log-" + DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.LocalDate.now()) + ".csv")
            } catch (_: ActivityNotFoundException) {
              note = "No file picker on this head unit"
            }
          },
          Modifier.height(layout.controlDp.dp),
        ) {
          FitText("Export CSV", sp = layout.type.tab - 2)
        }
      },
    )
    // Everything below comes from one load, so it never shows one source's choices on the other's rows.
    page?.let { p ->
      val shownSource = p.data.source
      Row(
        Modifier.weight(1f).fillMaxWidth().padding(start = layout.pagePadHDp.dp, end = layout.pagePadHDp.dp, bottom = layout.pagePadVDp.dp),
        horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
      ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
          Row(Modifier.fillMaxWidth().height(layout.trendTileDp.dp), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
            p.tiles.forEach { TileCard(it, layout, Modifier.weight(1f).fillMaxHeight()) }
          }
          ChartCard(p.chart, layout, Modifier.weight(1f).fillMaxWidth())
        }
        PidCard(
          p,
          groups.getValue(shownSource),
          settings,
          layout,
          Modifier.width(layout.trendPidsDp.dp).fillMaxHeight(),
          onGroup = { g ->
            groups = groups + (shownSource to g)
            prefs.setGroup(shownSource, g)
          },
          onPick = { next ->
            picks = picks + (shownSource to next)
            prefs.setPick(shownSource, next)
          },
        )
      }
    }
  }
}

/** The chosen source, range, and per source its chart and PID group, kept across launches. */
private class TrendPrefs(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("trends", Context.MODE_PRIVATE)

  fun source(): LogSource = LogSource.of(prefs.getString(KEY_SOURCE, LogSource.MX.code))

  fun setSource(s: LogSource) = prefs.edit { putString(KEY_SOURCE, s.code) }

  fun range(): TrendRange = byName<TrendRange>(prefs.getString(KEY_RANGE, null)) ?: TrendRange.MONTH

  fun setRange(r: TrendRange) = prefs.edit { putString(KEY_RANGE, r.name) }

  fun pick(s: LogSource): ChartPick {
    val default = TrendsModel.defaultPick(s)
    fun measure(key: String, fallback: TrendMeasure) = byName<TrendMeasure>(prefs.getString(key + s.code, null))?.takeIf { it.source == s && it.plottable } ?: fallback
    val left = measure(KEY_LEFT, default.left)
    return ChartPick(left, measure(KEY_RIGHT, default.right).takeIf { it != left } ?: default.left.takeIf { it != left } ?: default.right)
  }

  fun setPick(s: LogSource, p: ChartPick) = prefs.edit {
    putString(KEY_LEFT + s.code, p.left.name)
    putString(KEY_RIGHT + s.code, p.right.name)
  }

  fun group(s: LogSource): TrendGroup = byName<TrendGroup>(prefs.getString(KEY_GROUP + s.code, null))?.takeIf { it in TrendMeasure.groups(s) } ?: TrendGroup.CAPACITY

  fun setGroup(s: LogSource, g: TrendGroup) = prefs.edit { putString(KEY_GROUP + s.code, g.name) }

  private inline fun <reified E : Enum<E>> byName(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

  private companion object {
    const val KEY_SOURCE = "source"
    const val KEY_RANGE = "range"
    const val KEY_LEFT = "left_"
    const val KEY_RIGHT = "right_"
    const val KEY_GROUP = "group_"
  }
}

@Composable
private fun TileCard(t: TrendTile, layout: DashLayout, modifier: Modifier) {
  val type = layout.type
  Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    FitColumn(Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp), spacing = layout.cardGapDp.dp) {
      PriorityRow(Modifier.fit(1).fillMaxWidth(), gap = 6.dp) {
        FitText(if (layout.liveShort) t.measure.shortLabel else t.title, Modifier.slot(1, minWidth = layout.labelMinDp(type.body).dp), sp = type.body, color = TextDim)
        t.badge?.let { Marker(it.label, type.axis, Modifier.slot(0, end = true)) }
      }
      Row(Modifier.fit(0), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FitText(t.value ?: Units.DASH, sp = layout.trendValueSp, weight = FontWeight.Bold, lineFactor = DashLayout.VALUE_LINE_FACTOR, color = if (t.value == null) TextDim else Color.Unspecified)
        FitText(t.unit, Modifier.padding(bottom = 4.dp), sp = type.unit, color = TextDim)
      }
      FitText(t.line, Modifier.fit(2), sp = type.label, color = TextDim, maxLines = if (t.change == null) 2 else 1)
      t.change?.let { FitText(it, Modifier.fit(3), sp = type.label, color = TextDim) }
    }
  }
}

@Composable
private fun ChartCard(chart: TrendChart, layout: DashLayout, modifier: Modifier) {
  var tappedAt by remember(chart) { mutableStateOf<Long?>(null) }
  val short = layout.liveShort
  Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp)) {
      // The keys say which line is which, so they claim the width first.
      PriorityRow(Modifier.fillMaxWidth(), gap = layout.gapDp.dp) {
        LegendKey(chart, chart.left, LeftColor, dashed = false, tappedAt, layout, Modifier.slot(0, minWidth = layout.labelMinDp(layout.type.body).dp))
        LegendKey(chart, chart.right, RightColor, dashed = true, tappedAt, layout, Modifier.slot(1, minWidth = layout.labelMinDp(layout.type.body).dp))
        val hint = tappedAt?.let { timeText(it) } ?: "Tap the chart to read a point"
        FitText(hint, Modifier.slot(2, end = true), sp = layout.type.label, color = TextDim)
      }
      TrendCanvas(chart, tappedAt, layout, Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) { tappedAt = it }
      // Side by side only where both fit whole: the change is the part that must not be cut.
      if (short) {
        StatsLine(chart.left, layout, Modifier.fillMaxWidth())
        StatsLine(chart.right, layout, Modifier.fillMaxWidth())
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp)) {
          StatsLine(chart.left, layout, Modifier.weight(1f))
          StatsLine(chart.right, layout, Modifier.weight(1f))
        }
      }
    }
  }
}

private fun timeText(atMs: Long): String =
  DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(atMs))

/** Which line is which measure; after a tap, its value there. */
@Composable
private fun LegendKey(chart: TrendChart, s: TrendSeries, color: Color, dashed: Boolean, tappedAt: Long?, layout: DashLayout, modifier: Modifier) {
  val point = tappedAt?.let { TrendsModel.nearest(s, it, TrendsModel.tapWindowMs(chart)) }
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Canvas(Modifier.width(22.dp).height(10.dp)) {
      val y = size.height / 2f
      drawLine(color, Offset(0f, y), Offset(size.width, y), 2.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
    }
    FitText(TrendsModel.legendText(s, point, tappedAt != null, layout.liveShort), sp = layout.type.body, weight = FontWeight.Medium)
  }
}

@Composable
private fun StatsLine(s: TrendSeries, layout: DashLayout, modifier: Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FitText(s.measure.shortLabel, sp = layout.type.label, weight = FontWeight.Medium)
    FitText(TrendsModel.statsText(s), Modifier.weight(1f), sp = layout.type.label, color = TextDim)
  }
}

@Composable
private fun PidCard(p: TrendPage, group: TrendGroup, settings: UiSettings, layout: DashLayout, modifier: Modifier, onGroup: (TrendGroup) -> Unit, onPick: (ChartPick) -> Unit) {
  val source = p.data.source
  val rows = remember(p, group, settings.tempUnit, settings.distUnit) { TrendsModel.pidRows(p.data, group, settings.tempUnit, settings.distUnit) }
  val all = TrendMeasure.of(source)
  Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxSize().padding(horizontal = layout.cardPadHDp.dp, vertical = layout.cardPadVDp.dp), verticalArrangement = Arrangement.spacedBy(layout.cardGapDp.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitText("${source.label} PIDs", sp = layout.type.title, weight = FontWeight.SemiBold)
        FitText("${all.count { it.col in p.data.latest }} of ${all.size} have a reading · L and R chart one", Modifier.weight(1f), sp = layout.type.label, color = TextDim)
      }
      GroupChips(TrendMeasure.groups(source), group, layout, onGroup)
      Column {
        rows.forEach { row -> PidLine(row, p.data.pick, layout, onPick) }
      }
    }
  }
}

/** Each chip takes room by its text, so that seven of them fit a narrow list without an ellipsis. */
@Composable
private fun GroupChips(groups: List<TrendGroup>, selected: TrendGroup, layout: DashLayout, onSelect: (TrendGroup) -> Unit) {
  Row(Modifier.fillMaxWidth().height(layout.trendChipDp.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    groups.forEach { g ->
      val on = g == selected
      Box(
        Modifier
          .weight(g.label.length + 2f)
          .fillMaxHeight()
          .clip(RoundedCornerShape(50))
          .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
          .selectable(selected = on, role = Role.Tab, onClick = { onSelect(g) }),
        contentAlignment = Alignment.Center,
      ) {
        FitText(g.label, sp = layout.type.label, weight = if (on) FontWeight.SemiBold else null, color = if (on) Color.Unspecified else TextDim)
      }
    }
  }
}

@Composable
private fun PidLine(row: PidRow, pick: ChartPick, layout: DashLayout, onPick: (ChartPick) -> Unit) {
  val type = layout.type
  val button = layout.trendAxisButtonDp
  Box(Modifier.fillMaxWidth().height(1.dp).background(GridLine))
  Row(Modifier.fillMaxWidth().height(layout.trendRowDp.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // The name stays; the line under it goes first when the row is short.
    FitColumn(Modifier.weight(1f)) {
      FitText(row.measure.label, Modifier.fit(0), sp = type.body)
      Row(Modifier.fit(1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        row.badge?.let { Marker(it.label, type.axis) }
        FitText(row.detail, sp = type.axis, color = TextDim)
      }
    }
    if (row.value == null) {
      FitText(row.missing.orEmpty(), sp = type.label, color = TextDim)
    } else {
      Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FitText(row.value.orEmpty(), sp = type.title, weight = FontWeight.SemiBold)
        if (row.unit.isNotEmpty()) FitText(row.unit, Modifier.padding(bottom = 2.dp), sp = type.label, color = TextDim)
      }
      if (row.chartable) {
        AxisButton("L", pick.left == row.measure, LeftColor, button) { onPick(pick.withLeft(row.measure)) }
        AxisButton("R", pick.right == row.measure, RightColor, button) { onPick(pick.withRight(row.measure)) }
      } else {
        // A state has no line to draw: its value still lines up with the figures above it.
        Spacer(Modifier.width((2 * button + 8).dp))
      }
    }
  }
}

@Composable
private fun AxisButton(text: String, on: Boolean, color: Color, sizeDp: Int, onClick: () -> Unit) {
  Box(
    Modifier
      .size(sizeDp.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(if (on) color else MaterialTheme.colorScheme.surfaceVariant)
      .selectable(selected = on, role = Role.RadioButton, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    FitText(text, sp = (sizeDp / 3).coerceAtLeast(14), weight = FontWeight.Bold, color = if (on) Color.White else TextDim)
  }
}

@Composable
private fun TrendCanvas(chart: TrendChart, tappedAt: Long?, layout: DashLayout, modifier: Modifier, onTap: (Long) -> Unit) {
  val measurer = rememberTextMeasurer()
  val axisStyle = TextStyle(color = TextDim, fontSize = layout.type.axis.sp)
  val emptyStyle = TextStyle(color = TextDim, fontSize = layout.type.body.sp)
  val tick = { x: Double, axis: TrendAxis -> Units.num(x, axis.decimals) }
  val inspect = Modifier.inspectTap(true, chart) { o ->
    val left = layout.axisLeftDp.dp.toPx()
    val w = size.width - 2 * left
    if (w > 0) onTap(chart.startMs + (((o.x - left) / w).coerceIn(0f, 1f) * (chart.endMs - chart.startMs)).toLong())
  }
  Canvas(modifier.then(inspect)) {
    val axisW = layout.axisLeftDp.dp.toPx()
    val bottom = layout.axisBottomDp.dp.toPx()
    val plotW = size.width - 2 * axisW
    val plotH = size.height - bottom
    if (plotW <= 0 || plotH <= 0) return@Canvas
    val xOf = { at: Long -> axisW + (at - chart.startMs).toFloat() / (chart.endMs - chart.startMs).coerceAtLeast(1) * plotW }
    fun yOf(v: Double, a: TrendAxis): Float = ((a.max - v) / (a.max - a.min)).toFloat() * plotH

    for (v in chart.left.axis.ticks) {
      val y = yOf(v, chart.left.axis)
      drawLine(GridLine, Offset(axisW, y), Offset(axisW + plotW, y), 1f)
      val r = measurer.measure(tick(v, chart.left.axis), axisStyle)
      drawText(r, topLeft = Offset(axisW - r.size.width - 8.dp.toPx(), (y - r.size.height / 2f).coerceIn(0f, plotH)))
    }
    for (v in chart.right.axis.ticks) {
      val r = measurer.measure(tick(v, chart.right.axis), axisStyle)
      drawText(r, topLeft = Offset(axisW + plotW + 8.dp.toPx(), (yOf(v, chart.right.axis) - r.size.height / 2f).coerceIn(0f, plotH)))
    }
    chart.timeTicks.forEachIndexed { i, t ->
      val r = measurer.measure(t.label, axisStyle)
      val x = when (i) {
        0 -> xOf(t.atMs)
        chart.timeTicks.lastIndex -> xOf(t.atMs) - r.size.width
        else -> xOf(t.atMs) - r.size.width / 2f
      }
      drawText(r, topLeft = Offset(x, plotH + 4.dp.toPx()))
    }

    drawSeries(chart.right, RightColor, dashed = true, square = true, ::xOfPoint.bind(xOf)) { yOf(it, chart.right.axis) }
    drawSeries(chart.left, LeftColor, dashed = false, square = false, ::xOfPoint.bind(xOf)) { yOf(it, chart.left.axis) }

    tappedAt?.let { at ->
      val x = xOf(at)
      drawLine(ReadoutColor, Offset(x, 0f), Offset(x, plotH), 1.5.dp.toPx())
    }

    val missing = listOf(chart.left, chart.right).filterNot { it.hasData }
    if (missing.isNotEmpty()) {
      val text = missing.joinToString(" · ") { emptyText(it.measure) }
      val r = measurer.measure(text, emptyStyle, maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = plotW.toInt()))
      drawText(r, topLeft = Offset(axisW + (plotW - r.size.width) / 2f, (plotH - r.size.height) / 2f))
    }
  }
}

private fun xOfPoint(xOf: (Long) -> Float, p: TrendPoint): Float = xOf(p.atMs)

private fun ((xOf: (Long) -> Float, p: TrendPoint) -> Float).bind(xOf: (Long) -> Float): (TrendPoint) -> Float = { p -> this(xOf, p) }

/** What a chart says instead of a line. */
private fun emptyText(m: TrendMeasure): String =
  if (m.unidentified) "${m.label}: its PID isn't identified yet" else "${m.label}: no rows in this range"

private fun DrawScope.drawSeries(s: TrendSeries, color: Color, dashed: Boolean, square: Boolean, xOf: (TrendPoint) -> Float, yOf: (Double) -> Float) {
  val total = s.segments.sumOf { it.size }
  val line = Stroke(
    width = 2.dp.toPx(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())) else null,
  )
  val mark = if (square) 3.dp.toPx() else 2.5.dp.toPx()
  for (seg in s.segments) {
    if (s.band) {
      if (seg.size > 1) {
        val path = Path()
        seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(p), yOf(p.high)) else path.lineTo(xOf(p), yOf(p.high)) }
        seg.asReversed().forEach { p -> path.lineTo(xOf(p), yOf(p.low)) }
        path.close()
        drawPath(path, color.copy(alpha = 0.3f))
      }
      // A lone row still shows: a band has no line to carry it.
      for (p in seg) {
        if (abs(yOf(p.high) - yOf(p.low)) >= 1f) drawLine(color, Offset(xOf(p), yOf(p.high)), Offset(xOf(p), yOf(p.low)), 2.dp.toPx())
        else drawCircle(color, mark, Offset(xOf(p), yOf(p.mean)))
      }
    } else {
      if (seg.size > 1) {
        val path = Path()
        seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(p), yOf(p.mean)) else path.lineTo(xOf(p), yOf(p.mean)) }
        drawPath(path, color, style = line)
      }
      if (total <= MAX_MARKED_POINTS || seg.size == 1) {
        for (p in seg) {
          val c = Offset(xOf(p), yOf(p.mean))
          if (square) drawRect(color, Offset(c.x - mark, c.y - mark), Size(2 * mark, 2 * mark)) else drawCircle(color, mark, c)
        }
      }
    }
  }
}
