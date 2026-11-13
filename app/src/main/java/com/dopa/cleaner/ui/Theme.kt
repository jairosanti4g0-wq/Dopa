package com.dopa.cleaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PurpleLight = lightColorScheme(
    primary = Color(0xFF6A1B9A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9D5FF),
    onPrimaryContainer = Color(0xFF2E0A4A),
    secondary = Color(0xFF8E24AA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E5F5),
    onSecondaryContainer = Color(0xFF3B0F45),
    tertiary = Color(0xFFC2185B),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1D1A22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1D1A22),
    surfaceVariant = Color(0xFFE8DEF0),
    onSurfaceVariant = Color(0xFF4A4458),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val PurpleDark = darkColorScheme(
    primary = Color(0xFFD0A8FF),
    onPrimary = Color(0xFF3C1067),
    primaryContainer = Color(0xFF55248A),
    onPrimaryContainer = Color(0xFFF1E0FF),
    secondary = Color(0xFFE1B6F0),
    onSecondary = Color(0xFF442255),
    secondaryContainer = Color(0xFF5C386C),
    onSecondaryContainer = Color(0xFFF8DBFF),
    tertiary = Color(0xFFFFB0C8),
    background = Color(0xFF140F1C),
    onBackground = Color(0xFFE8E0EE),
    surface = Color(0xFF140F1C),
    onSurface = Color(0xFFE8E0EE),
    surfaceVariant = Color(0xFF2A2234),
    onSurfaceVariant = Color(0xFFCCC2DA),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun DopaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) PurpleDark else PurpleLight, content = content)
}
