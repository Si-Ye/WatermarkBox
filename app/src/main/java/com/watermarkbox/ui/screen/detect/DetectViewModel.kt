package com.watermarkbox.ui.screen.detect

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.invisible.DetectOptions
import com.watermarkbox.invisible.DetectResult
import com.watermarkbox.invisible.WatermarkDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 隐藏水印检测页 UI 状态。
 */
data class DetectUiState(
    /** 选中的图片 */
    val imageUri: Uri? = null,
    /** 是否正在检测 */
    val isDetecting: Boolean = false,
    /** 检测结果 */
    val result: DetectResult? = null,
    /** 检测选项（通道 / 强度 / 方向）—— 决定检测要多快、多全 */
    val options: DetectOptions = DetectOptions.Default
)

/**
 * 隐藏水印检测页 ViewModel。
 *
 * 流程：选图 → 后台检测（按 [DetectOptions] 决定查哪些通道）→ 展示结果。
 */
class DetectViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = WatermarkDetector(application)

    private val _uiState = MutableStateFlow(DetectUiState())
    val uiState: StateFlow<DetectUiState> = _uiState.asStateFlow()

    /**
     * 改检测选项。
     *
     * 顺带清掉已有结果 —— 那份结果对应的是**旧选项**，
     * 留在屏幕上会让人以为「新选项也是这个结论」。
     * 用户可以点「重新检测」用新选项再跑一遍。
     */
    fun updateOptions(newOptions: DetectOptions) {
        _uiState.update { it.copy(options = newOptions, result = null) }
    }

    /** 选择图片并立即开始检测。 */
    fun detect(uri: Uri) {
        val options = _uiState.value.options
        _uiState.update { it.copy(imageUri = uri, isDetecting = true, result = null) }
        viewModelScope.launch {
            val result = detector.detect(uri, options)
            _uiState.update { it.copy(isDetecting = false, result = result) }
        }
    }

    /** 用**当前选项**对同一张图重新检测。 */
    fun recheck() {
        _uiState.value.imageUri?.let { detect(it) }
    }

    /** 清空结果，准备检测下一张。 */
    fun reset() = _uiState.update { it.copy(imageUri = null, result = null) }
}