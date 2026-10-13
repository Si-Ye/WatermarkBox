package com.watermarkbox.ui.screen.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.R
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.DraggableBottomPanel
import com.watermarkbox.ui.component.PanelDragBehavior
import com.watermarkbox.ui.component.PanelDragHandle
import com.watermarkbox.ui.component.ShrinkablePreviewArea
import com.watermarkbox.ui.component.WatermarkPreview
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.resolve

/**
 * 编辑与预览页（页面 2 - 核心页面）。
 *
 * 布局（两段式拖拽）：
 * ```
 * 面板占比:   min ──────── shrinkEnd ──────── 1.0
 * 预览行为:   ├─ 阶段一：预览跟着缩小 ─┤├─ 阶段二：预览冻结、面板遮上来 ─┤
 * ```
 * - 阶段一：面板变高多少，预览等比缩小多少，预览始终完整可见
 * - 阶段二：预览尺寸冻结，面板继续变高并覆盖在预览之上，最终全屏
 *
 * 顶栏：预设、导出设置；FAB：导出
 */
private val PANEL_BEHAVIOR = PanelDragBehavior(
    minFraction = 0.20f,
    maxFraction = 1f,
    shrinkEndFraction = 0.70f   // 面板超过屏幕 70% 后预览冻结、面板覆盖
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onPickLogo: () -> Unit,
    onPickFont: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 对话框显示状态
    var showExportDialog by remember { mutableStateOf(false) }
    // 右下角「导出」键的参数框：与上面那个（全局设置）**刻意分开**，
    // 因为两者语义相反 —— 上面写全局，这个只管这一次
    var showExportParams by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }

    // 错误 / 提示消息
    // 消息存的是资源 ID，在这里（Composable 上下文）先解析好文本，
    // 再交给 LaunchedEffect —— 挂起块里不能调用 @Composable 函数。
    val errorText = state.errorMessage?.resolve()
    val infoText = state.infoMessage?.resolve()

    LaunchedEffect(state.errorMessage) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.infoMessage) {
        infoText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.editor_screen_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // 预设入口
                    IconButton(onClick = { showPresetDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = stringResource(R.string.editor_panel_presets)
                        )
                    }
                    // 导出设置入口
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.batch_export_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 先弹参数框，确认后才真正导出 ——
                // 框里改的东西只对这一次生效，不污染全局默认值
                onClick = { showExportParams = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                icon = {
                    if (state.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        stringResource(
                            if (state.isExporting) R.string.editor_exporting
                            else R.string.editor_export
                        )
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---- 预览区：阶段一跟着缩小，阶段二冻结 ----
            ShrinkablePreviewArea(
                fraction = state.panelFraction,
                behavior = PANEL_BEHAVIOR,
                modifier = Modifier.fillMaxSize()
            ) { previewModifier ->
                WatermarkPreview(
                    bitmap = state.previewBitmap,
                    watermarkType = state.watermarkType,
                    textConfig = state.config,
                    imageConfig = state.imageConfig,
                    logoBitmap = state.logoBitmap,
                    sourceFileName = state.sourceFileName,
                    isLoading = state.isLoading,
                    // 原图尺寸：预览的水印缩放基准必须与导出一致，
                    // 否则「预览里调好的大小，导出后完全不同」
                    sourceWidth = state.sourceWidth,
                    sourceHeight = state.sourceHeight,
                    modifier = previewModifier,
                    // 拖动水印改位置（仅单个模式生效，组件内部会判断）
                    onWatermarkDrag = { x, y ->
                        when (state.watermarkType) {
                            WatermarkType.TEXT -> viewModel.updateCustomPosition(x, y)
                            WatermarkType.IMAGE -> viewModel.updateImageCustomPosition(x, y)
                        }
                    }
                )
            }

            // ---- 可拖拽参数面板（覆盖层）----
            DraggableBottomPanel(
                fraction = state.panelFraction,
                onFractionChange = viewModel::updatePanelFraction,
                behavior = PANEL_BEHAVIOR,
                dragHandle = {
                    PanelDragHandle(
                        expanded = state.panelExpanded,
                        overlaying = PANEL_BEHAVIOR.isOverlaying(state.panelFraction),
                        onToggle = viewModel::togglePanel
                    )
                }
            ) {
                WatermarkPanel(
                    state = state.toPanelState(),
                    viewModel = viewModel,
                    onPickLogo = onPickLogo,
                    onPickFont = onPickFont
                )
            }

            // ---- 导出进行中：一张能看见「在做什么」的进度卡 ----
            //
            // 为什么要有它：大图导出要经历「解码 → 渲染 → 嵌隐藏水印 → 写文件」，
            // 全程可能好几秒；以前只有按钮文字在变，观感就是「卡住了」。
            //
            // 入场/退场用淡入 + 轻微放大（160/120ms）：够短，只为说明「它出现了」。
            AnimatedVisibility(
                visible = state.isExporting,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.96f, animationSpec = tween(160)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.98f, animationSpec = tween(120)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                ExportProgressCard(stage = state.exportStage)
            }
        }
    }

    // ---- 全局导出设置（右上角 Tune）----
    //
    // 这里改的是**默认值**：确认后写入全局，之后每次导出都用它。
    // 刻意**不触发导出** —— 「调设置」和「现在就导」是两回事，
    // 混在一起会让用户想在导出前改一下参数时，被迫先导一次。
    if (showExportDialog) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            title = stringResource(R.string.editor_export_global_title),
            // 明确"这是全局"：确认按钮 + 顶部作用域说明卡
            confirmText = stringResource(R.string.export_settings_global_confirm),
            scopeHint = stringResource(R.string.export_scope_global),
            persistentScope = true,
            onConfirm = {
                viewModel.updateExportSettings(it)
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false }
        )
    }

    // ---- 本次导出参数（右下角导出键）----
    //
    // 与上面相反：确认后**只用于这一次导出**，不动全局默认值。
    // 初始值仍是全局设置 —— 用户想微调一下就直接改，不想改就直接确认。
    if (showExportParams) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            title = stringResource(R.string.editor_export_title),
            confirmText = stringResource(R.string.editor_export_start),
            scopeHint = stringResource(R.string.export_scope_once),
            persistentScope = false,
            onConfirm = { temp ->
                showExportParams = false
                viewModel.export(temp)
            },
            onDismiss = { showExportParams = false }
        )
    }

    // ---- 预设对话框 ----
    if (showPresetDialog) {
        PresetDialog(
            presets = state.presets,
            onSaveCurrent = { name, overwrite ->
                viewModel.saveCurrentAsPreset(name, overwrite)
            },
            onApply = { preset ->
                viewModel.applyPreset(preset)
                showPresetDialog = false
            },
            onDelete = viewModel::deletePreset,
            onDismiss = { showPresetDialog = false }
        )
    }
}