package com.watermarkbox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 面板拖拽的两段式行为参数。
 *
 * ## 两段式设计
 *
 * 以 [shrinkEndFraction]（默认 70%）为界：
 *
 * ```
 *              ┌─────────────────┐  ← 屏幕顶
 *              │                 │
 *   阶段一      │   预览图片       │  预览占满「面板上方的整个区域」
 *   (< 70%)    │   （随面板变高    │  面板越高 → 上方区域越小 → 图片越小
 *              │     而变小）      │
 *              ├─────────────────┤  ← 面板顶边（随拖动上移）
 *   阶段二      │                 │
 *   (≥ 70%)    │   ↑ 面板继续上升  │  图片尺寸【冻结】，
 *              │   盖住预览       │  面板顶边越过图片底部，把它遮住
 *              │   水印参数       │
 *              └─────────────────┘  ← 屏幕底
 * ```
 *
 * ### 阶段一 `[minFraction, shrinkEndFraction]`
 * 预览区高度 = **容器高 − 面板高**，即占满面板上方的全部空间。
 * 面板升高多少，预览区就矮多少，缩略图（Fit）随之等比变小。
 *
 * ### 阶段二 `(shrinkEndFraction, maxFraction]`
 * 预览区高度**冻结**在分界点的值，不再变小。
 * 面板继续升高时，其上边缘会越过预览区域，把它**盖住**。
 *
 * ## 关键：预览必须「贴顶」
 *
 * 预览区必须用 [Alignment.TopCenter] 贴住屏幕顶部，
 * 而不是居中 —— 否则阶段二面板上移时，预览会浮在中间显得很怪。
 */
data class PanelDragBehavior(
    /** 最小面板占比（仅露把手） */
    val minFraction: Float = 0.20f,
    /** 最大面板占比（全屏） */
    val maxFraction: Float = 1f,
    /**
     * 阶段一结束 / 阶段二开始的分界点。
     * 面板超过此比例后，预览尺寸冻结、面板开始覆盖预览。
     */
    val shrinkEndFraction: Float = 0.70f
) {
    /**
     * 根据当前面板占比计算「预览区高度占比」（0~1）。
     *
     * - 阶段一：`1 - panelFraction`（占满面板上方剩余空间）
     * - 阶段二：冻结在 `1 - shrinkEndFraction`
     */
    fun previewHeightFraction(panelFraction: Float): Float {
        val f = panelFraction.coerceIn(minFraction, maxFraction)
        return if (f <= shrinkEndFraction) {
            (1f - f).coerceIn(0f, 1f)
        } else {
            (1f - shrinkEndFraction).coerceIn(0f, 1f)
        }
    }

    /** 是否处于阶段二（面板正在覆盖预览）。 */
    fun isOverlaying(panelFraction: Float): Boolean = panelFraction > shrinkEndFraction
}

/**
 * 可拖拽调整高度的底部面板（两段式）。
 *
 * @param fraction         当前面板占比，由外部状态持有
 * @param onFractionChange 拖拽回调
 * @param behavior         两段式行为参数
 * @param dragHandle       把手内容
 * @param content          面板主体（应自带滚动）
 */
@Composable
fun DraggableBottomPanel(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    behavior: PanelDragBehavior = PanelDragBehavior(),
    dragHandle: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        // 拖拽中的临时值：null 表示「未在拖拽」，此时完全跟随外部 fraction。
        var dragFraction: Float? by remember { mutableStateOf<Float?>(null) }
        val clamped = (dragFraction ?: fraction).coerceIn(
            behavior.minFraction,
            behavior.maxFraction
        )
        val panelHeight = maxHeight * clamped

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 拖拽把手 ----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    // delta 向上为负 → 面板变高
                                    val deltaFraction = -delta / containerHeightPx
                                    val next = (clamped + deltaFraction).coerceIn(
                                        behavior.minFraction,
                                        behavior.maxFraction
                                    )
                                    dragFraction = next
                                    onFractionChange(next)
                                },
                                onDragStopped = {
                                    val current = dragFraction ?: clamped
                                    val snapped = when {
                                        current >= 0.94f -> behavior.maxFraction
                                        current <= behavior.minFraction + 0.04f ->
                                            behavior.minFraction
                                        abs(current - behavior.shrinkEndFraction) < 0.03f ->
                                            behavior.shrinkEndFraction
                                        else -> current
                                    }
                                    dragFraction = null   // 交还控制权给外部状态
                                    onFractionChange(snapped)
                                }
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                    )
                            )
                            Box(modifier = Modifier.padding(top = 4.dp)) {
                                dragHandle()
                            }
                        }
                    }

                    // ---- 面板内容 ----
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 预览区容器：**贴顶**占满「面板上方的剩余空间」。
 *
 * 与 [DraggableBottomPanel] 配对，传入相同 fraction 与 behavior：
 *
 * - **阶段一**：高度 = 容器高 − 面板高，预览占满这片区域并随之变小
 * - **阶段二**：高度冻结，面板上边缘越过预览把它盖住
 *
 * ## 为什么必须贴顶（Alignment.TopCenter）
 *
 * 若居中放置，阶段二面板上升时预览会「浮」在屏幕中间，
 * 与「面板从下方盖上来」的预期不符。
 * 贴顶后，面板顶边上移即可自然遮住预览，视觉上连续且符合直觉。
 */
@Composable
fun ShrinkablePreviewArea(
    fraction: Float,
    behavior: PanelDragBehavior = PanelDragBehavior(),
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val target = behavior.previewHeightFraction(fraction)

        val animatedFraction by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 120),
            label = "previewHeightFraction"
        )

        val previewHeight = maxHeight * animatedFraction

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                // 关键：贴顶，让面板从下方盖上来时视觉连续
                .align(Alignment.TopCenter)
        ) {
            // 把「受限高度」通过 Modifier 传给内容。
            // 内容（WatermarkPreview）用 Image(fillMaxSize + ContentScale.Fit)，
            // 此时高度是唯一限制条件 → 区域变矮图片必然变小。
            content(Modifier.fillMaxSize())
        }
    }
}
