package com.watermarkbox.ui.screen.settings

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.watermark.WatermarkRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页状态。
 */
data class SettingsUiState(
    val exportSettings: ExportSettings = ExportSettings(),
    val presetCount: Int = 0,
    val autoCheckUpdate: Boolean = true,
    val inAppDownload: Boolean = false
)

/**
 * 设置页 ViewModel（模块 3）。
 *
 * 管理：默认导出格式、默认透明度、文件名模板、输出目录。
 * 全部持久化在 DataStore，与编辑页/批量页共享同一份配置。
 *
 * 注：更新流程不在这里，见 [com.watermarkbox.ui.screen.update.UpdateViewModel]
 */
@Stable
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.exportSettings.collect { settings ->
                _uiState.update { it.copy(exportSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepository.presets.collect { list ->
                _uiState.update { it.copy(presetCount = list.size) }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoCheckUpdate.collect { enabled ->
                _uiState.update { it.copy(autoCheckUpdate = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.inAppDownload.collect { enabled ->
                _uiState.update { it.copy(inAppDownload = enabled) }
            }
        }
    }

    /** 切换「自动检查更新」。 */
    fun setAutoCheckUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoCheckUpdate = enabled) }
        viewModelScope.launch { settingsRepository.setAutoCheckUpdate(enabled) }
    }

    /**
     * 切换「应用内下载」开关。
     *
     * 只负责落库；是否需要先申请「安装未知应用」权限由 UI 层判断
     * （见 SettingsScreen 里的 showInAppPermissionDialog 处理）。
     */
    fun setInAppDownload(enabled: Boolean) {
        _uiState.update { it.copy(inAppDownload = enabled) }
        viewModelScope.launch { settingsRepository.setInAppDownload(enabled) }
    }

    fun updateExportSettings(settings: ExportSettings) {
        _uiState.update { it.copy(exportSettings = settings) }
        viewModelScope.launch { settingsRepository.updateExportSettings(settings) }
    }

    fun updateFormat(format: ExportFormat) =
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
            _uiState.value.exportSettings.copy(customOutputDir = dir?.takeIf { it.isNotBlank() })
        )

    fun togglePreserveExif() =
        updateExportSettings(
            _uiState.value.exportSettings.copy(
                preserveExif = !_uiState.value.exportSettings.preserveExif
            )
        )

    /** 修改「新图片的默认水印文字」。 */
    fun updateDefaultWatermarkText(text: String) =
        updateExportSettings(_uiState.value.exportSettings.copy(defaultWatermarkText = text))

    /** 设置默认 logo（传 null 表示清除）。 */
    fun updateDefaultLogoUri(uri: String?) =
        updateExportSettings(_uiState.value.exportSettings.copy(defaultLogoUri = uri))

    /** 是否在开启隐藏水印时默认启用 DCT（编辑页初始值）。 */
    fun toggleInvisibleDctByDefault() =
        updateExportSettings(
            _uiState.value.exportSettings.copy(
                invisibleDctByDefault = !_uiState.value.exportSettings.invisibleDctByDefault
            )
        )
}