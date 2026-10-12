package com.watermarkbox.ui.screen.detect

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.invisible.DetectResult
import com.watermarkbox.invisible.InvisibleWatermarkEngine
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
    val result: DetectResult? = null
)

/**
 * 隐藏水印检测页 ViewModel。
 *
 * 流程：选图 → 后台检测（EXIF 优先，失败则走频域）→ 展示结果。
 */
class DetectViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = WatermarkDetector(application)

    private val _uiState = MutableStateFlow(DetectUiState())
    val uiState: StateFlow<DetectUiState> = _uiState.asStateFlow()

    /** 选择图片并立即开始检测。 */
    fun detect(uri: Uri) {
        _uiState.update { it.copy(imageUri = uri, isDetecting = true, result = null) }
        viewModelScope.launch {
            val engine = InvisibleWatermarkEngine()
            val result = detector.detect(uri, engine)
            _uiState.update { it.copy(isDetecting = false, result = result) }
        }
    }

    /** 清空结果，准备检测下一张。 */
    fun reset() = _uiState.update { it.copy(imageUri = null, result = null) }
}