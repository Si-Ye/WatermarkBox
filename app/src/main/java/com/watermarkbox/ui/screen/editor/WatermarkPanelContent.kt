package com.watermarkbox.ui.screen.editor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.ui.component.ColorPalette
import com.watermarkbox.ui.component.GravityGrid
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.watermark.WatermarkRenderer
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.ui.theme.WatermarkPresetColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 水印参数面板 —— **可见水印参数区**（文字 / 图片两套）。
 *
 * 两套参数区放在同一文件里，因为它们结构高度对称
 * （都是：内容 → 尺寸 → 布局 → 位置），改动时通常要**成对修改**，
 * 分开放反而容易漏改一边。
 */

@Composable
internal fun TextWatermarkPanelContent(
    state: WatermarkPanelState,
    viewModel: WatermarkPanelHost,
    onPickFont: () -> Unit
) {
    val config = state.config

    // ---- 1. 水印文字 ----
    SectionLabel(stringResource(R.string.editor_text_section))
    OutlinedTextField(
        value = config.text,
        onValueChange = viewModel::updateText,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        placeholder = { Text(stringResource(R.string.editor_text_placeholder)) },
        supportingText = {
            Text(
                stringResource(R.string.editor_text_placeholders),
                style = MaterialTheme.typography.labelSmall
            )
        }
    )
    // 快捷插入占位符
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("{timestamp}", "{date}", "{filename}").forEach { token ->
            OutlinedButton(
                onClick = { viewModel.updateText(config.text + token) },
                // heightIn：字号调大时按钮能长高，不把占位符文字裁掉
                modifier = Modifier.heightIn(min = 32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 0.dp
                )
            ) {
                Text(token, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Spacer(Modifier.height(Spacing.Item))

    // ---- 2. 字体设置 ----
    SectionLabel(stringResource(R.string.editor_font_section))
    LabeledSlider(
        label = stringResource(R.string.editor_font_size),
        value = config.fontSize,
        onValueChange = viewModel::updateFontSize,
        valueRange = 6f..300f,
        valueText = "${config.fontSize.roundToInt()} sp"
    )

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.editor_font_family),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    FontFamilySelector(
        selected = config.fontFamily,
        onSelect = viewModel::updateFontFamily
    )

    // ---- 自定义字体（阶段四）----
    Spacer(Modifier.height(Spacing.Item))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.editor_font_custom),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedButton(
            onClick = onPickFont,
            // 同占位符按钮：用 heightIn 让字号调大时能长高
            modifier = Modifier.heightIn(min = 32.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 0.dp
            )
        ) {
            Icon(
                imageVector = Icons.Filled.FileOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.editor_font_import), style = MaterialTheme.typography.labelSmall)
        }
    }

    if (state.customFonts.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.editor_font_import_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Spacer(Modifier.height(6.dp))
        // 已导入字体列表（横向 chip）
        state.customFonts.forEach { fontName ->
            val isSelected = config.fontFamily == FontFamilyOption.CUSTOM &&
                config.customFontName == fontName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { viewModel.selectCustomFont(fontName) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = fontName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.deleteCustomFont(fontName) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.editor_font_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    Spacer(Modifier.height(Spacing.Item))
    Text(
        text = stringResource(R.string.editor_font_color),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    ColorPalette(
        colors = WatermarkPresetColors,
        selected = config.color.color,
        onSelect = viewModel::updateColor
    )

    Spacer(Modifier.height(Spacing.Item))
    ToggleRow(stringResource(R.string.editor_font_bold), config.bold) { viewModel.toggleBold() }
    ToggleRow(stringResource(R.string.editor_font_italic), config.italic) { viewModel.toggleItalic() }

    PanelDivider()

    // ---- 3. 布局与密度 ----
    SectionLabel(stringResource(R.string.editor_layout_section))
    Text(
        text = stringResource(R.string.editor_layout_mode),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    SegmentedControl(
        options = listOf(
            LayoutMode.SINGLE to stringResource(R.string.editor_layout_single),
            LayoutMode.TILED to stringResource(R.string.editor_layout_tiled),
            LayoutMode.DIAGONAL to stringResource(R.string.editor_layout_diagonal)
        ),
        selected = config.layoutMode,
        onSelect = viewModel::updateLayoutMode
    )

    if (config.layoutMode != LayoutMode.SINGLE) {
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = stringResource(R.string.editor_layout_density),
            value = config.tileCount.toFloat(),
            onValueChange = { viewModel.updateTileCount(it.roundToInt()) },
            valueRange = 1f..20f,
            valueText = stringResource(R.string.editor_count_value, config.tileCount)
        )
    }

    Spacer(Modifier.height(Spacing.Item))
    LabeledSlider(
        label = stringResource(R.string.editor_layout_rotation),
        value = config.rotationDegrees,
        onValueChange = viewModel::updateRotation,
        valueRange = -90f..90f,
        valueText = "${config.rotationDegrees.roundToInt()}°"
    )
    LabeledSlider(
        label = stringResource(R.string.editor_layout_alpha),
        value = config.alpha,
        onValueChange = viewModel::updateAlpha,
        // ⚠️ 下限**必须引用** [WatermarkRenderer.MIN_ALPHA]，不要写死 0.01f ——
        // 否则改常量时滑块不会跟着变，用户能拖到常量允许范围之外
        // （拖到 0.01 却被 `coerceIn` 抬到 0.05，界面与实际不符）。
        valueRange = WatermarkRenderer.MIN_ALPHA..1f,
        valueText = "${(config.alpha * 100).roundToInt()}%"
    )

    // ---- 4. 位置与边距（仅单个模式）----
    if (config.layoutMode == LayoutMode.SINGLE) {
        PanelDivider()
        SectionLabel(stringResource(R.string.editor_gravity_section))
        GravityGrid(
            selectedIndex = config.gravity.toIndex(),
            onSelect = { viewModel.updateGravity(gravityFromIndex(it)) }
        )
        Spacer(Modifier.height(6.dp))
        // 拖动提示：CUSTOM 时改成「当前位置由拖动决定」，让用户知道九宫格没选中是正常的
        Text(
            text = if (config.gravity == GravityPosition.CUSTOM) {
                stringResource(R.string.editor_position_custom)
            } else {
                stringResource(R.string.editor_drag_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = stringResource(R.string.editor_margin),
            value = config.margin,
            onValueChange = viewModel::updateMargin,
            valueRange = 0f..100f,
            valueText = "${config.margin.roundToInt()} px"
        )
    }

    PanelDivider()

    // ---- 5. 水印增强 ----
    SectionLabel(stringResource(R.string.editor_enhance_section))
    ToggleRow(stringResource(R.string.editor_stroke), config.strokeEnabled) { viewModel.toggleStroke() }
    // 展开/收起用动画：开关一拨内容「瞬移」出现会很生硬。
    // 只影响这一块自身高度（180ms），不动其它兄弟节点 —— 符合「流畅优先」。
    AnimatedVisibility(
        visible = config.strokeEnabled,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
    ) {
        // ⚠️ **必须**用 Column 包住多个子节点。
        //
        // `AnimatedVisibility` 的 content 签名是 `AnimatedVisibilityScope.() -> Unit`，
        // 它**不提供** Column/Row 作用域 —— 也就是说多个直接子节点会
        // 被放在同一个布局位置、**互相重叠**。
        //
        // 这正是老大实测到的「描边颜色选择错位 + 重叠」：
        // LabeledSlider / Text / Spacer / ColorPalette 四个节点
        // 全部堆叠在左上角。
        //
        // 而且 `expandVertically` 算的是「内容整体高度」，
        // 重叠时高度也不对，动画会一跳一跳。
        Column {
            LabeledSlider(
                label = stringResource(R.string.editor_stroke_width),
                value = config.strokeWidth,
                onValueChange = viewModel::updateStrokeWidth,
                valueRange = 0.5f..10f,
                // 显式给 Locale：默认 Locale 下阿拉伯语等区域会把数字格式化成
                // 阿拉伯-印度数字，与界面其它数字不一致（lint: DefaultLocale）
                valueText = String.format(Locale.getDefault(), "%.1f px", config.strokeWidth)
            )
            Text(
                text = stringResource(R.string.editor_stroke_color),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            ColorPalette(
                colors = WatermarkPresetColors,
                selected = config.strokeColor.color,
                onSelect = viewModel::updateStrokeColor
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    ToggleRow(stringResource(R.string.editor_shadow), config.shadowEnabled) { viewModel.toggleShadow() }
    AnimatedVisibility(
        visible = config.shadowEnabled,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
    ) {
        // 同上：单个子节点虽然不会重叠，但显式 Column 保证
        // 将来往这里加内容时不会踩同一个坑。
        Column {
            LabeledSlider(
                label = stringResource(R.string.editor_shadow_radius),
                value = config.shadowRadius,
                onValueChange = viewModel::updateShadowRadius,
                valueRange = 0f..20f,
                valueText = String.format(Locale.getDefault(), "%.1f px", config.shadowRadius)
            )
        }
    }
}
@Composable
internal fun ImageWatermarkPanelContent(
    state: WatermarkPanelState,
    viewModel: WatermarkPanelHost,
    onPickLogo: () -> Unit
) {
    val config = state.imageConfig

    // ---- 1. Logo 选择 ----
    SectionLabel(stringResource(R.string.editor_logo_section))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo 缩略预览
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val logo = state.logoBitmap
            if (logo != null && !logo.isRecycled) {
                androidx.compose.foundation.Image(
                    bitmap = logo.asImageBitmap(),
                    contentDescription = stringResource(R.string.editor_logo_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .padding(4.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Button(
                onClick = onPickLogo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (state.logoBitmap == null) R.string.editor_logo_pick else R.string.editor_logo_change))
            }
        }

        if (state.logoBitmap != null) {
            IconButton(onClick = { viewModel.clearLogo() }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.editor_logo_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    Spacer(Modifier.height(Spacing.Item))

    // ---- 2. 尺寸与比例 ----
    SectionLabel(stringResource(R.string.editor_size_section))
    LabeledSlider(
        label = stringResource(R.string.editor_logo_width),
        value = config.widthPercent,
        onValueChange = viewModel::updateLogoWidth,
        valueRange = 0.05f..0.8f,
        valueText = "${(config.widthPercent * 100).roundToInt()}%"
    )
    ToggleRow(stringResource(R.string.editor_logo_aspect), config.preserveAspectRatio) {
        viewModel.togglePreserveAspectRatio()
    }

    PanelDivider()

    // ---- 3. 布局与密度 ----
    SectionLabel(stringResource(R.string.editor_layout_section))
    SegmentedControl(
        options = listOf(
            LayoutMode.SINGLE to stringResource(R.string.editor_layout_single),
            LayoutMode.TILED to stringResource(R.string.editor_layout_tiled),
            LayoutMode.DIAGONAL to stringResource(R.string.editor_layout_diagonal)
        ),
        selected = config.layoutMode,
        onSelect = viewModel::updateImageLayoutMode
    )

    if (config.layoutMode != LayoutMode.SINGLE) {
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = stringResource(R.string.editor_layout_density),
            value = config.tileCount.toFloat(),
            onValueChange = { viewModel.updateImageTileCount(it.roundToInt()) },
            valueRange = 1f..20f,
            valueText = stringResource(R.string.editor_count_value, config.tileCount)
        )
    }

    Spacer(Modifier.height(Spacing.Item))
    LabeledSlider(
        label = stringResource(R.string.editor_layout_rotation),
        value = config.rotationDegrees,
        onValueChange = viewModel::updateImageRotation,
        valueRange = -90f..90f,
        valueText = "${config.rotationDegrees.roundToInt()}°"
    )
    LabeledSlider(
        label = stringResource(R.string.editor_layout_alpha),
        value = config.alpha,
        onValueChange = viewModel::updateImageAlpha,
        // 下限引用常量，理由同上方文字透明度滑块
        valueRange = WatermarkRenderer.MIN_ALPHA..1f,
        valueText = "${(config.alpha * 100).roundToInt()}%"
    )

    // ---- 4. 位置与边距（仅单个模式）----
    if (config.layoutMode == LayoutMode.SINGLE) {
        PanelDivider()
        SectionLabel(stringResource(R.string.editor_gravity_section))
        GravityGrid(
            selectedIndex = config.gravity.toIndex(),
            onSelect = { viewModel.updateImageGravity(gravityFromIndex(it)) }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (config.gravity == GravityPosition.CUSTOM) {
                stringResource(R.string.editor_position_custom)
            } else {
                stringResource(R.string.editor_drag_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = stringResource(R.string.editor_margin),
            value = config.margin,
            onValueChange = viewModel::updateImageMargin,
            valueRange = 0f..100f,
            valueText = "${config.margin.roundToInt()} px"
        )
    }
}
