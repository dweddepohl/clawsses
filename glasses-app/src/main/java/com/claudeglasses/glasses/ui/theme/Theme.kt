package com.claudeglasses.glasses.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme optimized for Rokid Glasses monochrome HUD display
 *
 * Key considerations:
 * - Pure black backgrounds blend with the real world
 * - High contrast greens/cyans are most visible
 * - Minimal color palette for monochrome display
 */
private val HudColorScheme = darkColorScheme(
    primary = Color(0xFF00FF00),           // Bright green
    secondary = Color(0xFF00FFFF),          // Cyan
    tertiary = Color(0xFF39FF14),           // Neon green
    background = Color.Black,               // Pure black
    surface = Color.Black,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFF00FF00),
    onSurface = Color(0xFF00FF00),
    error = Color(0xFFFF4444)
)

@Composable
fun GlassesHudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HudColorScheme,
        typography = Typography(),
        content = content
    )
}
