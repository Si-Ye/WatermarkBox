package com.watermarkbox.ui.screen.home

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.ui.onboarding.OnboardingSteps
import com.watermarkbox.ui.onboarding.OnboardingTargetRegistry
import com.watermarkbox.ui.onboarding.SpotlightStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 新手引导的显示状态。
 *
 * ## 为什么单独一个 ViewModel，而不是塞进 HomeScreen
 *
 * [HomeScreen] 是**纯 UI**：所有动作都通过回调交给上层，自己不做任何 I/O ——
 * 这样它才能在 `@Preview` 里正常渲染。引导状态要读 DataStore，
 * 塞进去会让预览直接崩（Preview 没有 ViewModelStoreOwner）。
 *
 * 放在这里、由导航层持有，职责也更清楚：
 * 「该不该显示引导」是**应用级**状态，不属于首页本身。
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    /** 各控件上报的窗口坐标。 */
    val registry = OnboardingTargetRegistry()

    /** 引导脚本。目前只有首页一套。 */
    private val steps: List<SpotlightStep> = OnboardingSteps.HOME

    private val _active = MutableStateFlow(false)

    /** 是否正在显示引导。 */
    val visible: StateFlow<Boolean> = _active.asStateFlow()

    private val _index = MutableStateFlow(0)

    /** 当前步骤序号。 */
    val stepIndex: StateFlow<Int> = _index.asStateFlow()

    /** 总步数（气泡上的进度点要用）。 */
    val stepCount: Int get() = steps.size

    /**
     * 当前步骤 + 它对应控件的坐标，**合成一个值**给 UI。
     *
     * 分开给两个 Flow 也能用，但 UI 每次都要自己判「步骤和坐标是否配对」；
     * 合成后，UI 拿到的要么是完整的一对、要么是 null，少一类边界情况。
     *
     * 坐标可能暂时为 null：首帧、或目标控件还没组合出来。
     * 此时遮罩照常显示，只是还没有高亮孔 —— 这比「整层不显示」
     * 或者「指向 (0,0)」都更合理。
     */
    val current: StateFlow<Pair<SpotlightStep, Rect?>?> =
        combine(_active, _index, registry.bounds) { active, index, bounds ->
            if (!active) return@combine null
            val step = steps.getOrNull(index) ?: return@combine null
            step to bounds[step.target]
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            // 只在初始化时读一次：本次会话内不再变，
            // 用户完成后由 finish() 直接改状态，不必再查库
            _active.value = !repository.isOnboardingDone()
        }
    }

    /** 下一步；已在最后一步时等同完成。 */
    fun next() {
        if (_index.value >= steps.lastIndex) {
            finish()
        } else {
            _index.value += 1
        }
    }

    /** 跳过 / 完成 —— 记下来，下次不再显示。 */
    fun finish() {
        _active.value = false
        registry.clear()
        viewModelScope.launch { repository.setOnboardingDone() }
    }

    /**
     * 重新看一遍引导。
     *
     * 供「设置 → 其他 → 使用教程」页里的按钮调用。
     *
     * ## 为什么要清掉持久化的标记
     *
     * 只把 `_active` 设回 true 的话，这次会话能看到引导，
     * 但**下次冷启动又没了** —— 用户会以为"按钮只有一次有效"。
     * 所以这里把「已看过」标记也一并重置，行为才和第一次安装一致。
     */
    fun restart() {
        viewModelScope.launch { repository.setOnboardingDone(done = false) }
        _index.value = 0
        _active.value = true
    }
}