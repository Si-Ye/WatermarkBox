package com.watermarkbox.ui.screen.home

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.ui.onboarding.OnboardingSteps
import com.watermarkbox.ui.onboarding.OnboardingTargetRegistry
import com.watermarkbox.ui.onboarding.OnboardingTour
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

    private val _tour = MutableStateFlow<OnboardingTour?>(null)
    private val _index = MutableStateFlow(0)

    /**
     * 引导的全部对外状态，**聚合在一个值里**。
     *
     * 分开暴露「步骤 / 序号 / 坐标 / 总数」四个 Flow 也能用，但 UI 每次都要
     * 自己判断它们是否配对（比如"步骤换了但坐标还是上一个目标的"）。
     * 合成一个快照后，UI 拿到的要么是完整的一组、要么是 null。
     */
    data class Snapshot(
        val tour: OnboardingTour,
        val step: SpotlightStep,
        val stepIndex: Int,
        val stepCount: Int,
        /** 目标控件的窗口坐标；首帧或目标未组合时为 null。 */
        val anchor: Rect?
    )

    val current: StateFlow<Snapshot?> = combine(
        _tour, _index, registry.bounds
    ) { tour, index, bounds ->
        if (tour == null) return@combine null
        val steps = OnboardingSteps.of(tour)
        val step = steps.getOrNull(index) ?: return@combine null
        Snapshot(
            tour = tour,
            step = step,
            stepIndex = index,
            stepCount = steps.size,
            anchor = bounds[step.target]
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /**
     * 页面进入时调用：该套引导若没看过就开始。
     *
     * ## 为什么要判「当前没有别的引导在跑」
     *
     * 用户可能从首页一路点到预设页（首页引导还没走完），
     * 这时若直接切换，首页那套就被无声中断、且**不会被标记为已看过** ——
     * 下次回首页又从头来一遍，很烦。
     */
    fun startIfNeeded(tour: OnboardingTour) {
        viewModelScope.launch {
            if (repository.isOnboardingDone(tour.name)) return@launch
            if (_tour.value != null) return@launch
            _index.value = 0
            _tour.value = tour
        }
    }

    /** 下一步；已在最后一步时等同完成。 */
    fun next() {
        val tour = _tour.value ?: return
        val count = OnboardingSteps.of(tour).size
        if (_index.value >= count - 1) {
            finish()
        } else {
            _index.value += 1
        }
    }

    /** 跳过 / 完成 —— 记下来，下次不再显示。 */
    fun finish() {
        val tour = _tour.value ?: return
        _tour.value = null
        registry.clear()
        viewModelScope.launch { repository.setOnboardingDone(tour.name) }
    }

    /**
     * 重新看一遍某套引导。
     *
     * 供「设置 → 其他 → 使用教程」页里的按钮调用。
     *
     * ## 为什么要清掉持久化的标记
     *
     * 只把状态设回去的话，这次会话能看到引导，
     * 但**下次冷启动又没了** —— 用户会以为「这个按钮只有一次有效」。
     * 所以把「已看过」标记也一并重置，行为才和第一次安装一致。
     */
    fun restart(tour: OnboardingTour) {
        viewModelScope.launch { repository.setOnboardingDone(tour.name, done = false) }
        _index.value = 0
        _tour.value = tour
    }
}