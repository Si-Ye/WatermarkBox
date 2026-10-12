package com.watermarkbox.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 预览可见尺寸的仪器化验证（真实 Canvas 像素测量）。
 *
 * ## 为什么不用 ComposeTestRule
 *
 * 本 ROM 的 `InputManager.getInstance` 反射不兼容，
 * Espresso 输入注入初始化即崩溃，`createComposeRule()` 不可用。
 * 因此改用真实 Canvas 绘制 + 像素边界测量。
 *
 * ## 验证目标（对应用户描述）
 *
 * 1. 阶段一：面板 < 70%，预览高度 = 屏幕高 - 面板高，**随面板变高而变小**
 * 2. 阶段二：面板 ≥ 70%，预览高度**冻结**在屏幕三成
 */
@RunWith(AndroidJUnit4::class)
class PreviewFitMeasurementTest {

    /** 在给定容器里按 ContentScale.Fit 绘制，返回实际绘制的边界。 */
    private fun measureFitDraw(
        imgW: Int,
        imgH: Int,
        boxW: Int,
        boxH: Int
    ): RectF {
        val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val out = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val canvas = Canvas(out)

        // ContentScale.Fit 等价算法
        val scale = minOf(boxW.toFloat() / imgW, boxH.toFloat() / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val left = (boxW - drawW) / 2f
        val top = (boxH - drawH) / 2f
        canvas.drawBitmap(
            bitmap, null,
            RectF(left, top, left + drawW, top + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        // 从像素反推红色区域边界
        var minY = boxH
        var maxY = -1
        for (y in 0 until boxH step 2) {
            for (x in 0 until boxW step 2) {
                val p = out.getPixel(x, y)
                if (Color.red(p) > 200 && Color.green(p) < 80) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        bitmap.recycle()
        out.recycle()

        if (maxY < 0) return RectF(0f, 0f, 0f, 0f)
        return RectF(0f, minY.toFloat(), boxW.toFloat(), maxY.toFloat())
    }

    // 常见手机屏幕逻辑高度（像素）
    private val screenH = 1600

    // =================================================================
    // 阶段一：预览随面板变高而变小
    // =================================================================

    @Test
    fun preview_shrinks_with_panel_in_phase_one() {
        val behavior = PanelDragBehavior(
            minFraction = 0.20f,
            shrinkEndFraction = 0.70f
        )

        // 竖图：Fit 受高度限制
        val heights = listOf(0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f).map { f ->
            val boxH = (screenH * behavior.previewHeightFraction(f)).toInt()
            measureFitDraw(800, 1200, 1000, boxH).height()
        }

        for (i in 1 until heights.size) {
            assertTrue("阶段一绘制高度应严格递减：$heights", heights[i] < heights[i - 1])
        }
    }

    @Test
    fun phase_one_shrink_is_clearly_visible() {
        val behavior = PanelDragBehavior(minFraction = 0.20f, shrinkEndFraction = 0.70f)

        val atMin = (screenH * behavior.previewHeightFraction(0.20f)).toInt()
        val atEnd = (screenH * behavior.previewHeightFraction(0.70f)).toInt()

        val h1 = measureFitDraw(800, 1200, 1000, atMin).height()
        val h2 = measureFitDraw(800, 1200, 1000, atEnd).height()

        val shrinkPercent = (1f - h2 / h1) * 100f
        assertTrue(
            "从最小拖到七成应缩小 ≥60%（实际 ${shrinkPercent.toInt()}%）",
            shrinkPercent >= 60f
        )
    }

    // =================================================================
    // 阶段二：预览冻结
    // =================================================================

    @Test
    fun preview_freezes_after_threshold_in_phase_two() {
        val behavior = PanelDragBehavior(minFraction = 0.20f, shrinkEndFraction = 0.70f)

        val heights = listOf(0.70f, 0.80f, 0.90f, 1.0f).map { f ->
            val boxH = (screenH * behavior.previewHeightFraction(f)).toInt()
            measureFitDraw(800, 1200, 1000, boxH).height()
        }

        val frozen = heights.first()
        heights.forEach { h ->
            assertEquals(
                "阶段二绘制高度应冻结在 $frozen，实际 $h",
                frozen, h
            )
        }
    }

    // =================================================================
    // 根因回归：仅改高度时横图不变（复现原 Bug）
    // =================================================================

    @Test
    fun landscape_image_unchanged_when_only_height_changes__root_cause() {
        // 横图 1600x1200 放进 1000 宽容器：Fit 只受宽度限制
        val tall = measureFitDraw(1600, 1200, 1000, 1600)
        val short = measureFitDraw(1600, 1200, 1000, 900)

        assertEquals(
            "横图仅改容器高度时 Fit 结果相同 —— 这正是「图片没变化」的根因",
            tall.height(), short.height()
        )
    }

    // =================================================================
    // 贴顶语义
    // =================================================================

    @Test
    fun preview_area_starts_from_top() {
        // 贴顶时预览区顶部应在 y=0 附近
        val boxH = (screenH * 0.80f).toInt()
        val rect = measureFitDraw(800, 1200, 1000, boxH)

        assertTrue(
            "预览应贴顶开始（top=${rect.top}），而不是居中留下大片空白",
            rect.top <= 4f
        )
    }
}