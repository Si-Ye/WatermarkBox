package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BitmapBudget] 的单元测试。
 *
 * 全部用「显式注入堆上限」的方式测 —— 不依赖跑测试那台机器的内存，
 * 断言才是确定的。
 *
 * 判错方向的后果不对称：
 * - 判「放得下」而其实放不下 → 崩
 * - 判「放不下」而其实勉强可以 → 用户少导一张图
 * 所以宁可保守。
 */
class BitmapBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `字节数按像素与份数计算`() {
        // 1000x500 = 50 万像素 × 4 字节 = 2 MB
        assertEquals(2_000_000L, BitmapBudget.bytesOf(1000, 500))
        assertEquals(6_000_000L, BitmapBudget.bytesOf(1000, 500, copies = 3))
    }

    @Test
    fun `预算是堆上限的一半`() {
        assertEquals(128 * mb, BitmapBudget.budgetBytes(256 * mb))
        assertEquals(256 * mb, BitmapBudget.budgetBytes(512 * mb))
    }

    /** 常见机型：512 MB 堆 + 1200 万像素（4032×3024）→ 导出三份约 146 MB，放得下。 */
    @Test
    fun `一千二百万像素在中端机上放得下`() {
        assertTrue(
            BitmapBudget.fitsFullResolution(
                width = 4032, height = 3024,
                copies = BitmapBudget.EXPORT_COPIES,
                maxHeapBytes = 512 * mb
            )
        )
    }

    /** 5000 万像素（8160×6120）导出三份约 598 MB → 512 MB 堆上限下必然放不下。 */
    @Test
    fun `五千万像素在中端机上放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(
                width = 8160, height = 6120,
                copies = BitmapBudget.EXPORT_COPIES,
                maxHeapBytes = 512 * mb
            )
        )
    }

    /**
     * 同一张图，「导出三份」放不下、「检测两份」可能放得下 ——
     * 这正是份数必须区分的原因。
     */
    @Test
    fun `份数不同结论可以不同`() {
        val w = 6000
        val h = 4000          // 2400 万像素：一份 91.5 MB
        val heap = 512 * mb   // 预算 256 MB
        assertTrue(BitmapBudget.fitsFullResolution(w, h, BitmapBudget.DETECT_COPIES, heap))
        assertFalse(BitmapBudget.fitsFullResolution(w, h, BitmapBudget.EXPORT_COPIES, heap))
    }

    /** 尺寸非法（探测失败）时不能判成「放得下」。 */
    @Test
    fun `非法尺寸判为放不下`() {
        assertFalse(BitmapBudget.fitsFullResolution(0, 100, maxHeapBytes = 4L * 1024 * mb))
        assertFalse(BitmapBudget.fitsFullResolution(100, -1, maxHeapBytes = 4L * 1024 * mb))
    }

    /** 1 亿像素即便堆给到 2 GB 也不该通过（3 份 = 1.2 GB）。 */
    @Test
    fun `一亿像素即使大堆也放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(12000, 9000, BitmapBudget.EXPORT_COPIES, 2048 * mb)
        )
    }

    @Test
    fun `MB 换算向上取整`() {
        assertEquals(0, BitmapBudget.megabytes(0))
        assertEquals(1, BitmapBudget.megabytes(1))
        assertEquals(1, BitmapBudget.megabytes(mb))
        assertEquals(2, BitmapBudget.megabytes(mb + 1))
        assertEquals(200, BitmapBudget.megabytes(200 * mb))
    }

    /** 默认参数必须真的能用（别在构造时炸，例如 maxMemory 返回 0）。 */
    @Test
    fun `默认参数可直接调用`() {
        assertTrue(BitmapBudget.budgetBytes() > 0)
        // 1x1 的小图在任何真实环境都放得下
        assertTrue(BitmapBudget.fitsFullResolution(1, 1))
    }
}