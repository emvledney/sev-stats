package dev.silveradostats.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Always dark: a car screen at night must not glare. Carbon, the owner's choice (2026-11-11, from
// docs/mockups/style-2-carbon.html; Cell Pack's navy before it): near-black panels with hairline edges,
// and a green accent.
private val Background = Color(0xFF060708)
private val Surface = Color(0xFF0C0E11)
private val SurfaceHigh = Color(0xFF16191E)
private val Accent = Color(0xFF00E5A0)
private val AccentDim = Color(0xFF0B3A2C)

/** Text: what the theme calls onSurface, for what is drawn outside a theme's reach. */
val Ink = Color(0xFFF5F7FA)

val StatusOk = Color(0xFF00E5A0)
val StatusWarn = Color(0xFFFFB020)
val StatusError = Color(0xFFFF5D5D)
val TextDim = Color(0xFF8B94A3)

// Data colours. The cell heatmap is diverging blue <-> red with a gray midpoint (= pack average);
// module temperatures use one amber hue, dark -> bright.
val DivergingLow = Color(0xFF00B8FF)
val DivergingMid = Color(0xFF22262D)
val DivergingHigh = Color(0xFFFF5D5D)
val TempRampLow = Color(0xFF2B2A26)
val TempRampHigh = Color(0xFFFFB020)
/** Carbon's cards: square-ish corners and a hairline edge. */
val CardShape = RoundedCornerShape(8.dp)
val CardEdge = BorderStroke(1.dp, Color(0xFF1D2127))
val GridLine = Color(0xFF232830)

private val Colors = darkColorScheme(
  primary = Accent,
  onPrimary = Color(0xFF04140E),
  primaryContainer = AccentDim,
  onPrimaryContainer = Color(0xFFC9FBEA),
  secondary = Color(0xFF00B8FF),
  secondaryContainer = AccentDim,
  onSecondaryContainer = Color(0xFFC9FBEA),
  background = Background,
  onBackground = Ink,
  surface = Surface,
  onSurface = Ink,
  surfaceVariant = SurfaceHigh,
  onSurfaceVariant = TextDim,
  surfaceContainer = Surface,
  surfaceContainerHigh = SurfaceHigh,
  outline = Color(0xFF2A2F38),
  error = StatusError,
)

// About 1.35x the Material defaults: readable on a 17.7" screen at arm's length.
private val Base = Typography()
private val CarTypography = Typography(
  headlineMedium = Base.headlineMedium.copy(fontSize = 38.sp, lineHeight = 46.sp),
  headlineSmall = Base.headlineSmall.copy(fontSize = 32.sp, lineHeight = 40.sp),
  titleLarge = Base.titleLarge.copy(fontSize = 30.sp, lineHeight = 38.sp),
  titleMedium = Base.titleMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
  bodyLarge = Base.bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
  bodyMedium = Base.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
  bodySmall = Base.bodySmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
  labelLarge = Base.labelLarge.copy(fontSize = 21.sp, lineHeight = 26.sp),
  labelMedium = Base.labelMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
  labelSmall = Base.labelSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),
)

@Composable
fun SilveradoStatsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Colors, typography = CarTypography, content = content)
}
