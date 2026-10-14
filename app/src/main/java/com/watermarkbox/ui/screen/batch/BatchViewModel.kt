package com.watermarkbox.ui.screen.batch

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.watermarkbox.R
import com.watermarkbox.util.UiMessage
import com.watermarkbox.data.model.BatchLimits
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.data.model.InvisibleWatermarkConfig
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkColor
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.data.repository.ImageRepository
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.invisible.InvisibleContentResolver
import com.watermarkbox.ui.screen.editor.WatermarkPanelHost
import com.watermarkbox.worker.BatchFailureReason
import com.watermarkbox.worker.BatchWatermarkWorker
import com.watermarkbox.worker.JsonCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 批量处理页 UI 状态。
 */
@Immutable
data class BatchUiState(
    /** 待处理的图片 Uri 列表 */
    val images: List<Uri> = emptyList(),
    /** 水印类型 */
    val watermarkType: WatermarkType = WatermarkType.TEXT,
    /** 文字水印配置 */
    val textStyle: TextWatermarkStyle = TextWatermarkStyle.Default,
    /** 图片水印配置 */
    val imageStyle: ImageWatermarkStyle = ImageWatermarkStyle.Default,
    /** logo Bitmap */
    val logoBitmap: Bitmap? = null,
    /** 导出设置 */
    val exportSettings: ExportSettings = ExportSettings(),
    /** 隐藏水印配置（v1.3.0） */
    val invisibleConfig: InvisibleWatermarkConfig = InvisibleWatermarkConfig.Default,
    // ---- 进度 ----
    /** 是否正在处理 */
    val isProcessing: Boolean = false,
    /** 已完成数量 */
    val done: Int = 0,
    /** 总数 */
    val total: Int = 0,
    /** 成功数 */
    val successCount: Int = 0,
    /** 失败数 */
    val failCount: Int = 0,
    /**
     * 失败**按原因分类**的计数（只含非零项）。
     *
     * 结果页靠它回答「为什么失败」—— 只说「失败 3 张」用户无从下手：
     * 是图太大、文件坏了，还是存储空间不够？
     */
    val failureReasons: Map<BatchFailureReason, Int> = emptyMap(),
    /** 是否已结束（用于展示统计） */
    val finished: Boolean = false,
    /** 错误提示 */
    val errorMessage: UiMessage? = null,
    // ---- 预览（v1.4.0：批量页支持左右滑切换图片）----
    /**
     * 当前正在预览的图片下标。
     *
     * 取值范围 `0..images.size`：等于 `images.size` 时表示停在
     * 末尾的「添加更多」页（[com.watermarkbox.ui.screen.batch.BatchConfigScreen]）。
     */
    val previewIndex: Int = 0,
    /** 当前预览图的降采样 Bitmap（仅在渲染完成后填充） */
    val previewBitmap: Bitmap? = null,
    /**
     * 当前预览图的**原图**宽高（不是 [previewBitmap] 的尺寸）。
     *
     * ## 为什么需要
     *
     * 预览渲染要用原图尺寸当基准坐标系，才能让水印的**视觉占比**
     * 与导出一致。若拿降采样图的宽高当基准，同一个字号
     * 在预览（540px 宽）与导出（4032px 宽）上会差 7.5 倍。
     *
     * 详见 `WatermarkPreview.sourceWidth`。
     */
    val previewSourceWidth: Int = 0,
    val previewSourceHeight: Int = 0,
    /** 预览是否正在加载（避免切换时闪黑） */
    val previewLoading: Boolean = false,
    /** 已导入的自定义字体（供面板选择） */
    val customFonts: List<String> = emptyList(),
    // ---- 参数面板拖拽（与编辑页同款交互）----
    /** 底部参数面板高度比例（0.20 ~ 1.0；1.0 = 全屏遮住预览） */
    val panelFraction: Float = 0.42f,
    /** 面板是否展开（收起时仅剩把手） */
    val panelExpanded: Boolean = true,
    // ---- 预设（与编辑页共用同一份 DataStore 数据）----
    /** 已保存的预设列表 */
    val presets: List<WatermarkPreset> = emptyList()
) {
    /** 进度百分比 0~1。 */
    val progress: Float
        get() = if (total <= 0) 0f else done.toFloat() / total.toFloat()

    /** 当前预览的图片 Uri（停在「添加更多」页时为 null）。 */
    val previewUri: Uri?
        get() = images.getOrNull(previewIndex)

    /** 是否停在「添加更多」页。 */
    val onAddMorePage: Boolean
        get() = previewIndex >= images.size

    /** 分页总数 = 图片数 + 1（末尾的「添加更多」）。 */
    val pageCount: Int
        get() = images.size + 1
}

/**
 * 批量处理 ViewModel。
 *
 * 职责：
 * - 维护待处理图片列表与批量水印配置
 * - 通过 WorkManager 启动 [BatchWatermarkWorker]
 * - 观察 WorkInfo 上报进度与最终统计
 */
@Stable
class BatchViewModel(application: Application) :
    AndroidViewModel(application),
    WatermarkPanelHost {

    private val imageRepository = ImageRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _uiState = MutableStateFlow(BatchUiState())
    val uiState: StateFlow<BatchUiState> = _uiState.asStateFlow()

    init {
        // 复用设置页的导出配置
        viewModelScope.launch {
            settingsRepository.exportSettings.collect { settings ->
                _uiState.update { it.copy(exportSettings = settings) }
            }
        }
        // 观察预设列表（与编辑页共享同一份数据）
        viewModelScope.launch {
            settingsRepository.presets.collect { list ->
                _uiState.update { it.copy(presets = list) }
            }
        }
    }

    /** 设置待处理图片（由导航层传入）。 */
    fun setImages(uris: List<Uri>) {
        // 按上限截断（上限由 BatchLimits 统一管理）
        _uiState.update {
            it.copy(
                images = uris.take(MAX_BATCH_SIZE),
                finished = false,
                previewIndex = 0,
                // 换了一批图，上一批的原图尺寸也失效（否则会用旧基准渲染新图）
                previewSourceWidth = 0,
                previewSourceHeight = 0
            )
        }
        // 换了一批图，上一批的解码结果已无意义。
        // 用 drop（只丢引用）而不是 recycle —— 旧图可能还在当前这一帧里画着。
        imageRepository.dropPreviewCache()
        loadPreviewForCurrent()
    }

    /** 移除一张图片。 */
    fun removeImage(uri: Uri) {
        _uiState.update { it.copy(images = it.images.filterNot { u -> u == uri }) }
        // 删完可能越界（例如删掉最后一张时正停在它上面），修正下标
        clampPreviewIndex()
        // 若被删的正是当前预览图，重新加载新落位的那张
        if (_uiState.value.previewBitmap == null && !_uiState.value.onAddMorePage) {
            loadPreviewForCurrent()
        }
    }

    // ------------------------------------------------------------------
    // 配置更新
    // ------------------------------------------------------------------

    override fun updateWatermarkType(type: WatermarkType) =
        _uiState.update { it.copy(watermarkType = type) }

    override fun updateText(text: String) = updateTextStyle { copy(text = text) }
    override fun updateFontSize(size: Float) = updateTextStyle { copy(fontSize = size) }
    override fun updateColor(color: androidx.compose.ui.graphics.Color) =
        updateTextStyle { copy(color = WatermarkColor(color)) }

    override fun toggleBold() = updateTextStyle { copy(bold = !bold) }
    override fun updateLayoutMode(mode: LayoutMode) = updateTextStyle { copy(layoutMode = mode) }
    override fun updateTileCount(count: Int) = updateTextStyle { copy(tileCount = count) }
    override fun updateRotation(degrees: Float) = updateTextStyle { copy(rotationDegrees = degrees) }
    override fun updateAlpha(alpha: Float) = updateTextStyle { copy(alpha = alpha) }
    override fun updateGravity(gravity: GravityPosition) = updateTextStyle { copy(gravity = gravity) }
    override fun updateMargin(margin: Float) = updateTextStyle { copy(margin = margin) }

    private inline fun updateTextStyle(block: TextWatermarkStyle.() -> TextWatermarkStyle) {
        _uiState.update { it.copy(textStyle = it.textStyle.block()) }
    }

    override fun updateLogoWidth(percent: Float) = updateImageStyle { copy(widthPercent = percent) }
    override fun updateImageLayoutMode(mode: LayoutMode) = updateImageStyle { copy(layoutMode = mode) }
    override fun updateImageTileCount(count: Int) = updateImageStyle { copy(tileCount = count) }
    override fun updateImageRotation(degrees: Float) = updateImageStyle { copy(rotationDegrees = degrees) }
    override fun updateImageAlpha(alpha: Float) = updateImageStyle { copy(alpha = alpha) }
    override fun updateImageGravity(gravity: GravityPosition) = updateImageStyle { copy(gravity = gravity) }
    override fun updateImageMargin(margin: Float) = updateImageStyle { copy(margin = margin) }

    private inline fun updateImageStyle(block: ImageWatermarkStyle.() -> ImageWatermarkStyle) {
        _uiState.update { it.copy(imageStyle = it.imageStyle.block()) }
    }

    fun loadLogo(uri: Uri) {
        viewModelScope.launch {
            val bitmap = imageRepository.loadBitmapForLogo(uri, maxEdge = 512)
            if (bitmap == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.batch_error_logo)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    logoBitmap = bitmap,
                    imageStyle = it.imageStyle.copy(logoUri = uri.toString())
                )
            }
        }
    }

    override fun clearLogo() = _uiState.update {
        it.copy(logoBitmap = null, imageStyle = it.imageStyle.copy(logoUri = null))
    }

    fun updateExportSettings(settings: ExportSettings) {
        _uiState.update { it.copy(exportSettings = settings) }
        viewModelScope.launch { settingsRepository.updateExportSettings(settings) }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    // ------------------------------------------------------------------
    // 执行批量处理
    // ------------------------------------------------------------------

    /**
     * 启动批量处理。
     *
     * 通过 WorkManager 的 OneTimeWorkRequest 提交一个 CoroutineWorker，
     * 配置以 JSON 形式放入 inputData。
     *
     * @param overrideSettings 本次专用的导出设置。
     *        `null`（默认）表示用全局的 `state.exportSettings`。
     *
     *        ## 为什么需要这个参数
     *
     *        批量页点「开始」时会先弹一个参数框（与编辑页 FAB 同构），
     *        用户在框里的改动**只对这一批生效**，不该污染全局默认值。
     *        所以整条链路（Worker 入参、进度展示）都用这一份临时设置。
     */
    fun startProcessing(overrideSettings: ExportSettings? = null) {
        val state = _uiState.value
        // 本次生效的导出设置：优先用调用方传入的临时值
        val exportSettings = overrideSettings ?: state.exportSettings
        if (state.images.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiMessage(R.string.batch_error_no_images)) }
            return
        }
        if (state.watermarkType == WatermarkType.IMAGE &&
            (state.logoBitmap == null || state.logoBitmap.isRecycled)
        ) {
            _uiState.update { it.copy(errorMessage = UiMessage(R.string.batch_error_logo_required)) }
            return
        }
        if (state.isProcessing) return

        val input = Data.Builder()
            .putStringArray(
                BatchWatermarkWorker.KEY_URIS,
                state.images.map { it.toString() }.toTypedArray()
            )
            .putString(BatchWatermarkWorker.KEY_TYPE, state.watermarkType.name)
            .putString(
                BatchWatermarkWorker.KEY_TEXT_STYLE,
                JsonCodec.encodeTextStyle(state.textStyle)
            )
            .putString(
                BatchWatermarkWorker.KEY_IMAGE_STYLE,
                JsonCodec.encodeImageStyle(state.imageStyle)
            )
            .putString(
                BatchWatermarkWorker.KEY_EXPORT_SETTINGS,
                JsonCodec.encodeExportSettings(exportSettings)
            )
            .apply {
                state.imageStyle.logoUri?.let {
                    putString(BatchWatermarkWorker.KEY_LOGO_URI, it)
                }
            }
            .apply {
                // 隐藏水印：在**主线程入队时**解析内容，
                // 保证整批图片嵌的是同一个"设备ID+时间戳"，
                // 便于日后横向比对（同批导出可互相印证）
                val content = InvisibleContentResolver.resolve(
                    getApplication(),
                    state.invisibleConfig
                )
                if (content != null) {
                    putString(BatchWatermarkWorker.KEY_INVISIBLE_CONTENT, content)
                    putInt(
                        BatchWatermarkWorker.KEY_INVISIBLE_STRENGTH,
                        state.invisibleConfig.strength
                    )
                    // 两条通道各自独立，Worker 侧也要照做 ——
                    // 否则「只开 DCT / 只开 EXIF」这类组合在批量导出时会失效
                    putBoolean(
                        BatchWatermarkWorker.KEY_INVISIBLE_EXIF,
                        state.invisibleConfig.exifEnabled
                    )
                    putBoolean(
                        BatchWatermarkWorker.KEY_INVISIBLE_DCT,
                        state.invisibleConfig.dctEnabled
                    )
                }
            }
            .build()

        val request = OneTimeWorkRequestBuilder<BatchWatermarkWorker>()
            .setInputData(input)
            .addTag(BatchWatermarkWorker.WORK_NAME)
            .build()

        workManager.enqueue(request)

        _uiState.update {
            it.copy(
                isProcessing = true,
                done = 0,
                total = state.images.size,
                successCount = 0,
                failCount = 0,
                failureReasons = emptyMap(),
                finished = false
            )
        }

        observeWork(request.id)
    }

    /** 观察 Worker 进度与结果。 */
    private fun observeWork(workId: java.util.UUID) {
        // ================================================================
        // 为什么除了 collect(WorkInfo) 还要单独轮询进度
        // ================================================================
        //
        // WorkManager 的 `getWorkInfoByIdFlow` 只保证「最终一致」：
        // 它的 WorkInfo 来自一个**带节流的内部查询**（系统默认
        // 10 秒量级，且只在状态变化时刷新得更快）。
        //
        // 后果：3 张图、十几秒跑完的批次，UI 可能**一次进度都收不到** ——
        // 进度条从头到尾停在 0，然后「啪」地弹出完成统计。
        // 用户的直观感受就是「它把图堆在一起一口气处理完了」，
        // 而实际上 Worker 是老老实实一张一张处理的。
        //
        // 所以这里补一条**自己的轻量轮询**：每 250ms 直接查一次
        // `getWorkInfoById`，把进度尽早上屏。开销很小（一次内存查询），
        // 而且任务结束就随协程一起取消。
        //
        // 250ms 是权衡：更密（如 60ms）没有意义 —— 单张处理就要
        // 几百毫秒，用户看不出差别，却白耗 CPU；更疏（如 1s）
        // 又会让进度条一跳一大格。
        //
        // ⚠️ 这里用 `getWorkInfoById(...).await()` 是不行的：
        // 那个 await 扩展被标了 @RestrictTo（只在 androidx.work
        // 内部可用），lint 直接报 RestrictedApi。
        // 公开的等价写法是 ListenableFuture.addListener + 自己桥接协程，
        // 见 [awaitWorkInfo]。
        viewModelScope.launch {
            while (true) {
                val info = awaitWorkInfo(workId)
                if (info == null) break
                val done = info.progress.getInt(BatchWatermarkWorker.KEY_PROGRESS_DONE, 0)
                val total = info.progress.getInt(
                    BatchWatermarkWorker.KEY_PROGRESS_TOTAL,
                    0
                )
                if (total > 0 && done > _uiState.value.done) {
                    _uiState.update { it.copy(done = done, total = total) }
                }
                if (info.state.isFinished) break
                delay(PROGRESS_POLL_MS)
            }
        }

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                if (info == null) return@collect

                val done = info.progress.getInt(BatchWatermarkWorker.KEY_PROGRESS_DONE, 0)
                val total = info.progress.getInt(
                    BatchWatermarkWorker.KEY_PROGRESS_TOTAL,
                    _uiState.value.total
                )

                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        // ⚠️ 这里**不能**无条件写 done：
                        //
                        // WorkInfo 的 progress 是**快照**。任务刚被标记为
                        // RUNNING、第一张还没处理完时，快照里没有 progress，
                        // `getInt(..., 0)` 返回 0 —— 直接把进度**打回 0**，
                        // 界面上表现为进度条先跳到一半又弹回起点。
                        //
                        // 凡「新值小于已显示值」的快照一律视为过期，忽略。
                        if (done >= _uiState.value.done) {
                            _uiState.update { it.copy(done = done, total = total) }
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val success = info.outputData.getInt(
                            BatchWatermarkWorker.KEY_SUCCESS_COUNT, 0
                        )
                        val fail = info.outputData.getInt(
                            BatchWatermarkWorker.KEY_FAIL_COUNT, 0
                        )
                        // 失败原因：只收非零项，界面按固定顺序展示
                        val reasons = buildMap {
                            fun putIfAny(reason: BatchFailureReason, key: String) {
                                val n = info.outputData.getInt(key, 0)
                                if (n > 0) put(reason, n)
                            }
                            putIfAny(
                                BatchFailureReason.SOURCE_TOO_LARGE,
                                BatchWatermarkWorker.KEY_FAIL_TOO_LARGE
                            )
                            putIfAny(
                                BatchFailureReason.SOURCE_UNREADABLE,
                                BatchWatermarkWorker.KEY_FAIL_UNREADABLE
                            )
                            putIfAny(
                                BatchFailureReason.SAVE_FAILED,
                                BatchWatermarkWorker.KEY_FAIL_SAVE
                            )
                            putIfAny(
                                BatchFailureReason.UNKNOWN,
                                BatchWatermarkWorker.KEY_FAIL_UNKNOWN
                            )
                        }
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                finished = true,
                                done = it.total,
                                successCount = success,
                                failCount = fail,
                                failureReasons = reasons
                            )
                        }
                    }

                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                finished = true,
                                errorMessage = UiMessage(R.string.batch_error_interrupted)
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * 查一次某个任务的 [WorkInfo]，挂起直到结果就绪。
     *
     * ## 为什么不用 `ListenableFuture.await()`
     *
     * WorkManager 提供的那个 `await()` 扩展被标注了 `@RestrictTo`
     * （只允许 `androidx.work` 内部或同 groupId 调用），
     * 在应用代码里用会被 lint 判为 `RestrictedApi` 而**直接构建失败**。
     *
     * 所以用公开 API 自己桥接：`ListenableFuture.addListener` +
     * `suspendCancellableCoroutine`。这也是 Google 官方推荐的
     * 「把 ListenableFuture 变成挂起函数」的写法。
     *
     * 任务结束时 [WorkManager.getWorkInfoById] 也会正常回调
     * （返回最后一次快照），所以不会永久挂起。
     */
    private suspend fun awaitWorkInfo(workId: java.util.UUID): WorkInfo? =
        suspendCancellableCoroutine { cont ->
            val future = workManager.getWorkInfoById(workId)
            // 协程被取消时把监听摘掉，避免回调泄漏
            cont.invokeOnCancellation { future.cancel(true) }
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { if (cont.isActive) cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                },
                // 回调跑在哪个 Executor 上 —— 用主线程，因为我们只是
                // 读一下内存里的 WorkInfo 然后立刻切回协程
                ContextCompat.getMainExecutor(getApplication())
            )
        }

    /** 取消正在进行的批量任务。 */
    fun cancelProcessing() {
        workManager.cancelAllWorkByTag(BatchWatermarkWorker.WORK_NAME)
        _uiState.update {
            it.copy(isProcessing = false, errorMessage = UiMessage(R.string.batch_error_cancelled))
        }
    }

    /** 重置处理状态（重新开始）。 */
    fun resetProcessing() {
        _uiState.update {
            it.copy(
                isProcessing = false,
                finished = false,
                done = 0,
                successCount = 0,
                failCount = 0
            )
        }
    }

    // ==================================================================
    // 预览：左右滑切换图片
    // ==================================================================

    /**
     * 切换当前预览的图片。
     *
     * 由 `HorizontalPager` 在滑动落位时调用。**参数共用**：滑动只改变
     * 「用哪张图看效果」，不改变任何水印参数 —— 这是批量的语义
     * （一次设置，全部套用）。
     *
     * 停在「添加更多」页（`index == images.size`）时不加载预览图。
     */
    fun setPreviewIndex(index: Int) {
        val clamped = index.coerceIn(0, _uiState.value.images.size)
        if (clamped == _uiState.value.previewIndex) return
        // ⚠️ 先递增令牌：使此前所有在途的「位图加载 / 原图尺寸探测」立即失效。
        //
        // 必须在这里递增，而不是只放在 loadPreviewForCurrent 里 ——
        // 命中缓存的分支走的是 loadSourceSizeForCurrent，它自身不自增；
        // 若此处不增，连续两次「缓存命中」切页（相邻页已预取，是最常见操作）
        // 会共用同一个令牌 → `token != previewToken` 恒为 false
        // → 竞态保护形同虚设，旧页的原图尺寸会写进新页的状态。
        val token = newPreviewToken()
        // 命中缓存就**立刻**显示：相邻页在切过来之前已经被预解码，
        // 于是「滑到隔壁看一眼」不再闪加载圈（这是批量页最常用的动作）
        val cached = _uiState.value.images.getOrNull(clamped)
            ?.let { imageRepository.peekPreview(it) }
        _uiState.update {
            it.copy(
                previewIndex = clamped,
                previewBitmap = cached,
                // 换页了，原图尺寸也必须跟着换 ——
                // 否则会用上一张的基准渲染这一张，水印大小错误。
                // 命中缓存时先清零（渲染会用 fallback 的 bitmap 尺寸），
                // 真正的原图尺寸由下面的 loadPreviewForCurrent 补上。
                previewSourceWidth = 0,
                previewSourceHeight = 0,
                previewLoading = cached == null
            )
        }
        // 无论是否命中缓存，都要取一次原图尺寸（只读文件头，很便宜）。
        // 命中缓存时 loadPreviewForCurrent 不会跑，所以这里单独补。
        if (cached != null) loadSourceSizeForCurrent(token) else loadPreviewForCurrent(token)
    }

    /** 移除图片后修正下标，避免越界或停在空白页。 */
    private fun clampPreviewIndex() {
        _uiState.update {
            val maxIndex = it.images.size
            if (it.previewIndex > maxIndex) it.copy(previewIndex = maxIndex) else it
        }
    }

    /**
     * 加载当前预览图的降采样 Bitmap。
     *
     * 只加载**一张**（当前页），滑走时释放 —— 批量可能有几十张，
     * 全量缓存会直接把内存打爆（设备堆上限 256MB）。
     */
    private fun loadPreviewForCurrent() {
        loadPreviewForCurrent(newPreviewToken())
    }

    /**
     * 加载**本轮**（[token]）对应的预览位图与基准尺寸。
     *
     * @param token 本轮开始时递增得到的竞态令牌（见 [newPreviewToken]）。
     */
    private fun loadPreviewForCurrent(token: Int) {
        val uri = _uiState.value.previewUri
        if (uri == null) {
            _uiState.update { it.copy(previewBitmap = null, previewLoading = false) }
            return
        }
        _uiState.update { it.copy(previewLoading = true) }
        viewModelScope.launch {
            val bitmap = imageRepository.loadPreviewBitmap(uri)
            // 竞态保护：加载期间用户可能已经滑走了，丢弃过期结果
            if (token != previewToken) {
                // ⚠️ 这里**不要** recycle 这张位图。
                //
                // `loadPreviewBitmap` 命中缓存时直接返回缓存对象，未命中则
                // **已经把它放进了**仓库的 LRU 缓存 —— 两种情况下的所有权
                // 都不在本轮请求手上：
                //   - 当场 recycle → 缓存里留下"僵尸条目"占着槽位，
                //     后续 peekPreview 只能靠 isRecycled 兜住；
                //   - 若它正被某一帧绘制，还会直接崩在 Canvas 上。
                // 交给 LRU 淘汰与 GC 即可，这也与 `LruCacheMap`「淘汰不 recycle」
                // 的设计保持一致。
                return@launch
            }
            // 同时探测**原图**尺寸：预览渲染要用它当基准坐标系，
            // 才能让水印大小与导出一致（见 BatchUiState.previewSourceWidth）
            val originalSize = imageRepository.queryImageSize(uri)
            // ⚠️ queryImageSize 是**新增的挂起点**：它返回时用户可能已经滑走。
            // 必须在写入状态**之前**再查一次令牌 —— 否则会把旧页的
            // 位图与尺寸覆盖到当前页上（画面显示 A 页、缩放基准却是 B 页的）。
            if (token != previewToken) return@launch
            _uiState.update {
                it.copy(
                    previewBitmap = bitmap,
                    previewSourceWidth = originalSize?.first ?: bitmap?.width ?: 0,
                    previewSourceHeight = originalSize?.second ?: bitmap?.height ?: 0,
                    previewLoading = false
                )
            }
            // 当前这张好了，顺手把左右两张也解码好 —— 用户下一滑就是瞬时的
            prefetchNeighbours()
        }
    }

    /**
     * 预览加载的竞态令牌：只有**最新一轮**请求的结果会被采用。
     *
     * ## 为什么单独抽一个递增函数
     *
     * 递增点必须覆盖**所有**换图路径，否则保护会漏：
     * - [setPreviewIndex]（左右滑切页）
     * - [loadPreviewForCurrent] 的无参重载（首次进入 / 换一批图 / 删图后补位）
     * - [ensurePreviewLoaded] 命中缓存时
     *
     * 早期只在 `loadPreviewForCurrent` 里递增，于是「缓存命中切页」
     * 这条路径拿到的令牌永不变化 —— 看似有保护，实际恒为相等。
     */
    private var previewToken = 0

    /** 递增并返回预览竞态令牌（使此前所有在途请求失效）。 */
    private fun newPreviewToken(): Int = ++previewToken

    /**
     * 只取当前预览图的**原图尺寸**（不解码像素）。
     *
     * 命中预览缓存时走这条路 —— 位图已经有了，但原图尺寸还不知道。
     * 只读文件头，开销可忽略。
     *
     * @param token 本轮的竞态令牌，**必须由调用方在切页时递增后传入**
     *   （见 [newPreviewToken]）。早期这里自己读 `previewToken` 而不自增，
     *   于是连续两次「缓存命中」切页共用同一个值 →
     *   `token != previewToken` 恒为 false → 保护形同虚设。
     */
    private fun loadSourceSizeForCurrent(token: Int) {
        val uri = _uiState.value.previewUri ?: return
        viewModelScope.launch {
            val size = imageRepository.queryImageSize(uri)
            // 竞态保护：期间可能已经滑走了
            if (token != previewToken) return@launch
            if (size == null) return@launch
            _uiState.update {
                it.copy(previewSourceWidth = size.first, previewSourceHeight = size.second)
            }
        }
    }

    /** 预取任务句柄：换页时取消上一个，避免连续滑动堆积一串解码。 */
    private var prefetchJob: Job? = null

    /**
     * 预解码**左右相邻页**，让下一次滑动命中缓存。
     *
     * ## 为什么值得做
     *
     * 批量页的语义是「一次设置、全部套用」—— 用户会反复左右滑着挑图看效果。
     * 每次滑动都重新解码（十几到几十毫秒）就会一路闪加载圈，
     * 而相邻页往往马上就会被看到。
     *
     * ## 为什么只预取相邻两张
     *
     * 预取全量等于把几十张大图全解进内存（单张约 4 MB）。
     * 相邻两张覆盖了「手滑一下」这个绝大多数场景；滑得更远时，
     * 下一次落位又会触发新的预取。
     *
     * ## 为什么结果**不**写进 UI 状态
     *
     * 预取只是为了「下次能命中缓存」。若它顺手改了 `previewBitmap`，
     * 用户还没滑过去、界面就先跳成另一张图 —— 那是 bug。
     * 所以这里只调用仓库层解码（自带 LRU 缓存），完全不碰 `_uiState`。
     */
    private fun prefetchNeighbours() {
        prefetchJob?.cancel()
        val uris = _uiState.value.images
        val center = _uiState.value.previewIndex
        prefetchJob = viewModelScope.launch {
            for (offset in intArrayOf(1, -1)) {
                val uri = uris.getOrNull(center + offset) ?: continue
                if (imageRepository.peekPreview(uri) != null) continue
                imageRepository.loadPreviewBitmap(uri)
            }
        }
    }

    /** 首次进入时载入第一张的预览。 */
    fun ensurePreviewLoaded() {
        if (_uiState.value.previewBitmap != null || _uiState.value.previewLoading) return
        // 也可能直接命中缓存（例如刚预取过、或从别处返回本页）
        val cached = _uiState.value.previewUri?.let { imageRepository.peekPreview(it) }
        if (cached != null) {
            _uiState.update { it.copy(previewBitmap = cached) }
            // ⚠️ 命中缓存时**必须**单独取原图尺寸：
            // 预览渲染需要它当基准坐标系，否则水印大小与导出不一致。
            // （另一条路径 loadPreviewForCurrent 会顺带取，这里不会跑到。）
            loadSourceSizeForCurrent(newPreviewToken())
        } else {
            loadPreviewForCurrent()
        }
    }

    /** 追加图片（「添加更多」页选完图后调用）。 */
    fun appendImages(uris: List<Uri>) {
        _uiState.update {
            val merged = (it.images + uris).distinct().take(MAX_BATCH_SIZE)
            it.copy(images = merged)
        }
    }

    // ------------------------------------------------------------------
    // 参数面板拖拽（与编辑页同款交互）
    // ------------------------------------------------------------------

    /** 收起 / 展开参数面板。 */
    fun togglePanel() = _uiState.update { it.copy(panelExpanded = !it.panelExpanded) }

    /**
     * 更新面板高度比例（由拖拽手势调用）。
     *
     * 下限 0.20 保证把手始终可见（否则用户没法再拖回来）。
     */
    fun updatePanelFraction(fraction: Float) =
        _uiState.update { it.copy(panelFraction = fraction.coerceIn(0.20f, 1f)) }

    // ------------------------------------------------------------------
    // 预设（与编辑页共用同一份 DataStore 数据）
    //
    // 语义与编辑页完全一致 —— 用户在批量页存的预设，在编辑页也能看到，
    // 反之亦然。不需要"批量专用预设"，那会增加理解成本。
    // ------------------------------------------------------------------

    /**
     * 把当前配置保存为预设。
     *
     * @param overwrite 重名时：`true` = 替换旧的，`false` = 另存为新名字
     */
    fun saveCurrentAsPreset(
        name: String,
        overwrite: Boolean = true,
        onSaved: ((String) -> Unit)? = null
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiMessage(R.string.preset_error_name_empty)) }
            return
        }
        val state = _uiState.value
        val preset = WatermarkPreset(
            id = System.currentTimeMillis().toString(),
            name = trimmed,
            createdAt = System.currentTimeMillis(),
            textStyle = state.textStyle,
            imageStyle = state.imageStyle,
            type = state.watermarkType
        )
        viewModelScope.launch {
            val finalName = settingsRepository.savePreset(preset, overwrite)
            onSaved?.invoke(finalName)
        }
    }

    /**
     * 应用一个预设。
     *
     * 与编辑页一致：同时切换水印类型、文字样式、图片样式，
     * 并在预设引用了 logo 而当前未加载时尝试重新载入。
     */
    fun applyPreset(preset: WatermarkPreset) {
        _uiState.update {
            it.copy(
                textStyle = preset.textStyle,
                imageStyle = preset.imageStyle,
                watermarkType = preset.type
            )
        }
        val logoUri = preset.imageStyle.logoUri
        if (logoUri != null && _uiState.value.logoBitmap == null) {
            runCatching { logoUri.toUri() }.getOrNull()?.let { loadLogo(it) }
        }
    }

    /** 删除预设。 */
    fun deletePreset(id: String) {
        viewModelScope.launch { settingsRepository.deletePreset(id) }
    }

    // ==================================================================
    // WatermarkPanelHost 中本页尚未实现的部分
    //
    // 面板需要它们才能完整渲染（字体 / 描边 / 阴影 / 隐藏水印…）。
    // 逻辑与编辑页一致，直接照搬 —— 两边共用同一套参数语义。
    // ==================================================================

    override fun updateFontFamily(family: FontFamilyOption) =
        updateTextStyle { copy(fontFamily = family) }

    override fun toggleItalic() = updateTextStyle { copy(italic = !italic) }

    override fun toggleStroke() = updateTextStyle { copy(strokeEnabled = !strokeEnabled) }

    override fun updateStrokeWidth(width: Float) = updateTextStyle { copy(strokeWidth = width) }

    override fun updateStrokeColor(color: androidx.compose.ui.graphics.Color) =
        updateTextStyle { copy(strokeColor = WatermarkColor(color)) }

    override fun toggleShadow() = updateTextStyle { copy(shadowEnabled = !shadowEnabled) }

    override fun updateShadowRadius(radius: Float) = updateTextStyle { copy(shadowRadius = radius) }

    override fun togglePreserveAspectRatio() =
        updateImageStyle { copy(preserveAspectRatio = !preserveAspectRatio) }

    /**
     * 选中自定义字体。
     *
     * 本页**只改字体选择**，不提供导入/删除 —— 那属于设置页职责。
     */
    override fun selectCustomFont(fileName: String) = updateTextStyle {
        copy(fontFamily = FontFamilyOption.CUSTOM, customFontName = fileName)
    }

    /** 本页不支持删除字体文件（见 [selectCustomFont]），空实现。 */
    override fun deleteCustomFont(fileName: String) = Unit

    // ---------------- 隐藏水印 ----------------

    private inline fun updateInvisible(
        block: InvisibleWatermarkConfig.() -> InvisibleWatermarkConfig
    ) {
        _uiState.update { it.copy(invisibleConfig = it.invisibleConfig.block()) }
    }

    /**
     * 开关隐藏水印。
     *
     * 与编辑页一致：**开启时**套用设置里的「默认启用 DCT」，
     * 之后用户的单独开关不被覆盖。
     */
    override fun toggleInvisibleWatermark() {
        val willEnable = !_uiState.value.invisibleConfig.enabled
        val dctDefault = _uiState.value.exportSettings.invisibleDctByDefault
        updateInvisible {
            if (willEnable) copy(enabled = true, dctEnabled = dctDefault)
            else copy(enabled = false)
        }
    }

    override fun updateInvisibleContent(text: String) = updateInvisible { copy(content = text) }

    override fun updateInvisibleSource(source: InvisibleSource) =
        updateInvisible { copy(source = source) }

    override fun updateInvisibleStrength(strength: Int) =
        updateInvisible { copy(strength = strength.coerceIn(1, 4)) }

    override fun toggleInvisibleExif() = updateInvisible { copy(exifEnabled = !exifEnabled) }

    override fun toggleInvisibleDct() = updateInvisible { copy(dctEnabled = !dctEnabled) }

    override fun onCleared() {
        super.onCleared()
        imageRepository.recycleCache()
    }

    companion object {
        /**
         * 单次批量上限。
         *
         * 统一由 [BatchLimits] 管理，避免多处硬编码不一致。
         */
        const val MAX_BATCH_SIZE = BatchLimits.MAX_BATCH_SIZE

        /**
         * 进度轮询间隔（毫秒）。
         *
         * 为什么需要额外轮询：见 [observeWork] 的注释 ——
         * WorkManager 的 WorkInfo 流是**节流**的，短批次可能一次
         * 进度都收不到，用户会以为"一起处理"了。
         *
         * 250ms 的取舍：单张处理本身就要几百毫秒，采样更密看不出
         * 差别却白耗 CPU；更疏则进度条一跳一大格。
         */
        private const val PROGRESS_POLL_MS = 250L
    }
}