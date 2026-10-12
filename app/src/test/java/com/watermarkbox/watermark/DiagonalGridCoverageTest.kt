package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 对角线水印「网格铺满」单元测试。
 *
 * ## 背景（真实 Bug）
 *
 * 用户报告：对角线水印遇到**长方形图片**时，水印集中在中间一条斜带，
 * 两侧大片空白（实测 3000×1000 的图只覆盖画布宽度的 26%）。
 *
 * ## 根因（第三版重构）
 *
 * 旧实现在 **canvas.rotate() 之后的坐标系**里铺网格。
 * 那个坐标系里画布是一个**斜矩形（菱形）**，而网格是按 x/y 轴对齐铺的，
 * 于是大量网格行整行落在菱形外、被判定跳过 → 只覆盖中间一条。
 *
 * 新实现改为：**在原图坐标系里按图片尺寸铺满网格**，
 * 再对每个格子单独 `canvas.rotate()`。铺满与旋转解耦，
 * 网格天然覆盖整个矩形，不会有整行跳过的现象。
 *
 * ## 测试内容
 *
 * 本测试复刻新方案的「网格铺开范围」计算（纯算术，不依赖 Canvas），
 * 验证网格能覆盖画布的四角与四边 —— 这正是旧方案做不到的。
 */
class DiagonalGridCoverageTest {

    /** 复刻渲染器里的网格参数计算。 */
    private data class Grid(
        val cellW: Float,
        val cellH: Float,
        val cols: Int,
        val rows: Int,
        val startX: Float,
        val startY: Float
    ) {
        val endX get() = startX + cols * cellW
        val endY get() = startY + rows * cellH
    }

    private fun computeGrid(w: Int, h: Int, count: Int, lineHeight: Float): Grid {
        val diagonal = sqrt((w * w + h * h).toDouble()).toFloat()
        val step = diagonal / count
        val cellW = step
        val cellH = max(step / 1.6f, lineHeight * 1.4f)
        val cols = ceil(w / cellW).toInt() + 2
        val rows = ceil(h / cellH).toInt() + 2
        return Grid(cellW, cellH, cols, rows, -cellW, -cellH)
    }

    /** 网格是否完整覆盖画布 [0,w]×[0,h]。 */
    private fun coversCanvas(g: Grid, w: Int, h: Int): Boolean =
        g.startX <= 0f && g.startY <= 0f && g.endX >= w && g.endY >= h

    // ------------------------------------------------------------------
    // 核心断言：各种长宽比下，网格都要覆盖整个画布
    // ------------------------------------------------------------------

    @Test
    fun `宽图 3000x1000 网格应覆盖全画布`() {
        val g = computeGrid(3000, 1000, 5, 48f)
        println("宽图: cell=${g.cellW}x${g.cellH} cols=${g.cols} rows=${g.rows} X[${g.startX},${g.endX}] Y[${g.startY},${g.endY}]")
        assertTrue("网格 X 未覆盖画布: [${g.startX}, ${g.endX}] vs [0, 3000]", g.startX <= 0f && g.endX >= 3000f)
        assertTrue("网格 Y 未覆盖画布: [${g.startY}, ${g.endY}] vs [0, 1000]", g.startY <= 0f && g.endY >= 1000f)
    }

    @Test
    fun `竖图 1000x3000 网格应覆盖全画布`() {
        val g = computeGrid(1000, 3000, 5, 48f)
        println("竖图: cell=${g.cellW}x${g.cellH} cols=${g.cols} rows=${g.rows} X[${g.startX},${g.endX}] Y[${g.startY},${g.endY}]")
        assertTrue(g.startX <= 0f && g.endX >= 1000f)
        assertTrue(g.startY <= 0f && g.endY >= 3000f)
    }

    @Test
    fun `正方形 2000x2000 网格应覆盖全画布`() {
        val g = computeGrid(2000, 2000, 5, 48f)
        assertTrue(coversCanvas(g, 2000, 2000))
    }

    @Test
    fun `极端宽图 4000x500 网格应覆盖全画布`() {
        val g = computeGrid(4000, 500, 5, 48f)
        println("极端宽图: X[${g.startX},${g.endX}] Y[${g.startY},${g.endY}]")
        assertTrue(coversCanvas(g, 4000, 500))
    }

    @Test
    fun `极端竖图 500x4000 网格应覆盖全画布`() {
        val g = computeGrid(500, 4000, 5, 48f)
        assertTrue(coversCanvas(g, 500, 4000))
    }

    // ------------------------------------------------------------------
    // 密度：格子大小应与 count 成反比
    // ------------------------------------------------------------------

    @Test
    fun `格子尺寸随密度等比例变化`() {
        val g1 = computeGrid(3000, 1000, 5, 48f)
        val g2 = computeGrid(3000, 1000, 10, 48f)
        // count 翻倍 → 格子减半
        assertEquals(g1.cellW / 2f, g2.cellW, 0.01f)
    }

    @Test
    fun `密度越大覆盖画布所需格子数越多`() {
        val g1 = computeGrid(3000, 1000, 2, 48f)
        val g2 = computeGrid(3000, 1000, 10, 48f)
        assertTrue(
            "高密度应有更多列 (${g2.cols} > ${g1.cols})",
            g2.cols > g1.cols
        )
    }

    // ------------------------------------------------------------------
    // 反向验证：旧方案的铺开范围确实覆盖不了
    // ------------------------------------------------------------------

    /**
     * 量化旧方案为什么覆盖不全。
     *
     * 旧方案在「旋转 -45° 后的坐标系」里铺网格。那时画布变成一个斜矩形，
     * 它在**旋转坐标系中的 Y 范围**决定了能容纳多少网格行。
     *
     * 注意：这个 Y 夸度就是图片对角线长度（±45° 时），与长宽比无关。
     * 所以问题不在「Y 夸度太小」，而在：
     *
     *   **网格是按 x/y 对齐铺的，而画布是斜的** ——
     *   每一行网格只在某个 x 区间内与斜矩形相交，其余部分全在画布外。
     *   于是每一行真正能画出来的格子只有少数几个，
     *   看起来就像「水印只集中在中间一条斜带」。
     */
    @Test
    fun `旧方案中每行只有少数格子落在画布内`() {
        val w = 3000
        val h = 1000
        val count = 5
        val angleDeg = -45f

        val g = computeGrid(w, h, count, 48f)

        // 复刻旧方案：以画布中心为心，在旋转坐标系里铺网格
        val cx = w / 2f
        val cy = h / 2f
        val rad = Math.toRadians(-angleDeg.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()

        var totalCells = 0
        var insideCells = 0
        // 旧方案的铺开范围：以中心为准，边长覆盖对角线
        val spanX = sqrt((w * w + h * h).toDouble()).toFloat()
        val cols = ceil(spanX / g.cellW).toInt() + 1
        val rows = ceil(spanX / g.cellH).toInt() + 1
        val startX = cx - cols * g.cellW / 2f
        val startY = cy - rows * g.cellH / 2f

        for (row in 0..rows) {
            for (col in 0..cols) {
                totalCells++
                val offX = if (row % 2 == 1) g.cellW / 2f else 0f
                val x = startX + col * g.cellW + offX
                val y = startY + row * g.cellH
                // 格心反旋转回原图坐标系
                val dx = (x + g.cellW / 2f) - cx
                val dy = (y + g.cellH / 2f) - cy
                val px = cx + (dx * cosA - dy * sinA)
                val py = cy + (dx * sinA + dy * cosA)
                if (px >= -g.cellW / 2f && px <= w + g.cellW / 2f &&
                    py >= -g.cellH / 2f && py <= h + g.cellH / 2f
                ) {
                    insideCells++
                }
            }
        }
        println("旧方案：$insideCells / $totalCells 个格子在画布内，占 ${insideCells * 100 / totalCells}%")

        // 新方案：每个格子都画（不做 inside 判定），网格本身就覆盖全画布
        val newTotal = (g.cols + 1) * (g.rows + 1)
        println("新方案：$newTotal 个格子全部绘制（网格已覆盖全画布）")

        assertTrue(
            "旧方案应在画布外浪费大量格子（说明分布不均）",
            insideCells < totalCells / 2
        )
    }
}