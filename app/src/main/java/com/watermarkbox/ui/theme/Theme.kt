package com.watermarkbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 水印盒亮色配色方案（基于品牌色）。
 */
private val LightColorScheme = lightColorScheme(
    primary = BrandNavy,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BrandNavyLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = BrandAmber,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = BrandAmberLight,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF3A1B00),
    tertiary = BrandNavyLight,
    background = BrandBackground,
    onBackground = BrandOnSurface,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF44464F),
    outline = BrandOutline
)

/**
 * 水印盒暗色配色方案。
 */
private val DarkColorScheme = darkColorScheme(
    primary = BrandNavyDarkTheme,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0A0F44),
    primaryContainer = BrandNavyDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = BrandAmberDarkTheme,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF4A2600),
    secondaryContainer = BrandAmberDark,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDCC2),
    tertiary = BrandNavyDarkTheme,
    background = BrandBackgroundDark,
    onBackground = BrandOnSurfaceDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnSurfaceDark,
    surfaceVariant = BrandSurfaceVariantDark,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC5C6D0),
    outline = BrandOutlineDark
)

/**
 * 应用主题。
 *
 * @param dynamicColor 是否启用 Material You 动态取色（Android 12+）。
 *                     默认 false，优先保证品牌色一致性；设置页可开放开关。
 */
@Composable
fun WatermarkBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 同步状态栏图标颜色（浅色背景用深色图标）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = WatermarkBoxShapes,
        content = content
    )
}