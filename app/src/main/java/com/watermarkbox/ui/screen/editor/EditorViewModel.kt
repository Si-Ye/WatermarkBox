package com.watermarkbox.ui.screen.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.R
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
import com.watermarkbox.data.repository.ExportRepository
import com.watermarkbox.data.repository.FontRepository
import com.watermarkbox.data.repository.ImageRepository
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.invisible.InvisibleContentResolver
import com.watermarkbox.invisible.InvisibleWatermarkEngine
import com.watermarkbox.util.FileNameResolver
import com.watermarkbox.util.UiMessage
import com.watermarkbox.watermark.WatermarkRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 编辑页 UI 状态。
 */
data class EditorUiState(
    /** 选中的图片 Uri */
    val imageUri: Uri? = null,
    /** 原图显示名（用于 {filename} 与导出命名） */
    val sourceFileName: String? = null,
    /** 原图宽高 */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** 预览用的降采样 Bitmap */
    val previewBitmap: Bitmap? = null,
    /** 文字水印配置 */
    val config: TextWatermarkStyle = TextWatermarkStyle.Default,
    /** 图片水印配置 */
    val imageConfig: ImageWatermarkStyle = ImageWatermarkStyle.Default,
    /** 当前水印类型 */
    val watermarkType: WatermarkType = WatermarkType.TEXT,
    /** 已解码的 logo Bitmap */
    val logoBitmap: Bitmap? = null,
    /** 是否正在加载图片 */
    val isLoading: Boolean = false,
    /** 错误信息（存资源 ID，由 UI 层按当前语言解析） */
    val errorMessage: UiMessage? = null,
    /** 提示信息（成功类，同上） */
    val infoMessage: UiMessage? = null,
    /** 底部参数面板高度比例（0.35 ~ 1.0；1.0 = 全屏遮住预览） */
    val panelFraction: Float = 0.40f,
    /** 面板是否展开（收起时仅剩把手） */
    val panelExpanded: Boolean = true,
    // ---- 导出 ----
    /** 导出设置 */
    val exportSettings: ExportSettings = ExportSettings(),
    /** 是否正在导出 */
    val isExporting: Boolean = false,
    /** 最近一次导出结果（用于显示缩略图预览） */
    val lastExportUri: Uri? = null,
    // ---- 预设 ----
    /** 已保存的预设列表 */
    val presets: List<WatermarkPreset> = emptyList(),
    // ---- 自定义字体（阶段四）----
    /** 已导入的字体文件名列表 */
    val customFonts: List<String> = emptyList(),
    // ---- 隐藏水印（v1.3.0）----
    /** 隐藏水印配置 */
    val invisibleConfig: InvisibleWatermarkConfig = InvisibleWatermarkConfig.Default
)

/**
 * 编辑页 ViewModel。
 *
 * 职责：
 * - 加载图片并维护预览 Bitmap
 * - 维护水印配置状态（文字 / 图片两套）
 * - 导出：用全分辨率原图重新渲染并写入 MediaStore
 * - 预设：保存/应用/删除（持久化在 DataStore）
 */
class EditorViewModel(application: Application) :
    AndroidViewModel(application),
    WatermarkPanelHost {

    private val imageRepository = ImageRepository(application)
    private val exportRepository = ExportRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val fontRepository = FontRepository(application)
    private val renderer = WatermarkRenderer().apply {
        // 阶段四：接入自定义字体解析
        customFontResolver = WatermarkRenderer.CustomFontResolver { name ->
            fontRepository.loadFont(name)
        }
    }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init {
        // 启动时加载已导入字体
        refreshCustomFonts()

        // 观察预设列表
        viewModelScope.launch {
            settingsRepository.presets.collect { list ->
                _uiState.update { it.copy(presets = list) }
            }
        }
        // 观察导出设置，并应用默认真实透明度
        viewModelScope.launch {
            settingsRepository.exportSettings.collect { settings ->
                _uiState.update { it.copy(exportSettings = settings) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 图片加载
    // ------------------------------------------------------------------

    fun loadImage(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, imageUri = uri) }
        viewModelScope.launch {
            val name = imageRepository.queryDisplayName(uri)
            val bitmap = imageRepository.loadPreviewBitmap(uri)
            if (bitmap == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = UiMessage(R.string.editor_error_load_failed))
                }
                return@launch
            }
            val defaultAlpha = _uiState.value.exportSettings.defaultAlpha
            val defaults = _uiState.value.exportSettings
            _uiState.update {
                it.copy(
                    isLoading = false,
                    previewBitmap = bitmap,
                    sourceFileName = name,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                    // 新图片沿用设置里的默认透明度
                    config = it.config.copy(alpha = defaultAlpha),
                    imageConfig = it.imageConfig.copy(alpha = defaultAlpha)
                )
            }
            // 新图片套用设置里的「默认水印文字」。
            // 只在用户还没手动改过文字时套用 —— 否则会覆盖掉用户刚输入的内容。
            // 判断依据：当前文字仍是上次的默认值（即用户没动过）。
            if (defaults.defaultWatermarkText.isNotBlank()) {
                _uiState.update {
                    if (it.config.text == TextWatermarkStyle.Default.text ||
                        it.config.text.isBlank()
                    ) {
                        it.copy(config = it.config.copy(text = defaults.defaultWatermarkText))
                    } else {
                        it
                    }
                }
            }
            // 新图片自动载入设置里的「默认水印图」。
            // 若用户已手动选过 logo（logoBitmap 非空）则不覆盖。
            val defaultLogo = defaults.defaultLogoUri
            if (defaultLogo != null && _uiState.value.logoBitmap == null) {
                runCatching { Uri.parse(defaultLogo) }
                    .getOrNull()
                    ?.let { loadLogo(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 文字水印配置
    // ------------------------------------------------------------------

    override fun updateText(text: String) = updateConfig { copy(text = text) }
    override fun updateFontSize(size: Float) = updateConfig { copy(fontSize = size) }
    override fun updateFontFamily(family: FontFamilyOption) = updateConfig { copy(fontFamily = family) }
    override fun updateColor(color: androidx.compose.ui.graphics.Color) =
        updateConfig { copy(color = WatermarkColor(color)) }

    override fun toggleBold() = updateConfig { copy(bold = !bold) }
    override fun toggleItalic() = updateConfig { copy(italic = !italic) }
    override fun toggleStroke() = updateConfig { copy(strokeEnabled = !strokeEnabled) }
    override fun updateStrokeWidth(width: Float) = updateConfig { copy(strokeWidth = width) }
    override fun updateStrokeColor(color: androidx.compose.ui.graphics.Color) =
        updateConfig { copy(strokeColor = WatermarkColor(color)) }

    override fun toggleShadow() = updateConfig { copy(shadowEnabled = !shadowEnabled) }
    override fun updateShadowRadius(radius: Float) = updateConfig { copy(shadowRadius = radius) }
    override fun updateLayoutMode(mode: LayoutMode) = updateConfig { copy(layoutMode = mode) }
    override fun updateTileCount(count: Int) = updateConfig { copy(tileCount = count) }
    override fun updateRotation(degrees: Float) = updateConfig { copy(rotationDegrees = degrees) }
    override fun updateAlpha(alpha: Float) = updateConfig { copy(alpha = alpha) }
    override fun updateGravity(gravity: GravityPosition) = updateConfig { copy(gravity = gravity) }
    override fun updateMargin(margin: Float) = updateConfig { copy(margin = margin) }

    // ------------------------------------------------------------------
    // 自定义字体（阶段四）
    // ------------------------------------------------------------------

    /** 刷新已导入字体列表。 */
    private fun refreshCustomFonts() {
        _uiState.update { it.copy(customFonts = fontRepository.listFonts()) }
    }

    /** 导入一个字体文件并设为当前字体。 */
    fun importCustomFont(uri: Uri) {
        viewModelScope.launch {
            val name = fontRepository.queryFontName(uri)
            if (name == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.editor_error_font_name)) }
                return@launch
            }
            val saved = fontRepository.importFont(uri, name)
            if (saved == null) {
                _uiState.update {
                    it.copy(errorMessage = UiMessage(R.string.editor_error_font_import))
                }
                return@launch
            }
            refreshCustomFonts()
            updateConfig {
                copy(fontFamily = FontFamilyOption.CUSTOM, customFontName = saved)
            }
            _uiState.update { it.copy(infoMessage = UiMessage(R.string.editor_info_font_imported, saved)) }
        }
    }

    /** 选用一个已导入的字体。 */
    override fun selectCustomFont(fileName: String) {
        updateConfig {
            copy(fontFamily = FontFamilyOption.CUSTOM, customFontName = fileName)
        }
    }

    /** 删除一个已导入的字体；若正在使用则回退系统默认。 */
    override fun deleteCustomFont(fileName: String) {
        viewModelScope.launch {
            fontRepository.deleteFont(fileName)
            refreshCustomFonts()
            if (_uiState.value.config.customFontName == fileName) {
                updateConfig {
                    copy(fontFamily = FontFamilyOption.DEFAULT, customFontName = null)
                }
            }
            _uiState.update { it.copy(infoMessage = UiMessage(R.string.editor_info_font_deleted, fileName)) }
        }
    }

    private inline fun updateConfig(block: TextWatermarkStyle.() -> TextWatermarkStyle) {
        _uiState.update { state -> state.copy(config = state.config.block()) }
    }

    // ------------------------------------------------------------------
    // 面板与消息
    // ------------------------------------------------------------------

    fun togglePanel() = _uiState.update { it.copy(panelExpanded = !it.panelExpanded) }

    /** 更新面板高度比例（由拖拽手势调用），自动夹紧到合法区间。 */
    fun updatePanelFraction(fraction: Float) =
        _uiState.update { it.copy(panelFraction = fraction.coerceIn(0.20f, 1f)) }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun clearInfo() = _uiState.update { it.copy(infoMessage = null) }

    // ------------------------------------------------------------------
    // 水印类型 / 图片水印
    // ------------------------------------------------------------------

    override fun updateWatermarkType(type: WatermarkType) =
        _uiState.update { it.copy(watermarkType = type) }

    fun loadLogo(uri: Uri) {
        viewModelScope.launch {
            val bitmap = imageRepository.loadBitmapForLogo(uri, maxEdge = 512)
            if (bitmap == null) {
                _uiState.update { it.copy(errorMessage = UiMessage(R.string.editor_error_logo_load)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    logoBitmap = bitmap,
                    imageConfig = it.imageConfig.copy(logoUri = uri.toString())
                )
            }
        }
    }

    override fun clearLogo() = _uiState.update {
        it.copy(logoBitmap = null, imageConfig = it.imageConfig.copy(logoUri = null))
    }

    private inline fun updateImageConfig(block: ImageWatermarkStyle.() -> ImageWatermarkStyle) {
        _uiState.update { state -> state.copy(imageConfig = state.imageConfig.block()) }
    }

    override fun updateLogoWidth(percent: Float) = updateImageConfig { copy(widthPercent = percent) }
    override fun updateImageLayoutMode(mode: LayoutMode) = updateImageConfig { copy(layoutMode = mode) }
    override fun updateImageTileCount(count: Int) = updateImageConfig { copy(tileCount = count) }
    override fun updateImageRotation(degrees: Float) = updateImageConfig { copy(rotationDegrees = degrees) }
    override fun updateImageAlpha(alpha: Float) = updateImageConfig { copy(alpha = alpha) }
    override fun updateImageGravity(gravity: GravityPosition) = updateImageConfig { copy(gravity = gravity) }
    override fun updateImageMargin(margin: Float) = updateImageConfig { copy(margin = margin) }
    override fun togglePreserveAspectRatio() =
        updateImageConfig { copy(preserveAspectRatio = !preserveAspectRatio) }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 导出当前图片。
     *
     * 流程：
     * 1. 加载全分辨率原图（不降采样）
     * 2. 按当前配置重新渲染（保证与预览视觉一致）
     * 3. 以设置中的格式写入 MediaStore
     * 4. 释放大 Bitmap
     */
    fun export() {
        val state = _uiState.value
        val uri = state.imageUri
        if (uri == null) {
            _uiState.update { it.copy(errorMessage = UiMessage(R.string.editor_error_no_image)) }
            return
        }
        if (state.isExporting) return

        _uiState.update { it.copy(isExporting = true, errorMessage = null) }
        viewModelScope.launch {
            var fullBitmap: Bitmap? = null
            var rendered: Bitmap? = null
            try {
                fullBitmap = imageRepository.loadFullResolutionBitmap(uri)
                if (fullBitmap == null) {
                    _uiState.update {
                        it.copy(isExporting = false, errorMessage = UiMessage(R.string.editor_error_source_load))
                    }
                    return@launch
                }

                // 用原图坐标系渲染：此时 baseWidth == targetWidth，缩放系数为 1
                rendered = when (state.watermarkType) {
                    WatermarkType.TEXT -> renderer.render(
                        source = fullBitmap,
                        config = state.config,
                        sourceFileName = state.sourceFileName
                    )
                    WatermarkType.IMAGE -> {
                        val logo = state.logoBitmap
                        if (logo == null || logo.isRecycled) {
                            _uiState.update {
                                it.copy(
                                    isExporting = false,
                                    errorMessage = UiMessage(R.string.editor_error_no_logo)
                                )
                            }
                            return@launch
                        }
                        renderer.renderImage(
                            source = fullBitmap,
                            logo = logo,
                            config = state.imageConfig
                        )
                    }
                }

                val settings = state.exportSettings
                val fileName = FileNameResolver.build(
                    template = settings.fileNameTemplate,
                    sourceName = state.sourceFileName,
                    format = settings.format
                )

                // ---- 隐藏水印：先解析内容，再决定是否做频域嵌入 ----
                val invisible = state.invisibleConfig
                val invisibleContent = InvisibleContentResolver.resolve(
                    getApplication<Application>(),
                    invisible
                )

                // 频域嵌入必须发生在「渲染完成后、写文件前」。
                // 它对 rendered 做一次带噪声的复制，因此要回收原 rendered。
                //
                // 注意：`dctEnabled` 只控制**是否写频域**。EXIF 那条通道
                // 由下面的 saveBitmap(invisibleContent=...) 独立处理 ——
                // 用户关掉 DCT 仍然保留 EXIF 溯源，只是不抗压缩了。
                var toWrite = rendered
                if (invisibleContent != null && invisible.enabled && invisible.dctEnabled) {
                    val stamped = InvisibleWatermarkEngine(invisible.strength.toFloat())
                        .embed(rendered, invisibleContent)
                    if (stamped != null) {
                        rendered.takeIf { it != fullBitmap && !it.isRecycled }?.recycle()
                        toWrite = stamped
                        rendered = stamped
                    }
                }

                val result = exportRepository.saveBitmap(
                    bitmap = toWrite,
                    displayName = fileName,
                    format = settings.format,
                    jpegQuality = settings.jpegQuality,
                    albumPath = settings.customOutputDir,
                    sourceUri = uri,
                    preserveExif = settings.preserveExif,
                    invisibleContent = invisibleContent
                )

                if (result == null) {
                    _uiState.update {
                        it.copy(isExporting = false, errorMessage = UiMessage(R.string.editor_error_export_failed))
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            lastExportUri = result.uri,
                            infoMessage = UiMessage(R.string.editor_info_exported, result.displayName)
                        )
                    }
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isExporting = false, errorMessage = UiMessage(R.string.editor_error_export_exception, t.message ?: ""))
                }
            } finally {
                // 释放大对象，避免内存峰值滞留
                rendered?.takeIf { it != fullBitmap && !it.isRecycled }?.recycle()
                fullBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // 导出设置
    // ------------------------------------------------------------------

    fun updateExportSettings(settings: ExportSettings) {
        viewModelScope.launch { settingsRepository.updateExportSettings(settings) }
    }

    fun updateExportFormat(format: com.watermarkbox.data.model.ExportFormat) =
        updateExportSettings(_uiState.value.exportSettings.copy(format = format))

    fun updateJpegQuality(quality: Int) =
        updateExportSettings(
            _uiState.value.exportSettings.copy(jpegQuality = quality.coerceIn(1, 100))
        )

    fun updateDefaultAlpha(alpha: Float) =
        updateExportSettings(
            _uiState.value.exportSettings.copy(
                defaultAlpha = alpha.coerceIn(WatermarkRenderer.MIN_ALPHA, 1f)
            )
        )

    fun updateFileNameTemplate(template: String) =
        updateExportSettings(_uiState.value.exportSettings.copy(fileNameTemplate = template))

    fun updateCustomOutputDir(dir: String?) =
        updateExportSettings(
            _uiState.value.exportSettings.copy(
                customOutputDir = dir?.takeIf { it.isNotBlank() }
            )
        )

    fun togglePreserveExif() =
        updateExportSettings(
            _uiState.value.exportSettings.copy(
                preserveExif = !_uiState.value.exportSettings.preserveExif
            )
        )

    // ------------------------------------------------------------------
    // 隐藏水印（v1.3.0）
    // ------------------------------------------------------------------

    private inline fun updateInvisible(
        block: InvisibleWatermarkConfig.() -> InvisibleWatermarkConfig
    ) {
        _uiState.update { state ->
            state.copy(invisibleConfig = state.invisibleConfig.block())
        }
    }

    /**
     * 开关隐藏水印。
     *
     * ## 开启时套用设置里的「默认启用 DCT」
     *
     * 只在**从关到开**的那一刻套用一次，之后用户手动的开关不会被覆盖。
     * 这样设置页的默认值相当于"初始状态"，而不是"强制政策"。
     */
    override fun toggleInvisibleWatermark() {
        val willEnable = !_uiState.value.invisibleConfig.enabled
        val dctDefault = _uiState.value.exportSettings.invisibleDctByDefault
        updateInvisible {
            if (willEnable) {
                // 开启：把 DCT 设为设置里的默认值
                copy(enabled = true, dctEnabled = dctDefault)
            } else {
                // 关闭：只关总开关，保留 DCT 的选择以便下次开启沿用
                copy(enabled = false)
            }
        }
    }

    /** 更新自定义文字内容。 */
    override fun updateInvisibleContent(text: String) = updateInvisible { copy(content = text) }

    /** 切换内容来源（自定义 / 设备ID+时间戳）。 */
    override fun updateInvisibleSource(source: InvisibleSource) = updateInvisible { copy(source = source) }

    /** 更新嵌入强度倍数（1~4）。步长本身另有 MAX_STEP 硬上限兜底。 */
    override fun updateInvisibleStrength(strength: Int) =
        updateInvisible { copy(strength = strength.coerceIn(1, 4)) }

    /** 是否启用 DCT 频域嵌入（关闭 = 只写 EXIF，画质无损）。 */
    override fun toggleInvisibleDct() = updateInvisible { copy(dctEnabled = !dctEnabled) }

    // ------------------------------------------------------------------
    // 预设
    // ------------------------------------------------------------------

    /**
     * 把当前配置保存为预设。
     *
     * ## 重名处理
     *
     * UI 层在调用前应先检查是否重名（用 `_uiState.value.presets`），
     * 并询问用户后再传入 [overwrite]：
     * - `overwrite = true` → 替换旧的同名预设
     * - `overwrite = false` → 保留旧的，本次以「名称 (2)」等形式另存
     *
     * @param onSaved 保存完成后的回调，参数是**实际使用的名称**
     *                （`overwrite=false` 时可能与传入名称不同）
     */
    fun saveCurrentAsPreset(
        name: String,
        overwrite: Boolean = true,
        onSaved: ((String) -> Unit)? = null
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiMessage(R.string.editor_error_preset_name_empty)) }
            return
        }
        val state = _uiState.value
        val preset = WatermarkPreset(
            id = System.currentTimeMillis().toString(),
            name = trimmed,
            createdAt = System.currentTimeMillis(),
            textStyle = state.config,
            imageStyle = state.imageConfig,
            type = state.watermarkType
        )
        viewModelScope.launch {
            val finalName = settingsRepository.savePreset(preset, overwrite)
            val action = if (finalName == trimmed) {
                if (overwrite && state.presets.any { it.name == trimmed })
            R.string.editor_info_preset_replaced
        else R.string.editor_info_preset_saved
            } else {
                R.string.editor_info_preset_saved_as
            }
            _uiState.update { it.copy(infoMessage = UiMessage(action, finalName)) }
            onSaved?.invoke(finalName)
        }
    }

    /** 判断某个预设名是否已存在（UI 用于决定是否弹重名确认框）。 */
    fun hasPresetNamed(name: String): Boolean =
        _uiState.value.presets.any { it.name == name.trim() }

    /** 应用一个预设（同时切换水印类型）。 */
    fun applyPreset(preset: WatermarkPreset) {
        _uiState.update {
            it.copy(
                config = preset.textStyle,
                imageConfig = preset.imageStyle,
                watermarkType = preset.type,
                infoMessage = UiMessage(R.string.editor_info_preset_applied, preset.name)
            )
        }
        // 若预设引用了 logo 但当前未加载，尝试重新加载
        val logoUri = preset.imageStyle.logoUri
        if (logoUri != null && _uiState.value.logoBitmap == null) {
            viewModelScope.launch {
                runCatching { Uri.parse(logoUri) }
                    .getOrNull()
                    ?.let { loadLogo(it) }
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { settingsRepository.deletePreset(id) }
    }

    fun renamePreset(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { settingsRepository.renamePreset(id, trimmed) }
    }

    override fun onCleared() {
        super.onCleared()
        imageRepository.recycleCache()
        _uiState.update { it.copy(previewBitmap = null) }
    }
}