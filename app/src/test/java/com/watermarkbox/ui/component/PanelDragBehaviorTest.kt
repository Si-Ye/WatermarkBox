package com.watermarkbox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两段式拖拽行为单元测试。
 *
 * ## 用户描述的需求
 *
 * > 水印参数窗口高度小于屏幕 70% 的时候，窗口上方的区域放置预览图片，
 * > 随着窗口高度的变大，预览图片的大小随之变小。
 * > 超过 70% 时，预览图片大小不变，随着窗口高度的增大，
 * > 预览图片被水印参数窗口遮住。
 *
 * 对应 [PanelDragBehavior.previewHeightFraction]：
 * - 阶段一（f ≤ 0.7）：高度占比 = **1 - f**
 * - 阶段二（f > 0.7）：高度占比**冻结**在 1 - 0.7 = 0.3
 */
class PanelDragBehaviorTest {

    private val behavior = PanelDragBehavior(
        minFraction = 0.20f,
        maxFraction = 1f,
        shrinkEndFraction = 0.70f
    )

    // =================================================================
    // 分界点
    // =================================================================

    @Test
    fun `分界点默认为屏幕七成`() {
        assertEquals(0.70f, behavior.shrinkEndFraction, 1e-6f)
        assertEquals(0.70f, PanelDragBehavior().shrinkEndFraction, 1e-6f)
    }

    // =================================================================
    // 阶段一：预览占满上方剩余空间，随面板变高而变小
    // =================================================================

    @Test
    fun `面板最小时预览占满上方八成空间`() {
        assertEquals(0.80f, behavior.previewHeightFraction(0.20f), 1e-4f)
    }

    @Test
    fun `阶段一预览高度占比等于剩余空间`() {
        listOf(0.20f, 0.35f, 0.50f, 0.60f, 0.65f, 0.70f).forEach { f ->
            assertEquals(
                "f=$f 时预览应占满剩余空间",
                1f - f,
                behavior.previewHeightFraction(f),
                1e-4f
            )
        }
    }

    @Test
    fun `阶段一预览高度严格递减`() {
        val heights = listOf(0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f)
            .map { behavior.previewHeightFraction(it) }

        for (i in 1 until heights.size) {
            assertTrue("阶段一应严格递减：$heights", heights[i] < heights[i - 1])
        }
    }

    @Test
    fun `阶段一缩水幅度足够肉眼可见`() {
        val atMin = behavior.previewHeightFraction(0.20f)   // 0.80
        val atEnd = behavior.previewHeightFraction(0.70f)   // 0.30
        val shrinkPercent = (1f - atEnd / atMin) * 100f

        assertTrue(
            "从最小拖到分界点应缩小至少 60%（实际 ${shrinkPercent.toInt()}%）",
            shrinkPercent >= 60f
        )
    }

    @Test
    fun `分界点处预览恰好占三成`() {
        assertEquals(0.30f, behavior.previewHeightFraction(0.70f), 1e-4f)
    }

    // =================================================================
    // 阶段二：预览尺寸冻结（核心需求）
    // =================================================================

    @Test
    fun `超过七成后预览高度完全冻结 —— 这是核心需求`() {
        val frozen = behavior.previewHeightFraction(0.70f)  // 0.30

        listOf(0.71f, 0.80f, 0.90f, 0.95f, 1.0f).forEach { f ->
            assertEquals(
                "f=$f 时预览应冻结在 $frozen",
                frozen,
                behavior.previewHeightFraction(f),
                1e-5f
            )
        }
    }

    @Test
    fun `阶段二任意两点高度完全相同`() {
        assertEquals(
            behavior.previewHeightFraction(0.75f),
            behavior.previewHeightFraction(1.0f),
            1e-6f
        )
        assertEquals(
            behavior.previewHeightFraction(0.85f),
            behavior.previewHeightFraction(0.95f),
            1e-6f
        )
    }

    @Test
    fun `阶段二冻结值恰为屏幕三成`() {
        assertEquals(0.30f, behavior.previewHeightFraction(1.0f), 1e-4f)
    }

    // =================================================================
    // 连续性
    // =================================================================

    @Test
    fun `两阶段交界处高度连续无跳变`() {
        val before = behavior.previewHeightFraction(0.699f)
        val at = behavior.previewHeightFraction(0.700f)
        val after = behavior.previewHeightFraction(0.701f)

        assertEquals("交界处左侧应连续", at, before, 1e-3f)
        assertEquals("交界处右侧应完全相等", at, after, 1e-6f)
    }

    // =================================================================
    // isOverlaying
    // =================================================================

    @Test
    fun `isOverlaying 以七成为界`() {
        assertFalse(behavior.isOverlaying(0.20f))
        assertFalse(behavior.isOverlaying(0.60f))
        assertFalse("分界点本身不算覆盖", behavior.isOverlaying(0.70f))
        assertTrue(behavior.isOverlaying(0.71f))
        assertTrue(behavior.isOverlaying(1.0f))
    }

    // =================================================================
    // 越界
    // =================================================================

    @Test
    fun `小于最小占比按最小处理`() {
        assertEquals(0.80f, behavior.previewHeightFraction(0.05f), 1e-4f)
    }

    @Test
    fun `大于最大占比按最大处理`() {
        assertEquals(
            behavior.previewHeightFraction(1.0f),
            behavior.previewHeightFraction(1.5f),
            1e-6f
        )
    }

    @Test
    fun `全屏时预览高度占比仍为三成`() {
        val atFull = behavior.previewHeightFraction(1.0f)
        assertEquals(0.30f, atFull, 1e-4f)
        assertTrue("不应归零（避免面板下闪空）", atFull > 0.1f)
    }

    // =================================================================
    // 几何一致性
    // =================================================================

    @Test
    fun `阶段一面板与预览恰好占满整屏`() {
        listOf(0.20f, 0.35f, 0.50f, 0.60f, 0.70f).forEach { f ->
            assertEquals(
                "f=$f 时面板 + 预览应 = 1.0（占满）",
                1f,
                f + behavior.previewHeightFraction(f),
                1e-4f
            )
        }
    }

    @Test
    fun `阶段二面板与预览重叠量随面板增大`() {
        val frozenPreview = behavior.previewHeightFraction(0.70f)  // 0.30
        // 面板顶边 y = 1 - f；预览底边 y = frozenPreview
        // 重叠量 = frozenPreview - (1 - f)
        val overlaps = listOf(0.75f, 0.85f, 0.95f, 1.0f).map { f ->
            frozenPreview - (1f - f)
        }
        for (i in 1 until overlaps.size) {
            assertTrue(
                "面板越高，覆盖预览越多：$overlaps",
                overlaps[i] > overlaps[i - 1]
            )
        }
        assertTrue("全屏时应完全覆盖", overlaps.last() >= frozenPreview - 1e-4f)
    }

    @Test
    fun `自定义分界点依然生效`() {
        listOf(0.5f, 0.6f, 0.7f, 0.8f).forEach { shrinkEnd ->
            val b = PanelDragBehavior(
                minFraction = 0.2f,
                maxFraction = 1f,
                shrinkEndFraction = shrinkEnd
            )
            val frozen = b.previewHeightFraction(shrinkEnd)
            listOf(shrinkEnd + 0.01f, 0.9f, 1.0f).forEach { f ->
                assertEquals(
                    "shrinkEnd=$shrinkEnd, f=$f 应冻结",
                    frozen,
                    b.previewHeightFraction(f),
                    1e-5f
                )
            }
        }
    }
}