package com.ericbarone.drivetrace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF4FD1C5)
private val Navy = Color(0xFF1B2A4A)

private val DarkColors = darkColorScheme(primary = Teal, secondary = Navy)
private val LightColors = lightColorScheme(primary = Navy, secondary = Teal)

@Composable
fun DriveTraceTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
