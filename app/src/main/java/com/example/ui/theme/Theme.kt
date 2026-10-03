package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisDarkColorScheme =
  darkColorScheme(
    primary = JarvisPrimary,
    onPrimary = JarvisBackground,
    primaryContainer = JarvisSurfaceVariant,
    onPrimaryContainer = JarvisPrimary,
    secondary = JarvisSecondary,
    onSecondary = JarvisBackground,
    secondaryContainer = JarvisSurfaceVariant,
    onSecondaryContainer = JarvisSecondary,
    tertiary = JarvisAccent,
    background = JarvisBackground,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurface,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisTextSecondary,
    error = JarvisError,
    onError = JarvisBackground,
    outline = JarvisBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Jarvis is always dark futuristic
  dynamicColor: Boolean = false, // Keep Jarvis custom cinematic dark theme
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = JarvisDarkColorScheme,
    typography = Typography,
    content = content
  )
}
