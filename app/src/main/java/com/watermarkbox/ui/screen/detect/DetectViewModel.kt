package com.watermarkbox.ui.screen.detect

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
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
@Immutable
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
@Stable
class DetectViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = WatermarkDetector(application)

    private val _uiState = MutableStateFlow(DetectUiState())
    val uiState: StateFlow<DetectUiState> = _uiState.asStateFlow()

    /**
     * 检测请求令牌。
     *
     * ## 为什么需要（2026-10-16 补）
     *
     * [recheck] 的「防重入」只挡住了**同一个入口**的连点，
     * 挡不住「选图 A → 还没跑完 → 又选图 B」。那种情况下 A 的协程后完成，
     * 会把 `result` 写成 **A 的结论**，而 `imageUri` 已经是 B ——
     * 用户看到的是一个**挂错图的答案**。
     * 检测页的语义就是「**这张图**有没有水印」，答错比转圈严重得多。
     *
     * 做法与批量页 `BatchViewModel.previewToken` 一致：
     * 发起时发牌，回写前校验；不是最新那一次就直接丢弃。
     */
    private var detectToken = 0

    /**
     * 改检测选项。
     *
     * 顺带清掉已有结果与「开始前」的标记 —— 那份结果对应的是**旧选项**，
     * 留在屏幕上会让人以为「新选项也是这个结论」。
     * 用户可以点「用当前设置重新检测」在同一张图上跑一遍。
     *
     * 同时在跑的检测也要**作废**（它用的是旧选项），否则它会带着旧选项的
     * 结论回写，把这里的清空覆盖掉。
     */
    fun updateOptions(newOptions: DetectOptions) {
        detectToken++
        _uiState.update {
            it.copy(options = newOptions, result = null, isDetecting = false)
        }
    }

    /** 选择图片并立即开始检测。 */
    fun detect(uri: Uri) {
        val options = _uiState.value.options
        val token = ++detectToken
        _uiState.update { it.copy(imageUri = uri, isDetecting = true, result = null) }
        viewModelScope.launch {
            val result = detector.detect(uri, options)
            if (token != detectToken) return@launch  // 已被更新的请求取代，丢弃
            _uiState.update { it.copy(isDetecting = false, result = result) }
        }
    }

    /**
     * 用**当前选项**对同一张图重新检测。
     *
     * ## 为什么要有这个入口
     *
     * 「改设置 → 在同图上验证效果」是个高频动作。没有它，用户只能
     * 回相册重新选同一张图 —— 既烦琐，也无法确认「选的还是那张」。
     *
     * ## 防重入
     *
     * 已经在检测中就直接返回。否则连点会并发跑多个检测任务：
     * 既浪费算力（大图上每次都要 DCT 全图），也存在**结果乱序**风险 ——
     * 后完成的旧任务可能覆盖新任务的结果。
     *
     * 跨入口的乱序由 [detectToken] 兜住（见 [detect]）。
     */
    fun recheck() {
        if (_uiState.value.isDetecting) return
        _uiState.value.imageUri?.let { detect(it) }
    }

    /**
     * 清空结果，准备检测下一张。
     *
     * 同样要作废在跑的检测，并把 `isDetecting` 归位 ——
     * 否则那次检测会因为令牌过期而**提前返回**，
     * 没人再把 `isDetecting` 置回 false，页面就永远停在「检测中」。
     */
    fun reset() {
        detectToken++
        _uiState.update { it.copy(imageUri = null, result = null, isDetecting = false) }
    }
}