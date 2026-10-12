package com.watermarkbox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 水印盒品牌配色。
 *
 * 主色  #1A237E 深蓝
 * 强调  #FF6F00 琥珀
 * 背景  #F5F5F5
 */

// ---- 亮色主题 ----
val BrandNavy = Color(0xFF1A237E)          // 主色：深蓝
val BrandNavyLight = Color(0xFF534BAE)     // 主色浅变体
val BrandNavyDark = Color(0xFF000051)      // 主色深变体

val BrandAmber = Color(0xFFFF6F00)         // 强调色：琥珀
val BrandAmberLight = Color(0xFFFFA040)    // 强调色浅变体
val BrandAmberDark = Color(0xFFC43E00)     // 强调色深变体

val BrandBackground = Color(0xFFF5F5F5)    // 背景
val BrandSurface = Color(0xFFFFFFFF)       // 卡片表面
val BrandSurfaceVariant = Color(0xFFE7E7EC)
val BrandOnSurface = Color(0xFF1C1B1F)
val BrandOutline = Color(0xFFC4C6D0)

// ---- 暗色主题 ----
val BrandNavyDarkTheme = Color(0xFF8C93E8)
val BrandAmberDarkTheme = Color(0xFFFFB77C)
val BrandBackgroundDark = Color(0xFF121318)
val BrandSurfaceDark = Color(0xFF1E1F25)
val BrandSurfaceVariantDark = Color(0xFF44464F)
val BrandOnSurfaceDark = Color(0xFFE4E2E6)
val BrandOutlineDark = Color(0xFF8E9099)

/**
 * 编辑页取色器预设 8 色（面板用）。
 */
val WatermarkPresetColors = listOf(
    Color(0xFFFFFFFF), // 白
    Color(0xFF000000), // 黑
    Color(0xFFFF5252), // 红
    Color(0xFFFFB300), // 琥珀
    Color(0xFFFFEB3B), // 黄
    Color(0xFF4CAF50), // 绿
    Color(0xFF2196F3), // 蓝
    Color(0xFF9C27B0)  // 紫
)