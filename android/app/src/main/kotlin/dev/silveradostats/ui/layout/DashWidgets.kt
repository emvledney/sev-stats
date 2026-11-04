package dev.silveradostats.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silveradostats.ui.theme.TextDim

/*
 * The building blocks that keep the dashboards on one page whatever the window: a column that
 * gives up its least important lines instead of cutting them, a row that gives up its least
 * important parts first, text that never wraps out of its box, and the one place a chart may take a
 * tap. Who keeps what is decided in FitRules, which is tested on the JVM.
 */

interface FitColumnScope {
  /** A child kept while it fits. A lower [priority] is kept longer (figure 0, label 1, secondary 3). */
  fun Modifier.fit(priority: Int = 0): Modifier

  /** A child that takes the height left over (a chart), at least [min]; dropped if not even that fits. */
  fun Modifier.fill(priority: Int = 0, min: Dp = 0.dp): Modifier
}

private data class FitSpec(val priority: Int, val fill: Boolean, val min: Dp)

private object FitColumnScopeImpl : FitColumnScope {
  override fun Modifier.fit(priority: Int): Modifier = layoutId(FitSpec(priority, false, 0.dp))

  override fun Modifier.fill(priority: Int, min: Dp): Modifier = layoutId(FitSpec(priority, true, min))
}

private val FIT_DEFAULT = FitSpec(0, false, 0.dp)

/**
 * A column for a box of fixed height. A child that does not fit in what the more important ones
 * left is not placed at all, nor is anything less important than it, so no text is ever cut
 * mid-glyph at the bottom of a card. Shown in declared order.
 */
@Composable
fun FitColumn(modifier: Modifier = Modifier, spacing: Dp = 0.dp, content: @Composable FitColumnScope.() -> Unit) {
  Layout(content = { FitColumnScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
    val gap = spacing.roundToPx()
    val specs = measurables.map { it.layoutId as? FitSpec ?: FIT_DEFAULT }
    val bounded = constraints.hasBoundedHeight
    val free = Constraints(maxWidth = constraints.maxWidth)
    val placeables = arrayOfNulls<Placeable>(measurables.size)
    measurables.forEachIndexed { i, m -> if (!specs[i].fill) placeables[i] = m.measure(free) }
    val heights = measurables.indices.map { placeables[it]?.height ?: specs[it].min.roundToPx() }
    val maxHeight = if (bounded) constraints.maxHeight else Int.MAX_VALUE
    val kept = FitRules.keptInColumn(measurables.indices.map { FitRules.ColumnChild(specs[it].priority, heights[it]) }, gap, maxHeight)
    val fills = measurables.indices.filter { kept[it] && specs[it].fill }
    measurables.indices.forEach { if (!kept[it]) placeables[it] = null }
    var used = measurables.indices.filter { kept[it] }.sumOf { heights[it] } + gap * (kept.count { it } - 1).coerceAtLeast(0)
    if (fills.isNotEmpty()) {
      val spare = if (bounded) constraints.maxHeight - used else 0
      val share = spare / fills.size
      fills.forEachIndexed { n, i ->
        val h = specs[i].min.roundToPx() + share + if (n == 0) spare - share * fills.size else 0
        placeables[i] = measurables[i].measure(free.copy(minHeight = h, maxHeight = h))
      }
      used += spare
    }
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it?.width ?: 0 } ?: 0
    layout(width, used.coerceIn(constraints.minHeight, constraints.maxHeight)) {
      var y = 0
      for (p in placeables) {
        if (p == null) continue
        p.place(0, y)
        y += p.height + gap
      }
    }
  }
}

/**
 * The only text on the dashboards: one line (or [maxLines]) that ellipsizes when there is no
 * width, the last resort after the layout's own choices. Never wraps out of its box.
 */
@Composable
fun FitText(
  text: String,
  modifier: Modifier = Modifier,
  sp: Int,
  color: Color = Color.Unspecified,
  weight: FontWeight? = null,
  maxLines: Int = 1,
  lineFactor: Float = DashLayout.LINE_FACTOR,
  align: TextAlign? = null,
  overflow: TextOverflow = TextOverflow.Ellipsis,
) {
  Text(
    text,
    modifier,
    color = color,
    fontSize = sp.sp,
    lineHeight = (sp * lineFactor).sp,
    fontWeight = weight,
    textAlign = align,
    maxLines = maxLines,
    softWrap = maxLines > 1,
    overflow = overflow,
  )
}

interface PriorityRowScope {
  /**
   * A child of a [PriorityRow]: lower [priority] claims width first. With [minWidth] it may shrink to
   * that and ellipsize; without, it is shown whole or not at all. [group]: see FitRules.RowChild.
   * [end]: laid out from the row's right edge.
   */
  fun Modifier.slot(priority: Int, minWidth: Dp? = null, group: Int? = null, end: Boolean = false): Modifier
}

private data class SlotSpec(val priority: Int, val minWidth: Dp?, val group: Int?, val end: Boolean)

private object PriorityRowScopeImpl : PriorityRowScope {
  override fun Modifier.slot(priority: Int, minWidth: Dp?, group: Int?, end: Boolean): Modifier = layoutId(SlotSpec(priority, minWidth, group, end))
}

private val SLOT_DEFAULT = SlotSpec(Int.MAX_VALUE, null, null, false)

/**
 * A row whose parts claim its width by importance, not by the order Compose measures them in: a
 * badge before its label, a label before a direction pill, a figure before what qualifies it. What
 * has no room is not drawn at all, so a label is never reduced to a bare ellipsis. Centred vertically.
 */
@Composable
fun PriorityRow(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable PriorityRowScope.() -> Unit) {
  Layout(content = { PriorityRowScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
    val gapPx = gap.roundToPx()
    val specs = measurables.map { it.layoutId as? SlotSpec ?: SLOT_DEFAULT }
    val children = measurables.mapIndexed { i, m ->
      val natural = m.maxIntrinsicWidth(Constraints.Infinity)
      FitRules.RowChild(specs[i].priority, natural, specs[i].minWidth?.roundToPx() ?: natural, specs[i].group)
    }
    val total = if (constraints.hasBoundedWidth) constraints.maxWidth else children.sumOf { it.natural } + gapPx * (children.size - 1).coerceAtLeast(0)
    val widths = FitRules.claimWidths(children, total, gapPx)
    val placeables = measurables.mapIndexed { i, m -> if (widths[i] == FitRules.DROPPED) null else m.measure(Constraints(maxWidth = widths[i])) }
    val height = (placeables.maxOfOrNull { it?.height ?: 0 } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(total, height) {
      var x = 0
      placeables.forEachIndexed { i, p ->
        if (p != null && !specs[i].end) {
          p.placeRelative(x, (height - p.height) / 2)
          x += p.width + gapPx
        }
      }
      x = total
      placeables.indices.reversed().forEach { i ->
        val p = placeables[i]
        if (p != null && specs[i].end) {
          x -= p.width
          p.placeRelative(x, (height - p.height) / 2)
          x -= gapPx
        }
      }
    }
  }
}

/** All a dashboard draws in a window too short for even its figures ([DashLayout.tooSmall]). */
@Composable
fun WindowTooSmall(layout: DashLayout) {
  FitColumn(Modifier.fillMaxSize().padding(horizontal = layout.pagePadHDp.dp)) {
    FitText("This window is too small for the dashboard.", Modifier.fit(), sp = layout.type.title, color = TextDim)
  }
}

/** A row of mutually exclusive choices. Taps select directly, with no dialog or second step. */
@Composable
fun Segmented(labels: List<String>, selected: Int, heightDp: Int, sp: Int, padDp: Int, onSelect: (Int) -> Unit) {
  val shape = RoundedCornerShape((heightDp / 2).dp)
  val outline = MaterialTheme.colorScheme.outline
  Row(Modifier.height(heightDp.dp).clip(shape).border(1.dp, outline, shape), verticalAlignment = Alignment.CenterVertically) {
    labels.forEachIndexed { i, label ->
      val on = i == selected
      if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(outline))
      Box(
        Modifier
          .fillMaxHeight()
          .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
          .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
          .widthIn(min = DashLayout.MIN_SEGMENT_DP.dp)
          .padding(horizontal = padDp.dp),
        contentAlignment = Alignment.Center,
      ) {
        FitText(label, sp = sp, weight = if (on) FontWeight.SemiBold else null)
      }
    }
  }
}

/**
 * Tap-to-inspect on a chart. The only tap gesture on the dashboards: with [enabled] false (while
 * driving) the modifier adds nothing at all, so there is no gesture to reach.
 */
fun Modifier.inspectTap(enabled: Boolean, vararg keys: Any?, onTap: PointerInputScope.(Offset) -> Unit): Modifier =
  if (!enabled) {
    this
  } else {
    pointerInput(*keys) {
      val scope = this
      detectTapGestures { o -> scope.onTap(o) }
    }
  }
