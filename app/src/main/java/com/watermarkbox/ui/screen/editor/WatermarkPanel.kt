package com.watermarkbox.ui.screen.editor

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.ColorPalette
import com.watermarkbox.ui.component.GravityGrid
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.ui.theme.WatermarkPresetColors
import kotlin.math.roundToInt

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

/**
 * 隐藏水印配置区。
 *
 * 隐藏水印与「可见水印」互不影响：它不会改变画面外观，
 * 而是把内容编码后同时写进 EXIF 元数据与像素频域，
 * 用于日后溯源。详见 [com.watermarkbox.invisible.InvisibleWatermarkEngine]。
 */
@Composable
private fun InvisibleWatermarkSection(
    state: WatermarkPanelState,
    viewModel: WatermarkPanelHost
) {
    val config = state.invisibleConfig

    SectionLabel(stringResource(R.string.editor_invisible_title))

    ToggleRow(stringResource(R.string.editor_invisible_enable), config.enabled) {
        viewModel.toggleInvisibleWatermark()
    }

    if (!config.enabled) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.editor_invisible_disabled_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Spacer(Modifier.height(Spacing.Item))

    // ---- 内容来源 ----
    Text(
        text = stringResource(R.string.editor_invisible_source),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    SegmentedControl(
        options = listOf(
            InvisibleSource.CUSTOM_TEXT to stringResource(R.string.editor_invisible_source_custom),
            InvisibleSource.DEVICE_AND_TIME to stringResource(R.string.editor_invisible_source_device)
        ),
        selected = config.source,
        onSelect = viewModel::updateInvisibleSource
    )

    // ---- 自定义文字输入 ----
    if (config.source == InvisibleSource.CUSTOM_TEXT) {
        Spacer(Modifier.height(Spacing.Item))
        OutlinedTextField(
            value = config.content,
            onValueChange = viewModel::updateInvisibleContent,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.editor_invisible_content)) },
            placeholder = { Text(stringResource(R.string.editor_invisible_content_placeholder)) },
            supportingText = {
                Text(
                    stringResource(R.string.editor_invisible_content_limit),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        )
    } else {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_invisible_auto_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ---- 强度 ----
    Spacer(Modifier.height(Spacing.Item))
    LabeledSlider(
        label = stringResource(R.string.editor_invisible_strength),
        value = config.strength.toFloat(),
        onValueChange = { viewModel.updateInvisibleStrength(it.roundToInt()) },
        valueRange = 1f..4f,
        valueText = stringResource(R.string.editor_invisible_strength_value, config.strength)
    )
    Text(
        text = stringResource(R.string.editor_invisible_strength_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // ---- DCT 频域开关 ----
    Spacer(Modifier.height(Spacing.Item))
    DctToggle(config = config, viewModel = viewModel)
}

/**
 * DCT 频域嵌入开关 + 画质提示。
 *
 * ## 为什么要把代价写在界面上
 *
 * 隐藏水印有两条通道，行为差异很大：
 *
 * | 通道 | 画质影响 | 抗 JPEG 压缩 |
 * |---|---|---|
 * | EXIF 元数据 | **完全无损** | ❌ 一压缩就没 |
 * | DCT 频域 | 亮度中频被扰动（±3/255） | ✅ 抗重压缩 |
 *
 * 这是典型的「画质 ⇄ 耐久」取舍，**必须让用户知情后再决定**，
 * 不能替他默认勾上。所以这里用醒目的提示卡说明代价，
 * 而不是塞进一行小字里糊弄过去。
 */
@Composable
private fun DctToggle(
    config: com.watermarkbox.data.model.InvisibleWatermarkConfig,
    viewModel: WatermarkPanelHost
) {
    ToggleRow(
        label = stringResource(R.string.editor_invisible_dct),
        checked = config.dctEnabled,
        onCheckedChange = { viewModel.toggleInvisibleDct() }
    )

    Spacer(Modifier.height(6.dp))

    // 开 / 关给不同颜色的说明卡，让代价与收益都一眼可见
    val (container, content) = if (config.dctEnabled) {
        // 开启：橙黄警示 —— 有画质代价
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        // 关闭：中性灰 —— 无损但标注局限
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (config.dctEnabled) Icons.Filled.WarningAmber
                else Icons.Filled.Info,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (config.dctEnabled) {
                    stringResource(R.string.editor_invisible_dct_on)
                } else {
                    stringResource(R.string.editor_invisible_dct_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
        }
    }
}

// =====================================================================
// 文字水印参数区
// =====================================================================

@Composable
private fun TextWatermarkPanelContent(
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
                modifier = Modifier.height(32.dp),
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
            modifier = Modifier.height(32.dp),
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
        valueRange = 0.01f..1f,
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
    if (config.strokeEnabled) {
        LabeledSlider(
            label = stringResource(R.string.editor_stroke_width),
            value = config.strokeWidth,
            onValueChange = viewModel::updateStrokeWidth,
            valueRange = 0.5f..10f,
            valueText = String.format("%.1f px", config.strokeWidth)
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

    Spacer(Modifier.height(8.dp))
    ToggleRow(stringResource(R.string.editor_shadow), config.shadowEnabled) { viewModel.toggleShadow() }
    if (config.shadowEnabled) {
        LabeledSlider(
            label = stringResource(R.string.editor_shadow_radius),
            value = config.shadowRadius,
            onValueChange = viewModel::updateShadowRadius,
            valueRange = 0f..20f,
            valueText = String.format("%.1f px", config.shadowRadius)
        )
    }
}

// =====================================================================
// 图片水印参数区（阶段二新增）
// =====================================================================

@Composable
private fun ImageWatermarkPanelContent(
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
        valueRange = 0.01f..1f,
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

// =====================================================================
// 通用子组件
// =====================================================================

@Composable
private fun FontFamilySelector(
    selected: FontFamilyOption,
    onSelect: (FontFamilyOption) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FontFamilyOption.entries
            .filter { it != FontFamilyOption.CUSTOM }   // 自定义字体入口阶段四接入
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

@Composable
private fun PanelDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------------
// GravityPosition ↔ 九宫格 index 映射（0..8，行优先）
// ---------------------------------------------------------------------

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