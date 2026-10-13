package com.watermarkbox.ui.screen.presets

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.R
import com.watermarkbox.util.UiMessage
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.data.model.InvisibleWatermarkConfig
import com.watermarkbox.data.model.WatermarkColor
import com.watermarkbox.data.repository.FontRepository
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.ui.screen.editor.WatermarkPanelHost
import com.watermarkbox.ui.screen.editor.WatermarkPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「预设参数编辑页」的 UI 状态。
 *
 * 这里持有的是**一份可编辑的水印样式副本**，与编辑页的 [EditorUiState] 不同：
 * 没有图片相关的字段（`imageUri` / `previewBitmap` / 尺寸…），因为预设
 * 描述的是「水印长什么样」，与具体图片无关。
 */
@Immutable
data class PresetEditUiState(
    /** 是否已从磁盘读入原预设（false 时 UI 显示加载中，避免闪一下默认值） */
    val loaded: Boolean = false,
    /** 预设原名称（用于判断是否被改过、以及重名检查时排除自身） */
    val originalName: String = "",
    /** 当前名称（可编辑） */
    val name: String = "",
    /** 文字水印样式 */
    val config: TextWatermarkStyle = TextWatermarkStyle.Default,
    /** 图片水印样式 */
    val imageConfig: ImageWatermarkStyle = ImageWatermarkStyle.Default,
    /** 当前编辑的水印类型 */
    val watermarkType: WatermarkType = WatermarkType.TEXT,
    /** 已导入的自定义字体文件名列表（面板里要列出供选择） */
    val customFonts: List<String> = emptyList(),
    /** 是否已保存并触发退出 */
    val saved: Boolean = false,
    /** 错误 / 提示 */
    val message: UiMessage? = null
)

/**
 * [PresetEditUiState] → [WatermarkPanelState] 的映射（预设编辑页用）。
 *
 * 与编辑页的映射相比，关键差异是 `showInvisibleSection = false`：
 * 隐藏水印属于导出选项，预设不存它，所以这一区不该出现。
 *
 * `logoBitmap` 留空 —— 预设里的 logo 位图无法在此页选出（见类注释）。
 */
fun PresetEditUiState.toPanelState(): WatermarkPanelState = WatermarkPanelState(
    watermarkType = watermarkType,
    config = config,
    imageConfig = imageConfig,
    logoBitmap = null,
    customFonts = customFonts,
    invisibleConfig = InvisibleWatermarkConfig.Default,
    showInvisibleSection = false
)

/**
 * 预设参数编辑页 ViewModel（无图片预览）。
 *
 * ## 与 [com.watermarkbox.ui.screen.editor.EditorViewModel] 的关系
 *
 * 两者都实现 [WatermarkPanelHost]，因此**共用同一个参数面板 UI**
 * （`WatermarkPanel`）。区别在于：
 *
 * | 能力 | 编辑页 | 本页 |
 * |---|---|---|
 * | 加载图片 / 渲染预览 | ✅ | ❌ |
 * | 导出 | ✅ | ❌ |
 * | 编辑水印参数 | ✅ | ✅ |
 * | 保存去向 | 存为新预设 / 应用 | 更新自身那条预设 |
 *
 * ## 隐藏水印 / EXIF 不在这里
 *
 * 这两项属于**导出选项**（决定"怎么导出"），而预设只描述
 * "水印长什么样"。所以本页不出现在预设数据结构里，也就没有对应 UI。
 * 面板里对应的分区会由 `WatermarkPanel` 根据宿主能力隐藏 —— 见
 * [supportsExportSettings]。
 *
 * ## 图片水印的 logo 无法在此选择
 *
 * logo 本身就是一张图片，预设里存的是它的**位图数据**（`ImageWatermarkStyle`
 * 内嵌），不是路径。本页没有选图入口，所以只能编辑 logo 的**样式**
 * （大小 / 位置 / 透明度…），不能换 logo 本身。UI 会给出说明。
 */
@Stable
class PresetEditViewModel(application: Application) : AndroidViewModel(application), WatermarkPanelHost {

    private val settingsRepository = SettingsRepository(application)
    private val fontRepository = FontRepository(application)

    private val _uiState = MutableStateFlow(PresetEditUiState())
    val uiState: StateFlow<PresetEditUiState> = _uiState.asStateFlow()

    /** 正在编辑的预设 id（null = 尚未指定，视为不可保存）。 */
    private var presetId: String? = null

    /**
     * 本页是否提供「导出相关」设置。
     *
     * 恒为 `false` —— 预设不含导出选项。放在这里是为了让
     * `WatermarkPanel` 能用统一方式询问宿主，而不必判断具体类型。
     */
    val supportsExportSettings: Boolean = false

    /**
     * 载入一个预设进入编辑态。
     *
     * 若重复调用（例如重组导致 LaunchedEffect 重跑），只在**首次**生效，
     * 避免把用户已经改过的内容重置掉。
     */
    fun loadPreset(id: String) {
        if (presetId != null) return
        presetId = id
        viewModelScope.launch {
            val preset = settingsRepository.getPresets().firstOrNull { it.id == id }
            if (preset == null) {
                _uiState.update { it.copy(loaded = true, message = UiMessage(R.string.preset_error_not_found)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    loaded = true,
                    originalName = preset.name,
                    name = preset.name,
                    config = preset.textStyle,
                    imageConfig = preset.imageStyle,
                    watermarkType = preset.type
                )
            }
        }
        // 自定义字体列表（供面板选择）。
        // FontRepository.listFonts() 是同步的（读目录），直接取一次即可，
        // 不需要 Flow —— 本页也不提供导入/删除字体（那是设置页的职责）。
        _uiState.update { it.copy(customFonts = fontRepository.listFonts()) }
    }

    // ---------------- 名称 ----------------

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }

    /** 名称是否被改过（决定保存时要不要做重名检查）。 */
    fun isNameChanged(): Boolean {
        val s = _uiState.value
        return s.name.trim() != s.originalName
    }

    /**
     * 保存。重名时先询问由 UI 决定（见 [presetNamesExceptSelf]）。
     *
     * @param overwriteSameName 若改名后与**其它**预设撞名：
     *   `true` = 替换掉那条同名预设（用户确认），`false` = 取消保存
     */
    fun save(overwriteSameName: Boolean = false) {
        val id = presetId ?: return
        val s = _uiState.value
        val name = s.name.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(message = UiMessage(R.string.preset_error_name_empty)) }
            return
        }
        viewModelScope.launch {
            val all = settingsRepository.getPresets()
            val clash = all.firstOrNull { it.id != id && it.name == name }
            if (clash != null && !overwriteSameName) {
                // 交给 UI 弹确认框
                _uiState.update { it.copy(message = UiMessage(resId = 0)) }
                return@launch
            }
            if (clash != null) {
                // 用户选择替换：先删掉冲突那条，再更新自身（保住自己的 id/createdAt）
                settingsRepository.deletePreset(clash.id)
            }
            val ok = settingsRepository.updatePresetStyle(
                id = id,
                name = name,
                type = s.watermarkType,
                textStyle = s.config,
                imageStyle = s.imageConfig
            )
            _uiState.update {
                it.copy(
                    saved = ok,
                    message = if (ok) null else UiMessage(R.string.preset_error_save_failed)
                )
            }
        }
    }

    /** 除自己以外所有预设的名称（供 UI 做重名检查）。 */
    suspend fun presetNamesExceptSelf(): List<String> {
        val id = presetId ?: return emptyList()
        return settingsRepository.getPresets().filter { it.id != id }.map { it.name }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    // ==================================================================
    // WatermarkPanelHost 实现
    //
    // 这些方法只改内存中的 [_uiState]；点「保存」才写回磁盘。
    // ==================================================================

    private inline fun updateConfig(block: TextWatermarkStyle.() -> TextWatermarkStyle) =
        _uiState.update { it.copy(config = it.config.block()) }

    private inline fun updateImageConfig(
        block: ImageWatermarkStyle.() -> ImageWatermarkStyle
    ) = _uiState.update { it.copy(imageConfig = it.imageConfig.block()) }

    override fun updateWatermarkType(type: WatermarkType) =
        _uiState.update { it.copy(watermarkType = type) }

    override fun updateText(text: String) = updateConfig { copy(text = text) }

    override fun updateFontSize(size: Float) = updateConfig { copy(fontSize = size) }

    override fun updateFontFamily(family: FontFamilyOption) =
        updateConfig { copy(fontFamily = family) }

    override fun updateColor(color: Color) = updateConfig { copy(color = WatermarkColor(color)) }

    override fun toggleBold() = updateConfig { copy(bold = !bold) }

    override fun toggleItalic() = updateConfig { copy(italic = !italic) }

    override fun toggleStroke() = updateConfig { copy(strokeEnabled = !strokeEnabled) }

    override fun updateStrokeWidth(width: Float) = updateConfig { copy(strokeWidth = width) }

    override fun updateStrokeColor(color: Color) = updateConfig { copy(strokeColor = WatermarkColor(color)) }

    override fun toggleShadow() = updateConfig { copy(shadowEnabled = !shadowEnabled) }

    override fun updateShadowRadius(radius: Float) = updateConfig { copy(shadowRadius = radius) }

    override fun updateLayoutMode(mode: LayoutMode) = updateConfig { copy(layoutMode = mode) }

    override fun updateTileCount(count: Int) = updateConfig { copy(tileCount = count) }

    override fun updateRotation(degrees: Float) = updateConfig { copy(rotationDegrees = degrees) }

    override fun updateAlpha(alpha: Float) = updateConfig { copy(alpha = alpha) }

    override fun updateGravity(gravity: GravityPosition) = updateConfig { copy(gravity = gravity) }

    override fun updateMargin(margin: Float) = updateConfig { copy(margin = margin) }

    override fun updateLogoWidth(percent: Float) =
        updateImageConfig { copy(widthPercent = percent) }

    override fun updateImageLayoutMode(mode: LayoutMode) =
        updateImageConfig { copy(layoutMode = mode) }

    override fun updateImageTileCount(count: Int) = updateImageConfig { copy(tileCount = count) }

    override fun updateImageRotation(degrees: Float) =
        updateImageConfig { copy(rotationDegrees = degrees) }

    override fun updateImageAlpha(alpha: Float) = updateImageConfig { copy(alpha = alpha) }

    override fun updateImageGravity(gravity: GravityPosition) =
        updateImageConfig { copy(gravity = gravity) }

    override fun updateImageMargin(margin: Float) = updateImageConfig { copy(margin = margin) }

    override fun togglePreserveAspectRatio() =
        updateImageConfig { copy(preserveAspectRatio = !preserveAspectRatio) }

    /**
     * 选中自定义字体。
     *
     * 本页**只改字体选择**，不涉及导入 / 删除文件 ——
     * 那属于设置页的职责。因此 [deleteCustomFont] 在本页是空实现。
     */
    override fun selectCustomFont(fileName: String) = updateConfig {
        copy(fontFamily = FontFamilyOption.CUSTOM, customFontName = fileName)
    }

    /** 本页不支持删除字体文件（见 [selectCustomFont] 说明），空实现。 */
    override fun deleteCustomFont(fileName: String) = Unit

    /**
     * 清除 logo。
     *
     * 预设里没有独立可选的 logo 图片（见类注释），因此这里只把
     * logo 样式复位到默认值，避免留下一个"看起来清空了但还占着位"的状态。
     */
    override fun clearLogo() = updateImageConfig { ImageWatermarkStyle.Default }

    // ---------------- 隐藏水印：本页不支持 ----------------
    // 隐藏水印是「导出选项」而非「水印样式」，预设数据结构里不含它。
    // 这些方法必须存在（接口要求），但不会有 UI 触发。

    override fun toggleInvisibleWatermark() = Unit

    override fun updateInvisibleContent(text: String) = Unit

    override fun updateInvisibleSource(source: InvisibleSource) = Unit

    override fun updateInvisibleStrength(strength: Int) = Unit

    /** 本页不支持隐藏水印（见上方说明），空实现。 */
    override fun toggleInvisibleExif() = Unit

    /** 本页不支持隐藏水印（见上方说明），空实现。 */
    override fun toggleInvisibleDct() = Unit
}
