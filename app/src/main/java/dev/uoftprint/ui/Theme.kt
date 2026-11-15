package dev.uoftprint.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Toner palette: cool paper, ink-dark text, a cyan-toner accent, and CMY for colour printers. */
object Toner {
    val Cyan = Color(0xFF00A3C4)
    val Magenta = Color(0xFFD6246E)
    val Yellow = Color(0xFFF2C200)
}

private val Light = lightColorScheme(
    primary = Color(0xFF0B5F6B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBEF),
    onPrimaryContainer = Color(0xFF00363E),
    secondary = Color(0xFF4A6266),
    secondaryContainer = Color(0xFFDDE6E8),
    onSecondaryContainer = Color(0xFF1C2B2E),
    background = Color(0xFFF5F7F8),
    onBackground = Color(0xFF181C1D),
    surface = Color(0xFFF5F7F8),
    onSurface = Color(0xFF181C1D),
    surfaceVariant = Color(0xFFE3E8EA),
    onSurfaceVariant = Color(0xFF4F5759),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF3F4),
    surfaceContainer = Color(0xFFE9EEEF),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    outline = Color(0xFF7A8385),
    outlineVariant = Color(0xFFC9D0D2),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD3DF),
    onPrimary = Color(0xFF00363E),
    primaryContainer = Color(0xFF004F59),
    onPrimaryContainer = Color(0xFFCDEBEF),
    secondary = Color(0xFFB1CBCF),
    secondaryContainer = Color(0xFF334A4E),
    onSecondaryContainer = Color(0xFFDDE6E8),
    background = Color(0xFF101415),
    onBackground = Color(0xFFE0E3E4),
    surface = Color(0xFF101415),
    onSurface = Color(0xFFE0E3E4),
    surfaceVariant = Color(0xFF3F4849),
    onSurfaceVariant = Color(0xFFBFC8CA),
    surfaceContainerLowest = Color(0xFF0B0F10),
    surfaceContainerLow = Color(0xFF181D1E),
    surfaceContainer = Color(0xFF1C2122),
    surfaceContainerHigh = Color(0xFF262B2C),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F4849),
    error = Color(0xFFF2B8B5),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}

/** Type used inside the queue tile: tight, heavy, like a label printed on the machine. */
val TileText = TextStyle(fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = (-0.3).sp, lineHeight = 16.sp)

@Composable
fun UofTPrintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
