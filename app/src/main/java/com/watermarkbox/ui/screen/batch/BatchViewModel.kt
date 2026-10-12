package com.watermarkbox.ui.screen.batch

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
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
import com.watermarkbox.worker.BatchWatermarkWorker
import com.watermarkbox.worker.JsonCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 批量处理页 UI 状态。
 */
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
            it.copy(images = uris.take(MAX_BATCH_SIZE), finished = false, previewIndex = 0)
        }
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
     */
    fun startProcessing() {
        val state = _uiState.value
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
                JsonCodec.encodeExportSettings(state.exportSettings)
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
                finished = false
            )
        }

        observeWork(request.id)
    }

    /** 观察 Worker 进度与结果。 */
    private fun observeWork(workId: java.util.UUID) {
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
                        _uiState.update { it.copy(done = done, total = total) }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val success = info.outputData.getInt(
                            BatchWatermarkWorker.KEY_SUCCESS_COUNT, 0
                        )
                        val fail = info.outputData.getInt(
                            BatchWatermarkWorker.KEY_FAIL_COUNT, 0
                        )
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                finished = true,
                                done = it.total,
                                successCount = success,
                                failCount = fail
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
        _uiState.update { it.copy(previewIndex = clamped, previewBitmap = null) }
        loadPreviewForCurrent()
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
        val uri = _uiState.value.previewUri
        if (uri == null) {
            _uiState.update { it.copy(previewBitmap = null, previewLoading = false) }
            return
        }
        val token = ++previewToken
        _uiState.update { it.copy(previewLoading = true) }
        viewModelScope.launch {
            val bitmap = imageRepository.loadPreviewBitmap(uri)
            // 竞态保护：加载期间用户可能已经滑走了，丢弃过期结果
            if (token != previewToken) {
                bitmap?.recycle()
                return@launch
            }
            _uiState.update {
                it.copy(previewBitmap = bitmap, previewLoading = false)
            }
        }
    }

    /** 预览加载的竞态令牌：只有最新一次请求的结果会被采用。 */
    private var previewToken = 0

    /** 首次进入时载入第一张的预览。 */
    fun ensurePreviewLoaded() {
        if (_uiState.value.previewBitmap == null && !_uiState.value.previewLoading) {
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
            runCatching { Uri.parse(logoUri) }.getOrNull()?.let { loadLogo(it) }
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
    }
}