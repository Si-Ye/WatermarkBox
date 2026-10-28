package com.watermarkbox.ui.screen.batch

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
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
import com.watermarkbox.data.model.BatchPositionOverrides
import com.watermarkbox.data.model.BatchPositionResolver
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.PositionScope
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.DraggableBottomPanel
import com.watermarkbox.ui.component.PanelDragBehavior
import com.watermarkbox.ui.component.PanelDragHandle
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ShrinkablePreviewArea
import com.watermarkbox.ui.component.WatermarkPreview
import com.watermarkbox.ui.screen.editor.ExportSettingsDialog
import com.watermarkbox.ui.screen.editor.PresetDialog
import com.watermarkbox.ui.screen.editor.WatermarkPanel
import com.watermarkbox.ui.screen.editor.WatermarkPanelState
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.ShareUtils
import com.watermarkbox.util.resolve
import com.watermarkbox.worker.BatchFailureReason
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 清单 CSV 文件名里的时间戳。
 *
 * 用固定 pattern 而不是本地化格式：文件名要跨设备可读、可排序，
 * 「2026年10月28日」这种带中文的格式在部分文件管理器上会被转义成乱码。
 */
private val CSV_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

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
    // 「开始」时弹的**本次导出**参数框（与顶栏齿轮的"全局默认"是两件事）
    var showExportParams by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    // 用于缩略图点击跳页（animateScrollToPage 是挂起函数）
    val scope = rememberCoroutineScope()

    // ---- 提示出口（Snackbar）----
    //
    // 批量页此前把 errorMessage 写进 state 却**没有任何地方显示**：
    // 「图片太大」「处理被中断」这类提示全部静默丢失，用户只看到一个
    // 不再变化的进度条，完全不知道发生了什么。这里补上统一出口 ——
    // 同时也给「清单已导出」这类成功反馈一个位置。
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.errorMessage?.resolve()
    val infoText = state.infoMessage?.resolve()
    LaunchedEffect(state.errorMessage) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.infoMessage) {
        if (infoText != null) {
            snackbarHostState.showSnackbar(infoText)
            viewModel.clearInfo()
        }
    }

    // ---- 清单导出（CSV）----
    //
    // 用 SAF（CreateDocument）而不是自己往某个固定目录写：
    // 不需要存储权限、位置由用户决定、重名由系统询问。
    // 与预设导出同一套思路，但 launcher 放在**用到它的这个页面**里 ——
    // 预设那份之所以挂在 Activity，是因为入口分散在多处。
    val csvFilePrefix = stringResource(R.string.batch_csv_file_prefix)
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) viewModel.exportCsv(uri) }

    // ---- 水印位置拖动（双击预览进入，点预览外退出）----
    //
    // 为什么需要这个"锁定"：
    // 预览区同时要响应「左右滑切图」和「拖动水印」——两者都是单指水平拖动，
    // 不设状态机就只能二选一。双击进入锁定态后关掉 pager 的滑动，
    // 手势才不会被两方抢。
    var positionLocked by rememberSaveable { mutableStateOf(false) }
    // 预览区在整页坐标系里的矩形：用来判断「这一下点在了预览外」
    // 「预览区域」的矩形，以及本页根布局在窗口里的原点。
    //
    // ⚠️ 两个都用**窗口坐标**，不能一个窗口一个局部：
    // 预览区深埋在 pager 的页面里，拿它的 boundsInParent 得到的是
    // 相对某层父容器的坐标，与根布局收到的触摸点不在同一套坐标里，
    // 判断必然错（最初就是用预览"层"的 boundsInParent，结果该层是
    // fillMaxSize，整个屏幕都被算成"预览内"，点哪都不解锁）。
    var previewBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // 换图即解锁 —— 锁定是「针对这张图构图」的临时状态，不该跨图残留
    LaunchedEffect(state.previewIndex) { positionLocked = false }

    // 「位置」这一组参数只在水印是**单个**时才有意义：
    // 平铺 / 对角线是铺满整张图的，没有"位置"可拖动、也没有"每张独立"的余地。
    val supportsPosition = when (state.watermarkType) {
        WatermarkType.TEXT -> state.textStyle.layoutMode == LayoutMode.SINGLE
        WatermarkType.IMAGE -> state.imageStyle.layoutMode == LayoutMode.SINGLE
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                // 锁定态下，点「预览区以外」的任何地方解锁。
                //
                // 用 Initial 阶段**只观察、不消费**：
                // 用户在锁定态去点按钮/滑面板时，那一下既该解锁、也该照常生效。
                // 若在这里消费事件，反而会让整个界面"先失灵一次"。
                .onGloballyPositioned { rootOrigin = it.positionInWindow() }
                .pointerInput(positionLocked, previewBounds, rootOrigin) {
                    if (!positionLocked) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.pressed }
                                ?: continue
                            // 触摸点是**本 Box 的局部坐标**，换算到窗口再比
                            val absolute = down.position + rootOrigin
                            if (!previewBounds.contains(absolute)) {
                                positionLocked = false
                                return@awaitPointerEventScope
                            }
                        }
                    }
                }
        ) {
            // ============ 预览区：阶段一跟着缩小，阶段二冻结 ============
            ShrinkablePreviewArea(
                fraction = state.panelFraction,
                behavior = PANEL_BEHAVIOR,
                modifier = Modifier
                    .fillMaxSize()
            ) { previewModifier ->
                BatchPreviewPager(
                    images = state.images,
                    previewIndex = state.previewIndex,
                    previewBitmap = state.previewBitmap,
                    previewLoading = state.previewLoading,
                    watermarkType = state.watermarkType,
                    textStyle = state.textStyle,
                    imageStyle = state.imageStyle,
                    logoBitmap = state.logoBitmap,
                    pagerState = pagerState,
                    pagerCoroutineScope = scope,
                    positions = state.positions,
                    positionLocked = positionLocked,
                    onLockChange = { positionLocked = it },
                    onDragText = viewModel::dragTextPosition,
                    onDragImage = viewModel::dragImagePosition,
                    onAddMore = onAddMore,
                    onRemove = { viewModel.removeImage(it) },
                    onPageBounds = { previewBounds = it },
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
                        onToggle = viewModel::togglePanel,
                        // 锁定拖动位置时，把「拖动调整高度」暂时换成退出提示 ——
                        // 这句原本浮在预览上，占画面还挡图；面板标题行本就常驻，
                        // 借它显示状态不额外占地方。解锁后自动恢复原文案。
                        hintRes = if (positionLocked) {
                            R.string.batch_position_locked
                        } else {
                            null
                        },
                        hintHighlighted = positionLocked
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
                    onPickFont = { },
                    // 位置作用域搬进参数面板（原先是浮在预览上的胶囊）。
                    // 只有「单个」布局才有位置可拖：平铺/对角线是铺满整图的。
                    positionScopeSection = if (supportsPosition) {
                        {
                            PositionScopeSection(
                                scope = state.positions.scope,
                                onScopeChange = viewModel::setPositionScope,
                                onReset = viewModel::resetCurrentPosition
                            )
                        }
                    } else {
                        null
                    }
                )
            }

            // ============ 底部执行条（浮在最上层）============
            BatchActionBar(
                state = state,
                // 先弹参数框，确认后才真正开始 ——
                // 与编辑页 FAB 完全同构：框里改的参数**只对这一次生效**，
                // 不污染全局默认值（全局默认仍由顶栏齿轮那个入口维护）。
                onStart = { showExportParams = true },
                onCancel = viewModel::cancelProcessing,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // ---- 本次导出的参数框（点「开始」时弹）----
    //
    // 与编辑页 `showExportParams` 同款语义：
    // - 初始值 = 全局设置，用户想微调就直接改，不想改就点确认
    // - 确认后**只用于这一次**，不动全局默认值
    //
    // 标题用「本次导出设置」而不是通用的「导出设置」——
    // 否则和顶栏齿轮那个框（全局）重名，用户分不清。
    if (showExportParams) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            title = stringResource(R.string.export_settings_once_title),
            confirmText = stringResource(R.string.editor_export_start),
            // 明确告诉用户：这里的改动**只对本次生效**
            scopeHint = stringResource(R.string.export_scope_once),
            persistentScope = false,
            onConfirm = { temp ->
                showExportParams = false
                viewModel.startProcessing(overrideSettings = temp)
            },
            onDismiss = { showExportParams = false }
        )
    }

    // ---- 全局默认导出设置（顶栏齿轮入口）----
    //
    // 与「开始」那个框**明确区分**：
    // - 标题：「全局导出设置」（不是通用的「导出设置」）
    // - 确认：「保存为默认」
    // - 顶部有作用域说明卡片
    //
    // 老大反馈过「齿轮功能没有改成全局设置」—— 功能一直是对的，
    // 但两个入口长得一模一样，用户根本分不清点「保存」会改什么。
    if (showExportDialog) {
        ExportSettingsDialog(
            settings = state.exportSettings,
            title = stringResource(R.string.editor_export_global_title),
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
        val context = LocalContext.current
        // ⚠️ 分享标题必须在**组合期**取好：`context.getString` 在
        // Composable 里取资源会被 lint 判为 LocalContextGetResourceValueCall
        // （它绕过了配置变更时的重组）。stringResource 才对。
        val shareTitle = stringResource(R.string.batch_share_chooser_title)
        BatchSummaryDialog(
            success = state.successCount,
            fail = state.failCount,
            failureReasons = state.failureReasons,
            canShare = state.successUris.isNotEmpty(),
            canRetry = state.failedItems.isNotEmpty(),
            canExportCsv = state.successUris.isNotEmpty() || state.failedItems.isNotEmpty(),
            onExportCsv = {
                csvLauncher.launch(
                    "${csvFilePrefix}_${LocalDateTime.now().format(CSV_TIMESTAMP)}.csv"
                )
            },
            onShare = {
                ShareUtils.shareImages(
                    context = context,
                    uris = state.successUris,
                    title = shareTitle
                )
            },
            onRetryFailed = {
                // 先关掉统计框：重跑会重新进入进度态
                showSummary = false
                viewModel.retryFailedItems()
            },
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
 *
 * ## ⚠️ 为什么接收**细粒度参数**而不是整个 `BatchUiState`（关键性能修复）
 *
 * 早期签名是 `BatchPreviewPager(state: BatchUiState, ...)`。
 * 问题：拖动字号/角度/透明度时，`state.textStyle` 每帧都变 →
 * 整个 `state` 引用变化 → **本函数（含内部的 `HorizontalPager`）整体重组**。
 *
 * `HorizontalPager` 的重组不便宜：它要重新测量、重新布局所有可见页，
 * 并确定可滚动范围。实测 0.5 秒内触发了 **485 次** `WatermarkPreview`
 * 的组合 —— 主线程被重组压垮，手感就是"卡"。
 *
 * （编辑页没有 pager，所以同样的参数变化只重组一个 `WatermarkPreview`，
 *   这就是"单张流畅、批量卡"的真正原因。）
 *
 * 拆成细粒度参数后，Compose 的智能重组才能生效：
 * 水印参数变化只会传给 `WatermarkPreview`，pager 本身不受影响。
 *
 * **注意**：`textStyle` / `imageStyle` 这类频繁变化的参数必须放在
 * **最后**，且不要在函数体内被 pager 的 scope 捕获 —— 见下方实现注释。
 */
@Composable
private fun BatchPreviewPager(
    images: List<Uri>,
    previewIndex: Int,
    previewBitmap: android.graphics.Bitmap?,
    previewLoading: Boolean,
    watermarkType: WatermarkType,
    textStyle: TextWatermarkStyle,
    imageStyle: ImageWatermarkStyle,
    logoBitmap: android.graphics.Bitmap?,
    pagerState: androidx.compose.foundation.pager.PagerState,
    pagerCoroutineScope: kotlinx.coroutines.CoroutineScope,
    positions: BatchPositionOverrides,
    positionLocked: Boolean,
    onLockChange: (Boolean) -> Unit,
    onDragText: (Float, Float) -> Unit,
    onDragImage: (Float, Float) -> Unit,
    onAddMore: () -> Unit,
    onRemove: (Uri) -> Unit,
    /** 回报当前页的矩形（窗口坐标）—— 供「点预览外解锁」判断用。 */
    onPageBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    // ---- 把「水印参数」包成 State，让 pager 内容延迟读取 ----
    //
    // 这是本函数性能的关键：
    // 直接捕获 `textStyle` 会让整个 lambda 随参数变化而失效；
    // 用 `rememberUpdatedState` 持有它，lambda 引用的只是一个**稳定的**
    // State 对象，重组时不会让 pager 的测量/布局失效。
    val watermarkState = rememberUpdatedState(
        WatermarkParams(watermarkType, textStyle, imageStyle, logoBitmap)
    )

    // 位置覆盖表同理，而且更要紧：拖动时它**每一帧都在变**。
    // 若被 pager 的内容 lambda 直接捕获，拖动就会变成"每帧重排整个 pager"。
    val positionsState = rememberUpdatedState(positions)
    val lockedState = rememberUpdatedState(positionLocked)
    val dragTextCallback = rememberUpdatedState(onDragText)
    val dragImageCallback = rememberUpdatedState(onDragImage)

    Column(modifier = modifier.fillMaxWidth()) {
        // 预览高度：填满剩余空间（不写死），这样面板拖动时预览能跟着缩放
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        ) {
            // ---- 为什么把 pager 的内容拆成独立 Composable（性能关键）----
            //
            // `HorizontalPager` 的 content lambda 会在**每一页**被调用，
            // 而它所在的作用域如果捕获了频繁变化的参数（如 textStyle），
            // 那么参数一变，pager 的整个内容区都要重新组合 + 重新测量。
            //
            // 这里把「页内容」放进单独的 `BatchPagerPage`，
            // 并把变化的水印参数通过 `watermarkState`（稳定的 State 引用）传入。
            // 这样参数变化时：
            // - pager 的测量/布局 ✅ 不受影响
            // - 只有真正用到参数的 `WatermarkPreview` 重组 ✅
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 锁定态禁止翻页：把水平拖动整个让给"拖水印"
                userScrollEnabled = !positionLocked
            ) { page ->
                BatchPagerPage(
                    page = page,
                    images = images,
                    previewIndex = previewIndex,
                    previewBitmap = previewBitmap,
                    previewLoading = previewLoading,
                    watermark = watermarkState,
                    positions = positionsState,
                    locked = lockedState,
                    onDragText = dragTextCallback,
                    onDragImage = dragImageCallback,
                    onLockChange = onLockChange,
                    onRemove = onRemove,
                    onAddMore = onAddMore,
                    onPageBounds = onPageBounds
                )
            }
        }
        // ---- 缩略图指示行（可点跳页）----
        ThumbnailStrip(
            images = images,
            currentIndex = previewIndex,
            onSelect = { index ->
                pagerCoroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
            onAddMore = {
                pagerCoroutineScope.launch {
                    pagerState.animateScrollToPage(images.size)
                }
            }
        )
    }
}

/**
 * 参数面板里的「水印位置」区块：作用域切换 + 恢复默认。
 *
 * ## 为什么从预览挪进面板
 *
 * 早先它浮在预览左上角，理由是"控件离动作近"。实际用下来是反的：
 * - 它压在图片上，正好是最该看清画面的地方；
 * - 它和"双击拖动"这条提示叠成两行胶囊，把预览顶部占掉一大块；
 * - 而它本质是**一组参数**（改的是导出行为），参数就该在参数面板里，
 *   和字号、颜色、透明度放在一起，用户找的时候会往那找。
 *
 * 锁定提示也因此搬去了面板标题行（见 [PanelDragHandle] 的 hintRes）。
 */
@Composable
private fun PositionScopeSection(
    scope: PositionScope,
    onScopeChange: (PositionScope) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.batch_position_section),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.batch_position_reset),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onReset)
                    .padding(horizontal = Spacing.Small, vertical = Spacing.Tiny)
                    // 小字按钮必须补足最小可点区域，否则在小屏上按不中
                    .minimumInteractiveComponentSize()
            )
        }
        Spacer(Modifier.height(Spacing.Tiny))
        SegmentedControl(
            options = listOf(
                PositionScope.UNIFIED to stringResource(R.string.batch_position_scope_unified),
                PositionScope.PER_IMAGE to stringResource(R.string.batch_position_scope_per_image)
            ),
            selected = scope,
            onSelect = onScopeChange
        )
        Spacer(Modifier.height(Spacing.Tiny))
        Text(
            text = stringResource(
                if (scope == PositionScope.UNIFIED) {
                    R.string.batch_position_scope_unified_desc
                } else {
                    R.string.batch_position_scope_per_image_desc
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 「怎么改位置」这条发现性说明原先也浮在预览上；随控件一起搬进来，
        // 预览区因此彻底不再有任何浮层胶囊。
        Text(
            text = stringResource(R.string.batch_position_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 预览参数包：把分散的水印参数聚成一个**可比较**的值对象。
 *
 * 用 `data class` 是为了让 `rememberUpdatedState` 的更新判断有值语义 ——
 * 参数没实际变化时（例如只是别的字段导致重组）不会产生新的对象。
 */
private data class WatermarkParams(
    val type: WatermarkType,
    val textStyle: TextWatermarkStyle,
    val imageStyle: ImageWatermarkStyle,
    val logo: android.graphics.Bitmap?
)

/**
 * pager 里的**单页**内容。
 *
 * 拆出来是为了让 `HorizontalPager` 的内容 lambda 保持"轻"：
 * 它只负责把稳定的 [watermark] State 透传下去，
 * 不直接捕获会频繁变化的参数值。
 *
 * @param watermark 水印参数的 State。
 *                  在**真正要渲染时**才 `.value` 读取 ——
 *                  这样参数变化只会让读取点重组，
 *                  不会让 pager 的测量/布局失效。
 */
@Composable
private fun BatchPagerPage(
    page: Int,
    images: List<Uri>,
    previewIndex: Int,
    previewBitmap: android.graphics.Bitmap?,
    previewLoading: Boolean,
    watermark: androidx.compose.runtime.State<WatermarkParams>,
    /** 每张图的位置覆盖（含作用域）—— 同样用 State 传递，理由见 [watermark]。 */
    positions: androidx.compose.runtime.State<BatchPositionOverrides>,
    /** 是否处于「拖动位置」锁定态。 */
    locked: androidx.compose.runtime.State<Boolean>,
    onDragText: androidx.compose.runtime.State<(Float, Float) -> Unit>,
    onDragImage: androidx.compose.runtime.State<(Float, Float) -> Unit>,
    onLockChange: (Boolean) -> Unit,
    onRemove: (Uri) -> Unit,
    onAddMore: () -> Unit,
    onPageBounds: (Rect) -> Unit
) {
    if (page >= images.size) {
        AddMorePage(onClick = onAddMore)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 报出本页矩形（窗口坐标）。
            // 这一层才是用户眼里的"预览区域"：它只有 pager 那么大，
            // 不含参数面板和顶部工具栏 —— 点后者才算"点到预览外面"。
            .onGloballyPositioned {
                if (page == previewIndex) onPageBounds(it.boundsInWindow())
            }
            .padding(8.dp)
    ) {
        // 只有当前页才用已加载的 bitmap，其它页显示占位，
        // 避免横向预加载时把多张大图同时留在内存里。
        val isCurrent = page == previewIndex
        val params = watermark.value
        val overrides = positions.value
        // 这张图自己的位置（每张独立作用域下才有值；统一模式恒为 null）
        val uriKey = images.getOrNull(page)?.toString().orEmpty()
        // 位置覆盖 → 本页真正生效的样式。
        // ⚠️ 与 Worker 导出调的是**同一个纯函数**（BatchPositionResolver），
        // 所以「预览看到的」与「导出的」不可能对不上。
        val textConfig = BatchPositionResolver.applyText(
            params.textStyle,
            overrides.textFor(uriKey)
        )
        val imageConfig = BatchPositionResolver.applyImage(
            params.imageStyle,
            overrides.imageFor(uriKey)
        )
        val isLocked = isCurrent && locked.value

        WatermarkPreview(
            bitmap = if (isCurrent) previewBitmap else null,
            watermarkType = params.type,
            textConfig = textConfig,
            imageConfig = imageConfig,
            logoBitmap = params.logo,
            // 批量页的 {filename} 占位符在导出时才解析，
            // 预览时不假装知道文件名（传 null 让它显示占位符原文）
            sourceFileName = null,
            isLoading = isCurrent && previewLoading,
            // 平时关掉单指平移，把手势让给外层 HorizontalPager 切图；
            // 锁定后翻页已禁用，此时放开缩放 —— 用户常要在放大后精调位置。
            panEnabled = isLocked,
            // ⚠️ 只有当前页参与水印绘制。
            //
            // pager 会把相邻页也短暂组合起来，若它们也绘制：
            // - 每个实例都有自己的 WatermarkRenderer（Paint 池 + 字体缓存）
            // - 拖参数时全部触发绘制，但相邻页 bitmap 为 null，画了也看不到
            enabled = isCurrent,
            // 锁定态才允许拖动水印，且**不再受"缩放必须≈1"的限制** ——
            // 放大后微调位置是正常需求，限制住反而别扭
            onWatermarkDrag = if (isLocked) {
                { x, y ->
                    when (params.type) {
                        WatermarkType.TEXT -> onDragText.value(x, y)
                        WatermarkType.IMAGE -> onDragImage.value(x, y)
                    }
                }
            } else {
                null
            },
            dragRegardlessOfZoom = isLocked,
            // 锁定态给水印描虚线框：明确告诉用户"现在拖的是它"
            showWatermarkBounds = isLocked,
            // 双击预览 → 进入拖动位置锁定态（仅当前页响应）
            onWatermarkDoubleTap = if (isCurrent) {
                { onLockChange(true) }
            } else {
                null
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isCurrent && previewLoading) {
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
            onClick = { images.getOrNull(page)?.let(onRemove) },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.batch_remove_one),
                tint = MaterialTheme.colorScheme.error
            )
        }
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
    canShare: Boolean,
    canRetry: Boolean,
    canExportCsv: Boolean,
    onExportCsv: () -> Unit,
    onShare: () -> Unit,
    onRetryFailed: () -> Unit,
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
                                    stringResource(reason.labelRes),
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
                // 清单导出放在正文里，不进底部那一行按钮：
                // 底部已经有「分享 / 重跑失败 / 好」，窄屏上再加一个必然挤到换行。
                // 这里是**次要动作**，左对齐的文本按钮正合适。
                if (canExportCsv) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = onExportCsv,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.batch_csv_export),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        // 底部一行：左侧是「下一步动作」（分享 / 重跑失败项），右侧是「好」。
        //
        // 为什么要塞进 dismissButton、把 confirmButton 留空：
        // Material3 的 AlertDialog 会把两个按钮槽当成一组**靠右**排列，
        // 于是「分享」只能贴在「好」左边、离对话框左边缘还差一大截，
        // 看着像悬在中间。要让「分享」真正落在左下角、和「好」同一条水平线，
        // 只能自己接管这一行 —— 用一个 SpaceBetween 的 Row 把两端撑开。
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tiny)) {
                    // 只在该动作确实可用时才出现：全部成功时没有「重跑失败项」，
                    // 全部失败时没有「分享」。
                    if (canShare) {
                        TextButton(onClick = onShare) {
                            Text(stringResource(R.string.batch_share_success, success))
                        }
                    }
                    if (canRetry) {
                        TextButton(onClick = onRetryFailed) {
                            Text(stringResource(R.string.batch_retry_failed, fail))
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.batch_ok)) }
            }
        },
        confirmButton = {}
    )
}
