package com.watermarkbox.ui.screen.batch

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.R
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.DraggableBottomPanel
import com.watermarkbox.ui.component.PanelDragBehavior
import com.watermarkbox.ui.component.PanelDragHandle
import com.watermarkbox.ui.component.ShrinkablePreviewArea
import com.watermarkbox.ui.component.WatermarkPreview
import com.watermarkbox.ui.screen.editor.ExportSettingsDialog
import com.watermarkbox.ui.screen.editor.PresetDialog
import com.watermarkbox.ui.screen.editor.WatermarkPanel
import com.watermarkbox.ui.screen.editor.WatermarkPanelState
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.worker.BatchFailureReason
import kotlinx.coroutines.launch

/**
 * 面板拖拽的两段式行为，与编辑页保持一致
 * （`EditorScreen.PANEL_BEHAVIOR`）。
 *
 * - 阶段一（≤70%）：面板变高多少，预览等比缩小多少
 * - 阶段二（>70%）：预览尺寸冻结，面板继续变高并覆盖在其上
 */
private val PANEL_BEHAVIOR = PanelDragBehavior(
    minFraction = 0.20f,
    maxFraction = 1f,
    shrinkEndFraction = 0.70f
)

/**
 * 批量处理页（v1.4.0 重构）。
 *
 * ## 现在的形态：和单张编辑页基本一致
 *
 * ```
 * ┌────────────────────────────────┐
 * │ ← 批量处理（3/50）    [导出设置] │  顶栏
 * ├────────────────────────────────┤
 * │                                │
 * │        ┌──────────┐            │
 * │        │  预览图   │   ← 左右滑  │  预览区
 * │        │ (带水印)  │     切换   │
 * │        └──────────┘            │
 * │         ● ○ ○  ⊞               │  页码点 + 缩略图行
 * ├────────────────────────────────┤
 * │  文字水印 | 图片水印             │
 * │  水印文字 [____]                │  参数面板
 * │  字号 ──●────  颜色 ●●●         │  （与单张共用同一个组件）
 * │  隐藏水印 ▢  DCT ▢              │
 * ├────────────────────────────────┤
 * │  [═════ 60% ═════]  [开始]     │  执行条
 * └────────────────────────────────┘
 * ```
 *
 * ## 两条关键设计
 *
 * ### 1. 参数由所有图片**共用**
 *
 * 滑动只改变「用哪张图看效果」，**不改变任何参数**。
 * 这是批量的语义：一次设置，全部套用。
 * 若做成每张独立，那和逐张处理没区别，还会让人误以为设置会丢失。
 *
 * ### 2. 末尾追加「添加更多」页
 *
 * 分页总数 = 图片数 + 1。最后一页不是图片，是一个加号卡片，
 * 点它唤起系统图片选择器追加。这样"添加"和"浏览"在同一个手势里，
 * 不用回到顶部找按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConfigScreen(
    viewModel: BatchViewModel,
    onBack: () -> Unit,
    onPickLogo: () -> Unit,
    modifier: Modifier = Modifier,
    onAddMore: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showExportDialog by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    // 用于缩略图点击跳页（animateScrollToPage 是挂起函数）
    val scope = rememberCoroutineScope()

    // 处理完成后自动弹出统计
    LaunchedEffect(state.finished) {
        if (state.finished) showSummary = true
    }

    // 首次进入加载第一张预览
    LaunchedEffect(Unit) { viewModel.ensurePreviewLoaded() }

    // 分页状态：总数 = 图片数 + 1（末尾「添加更多」）
    val pageCount = state.pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = state.previewIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )

    // 滑动落位 → 通知 ViewModel 换预览图
    LaunchedEffect(pagerState, state.images.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setPreviewIndex(page)
        }
    }

    // 图片被删/追加后页面数变了，把 pager 拉回合法位置
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage(pageCount - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // 与单张编辑页保持完全一致：居中标题 + 右上两个图标
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(
                        R.string.batch_title,
                        state.images.size,
                        BatchViewModel.MAX_BATCH_SIZE
                    ),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // 预设入口（与单张同图标、同位置）
                    IconButton(onClick = { showPresetDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = stringResource(R.string.batch_presets)
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
        }
    ) { innerPadding ->
        // 整页作为一个 Box：预览铺满，参数面板覆盖在上面（与编辑页同构）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ============ 预览区：阶段一跟着缩小，阶段二冻结 ============
            ShrinkablePreviewArea(
                fraction = state.panelFraction,
                behavior = PANEL_BEHAVIOR,
                modifier = Modifier.fillMaxSize()
            ) { previewModifier ->
                BatchPreviewPager(
                    state = state,
                    pagerState = pagerState,
                    pagerCoroutineScope = scope,
                    onAddMore = onAddMore,
                    onRemove = { viewModel.removeImage(it) },
                    modifier = previewModifier
                )
            }

            // ============ 可拖拽参数面板（覆盖层，与编辑页同款）============
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
                    state = WatermarkPanelState(
                        watermarkType = state.watermarkType,
                        config = state.textStyle,
                        imageConfig = state.imageStyle,
                        logoBitmap = state.logoBitmap,
                        customFonts = state.customFonts,
                        invisibleConfig = state.invisibleConfig,
                        showInvisibleSection = true
                    ),
                    viewModel = viewModel,
                    onPickLogo = onPickLogo,
                    onPickFont = { }
                )
            }

            // ============ 底部执行条（浮在最上层）============
            BatchActionBar(
                state = state,
                onStart = viewModel::startProcessing,
                onCancel = viewModel::cancelProcessing,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // ---- 导出设置对话框 ----
    if (showExportDialog) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            // 批量页这个入口的语义是「设全局默认导出参数」（与编辑页右上角一致）
            onConfirm = {
                viewModel.updateExportSettings(it)
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false }
        )
    }

    // ---- 预设对话框（与编辑页同一个组件，共用同一份数据）----
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

    // ---- 完成统计 ----
    if (showSummary) {
        BatchSummaryDialog(
            success = state.successCount,
            fail = state.failCount,
            failureReasons = state.failureReasons,
            onDismiss = {
                showSummary = false
                viewModel.resetProcessing()
            }
        )
    }
}

/**
 * 批量预览区分页器。
 *
 * 每页两种形态：
 * - **图片页**（index < images.size）：实时渲染带水印的预览 + 右下角删除
 * - **追加页**（index == images.size）：一个加号卡片，点击唤起选图
 *
 * 下方有页码指示行（含缩略图），点缩略图可直接跳页。
 */
@Composable
private fun BatchPreviewPager(
    state: BatchUiState,
    pagerState: androidx.compose.foundation.pager.PagerState,
    pagerCoroutineScope: kotlinx.coroutines.CoroutineScope,
    onAddMore: () -> Unit,
    onRemove: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 预览高度：填满剩余空间（不写死），这样面板拖动时预览能跟着缩放
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page < state.images.size) {
                    // ---- 图片页：复用编辑页的预览组件 ----
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        // 只有当前页才用已加载的 bitmap，其它页显示占位，
                        // 避免横向预加载时把多张大图同时留在内存里。
                        val isCurrent = page == state.previewIndex
                        WatermarkPreview(
                            bitmap = if (isCurrent) state.previewBitmap else null,
                            watermarkType = state.watermarkType,
                            textConfig = state.textStyle,
                            imageConfig = state.imageStyle,
                            logoBitmap = state.logoBitmap,
                            // 批量页的 {filename} 占位符在导出时才解析，
                            // 预览时不假装知道文件名（传 null 让它显示占位符原文）
                            sourceFileName = null,
                            isLoading = isCurrent && state.previewLoading,
                            // 关掉单指平移，把手势让给外层 HorizontalPager 切图。
                            // 详见该参数文档。
                            panEnabled = false,
                            // ⚠️ 关键：**只有当前页参与渲染**。
                            //
                            // pager 会把相邻页也短暂组合起来，若它们也渲染：
                            // - 每个实例一套三缓冲 + 渲染引擎
                            // - 拖参数时全部重组、全部触发渲染
                            // - 但相邻页 bitmap 为 null，渲染结果根本用不到
                            //
                            // 实测（2048×1536 × 3 页）：submit 579 次、
                            // result 仅 143 次 —— 大量渲染被丢弃，
                            // 抢走当前页的 CPU/内存 → 当前页结果延迟上屏
                            // → 表现就是「批量页预览不实时」。
                            enabled = isCurrent,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isCurrent && state.previewLoading) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }

                        // 删除当前图
                        IconButton(
                            onClick = { state.images.getOrNull(page)?.let(onRemove) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.batch_remove_one),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    AddMorePage(onClick = onAddMore)
                }
            }
        }

        // ---- 缩略图指示行（可点跳页）----
        ThumbnailStrip(
            images = state.images,
            currentIndex = state.previewIndex,
            onSelect = { index ->
                pagerCoroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
            onAddMore = {
                pagerCoroutineScope.launch {
                    pagerState.animateScrollToPage(state.images.size)
                }
            }
        )
    }
}

/**
 * 末尾的「添加更多」页。
 *
 * 刻意做成一个大号虚线感卡片而不是小按钮 —— 它是这一页的**唯一内容**，
 * 在滑动语境下要让人一眼看出"这里还有一页，点它加图"。
 */
@Composable
private fun AddMorePage(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .clickable(onClick = onClick)
                .padding(horizontal = 40.dp, vertical = 36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.batch_add_more),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.batch_add_more_limit, BatchViewModel.MAX_BATCH_SIZE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 缩略图指示行。
 *
 * 用可横向滚动的缩略图而不是圆点：批量常有十几张，
 * 圆点数量一多就看不出"当前在哪"，缩略图则一眼可辨。
 */
@Composable
private fun ThumbnailStrip(
    images: List<Uri>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onAddMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.PageHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        images.forEachIndexed { index, _ ->
            val selected = index == currentIndex
            // 无障碍：圆点没有任何文字，必须自己说明「这是第几张」；
            // 另外它视觉上只有 20dp，用 minimumInteractiveComponentSize 把**触摸目标**
            // 撑到 48dp —— 手部不便的用户才点得中（视觉大小不变）
            val pageLabel = stringResource(R.string.a11y_page_dot, index + 1)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(if (selected) 26.dp else 20.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = pageLabel
                        this.selected = selected
                    }
                    .clickable { onSelect(index) }
            )
        }
        // 末尾的「添加更多」指示点：当前停在追加页时高亮
        val addMoreLabel = stringResource(R.string.batch_add_more)
        val onAddMorePage = currentIndex >= images.size
        Box(
            modifier = Modifier
                // 同上一处：把触摸目标撑到 48dp（视觉仍是小圆点）
                .minimumInteractiveComponentSize()
                .size(if (onAddMorePage) 26.dp else 20.dp)
                .clip(CircleShape)
                .background(
                    if (onAddMorePage) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
                .semantics {
                    role = Role.Button
                    contentDescription = addMoreLabel
                    this.selected = onAddMorePage
                }
                .clickable(onClick = onAddMore),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = stringResource(R.string.batch_jump_add_more),
                tint = if (currentIndex >= images.size) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * 底部执行条：进度 + 开始/取消。
 *
 * 处理中显示进度条与「取消」，空闲时只显示「开始处理」。
 */
@Composable
private fun BatchActionBar(
    state: BatchUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.PageHorizontal, vertical = 10.dp)
    ) {
        if (state.isProcessing) {
            // ---- 处理中：进度 + 取消 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${state.done}/${state.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        } else {
            Button(
                onClick = onStart,
                enabled = state.images.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.batch_start, state.images.size))
            }
        }
    }
}

/** 失败原因 → 文案资源。 */
private fun BatchFailureReason.labelRes(): Int = when (this) {
    BatchFailureReason.SOURCE_TOO_LARGE -> R.string.batch_fail_reason_too_large
    BatchFailureReason.SOURCE_UNREADABLE -> R.string.batch_fail_reason_unreadable
    BatchFailureReason.SAVE_FAILED -> R.string.batch_fail_reason_save_failed
    BatchFailureReason.UNKNOWN -> R.string.batch_fail_reason_unknown
}

/**
 * 完成统计对话框。
 *
 * ## 为什么要把「失败原因」列出来
 *
 * 只说「失败 3 张」用户什么都做不了：不知道该缩小图片、换一张图，
 * 还是清一下存储空间。按原因分组之后，每一行都直接对应一个可采取的动作。
 *
 * 原因顺序是固定的（图太大 → 读不出 → 写入失败 → 其它），
 * 不按数量排序 —— 顺序稳定，用户第二次看不用重新找。
 */
@Composable
private fun BatchSummaryDialog(
    success: Int,
    fail: Int,
    failureReasons: Map<BatchFailureReason, Int>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_done_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.batch_success_count, success),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (fail > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.batch_fail_count, fail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    // 失败明细：按原因分组，每行都对应一个可采取的动作
                    if (failureReasons.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.batch_fail_reason_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        failureReasons.forEach { (reason, count) ->
                            Text(
                                text = stringResource(
                                    R.string.batch_fail_reason_item,
                                    stringResource(reason.labelRes()),
                                    count
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.batch_saved_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.batch_ok)) }
        }
    )
}
