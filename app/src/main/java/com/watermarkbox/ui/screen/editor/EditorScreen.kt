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
                onClick = { viewModel.export() },
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
                    modifier = previewModifier
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
        }
    }

    // ---- 导出设置对话框 ----
    if (showExportDialog) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            onSettingsChange = viewModel::updateExportSettings,
            onConfirm = {
                showExportDialog = false
                viewModel.export()
            },
            onDismiss = { showExportDialog = false }
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