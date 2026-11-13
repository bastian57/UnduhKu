package com.daftech.unduhku.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UnduhkuColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF0B1220),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    onSurface = Color(0xFFF9FAFB),
    secondary = Color(0xFFA5F3FC)
)

@Composable
fun UnduhkuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UnduhkuColors, content = content)
}
