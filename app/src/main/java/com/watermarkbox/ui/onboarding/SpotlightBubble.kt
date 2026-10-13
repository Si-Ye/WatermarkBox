package com.watermarkbox.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import kotlin.math.roundToInt

/**
 * 气泡的**摆放方案**：算完之后交给 UI 原样绘制。
 *
 * 单独建这个类型，是为了让「怎么摆」和「怎么画」分开 ——
 * 摆放逻辑是纯数学，可以写单元测试；绘制逻辑依赖 Compose，只能靠肉眼看。
 */
data class BubblePlacement(
    /** 实际采用的方向（AUTO 会被解析成具体值）。 */
    val side: BubbleSide,
    /** 气泡矩形（窗口坐标）。 */
    val bubble: Rect,
    /** 箭头尖端的位置（窗口坐标）—— 指向目标中心。 */
    val arrowTip: Offset,
    /** 箭头底部中心（贴在气泡边缘上）。 */
    val arrowBase: Offset
)

/** 气泡与目标之间的间隙。 */
private val BUBBLE_GAP = 10.dp

/** 箭头长度。 */
private val ARROW_LENGTH = 10.dp

/** 气泡与屏幕边缘的最小距离。 */
private val SCREEN_MARGIN = 12.dp

/**
 * 计算气泡摆在哪 —— **纯函数，无 Compose 依赖**，可以直接单测。
 *
 * ## 输入输出都用窗口坐标
 *
 * [anchor] 来自 `onGloballyPositioned` 的 `boundsInWindow`，
 * [screenSize] 是整窗口尺寸。两者同一坐标系，算出来的
 * [BubblePlacement] 才能直接拿去摆放。
 *
 * ## 处理顺序（顺序很重要）
 *
 * 1. **解析方向**：AUTO 看目标上方放不放得下
 * 2. **算理想位置**：按方向把气泡放在目标旁边，并让它在垂直于方向的那一轴居中
 * 3. **夹紧到屏幕内**：理想位置可能跑出屏幕（目标贴边时），需要平移回来
 * 4. **最后一算箭头**：箭头必须**落在气泡边缘范围内**，
 *    否则会出现"箭头浮在气泡外面的空气里"这种明显错位
 */
fun computeBubblePlacement(
    anchor: Rect,
    screenSize: Size,
    bubbleSize: Size,
    preferred: BubbleSide,
    densityScale: Float,
    gapPx: Float = BUBBLE_GAP.value * densityScale,
    arrowPx: Float = ARROW_LENGTH.value * densityScale,
    marginPx: Float = SCREEN_MARGIN.value * densityScale
): BubblePlacement {
    // ---- 1. 解析方向 ----
    val needAbove = bubbleSize.height + gapPx + arrowPx + marginPx
    val side = when (preferred) {
        BubbleSide.AUTO ->
            if (anchor.top >= needAbove) BubbleSide.TOP else BubbleSide.BOTTOM
        else -> preferred
    }

    // ---- 2. 理想位置 ----
    val anchorCenterX = (anchor.left + anchor.right) / 2f
    val anchorCenterY = (anchor.top + anchor.bottom) / 2f

    var left: Float
    var top: Float
    when (side) {
        BubbleSide.TOP, BubbleSide.BOTTOM -> {
            left = anchorCenterX - bubbleSize.width / 2f
            top = if (side == BubbleSide.TOP) {
                anchor.top - gapPx - arrowPx - bubbleSize.height
            } else {
                anchor.bottom + gapPx + arrowPx
            }
        }
        BubbleSide.START, BubbleSide.END -> {
            left = if (side == BubbleSide.START) {
                anchor.left - gapPx - arrowPx - bubbleSize.width
            } else {
                anchor.right + gapPx + arrowPx
            }
            top = anchorCenterY - bubbleSize.height / 2f
        }
        BubbleSide.AUTO -> {
            // 上面已经解析过，这里不会走到
            left = anchorCenterX - bubbleSize.width / 2f
            top = anchor.bottom + gapPx + arrowPx
        }
    }

    // ---- 3. 夹紧到屏幕内 ----
    left = left.coerceIn(
        marginPx,
        (screenSize.width - bubbleSize.width - marginPx).coerceAtLeast(marginPx)
    )
    top = top.coerceIn(
        marginPx,
        (screenSize.height - bubbleSize.height - marginPx).coerceAtLeast(marginPx)
    )

    val bubbleRect = Rect(left, top, left + bubbleSize.width, top + bubbleSize.height)

    // ---- 4. 箭头 ----
    //
    // 尖端对准目标中心，但要**沿气泡边缘夹紧**：
    // 目标在屏幕最左时，气泡被推到左边距处，此时尖端若仍取目标中心，
    // 就会跑到气泡左边缘之外。
    val halfArrowSpan = 12f * densityScale   // 尖端离气泡圆角至少留这点距离
    val tip: Offset
    val base: Offset
    when (side) {
        BubbleSide.TOP, BubbleSide.BOTTOM, BubbleSide.AUTO -> {
            val clampedX = anchorCenterX.coerceIn(
                bubbleRect.left + halfArrowSpan,
                bubbleRect.right - halfArrowSpan
            )
            tip = Offset(clampedX, anchorCenterY.coerceIn(anchor.top, anchor.bottom))
            base = Offset(
                clampedX,
                if (side == BubbleSide.TOP) bubbleRect.bottom else bubbleRect.top
            )
        }
        BubbleSide.START, BubbleSide.END -> {
            val clampedY = anchorCenterY.coerceIn(
                bubbleRect.top + halfArrowSpan,
                bubbleRect.bottom - halfArrowSpan
            )
            tip = Offset(anchorCenterX.coerceIn(anchor.left, anchor.right), clampedY)
            base = Offset(
                if (side == BubbleSide.START) bubbleRect.right else bubbleRect.left,
                clampedY
            )
        }
    }

    return BubblePlacement(side = side, bubble = bubbleRect, arrowTip = tip, arrowBase = base)
}

/**
 * 引导气泡本体。
 *
 * ## 布局方式：绝对定位 + IntOffset
 *
 * 气泡不参与任何 Row/Column 的流式布局 —— 它的位置完全由
 * [computeBubblePlacement] 算出来。用 `offset { IntOffset(...) }`
 * 把它放到算好的坐标上，是唯一能精确对齐的做法。
 */
@Composable
fun SpotlightBubble(
    placement: BubblePlacement,
    title: String,
    body: String,
    stepIndex: Int,
    stepCount: Int,
    isLast: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .offsetToRect(placement.bubble)
            .widthIn(max = 300.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 标题 ----
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            // ---- 正文 ----
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            // ---- 底部：进度点 · 跳过 · 下一步 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StepDots(total = stepCount, current = stepIndex)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isLast) {
                        TextButton(onClick = onSkip) {
                            Text(
                                text = stringResource(R.string.onboarding_skip),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onNext) {
                        Text(
                            text = stringResource(
                                if (isLast) R.string.onboarding_start else R.string.onboarding_next
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把组件精确放到窗口坐标系里的某个矩形上。
 *
 * 用 `offset { IntOffset }`（**像素版**）而不是 `offset(dp)`：
 * 坐标本来就算到像素了，再折成 dp 会多一次取整，累积起来
 * 会让气泡与高亮孔错开一两个像素 —— 肉眼能看出"没对齐"。
 */
private fun Modifier.offsetToRect(rect: Rect): Modifier =
    this.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }

/** 步骤进度圆点，当前步高亮、已过步半透明。 */
@Composable
private fun StepDots(total: Int, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Spacer(
                modifier = Modifier
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            i == current -> MaterialTheme.colorScheme.primary
                            i < current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    )
            )
            if (i != total - 1) Spacer(Modifier.width(5.dp))
        }
    }
}

/** 给外部用的默认箭头颜色（遮罩层画箭头时用同一套）。 */
val ArrowColor: Color @Composable get() = MaterialTheme.colorScheme.surface

/** 箭头绘制用的尺寸常量，遮罩层与气泡必须一致。 */
object BubbleMetrics {
    val gap: Dp = BUBBLE_GAP
    val arrow: Dp = ARROW_LENGTH
}