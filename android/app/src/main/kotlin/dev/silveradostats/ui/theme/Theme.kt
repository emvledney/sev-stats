package dev.silveradostats.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// Always dark: a car screen at night must not glare. High-contrast text, muted surfaces.
private val Background = Color(0xFF0E1114)
private val Surface = Color(0xFF171C21)
private val SurfaceHigh = Color(0xFF212830)
private val Accent = Color(0xFF7CC4FF)
private val AccentDim = Color(0xFF1F3B52)

val StatusOk = Color(0xFF6FD08C)
val StatusWarn = Color(0xFFF2C14E)
val StatusError = Color(0xFFFF7A70)
val TextDim = Color(0xFFA9B4BF)

// Data colours. Power direction is a two-hue pair (orange / aqua, validated for colour-vision
// deficiency and contrast on a dark surface); the cell heatmap is diverging blue <-> red with a
// gray midpoint (= pack average); module temperatures use one amber hue, dark -> bright.
val Discharge = Color(0xFFD95926)
val ChargeRegen = Color(0xFF199E70)
val DivergingLow = Color(0xFF3987E5)
val DivergingMid = Color(0xFF383835)
val DivergingHigh = Color(0xFFE66767)
val TempRampLow = Color(0xFF2B2A26)
val TempRampHigh = Color(0xFFEDA100)
val DemoBannerColor = Color(0xFFFAB219)
val GridLine = Color(0xFF39434D)

// Simple tab: cumulative efficiency (green line) over per-mile bars (blue), validated as a pair on
// the dark card surface (lightness band, CVD and normal-vision separation, >= 3:1 contrast).
val EfficiencyLine = Color(0xFF3FAE4A)
val EfficiencyBars = Color(0xFF3987E5)
val BatteryFill = Color(0xFF7CC4FF)
val CardSurface = Color(0xFF171C21)

private val Colors = darkColorScheme(
  primary = Accent,
  onPrimary = Color(0xFF00213A),
  primaryContainer = AccentDim,
  onPrimaryContainer = Color(0xFFD3E9FF),
  secondary = Color(0xFFB7C8D9),
  background = Background,
  onBackground = Color(0xFFE8EDF2),
  surface = Surface,
  onSurface = Color(0xFFE8EDF2),
  surfaceVariant = SurfaceHigh,
  onSurfaceVariant = TextDim,
  surfaceContainer = Surface,
  surfaceContainerHigh = SurfaceHigh,
  outline = Color(0xFF55616D),
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
