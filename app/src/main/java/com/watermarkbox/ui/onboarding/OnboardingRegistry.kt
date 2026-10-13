package com.watermarkbox.ui.onboarding

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 引导目标的**坐标收集器**。
 *
 * ## 为什么不让引导层自己去界面里找控件
 *
 * Compose 没有「按 id 查控件」的机制，而且那种做法会逼着引导层
 * 知道每个页面的内部结构 —— 页面一改，引导就悄悄指错地方。
 *
 * 反过来做：**控件主动上报自己的位置**。谁被聚焦，谁负责说
 * 「我在这」。引导层只认名字（[OnboardingTarget]），不认结构。
 *
 * ## 用窗口坐标而不是根坐标
 *
 * 引导层挂在一个独立的全屏 Dialog 里，它的坐标系起点是
 * **窗口**而不是 Activity 的内容区。`boundsInWindow()`
 * 正好是窗口坐标，两边能直接对齐。
 *
 * ## 为什么用 StateFlow 而不是普通 Map
 *
 * 位置会变（滚动、旋转、软键盘弹出、语言切换重建 Activity），
 * Compose 需要知道「变了」才能重绘遮罩。用 `MutableStateFlow`
 * 让这层变化自动传播。
 */
class OnboardingTargetRegistry {

    private val _bounds = MutableStateFlow<Map<OnboardingTarget, Rect>>(emptyMap())

    /** 当前已收集到的目标位置。 */
    val bounds: StateFlow<Map<OnboardingTarget, Rect>> = _bounds.asStateFlow()

    /** 由控件在布局完成后调用。 */
    fun report(target: OnboardingTarget, rect: Rect) {
        _bounds.update { current ->
            // 位置没变就不更新 —— 否则每次布局都会推动一次重组，
            // 而 onGloballyPositioned 在滚动时会被高频调用
            val old = current[target]
            if (old != null && old.left == rect.left && old.top == rect.top &&
                old.right == rect.right && old.bottom == rect.bottom
            ) {
                current
            } else {
                current + (target to rect)
            }
        }
    }

    /** 清空（引导结束时调用，避免持有旧坐标）。 */
    fun clear() = _bounds.update { emptyMap() }
}

/**
 * 把一个控件登记为引导目标 —— 它会持续上报自己在窗口中的位置。
 *
 * 用法：
 * ```
 * Button(
 *     onClick = { ... },
 *     modifier = Modifier.onboardingTarget(OnboardingTarget.ENTRY_PICK_IMAGE, registry)
 * )
 * ```
 *
 * 挂在**真实控件**上而不是外面套一层 Box，是为了让高亮孔正好
 * 框住这个控件本身（套 Box 会多出内边距，孔看着会偏大）。
 */
fun Modifier.onboardingTarget(
    target: OnboardingTarget,
    registry: OnboardingTargetRegistry
): Modifier = this.onGloballyPositioned { coordinates ->
    registry.report(target, coordinates.boundsInWindow())
}