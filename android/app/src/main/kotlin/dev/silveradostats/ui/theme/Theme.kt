package dev.silveradostats.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// Always dark: a car screen at night must not glare. Cell Pack, the owner's choice (2026-11-06): the blue and
// coral of the app icon and the Live heatmap, on navy, with near-white text.
private val Background = Color(0xFF0B1020)
private val Surface = Color(0xFF141B30)
private val SurfaceHigh = Color(0xFF1E2742)
private val Accent = Color(0xFF6CB4FF)
private val AccentDim = Color(0xFF1E3A66)

/** Text: what the theme calls onSurface, for what is drawn outside a theme's reach. */
val Ink = Color(0xFFEEF3FF)

val StatusOk = Color(0xFF6FD08C)
val StatusWarn = Color(0xFFF2C14E)
val StatusError = Color(0xFFFF7A70)
val TextDim = Color(0xFFAEBBDA)

// Data colours. The cell heatmap is diverging blue <-> red with a gray midpoint (= pack average);
// module temperatures use one amber hue, dark -> bright.
val DivergingLow = Color(0xFF3987E5)
val DivergingMid = Color(0xFF383835)
val DivergingHigh = Color(0xFFE66767)
val TempRampLow = Color(0xFF2B2A26)
val TempRampHigh = Color(0xFFEDA100)
val GridLine = Color(0xFF2E3A5C)

// Simple tab, what a tile is about: energy you have, energy spent, and what one got out of the other.
val ToneHave = Color(0xFF3987E5)
val ToneSpent = Color(0xFFE66767)
val ToneEfficiency = Color(0xFF9273E6)

private val Colors = darkColorScheme(
  primary = Accent,
  onPrimary = Color(0xFF00213A),
  primaryContainer = AccentDim,
  onPrimaryContainer = Color(0xFFD3E9FF),
  secondary = Color(0xFFB4C4E4),
  background = Background,
  onBackground = Ink,
  surface = Surface,
  onSurface = Ink,
  surfaceVariant = SurfaceHigh,
  onSurfaceVariant = TextDim,
  surfaceContainer = Surface,
  surfaceContainerHigh = SurfaceHigh,
  outline = Color(0xFF4A5A85),
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
