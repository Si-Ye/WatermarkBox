package com.watermarkbox.ui.screen.presets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页「水印预设」页的 UI 状态。
 */
data class PresetsUiState(
    val presets: List<WatermarkPreset> = emptyList(),
    /** 是否已从磁盘加载完成（避免闪一下空状态） */
    val loaded: Boolean = false,
    /** 待删除确认的预设 */
    val pendingDelete: WatermarkPreset? = null,
    /** 操作反馈 */
    val message: String? = null
)

/**
 * 首页「水印预设」页 ViewModel。
 *
 * 从首页进入时，这里只做**管理**（查看 / 重命名 / 删除）。
 * 「应用」一个预设需要图片，因此由 UI 层负责：
 * 点预设 → 触发选图 → 带着 presetId 进编辑页。
 *
 * 为什么不在这里直接做「应用」：
 * 编辑页的 ViewModel 与图片加载强绑定，从首页跨越过去
 * 需要把 presetId 通过路由传递，由编辑页自己应用。
 */
class PresetsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(PresetsUiState())
    val uiState: StateFlow<PresetsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.presets.collect { list ->
                _uiState.update { it.copy(presets = list, loaded = true) }
            }
        }
    }

    fun requestDelete(preset: WatermarkPreset) =
        _uiState.update { it.copy(pendingDelete = preset) }

    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val target = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            settingsRepository.deletePreset(target.id)
            _uiState.update {
                it.copy(pendingDelete = null, message = "预设「${target.name}」已删除")
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }
}