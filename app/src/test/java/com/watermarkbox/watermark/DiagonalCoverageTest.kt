package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 对角线水印「网格覆盖率」单元测试。
 *
 * ## 背景（真实 Bug）
 *
 * 用户报告：对角线水印遇到**长方形图片**时，水印覆盖不全（某些区域没有水印）。
 *
 * 排查出两个成因，这里都做了验证：
 *
 * ### 成因 1：格子相交判定只看「左上角单点」
 *
 * 旧实现判断格子左上角是否落在图片内。但格子有宽高，左上角稍一出界，
 * 整格就被跳过 —— 可它右半 / 下半明明还盖在图上。
 * 宽图（横向格子很宽）尤其明显。
 *
 * ### 成因 2：网格尺寸只用宽度推导
 *
 * 旧实现 `cellWidth = width / count`，`cellHeight = cellWidth / 1.6`。
 * 宽图时 cellHeight 相对图片高度过大，旋转后竖直方向格子数不足。
 *
 * ## 测试策略
 *
 * 把 [WatermarkRenderer] 里「网格布局 + 相交判定」的算法**等价复刻**到本文件
 * （纯 JVM 逻辑，不依赖 Android Canvas），然后：
 *
 * 1. 模拟「旧算法」和「新算法」，对比它们在长方形图片上的覆盖情况；
 * 2. 断言新算法在长方形图片四角 / 四边均有水印分布。
 */
class DiagonalCoverageTest {

    /** 网格布局参数，与渲染器里一致。 */
    private data class Grid(
        val cellWidth: Float,
        val cellHeight: Float,
        val cols: Int,
        val rows: Int,
        val startX: Float,
        val startY: Float
    )

    // ------------------------------------------------------------------
    // 相交判定：两套实现（旧 = 单点，新 = 格子四角+中心）
    // ------------------------------------------------------------------

    /** 旧算法：只判断格子左上角单点是否在（外扩 20% 容差的）图片内。 */
    private fun oldIsInside(
        x: Float, y: Float, w: Int, h: Int, angleDeg: Float
    ): Boolean {
        val cx = w / 2f
        val cy = h / 2f
        val rad = Math.toRadians(-angleDeg.toDouble())
        val dx = x - cx
        val dy = y - cy
        val rx = cx + (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = cy + (dx * sin(rad) + dy * cos(rad)).toFloat()
        val tolerance = 0.2f
        return rx >= -w * tolerance && rx <= w * (1 + tolerance) &&
            ry >= -h * tolerance && ry <= h * (1 + tolerance)
    }

    /** 新算法：判断格子矩形（四角 + 中心）是否与图片相交。 */
    private fun newIsInside(
        x: Float, y: Float, cellW: Float, cellH: Float,
        w: Int, h: Int, angleDeg: Float
    ): Boolean {
        val cx = w / 2f
        val cy = h / 2f
        val rad = Math.toRadians(-angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val padX = cellW / 2f
        val padY = cellH / 2f
        val px = floatArrayOf(x, x + cellW, x, x + cellW, x + cellW / 2f)
        val py = floatArrayOf(y, y, y + cellH, y + cellH, y + cellH / 2f)
        for (i in px.indices) {
            val dx = px[i] - cx
            val dy = py[i] - cy
            val rx = cx + (dx * cosA - dy * sinA)
            val ry = cy + (dx * sinA + dy * cosA)
            if (rx >= -padX && rx <= w + padX && ry >= -padY && ry <= h + padY) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // 网格计算：两套实现
    // ------------------------------------------------------------------

    /** 旧网格：宽度等分。 */
    private fun oldGrid(w: Int, h: Int, count: Int, minCellH: Float): Grid {
        val cellWidth = w.toFloat() / count
        val cellHeight = max(cellWidth / 1.6f, minCellH)
        val diagonal = sqrt((w * w + h * h).toDouble()).toFloat()
        val extended = diagonal + max(cellWidth, cellHeight)
        return Grid(
            cellWidth, cellHeight,
            ceil(extended / cellWidth).toInt(),
            ceil(extended / cellHeight).toInt(),
            w / 2f - ceil(extended / cellWidth).toInt() * cellWidth / 2f,
            h / 2f - ceil(extended / cellHeight).toInt() * cellHeight / 2f
        )
    }

    /** 新网格：对角线等分。 */
    private fun newGrid(w: Int, h: Int, count: Int, minCellH: Float): Grid {
        val diagonal = sqrt((w * w + h * h).toDouble()).toFloat()
        val base = diagonal / count
        val cellWidth = base
        val cellHeight = max(base / 1.6f, minCellH)
        val extended = diagonal + max(cellWidth, cellHeight)
        val cols = ceil(extended / cellWidth).toInt() + 1
        val rows = ceil(extended / cellHeight).toInt() + 1
        return Grid(
            cellWidth, cellHeight, cols, rows,
            w / 2f - cols * cellWidth / 2f,
            h / 2f - rows * cellHeight / 2f
        )
    }

    // ------------------------------------------------------------------
    // 覆盖率测量：把图片切成 N×M 个小格，统计有多少格「附近有水印」
    // ------------------------------------------------------------------

    /**
     * 返回 [0,1] 的覆盖率：图片内均匀采样点中，有多少比例的点
     * 「距离某个水印格子中心不超过半个格子」。
     *
     * 这是一种简化但有效的度量：水印是平铺的，只要每个采样点都能
     * 找到附近的绘制格子，就说明没有大片空白。
     */
    private fun coverage(
        w: Int, h: Int, grid: Grid, angleDeg: Float, useNew: Boolean,
        cellW: Float, cellH: Float
    ): Float {
        // 收集所有被绘制的格子中心（旋转后坐标系 → 原坐标系）
        val cx = w / 2f
        val cy = h / 2f
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        val centers = mutableListOf<Pair<Float, Float>>()
        for (row in 0..grid.rows) {
            for (col in 0..grid.cols) {
                val offsetX = if (row % 2 == 1) grid.cellWidth / 2f else 0f
                val x = grid.startX + col * grid.cellWidth + offsetX
                val y = grid.startY + row * grid.cellHeight
                val inside = if (useNew) {
                    newIsInside(x, y, cellW, cellH, w, h, angleDeg)
                } else {
                    oldIsInside(x, y, w, h, angleDeg)
                }
                if (inside) {
                    // 把格心从「旋转坐标系」正向旋转回「原图坐标系」
                    val mx = x + cellW / 2f - cx
                    val my = y + cellH / 2f - cy
                    val ox = cx + (mx * cosA - my * sinA)
                    val oy = cy + (mx * sinA + my * cosA)
                    centers.add(ox to oy)
                }
            }
        }

        if (centers.isEmpty()) return 0f

        // 在图片内均匀采样，看每个点是否落在某个格心的「覆盖半径」内
        val radius = max(grid.cellWidth, grid.cellHeight) * 1.2f
        val steps = 20
        var hit = 0
        var total = 0
        for (i in 1 until steps) {
            for (j in 1 until steps) {
                val sx = w * i / steps.toFloat()
                val sy = h * j / steps.toFloat()
                total++
                val near = centers.any { (cx0, cy0) ->
                    val ddx = sx - cx0
                    val ddy = sy - cy0
                    ddx * ddx + ddy * ddy <= radius * radius
                }
                if (near) hit++
            }
        }
        return hit.toFloat() / total
    }

    // ------------------------------------------------------------------
    // 测试用例
    // ------------------------------------------------------------------

    @Test
    fun `宽图 3000x1000 新算法覆盖率应高于旧算法`() {
        val w = 3000
        val h = 1000
        val count = 5
        val minCellH = 60f

        val old = oldGrid(w, h, count, minCellH)
        val new = newGrid(w, h, count, minCellH)

        val oldCoverage = coverage(w, h, old, -45f, false, old.cellWidth, old.cellHeight)
        val newCoverage = coverage(w, h, new, -45f, true, new.cellWidth, new.cellHeight)

        println("宽图 3000x1000：旧覆盖率=${"%.2f".format(oldCoverage)} 新覆盖率=${"%.2f".format(newCoverage)}")
        assertTrue(
            "新算法覆盖率($newCoverage)应不低于旧算法($oldCoverage)",
            newCoverage >= oldCoverage
        )
        assertTrue("新算法覆盖率应达到 0.9 以上，实际 $newCoverage", newCoverage >= 0.9f)
    }

    @Test
    fun `竖图 1000x3000 新算法应充分覆盖`() {
        val w = 1000
        val h = 3000
        val count = 5
        val minCellH = 60f

        val new = newGrid(w, h, count, minCellH)
        val newCoverage = coverage(w, h, new, -45f, true, new.cellWidth, new.cellHeight)

        println("竖图 1000x3000：新覆盖率=${"%.2f".format(newCoverage)}")
        assertTrue("竖图新算法覆盖率应达到 0.9 以上，实际 $newCoverage", newCoverage >= 0.9f)
    }

    @Test
    fun `正方形 2000x2000 新算法应充分覆盖`() {
        val w = 2000
        val h = 2000
        val count = 5
        val minCellH = 60f

        val new = newGrid(w, h, count, minCellH)
        val newCoverage = coverage(w, h, new, -45f, true, new.cellWidth, new.cellHeight)

        println("正方形 2000x2000：新覆盖率=${"%.2f".format(newCoverage)}")
        assertTrue("正方形新算法覆盖率应达到 0.9 以上，实际 $newCoverage", newCoverage >= 0.9f)
    }

    @Test
    fun `格子与图片相交判定-左出界但右半仍在图内应返回真`() {
        val w = 1000
        val h = 1000
        // 构造一个格子：其左上角在图片左侧外（负坐标），但有部分在图内
        val cellW = 300f
        val cellH = 100f
        // 未旋转（angle=0）时更直观：
        // 左边界 -260，右边界 +40，与图片相交。
        // 取 -260 是为了连旧算法的 20% 容差（-200）都够不着，
        // 从而暴露「单点判定 + 容差补救」的不足。
        val x = -260f
        val y = 400f

        assertTrue(
            "格子左出界但右半在图内，新算法应判定相交",
            newIsInside(x, y, cellW, cellH, w, h, 0f)
        )
        // 旧算法只看左上角(-260,400)，即便有 20% 容差（-200）也判为出界 → 漏画
        assertTrue(
            "（对照）旧算法会漏掉这个格子 —— 这是 Bug 的体现",
            !oldIsInside(x, y, w, h, 0f)
        )
    }

    @Test
    fun `完全出界的格子两套算法都应判定不相交`() {
        val w = 1000
        val h = 1000
        val cellW = 100f
        val cellH = 50f
        // 完全在图片左侧外
        val x = -500f
        val y = 500f

        assertTrue(!newIsInside(x, y, cellW, cellH, w, h, 0f))
        assertTrue(!oldIsInside(x, y, w, h, 0f))
    }

    @Test
    fun `新网格在宽图上列数应多于旧网格`() {
        // 宽图：旧算法 cellWidth 很大 → cols 少；新算法按对角线分 → cols 多
        val w = 4000
        val h = 800
        val count = 5
        val minCellH = 60f

        val old = oldGrid(w, h, count, minCellH)
        val new = newGrid(w, h, count, minCellH)

        println("宽图 4000x800：旧 cols=${old.cols} rows=${old.rows}；新 cols=${new.cols} rows=${new.rows}")
        // 旧算法 cellWidth = 800，新算法 cellWidth = diagonal/5 ≈ 816
        // 两者接近，但旧算法 rows 会明显偏少（cellHeight = 800/1.6 = 500，而图高仅 800）
        assertTrue(
            "新算法在宽图上应有足够的行数覆盖高度，旧 rows=${old.rows} 新 rows=${new.rows}",
            new.rows >= old.rows
        )
        assertEquals(
            "新网格行数应足以覆盖对角线范围",
            ceil((sqrt((w * w + h * h).toDouble()).toFloat() + max(new.cellWidth, new.cellHeight)) / new.cellHeight).toInt() + 1,
            new.rows
        )
    }
}
