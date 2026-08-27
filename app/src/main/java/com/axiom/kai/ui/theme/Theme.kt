package com.axiom.kai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import android.content.Context

object KaiBanner {
    val Green = Color(0xFF2E7D32)
    val LightGreen = Color(0xFF4CAF50)
    val DarkGreen = Color(0xFF1B5E20)
    val Dark = Color(0xFF1A1A1A)
    val DarkGray = Color(0xFF2D2D2D)
    val NearBlack = Color(0xFF141414)
    val TextLight = Color(0xFFE8F5E9)
    val TextGray = Color(0xFF9E9E9E)
}

@Composable
fun lightBannerScheme() = lightColorScheme(
    primary = KaiBanner.Green,
    secondary = KaiBanner.LightGreen,
    tertiary = KaiBanner.DarkGreen,
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF616161),
    onPrimary = Color(0xFFFFFFFF)
)

@Composable
fun darkBannerScheme() = darkColorScheme(
    primary = KaiBanner.Green,
    secondary = KaiBanner.LightGreen,
    tertiary = KaiBanner.DarkGreen,
    surface = KaiBanner.NearBlack,
    surfaceVariant = KaiBanner.DarkGray,
    onSurface = KaiBanner.TextLight,
    onSurfaceVariant = KaiBanner.TextGray,
    onPrimary = Color(0xFF1A1A1A)
)

@Composable
fun blackBannerScheme() = darkColorScheme(
    primary = KaiBanner.Green,
    secondary = KaiBanner.LightGreen,
    tertiary = KaiBanner.DarkGreen,
    surface = KaiBanner.NearBlack,
    surfaceVariant = KaiBanner.Dark,
    onSurface = KaiBanner.TextLight,
    onSurfaceVariant = KaiBanner.TextGray,
    onPrimary = Color(0xFF1A1A1A)
)

private const val PREFS = "kai_theme"
private const val KEY_MODE = "theme_mode"

fun getThemeMode(ctx: Context): Int =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 1)

fun setThemeMode(ctx: Context, mode: Int) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
    ThemeVersion.version.value++
}

object ThemeVersion {
    var version = mutableStateOf(0)
        private set
}

@Composable
fun KaiTheme(content: @Composable () -> Unit) {
    ThemeVersion.version.value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mode = getThemeMode(ctx)
    val scheme = when (mode) {
        1 -> darkBannerScheme()
        2 -> blackBannerScheme()
        else -> lightBannerScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
