package com.watermarkbox.ui.screen.editor

/**
 * 水印参数面板（页面 2 的面板主体）。
 *
 * ## 文件拆分说明
 *
 * 面板内容原本挤在一个 894 行的文件里。拆成三份，划分依据是
 * **「谁跟谁一起改」** —— 而不是按行数平均切：
 *
 * | 文件 | 内容 | 什么时候动它 |
 * |---|---|---|
 * | `WatermarkPanel.kt`（本文件） | 类型切换入口 + 面板级小件 + 九宫格映射 | 改面板骨架 / 通用小件 |
 * | `WatermarkPanelContent.kt` | 文字 / 图片两套参数区 | 改某个可见水印参数 |
 * | `WatermarkPanelInvisible.kt` | 隐藏水印配置区 | 改隐藏水印（EXIF / DCT） |
 *
 * 这样改「字体颜色」只会在 `…Content.kt` 产生 diff，
 * 不会把隐藏水印那部分的代码也卷进代码评审。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.theme.Spacing

/**
 * 水印参数面板内容（页面 2 的面板主体）。
 *
 * 注意：外壳（拖拽把手、圆角、阴影）由 [com.watermarkbox.ui.component.DraggableBottomPanel] 提供，
 * 本组件只负责内容 —— 类型切换 + 各参数分组，并自带滚动。
 */
@Composable
fun WatermarkPanel(
    state: WatermarkPanelState,
    viewModel: WatermarkPanelHost,
    onPickLogo: () -> Unit,
    onPickFont: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否由面板自己负责滚动。
     *
     * 编辑页把面板塞进可拖拽底部面板，需要面板**内部**滚动，传 `true`。
     *
     * 预设编辑页则是「名称 + 画布 + 面板」整页一起滚，
     * 外层已经有了 `verticalScroll`。此时必须传 `false`，
     * 否则两个垂直滚动容器嵌套，内层会拿到无限高度约束并抛
     * `IllegalStateException: Vertically scrollable component was measured
     * with an infinity maximum height constraints`。
     */
    scrollable: Boolean = true
) {
    // 只有需要内部滚动时才挂 verticalScroll —— 见上方 scrollable 的说明。
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollModifier)
            .padding(horizontal = Spacing.PageHorizontal)
            .padding(bottom = 96.dp)   // 给 FAB 留出空间
    ) {
        // ============ 水印类型切换 ============
        SegmentedControl(
            options = listOf(
                WatermarkType.TEXT to stringResource(R.string.editor_type_text),
                WatermarkType.IMAGE to stringResource(R.string.editor_type_image)
            ),
            selected = state.watermarkType,
            onSelect = viewModel::updateWatermarkType
        )

        Spacer(Modifier.height(Spacing.Item))

        when (state.watermarkType) {
            WatermarkType.TEXT -> TextWatermarkPanelContent(
                state = state,
                viewModel = viewModel,
                onPickFont = onPickFont
            )
            WatermarkType.IMAGE -> ImageWatermarkPanelContent(
                state = state,
                viewModel = viewModel,
                onPickLogo = onPickLogo
            )
        }

        // ============ 隐藏水印（与可见水印独立，两种类型下都可配置）============
        // 预设编辑页不显示这一区：隐藏水印属于「导出选项」，预设数据结构里不含它。
        if (state.showInvisibleSection) {
            PanelDivider()
            InvisibleWatermarkSection(state = state, viewModel = viewModel)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================
// 面板级通用小件
// =====================================================================

/**
 * 字体族横向选择器（系统内置族）。
 *
 * [FontFamilyOption.CUSTOM] 不出现在这里 —— 它的入口是「导入字体」那一行，
 * 两个入口混在一起会让人以为要在这里选自定义字体。
 */
@Composable
internal fun FontFamilySelector(
    selected: FontFamilyOption,
    onSelect: (FontFamilyOption) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FontFamilyOption.entries
            .filter { it != FontFamilyOption.CUSTOM }
            .forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
    }
}

/** 面板内的分组分隔：上下留白 + 一条淡分隔线。 */
@Composable
internal fun PanelDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------------
// GravityPosition ↔ 九宫格 index 映射（0..8，行优先）
// ---------------------------------------------------------------------

/**
 * 映射到九宫格按钮的索引。
 *
 * [GravityPosition.CUSTOM] 不属于九宫格，返回 -1
 * —— 界面据此判断「当前没有选中任何格子」。
 */
internal fun GravityPosition.toIndex(): Int = when (this) {
    GravityPosition.TOP_LEFT -> 0
    GravityPosition.TOP_CENTER -> 1
    GravityPosition.TOP_RIGHT -> 2
    GravityPosition.CENTER_LEFT -> 3
    GravityPosition.CENTER -> 4
    GravityPosition.CENTER_RIGHT -> 5
    GravityPosition.BOTTOM_LEFT -> 6
    GravityPosition.BOTTOM_CENTER -> 7
    GravityPosition.BOTTOM_RIGHT -> 8
    GravityPosition.CUSTOM -> -1
}

internal fun gravityFromIndex(index: Int): GravityPosition = when (index) {
    0 -> GravityPosition.TOP_LEFT
    1 -> GravityPosition.TOP_CENTER
    2 -> GravityPosition.TOP_RIGHT
    3 -> GravityPosition.CENTER_LEFT
    4 -> GravityPosition.CENTER
    5 -> GravityPosition.CENTER_RIGHT
    6 -> GravityPosition.BOTTOM_LEFT
    7 -> GravityPosition.BOTTOM_CENTER
    else -> GravityPosition.BOTTOM_RIGHT
}