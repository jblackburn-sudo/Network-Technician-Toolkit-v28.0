package com.example.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * High-contrast dark color scheme tailored for network technicians in low-light environments.
 */
private val DarkColorScheme = darkColorScheme(
    primary = StellarTeal,
    onPrimary = DarkSlate,
    primaryContainer = StellarTealDark,
    onPrimaryContainer = Color.White,
    inversePrimary = StellarTealLight,
    secondary = StatusBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = StatusAmber,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = DarkSlate,
    onBackground = Color(0xFFF1F5F9),
    surface = DarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = DarkCard,
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceTint = StellarTeal,
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = DarkSlate,
    outline = DarkBorder,
    outlineVariant = Color(0xFF1E293B),
    scrim = Color.Black,
    error = StatusRed,
    onError = Color.White,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFCA5A5)
)

/**
 * Clean, high-legibility light color scheme for outdoor and brightly lit field environments.
 */
private val LightColorScheme = lightColorScheme(
    primary = StellarTealDark,
    onPrimary = Color.White,
    primaryContainer = StellarTealLight,
    onPrimaryContainer = DarkSlate,
    inversePrimary = StellarTeal,
    secondary = StatusBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightCard,
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = StellarTealDark,
    inverseSurface = DarkSurface,
    inverseOnSurface = Color(0xFFF1F5F9),
    outline = LightBorder,
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color.Black,
    error = StatusRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

/**
 * Resolves whether the application should display in dark mode based on:
 * 1. Explicit override passed to composable
 * 2. Saved user preference in SharedPreferences ("system", "dark", "light")
 * 3. System-wide configuration (isSystemInDarkTheme)
 */
fun resolveThemeMode(context: Context, systemDark: Boolean): Boolean {
    return try {
        val prefs = context.getSharedPreferences("ToolkitSettings", Context.MODE_PRIVATE)
        when (prefs.getString("theme", "dark")) {
            "light" -> false
            "system" -> systemDark
            else -> true // "dark" is app standard default
        }
    } catch (e: Exception) {
        systemDark
    }
}

/**
 * Resolves whether Dynamic Color should be activated on Android 12+ devices.
 */
fun resolveDynamicColorSetting(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return try {
        val prefs = context.getSharedPreferences("ToolkitSettings", Context.MODE_PRIVATE)
        prefs.getBoolean("dynamic_color", true)
    } catch (e: Exception) {
        true
    }
}

/**
 * Enhanced Material3 Theme for Network Technician Toolkit.
 *
 * Supports:
 * - Android 12+ (API 31+) Dynamic Material You wallpaper-based color schemes.
 * - Dynamic responsiveness to system-wide dark mode settings and manual user preferences.
 * - Complete semantic Material3 color tokens ensuring high contrast in field technician workflows.
 */
@Composable
fun NetworkToolkitTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: resolveThemeMode(context, systemInDark)
    val useDynamic = dynamicColor ?: resolveDynamicColorSetting(context)

    val colorScheme: ColorScheme = when {
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
