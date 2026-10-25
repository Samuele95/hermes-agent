package com.nousresearch.hermes.android.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.R

/**
 * The desktop's default identity, `nous` (apps/shared/src/theme-presets.ts: GitHub chrome, Nous blue accent), as
 * Material 3 colours, plus the desktop tokens Material has no slot for. Light and dark follow the system.
 */
@Immutable
data class HermesColors(
    val userBubble: Color,
    val userBubbleBorder: Color,
    /** Hairlines in the transcript and between list rows (`--ui-stroke-tertiary`). */
    val stroke: Color,
    /** Inline chat widgets: question cards, tool details (`--ui-widget-surface-background`). */
    val widget: Color,
    val textTertiary: Color,
    val dark: Boolean,
)

private val NousLight = lightColorScheme(
    primary = Color(0xFF0053FD), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEEAFF), onPrimaryContainer = Color(0xFF1F2328),
    secondary = Color(0xFF656D76), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEAFF), onSecondaryContainer = Color(0xFF1F2328),
    tertiary = Color(0xFF0053FD), tertiaryContainer = Color(0xFFE3EDFF), onTertiaryContainer = Color(0xFF1F2328),
    background = Color.White, onBackground = Color(0xFF1F2328),
    surface = Color.White, onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFF6F8FA), onSurfaceVariant = Color(0xFF656D76),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFF6F8FA), surfaceContainerHigh = Color(0xFFF0F2F4), surfaceContainerHighest = Color(0xFFEAEEF2),
    outline = Color(0xFFD0D7DE), outlineVariant = Color(0xFFE5E9ED),
    error = Color(0xFFCF222E), onError = Color.White, errorContainer = Color(0xFFFFEBE9), onErrorContainer = Color(0xFF82071E),
)

private val NousDark = darkColorScheme(
    primary = Color(0xFF4A84FE), onPrimary = Color(0xFF161616),
    primaryContainer = Color(0xFF1D2E4F), onPrimaryContainer = Color(0xFFE6EDF3),
    secondary = Color(0xFF7D8590), onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF1D2E4F), onSecondaryContainer = Color(0xFFE6EDF3),
    tertiary = Color(0xFF4A84FE), tertiaryContainer = Color(0xFF17243A), onTertiaryContainer = Color(0xFFE6EDF3),
    background = Color(0xFF0D1117), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF161B22), onSurfaceVariant = Color(0xFF7D8590),
    surfaceContainerLowest = Color(0xFF010409), surfaceContainerLow = Color(0xFF010409),
    surfaceContainer = Color(0xFF161B22), surfaceContainerHigh = Color(0xFF1A1E24), surfaceContainerHighest = Color(0xFF21262D),
    outline = Color(0xFF30363D), outlineVariant = Color(0xFF21262D),
    error = Color(0xFFF85149), onError = Color.White, errorContainer = Color(0xFF3D1214), onErrorContainer = Color(0xFFFFA198),
)

private val LightTokens = HermesColors(userBubble = Color(0xFFDAE7FD), userBubbleBorder = Color(0xFFD0D7DE), stroke = Color(0xFFD8DEE4),
                                       widget = Color(0xFFF6F8FA), textTertiary = Color(0xFF8C959F), dark = false)
private val DarkTokens = HermesColors(userBubble = Color(0xFF07162C), userBubbleBorder = Color(0xFF30363D), stroke = Color(0xFF30363D),
                                      widget = Color(0xFF161B22), textTertiary = Color(0xFF6E7681), dark = true)

/** JetBrains Mono, bundled like the desktop's (SIL OFL 1.1), for code and commands. */
val CodeFont = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

/**
 * The HERMES AGENT wordmark. The desktop sets it in Collapse (Blaze Type, licensed per application, not bundled
 * here: D29); a bold condensed face in Nous blue stands in.
 */
val WordmarkFont = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

private val LocalHermesColors = staticCompositionLocalOf { LightTokens }

object Hermes {
    val colors: HermesColors
        @Composable get() = LocalHermesColors.current
}

private fun typography(): Typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Rounded like the desktop's inputs and cards (rounded-lg): text fields take extraSmall. */
private val Shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp),
                            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp))

/** The section labels of the desktop sidebar (PINNED, SESSIONS): small caps in the accent colour. */
val SectionLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun HermesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) NousDark else NousLight
    CompositionLocalProvider(LocalHermesColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = Shapes, content = content)
    }
}
