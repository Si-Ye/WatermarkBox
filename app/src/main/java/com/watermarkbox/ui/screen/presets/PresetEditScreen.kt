package com.watermarkbox.ui.screen.presets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.R
import com.watermarkbox.ui.screen.editor.WatermarkPanel
import com.watermarkbox.ui.theme.Spacing

/**
 * 预设参数编辑页（**无图片预览**）。
 *
 * ## 这个页面解决什么问题
 *
 * 预设原本只能「在编辑页调好参数 → 存下来」，存完就改不动了，
 * 想微调某个参数只能重新找一张图、重新调一遍。本页让预设成为
 * **可独立维护的配置对象**。
 *
 * ## 为什么复用编辑页的面板
 *
 * 参数面板（[WatermarkPanel]）通过 [com.watermarkbox.ui.screen.editor.WatermarkPanelHost]
 * 与 [com.watermarkbox.ui.screen.editor.WatermarkPanelState] 与宿主解耦，
 * 因此本页能用**完全相同的参数 UI** —— 参数项永远一致，
 * 加新参数只需改面板一处。
 *
 * ## 灰色占位画布
 *
 * 顶部是一块 16:9 的浅灰画布，**不是真实图片**。它的作用：
 *
 * - 让用户直观看到水印位于画面的哪个位置（九宫格选择的结果立即可见）
 * - 顺带展示字号 / 颜色 / 旋转 / 透明度 / 平铺等效果
 *
 * 用占位而不是真图，是因为预设本来就与图片无关 —— 硬塞一张示例图
 * 反而会让人误以为"预设绑定到那张图"。
 *
 * ## 本页不提供的能力
 *
 * | 能力 | 为什么没有 |
 * |---|---|
 * | 选 logo 图 | 预设里的 logo 是内嵌位图，没有可选文件 |
 * | 隐藏水印 | 属于导出选项，预设不存它 |
 * | EXIF 保留 | 同上 |
 *
 * @param presetId 要编辑的预设 id
 * @param onBack   返回（保存成功后也走它）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    presetId: String,
    viewModel: PresetEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 首次进入时载入预设（ViewModel 内部保证只生效一次）
    LaunchedEffect(presetId) { viewModel.loadPreset(presetId) }

    // 保存成功 → 退出
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    // 提示
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        // NAME_CLASH 是给 UI 的信号（弹确认框），不当作提示文字
        if (msg != NAME_CLASH) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // 重名确认框
    var showClashDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        if (state.message == NAME_CLASH) {
            showClashDialog = true
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_edit_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = state.loaded
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.preset_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (!state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.preset_edit_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // ============ 名称 ============
            Column(modifier = Modifier.padding(horizontal = Spacing.PageHorizontal)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_name_label)) }
                )
                Spacer(Modifier.height(Spacing.Item))
                WatermarkPreviewCanvas(state)

                // logo 说明（仅图片水印时显示）
                if (state.watermarkType == com.watermarkbox.data.model.WatermarkType.IMAGE) {
                    Spacer(Modifier.height(Spacing.Item))
                    LogoNotice()
                }
            }

            Spacer(Modifier.height(Spacing.Item))

            // ============ 参数面板（与编辑页同一个组件）============
            WatermarkPanel(
                state = state.toPanelState(),
                viewModel = viewModel,
                // 本页不提供选 logo / 选字体文件的能力，点击给个说明
                onPickLogo = { },
                onPickFont = { },
                // 外层 Column 已经整页滚动，这里必须关掉面板的内部滚动，
                // 否则嵌套滚动会抛「无限高度约束」异常。详见该参数文档。
                scrollable = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // ---- 重名确认 ----
    if (showClashDialog) {
        val name = state.name.trim()
        AlertDialog(
            onDismissRequest = { showClashDialog = false },
            title = { Text(stringResource(R.string.preset_clash_title)) },
            text = {
                Text(
                    stringResource(R.string.preset_clash_message, name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClashDialog = false
                        viewModel.save(overwriteSameName = true)
                    }
                ) {
                    Text(stringResource(R.string.preset_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClashDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 重名信号的哨兵值（ViewModel → UI）。 */
private const val NAME_CLASH = "NAME_CLASH"

/**
 * 灰色占位画布：16:9 浅灰底 + 按参数绘制的水印示意。
 *
 * 不是真实图片，只用来让「位置 / 字号 / 颜色 / 旋转 / 透明度 / 平铺」
 * 这些参数的变化**看得见**。见 [PresetEditScreen] 的说明。
 */
@Composable
private fun WatermarkPreviewCanvas(state: PresetEditUiState) {
    val config = state.config
    val type = state.watermarkType

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(CanvasBackground)
            .border(1.dp, CanvasBorder, RoundedCornerShape(12.dp))
    ) {
        if (type == com.watermarkbox.data.model.WatermarkType.TEXT) {
            TextPreview(config)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.preset_edit_image_logo_fixed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 角落标注，避免被误认为真实预览
        Text(
            text = stringResource(R.string.preset_edit_preview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        )
    }
}

/**
 * 在画布上绘制文字水印示意。
 *
 * 用 [Canvas] + `nativeCanvas.drawText` 而不是 Compose 的 `Text`，
 * 是为了**能直接应用旋转与平铺** —— 用 `Text` 组件做这些要嵌套
 * 多层 graphicsLayer，不如直接画来得直接、也更接近真实渲染路径。
 */
@Composable
private fun TextPreview(config: com.watermarkbox.data.model.TextWatermarkStyle) {
    val text = config.text.ifBlank { stringResource(R.string.preset_edit_empty_text) }
    val color = config.color.color
    val alpha = config.alpha.coerceIn(0f, 1f)
    val rotation = config.rotationDegrees
    val tiled = config.layoutMode == com.watermarkbox.data.model.LayoutMode.TILED
    val tileCount = config.tileCount.coerceAtLeast(1)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val density = this.density
        // 字号：配置里是"相对图片宽度的百分比"，这里按画布宽度换算
        val px = config.fontSize / 100f * size.width
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = px
            this.color = color.toArgbCompat()
            this.alpha = (alpha * 255).toInt()
            if (config.bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (config.shadowEnabled) {
                setShadowLayer(config.shadowRadius * density, 0f, 0f, android.graphics.Color.BLACK)
            }
            if (config.strokeEnabled) {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = config.strokeWidth * density
                this.color = config.strokeColor.color.toArgbCompat()
            }
        }
        val drawPaint = android.graphics.Paint(paint).apply {
            // 描边优先展示描边色，否则用填充色
            if (!config.strokeEnabled) {
                style = android.graphics.Paint.Style.FILL
                this.color = color.toArgbCompat()
            }
            this.alpha = (alpha * 255).toInt()
        }

        val native = drawContext.canvas.nativeCanvas
        native.save()
        native.rotate(rotation, size.width / 2f, size.height / 2f)

        if (tiled) {
            // 平铺：按 tileCount 铺满，示意效果
            val step = size.width / tileCount
            var y = step / 2f
            while (y < size.height + step) {
                var x = 0f
                while (x < size.width) {
                    native.drawText(text, x, y, drawPaint)
                    x += step
                }
                y += step
            }
        } else {
            // 单个：按 gravity 决定锚点
            val tw = drawPaint.measureText(text)
            val fm = drawPaint.fontMetrics
            val th = fm.descent - fm.ascent
            val marginPx = config.margin * density
            val x: Float
            val y: Float
            when (config.gravity) {
                com.watermarkbox.data.model.GravityPosition.TOP_LEFT -> { x = marginPx; y = marginPx - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.TOP_CENTER -> { x = (size.width - tw) / 2f; y = marginPx - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.TOP_RIGHT -> { x = size.width - tw - marginPx; y = marginPx - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.CENTER_LEFT -> { x = marginPx; y = (size.height - th) / 2f - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.CENTER -> { x = (size.width - tw) / 2f; y = (size.height - th) / 2f - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.CENTER_RIGHT -> { x = size.width - tw - marginPx; y = (size.height - th) / 2f - fm.ascent }
                com.watermarkbox.data.model.GravityPosition.BOTTOM_LEFT -> { x = marginPx; y = size.height - marginPx - fm.descent }
                com.watermarkbox.data.model.GravityPosition.BOTTOM_CENTER -> { x = (size.width - tw) / 2f; y = size.height - marginPx - fm.descent }
                com.watermarkbox.data.model.GravityPosition.BOTTOM_RIGHT -> { x = size.width - tw - marginPx; y = size.height - marginPx - fm.descent }
            }
            native.drawText(text, x, y, drawPaint)
        }
        native.restore()

        // 画布边框（轻微，提示这是示意）
        drawRect(
            color = CanvasBorder,
            style = Stroke(width = 1f),
            topLeft = Offset.Zero,
            size = androidx.compose.ui.geometry.Size(size.width, size.height)
        )
    }
}

/** 图片水印在预设页的限制说明。 */
@Composable
private fun LogoNotice() {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.width(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.preset_edit_logo_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** 画布底色（浅灰，区别于真实图片）。 */
private val CanvasBackground = Color(0xFFEDEDED)

/** 画布边框色。 */
private val CanvasBorder = Color(0xFFD0D0D0)

/** Compose Color → ARGB Int（避免每处都写 import）。 */
private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
