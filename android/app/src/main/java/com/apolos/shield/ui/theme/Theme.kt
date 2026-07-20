package com.apolos.shield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ShieldGreen = Color(0xFF1DB954)
val ShieldAmber = Color(0xFFFFB300)
val ShieldRed = Color(0xFFE53935)
private val Navy = Color(0xFF0E1626)
private val NavySurface = Color(0xFF162236)

private val DarkColors = darkColorScheme(
    primary = ShieldGreen,
    secondary = ShieldAmber,
    error = ShieldRed,
    background = Navy,
    surface = NavySurface,
)

private val LightColors = lightColorScheme(
    primary = ShieldGreen,
    secondary = ShieldAmber,
    error = ShieldRed,
)

@Composable
fun ApolosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
