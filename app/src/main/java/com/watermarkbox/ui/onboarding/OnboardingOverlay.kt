package com.watermarkbox.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.watermarkbox.R

/** 遮罩的不透明度。 */
private const val MASK_ALPHA = 0.72f

/** 自动聚焦时，气泡宽度上限（与 `SpotlightBubble` 的 `widthIn` 保持一致）。 */
private val BUBBLE_MAX_WIDTH = 300.dp

/**
 * 新手引导的**全屏遮罩层**。
 *
 * ## 它做四件事
 *
 * 1. **铺一层半透明黑**，把「当前该看的控件」以外的区域压暗
 * 2. 在目标控件的位置**挖一个圆角孔**（`BlendMode.Clear`），
 *    孔里透出底下的真实界面，形成"聚光灯"效果
 * 3. 给孔描边 + 外发光，让聚焦感更明确
 * 4. 在孔旁边摆一个**带箭头的说明气泡**
 *
 * ## 为什么挖孔必须配合 `CompositingStrategy.Offscreen`
 *
 * `BlendMode.Clear` 的作用是「把已经画上去的像素擦成透明」。
 * 但默认情况下 Compose 直接把绘制指令发给窗口，`Clear` 会把
 * **整个窗口**擦出一个洞（露出桌面），而不是只擦本层。
 *
 * 开启 `Offscreen` 后，本层先渲染到一张离屏缓冲，`Clear` 只在这张
 * 缓冲上生效 —— 于是只有遮罩被擦掉，底下的 Activity 正常透出来。
 *
 * ## 动画
 *
 * - 整层**淡入**（避免引导"啪"地跳出来）
 * - 孔的四个边**分别补间**，因此从上一目标移到下一目标时是**滑动**过去的，
 *   不是瞬间跳变
 * - 气泡跟着孔一起移动（它自己不做动画，位置由孔的动画值决定）
 *
 * @param anchor 目标控件在窗口中的矩形；为 null 表示还没量到
 *               （首帧或目标尚未组合），此时只显示遮罩不显示孔
 */
@Composable
fun OnboardingOverlay(
    step: SpotlightStep,
    stepIndex: Int,
    stepCount: Int,
    anchor: Rect?,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    var screenSize by remember { mutableStateOf(Size.Zero) }
    var bubbleSize by remember(stepIndex) { mutableStateOf(Size.Zero) }

    // ---- 淡入 ----
    // 只在首次进入时动画一次；之后切步骤不再整体重放淡入
    // （那会让每一步都"闪"一下，很吵）
    var appeared by remember { mutableStateOf(false) }
    val layerAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "onboarding-fade"
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { appeared = true }

    // ---- 孔：四个边分别补间，产生"滑动到下一个目标"的效果 ----
    val holeTarget = remember(anchor, screenSize, step.shape) {
        if (anchor != null && screenSize != Size.Zero) {
            computeHoleRect(anchor, step.shape, screenSize, density)
        } else {
            null
        }
    }
    val holeLeft by animateFloatAsState(holeTarget?.left ?: 0f, tween(320), label = "hole-l")
    val holeTop by animateFloatAsState(holeTarget?.top ?: 0f, tween(320), label = "hole-t")
    val holeRight by animateFloatAsState(holeTarget?.right ?: 0f, tween(320), label = "hole-r")
    val holeBottom by animateFloatAsState(holeTarget?.bottom ?: 0f, tween(320), label = "hole-b")

    val hasHole = holeTarget != null
    val holeRect = if (hasHole) {
        Rect(holeLeft, holeTop, holeRight, holeBottom)
    } else {
        null
    }

    // ---- 气泡摆放 ----
    val placement = remember(holeRect, bubbleSize, screenSize, step.side) {
        if (hasHole && holeRect != null &&
            bubbleSize != Size.Zero && screenSize != Size.Zero
        ) {
            computeBubblePlacement(
                anchor = holeRect,
                screenSize = screenSize,
                bubbleSize = bubbleSize,
                preferred = step.side,
                densityScale = density.density
            )
        } else {
            null
        }
    }

    val cornerPx = with(density) {
        (step.shape as? HoleShape.RoundedRect)?.corner?.toPx() ?: 12f
    }
    val strokeColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(layerAlpha)
            .onSizeChanged {
                screenSize = Size(it.width.toFloat(), it.height.toFloat())
            }
    ) {
        // ================= 遮罩 + 挖孔 + 描边 =================
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ 缺失这一行，BlendMode.Clear 会把整个窗口擦穿（见类注释）
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(stepIndex) {
                    detectTapGestures { offset ->
                        // 点孔内且该步允许 → 等同"下一步"
                        val inside = holeRect?.contains(offset) == true
                        if (inside && step.allowTapHole) onNext() else onSkip()
                    }
                }
        ) {
            // ---- 半透明遮罩 ----
            drawRect(color = Color.Black.copy(alpha = MASK_ALPHA))

            if (holeRect == null) return@Canvas

            val topLeft = Offset(holeRect.left, holeRect.top)
            val holeSize = Size(holeRect.width, holeRect.height)
            val radius = CornerRadius(cornerPx, cornerPx)

            // ---- 挖孔 ----
            drawRoundRect(
                color = Color.Transparent,
                topLeft = topLeft,
                size = holeSize,
                cornerRadius = radius,
                blendMode = BlendMode.Clear
            )

            // ---- 外发光：由宽到窄叠几圈半透明描边 ----
            //
            // 用"叠加多层描边"而不是真模糊，是因为 blur 在每帧重绘时
            // 开销明显（尤其动画中每帧都要重算），而这套叠法几乎免费。
            if (step.glow) {
                val rings = listOf(
                    14f to 0.06f,
                    10f to 0.10f,
                    7f to 0.16f
                )
                rings.forEach { (widthDp, alpha) ->
                    drawRoundRect(
                        color = strokeColor.copy(alpha = alpha),
                        topLeft = topLeft,
                        size = holeSize,
                        cornerRadius = radius,
                        style = Stroke(width = widthDp.dp.toPx())
                    )
                }
            }

            // ---- 主描边 ----
            drawRoundRect(
                color = strokeColor,
                topLeft = topLeft,
                size = holeSize,
                cornerRadius = radius,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // ================= 箭头 =================
        //
        // 颜色必须在**组合作用域**里取好再传进去：
        // Canvas 的绘制 lambda 不是 @Composable 上下文，
        // 在里面读 MaterialTheme 会直接编译失败。
        val arrowColor = MaterialTheme.colorScheme.surface
        if (placement != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArrow(placement, arrowColor)
            }
        }

        // ================= 气泡 =================
        if (placement != null) {
            SpotlightBubble(
                placement = placement,
                title = stringResource(step.titleRes),
                body = stringResource(step.bodyRes),
                stepIndex = stepIndex,
                stepCount = stepCount,
                isLast = stepIndex == stepCount - 1,
                onSkip = onSkip,
                onNext = onNext,
                modifier = Modifier.onSizeChanged {
                    bubbleSize = Size(it.width.toFloat(), it.height.toFloat())
                }
            )
        } else if (hasHole) {
            // 气泡尺寸还没量出来：先隐形渲染一帧，量到尺寸后再正式摆。
            //
            // 这样安排是因为「摆放位置」依赖「气泡尺寸」，
            // 而尺寸只有渲染一次才知道 —— 鸡生蛋。
            // 与其猜一个尺寸（不同语言文案高度差别很大），
            // 不如让它隐形渲染一帧。
            SpotlightBubble(
                placement = BubblePlacement(
                    side = BubbleSide.BOTTOM,
                    bubble = Rect(0f, 0f, 0f, 0f),
                    arrowTip = Offset.Zero,
                    arrowBase = Offset.Zero
                ),
                title = stringResource(step.titleRes),
                body = stringResource(step.bodyRes),
                stepIndex = stepIndex,
                stepCount = stepCount,
                isLast = stepIndex == stepCount - 1,
                onSkip = onSkip,
                onNext = onNext,
                modifier = Modifier
                    .size(width = BUBBLE_MAX_WIDTH, height = 1.dp)
                    .alpha(0f)
                    .onSizeChanged {
                        bubbleSize = Size(it.width.toFloat(), it.height.toFloat())
                    }
            )
        }
    }
}

/** 在 [placement] 指定的位置画一个小三角箭头。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(
    placement: BubblePlacement,
    color: Color
) {
    val tip = placement.arrowTip
    val base = placement.arrowBase
    val halfWidth = 9.dp.toPx()

    // 箭头方向 = 从底边指到尖端
    val dx = tip.x - base.x
    val dy = tip.y - base.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len < 0.01f) return

    // 垂直方向单位向量（用于撑开三角形的底边）
    val px = -dy / len
    val py = dx / len

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + px * halfWidth, base.y + py * halfWidth)
        lineTo(base.x - px * halfWidth, base.y - py * halfWidth)
        close()
    }
    drawPath(path, color)
}