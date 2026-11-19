package dev.silveradostats.ui.simple

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitColumn
import dev.silveradostats.ui.layout.FitColumnScope
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.layout.PriorityRow
import dev.silveradostats.ui.live.Marker
import dev.silveradostats.ui.theme.CardEdge
import dev.silveradostats.ui.theme.CardShape
import dev.silveradostats.ui.theme.GridLine
import dev.silveradostats.ui.theme.StatusError
import dev.silveradostats.ui.theme.StatusOk
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TextDim

/*
 * The car API's battery figures, in a card over the map (the owner, 2026-11-18, when the API tab went): the energy
 * left and the capacity large on top, at the battery's two ends, then the battery drawn filled to the charge, then the
 * state of charge, smaller (the owner put the kWh first). One decimal each. No mi/kWh: the API's battery level drifts as the truck wakes, so figures worked out from it read wrong.
 * Also here: the panel, head and figure the OBD page's cards are drawn with.
 */

/** Sizes of the card, for a car's display: large, as the API page's figures were. */
private const val FIGURE_SP = 60
private const val SOC_SP = 38
private val BATTERY_HEIGHT = 64.dp

/** The card. [view]: [SimpleModel.build]. A notice about the feed takes the figures' place, with its button in Park. */
@Composable
fun BatteryCard(view: SimpleView, layout: DashLayout, onRequestPermissions: () -> Unit, modifier: Modifier = Modifier) {
  val labelSp = layout.type.cardTitle
  Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
          FitText(view.remaining.title, sp = labelSp, color = TextDim)
          BigFigure(view.remaining, FIGURE_SP)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
          FitText(view.capacity.title, sp = labelSp, color = TextDim)
          BigFigure(view.capacity, FIGURE_SP)
        }
      }
      Battery(view.socFraction, Modifier.fillMaxWidth().height(BATTERY_HEIGHT))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FitText(view.soc.title, Modifier.weight(1f), sp = labelSp, color = TextDim)
        BigFigure(view.soc, SOC_SP)
      }
      view.notice?.let { n ->
        FitText(n.title, sp = layout.type.title, weight = FontWeight.SemiBold)
        if (!layout.restricted) {
          FitText(n.text, sp = layout.type.body, color = TextDim, maxLines = 3)
          if (n.action == NoticeAction.GRANT) {
            Button(onClick = onRequestPermissions, modifier = Modifier.heightIn(min = layout.controlDp.dp)) { FitText("Allow access", sp = layout.type.tab - 2) }
          }
        }
      }
    }
  }
}

@Composable
internal fun Panel(modifier: Modifier, layout: DashLayout, content: @Composable FitColumnScope.() -> Unit) {
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

/** A tile's title and badge. The badge changes what the figure means: it has first claim. Then the label. */
@Composable
internal fun StatHead(s: Stat, layout: DashLayout, modifier: Modifier, sp: Int = layout.type.title) {
  val type = layout.type
  PriorityRow(modifier) {
    FitText(s.title, Modifier.slot(1, minWidth = layout.labelMinDp(sp).dp), sp = sp, color = TextDim)
    s.marker?.let { Marker(it, type.label, Modifier.slot(0)) }
  }
}

/** A big value with its unit. */
@Composable
internal fun StatFigure(s: Stat, layout: DashLayout, sp: Int, modifier: Modifier = Modifier, unitSp: Int = layout.simpleUnitSp(sp)) {
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

/** The charge's colour: green, amber at 20 % and under, red at 10 % and under. */
private fun chargeColor(f: Float?): Color = when {
  f == null -> GridLine
  f <= 0.10f -> StatusError
  f <= 0.20f -> StatusWarn
  else -> StatusOk
}

/** A big figure and its unit, sized by the caller from the room it has. */
@Composable
private fun BigFigure(s: Stat, sp: Int, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    FitText(s.value, Modifier.alignByBaseline(), sp = sp, lineFactor = DashLayout.VALUE_LINE_FACTOR, weight = FontWeight.Bold, color = if (s.hasValue) MaterialTheme.colorScheme.onSurface else TextDim)
    FitText(s.unit, Modifier.alignByBaseline(), sp = maxOf(16, (sp * 0.32f).toInt()), color = TextDim)
  }
}

/** A battery on its side: an outline with its terminal, filled to [fraction], with a mark at each quarter. */
@Composable
private fun Battery(fraction: Float?, modifier: Modifier) {
  val fill = chargeColor(fraction)
  Canvas(modifier) {
    val stroke = 3.dp.toPx()
    val nubW = 14.dp.toPx()
    val body = Size(size.width - nubW - 4.dp.toPx(), size.height)
    val r = CornerRadius(14.dp.toPx())
    // The outline a little brighter than the grid lines: it sits on a card over the map.
    drawRoundRect(TextDim.copy(alpha = 0.6f), size = body, cornerRadius = r, style = Stroke(stroke))
    drawRoundRect(TextDim.copy(alpha = 0.6f), topLeft = Offset(body.width + 4.dp.toPx(), size.height * 0.3f), size = Size(nubW, size.height * 0.4f), cornerRadius = CornerRadius(4.dp.toPx()))
    val inset = 8.dp.toPx()
    val inner = Size(body.width - 2 * inset, body.height - 2 * inset)
    if (fraction != null && fraction > 0f) {
      drawRoundRect(fill, topLeft = Offset(inset, inset), size = Size(inner.width * fraction, inner.height), cornerRadius = CornerRadius(8.dp.toPx()))
    }
    for (q in 1..3) {
      val x = inset + inner.width * q / 4f
      drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, inset), Offset(x, inset + inner.height), strokeWidth = 2.dp.toPx())
    }
  }
}

