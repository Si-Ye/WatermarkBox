package com.watermarkbox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 水印盒圆角规范：
 * - 卡片 16dp
 * - 按钮 12dp
 */
val WatermarkBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),   // 按钮
    large = RoundedCornerShape(16.dp),    // 卡片
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * 间距规范（页面内边距 16dp / 组件间距 12dp）。
 */
object Spacing {
    val PageHorizontal = 16.dp
    val Item = 12.dp
    val Small = 8.dp
    val Tiny = 4.dp
}