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
 * ## 判错方向的后果（不对称）
 *
 * - 判「放得下」而其实放不下 → **崩溃**
 * - 判「放不下」而其实可以 → 用户少导一张图（但会明确告知"图太大"）
 *
 * 所以宁可保守。但**过度保守也是 bug**：早期按 `0.5` 系数 + 固定 3 份，
 * 256MB 堆下上限只有 42.7MB（约 1120 万像素），
 * 导致随手拍的照片就"导出失败" —— 这正是老大实测到的现象。
 */
class BitmapBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `字节数按像素与份数计算`() {
        // 1000x500 = 50 万像素 × 4 字节 = 2 MB
        assertEquals(2_000_000L, BitmapBudget.bytesOf(1000, 500))
        assertEquals(6_000_000L, BitmapBudget.bytesOf(1000, 500, copies = 3))
    }

    /**
     * 预算是堆上限的 60%。
     *
     * 历史：早期是 50%。下调到 60% 是因为
     * API 26+ 位图内存走 NativeAllocationRegistry、不计入 Java 堆，
     * "留一半给 Java 堆"的前提不成立。
     */
    @Test
    fun `预算是堆上限的六成`() {
        assertEquals((256 * mb * 0.6).toLong(), BitmapBudget.budgetBytes(256 * mb))
        assertEquals((512 * mb * 0.6).toLong(), BitmapBudget.budgetBytes(512 * mb))
    }

    // ------------------------------------------------------------------
    // exportCopies：按实际通道数取份数
    // ------------------------------------------------------------------

    @Test
    fun `开 DCT 时导出需要三份`() {
        assertEquals(3, BitmapBudget.exportCopies(withDct = true))
    }

    /** 只写 EXIF 时没有频域副本，只需 2 份 —— 别多扣 1/3 预算。 */
    @Test
    fun `不开 DCT 时导出只需两份`() {
        assertEquals(2, BitmapBudget.exportCopies(withDct = false))
    }

    // ------------------------------------------------------------------
    // 回归：这次修复的核心场景
    // ------------------------------------------------------------------

    /**
     * **回归防护**：1200 万像素（手机随手拍）在 256MB 堆下必须能导出。
     *
     * 4032×3024 = 1219 万像素，一份 46.5 MB。
     * - 不开 DCT：2 份 = 93 MB ≤ 256×0.6 = 153.6 MB ✅
     * - 开 DCT：  3 份 = 139.5 MB ≤ 153.6 MB ✅
     *
     * 而旧实现（0.5 系数 + 固定 3 份）预算只有 128 MB，
     * 3 份 139.5 MB > 128 MB → **判为太大而失败**。
     */
    @Test
    fun `一千二百万像素在256MB堆下应能导出`() {
        val w = 4032
        val h = 3024
        val heap = 256 * mb

        assertTrue(
            "不开 DCT 时必须放得下（旧实现这里会失败）",
            BitmapBudget.fitsFullResolution(
                w, h, BitmapBudget.exportCopies(withDct = false), heap
            )
        )
        assertTrue(
            "开 DCT 时也必须放得下",
            BitmapBudget.fitsFullResolution(
                w, h, BitmapBudget.exportCopies(withDct = true), heap
            )
        )
    }

    /** 512MB 堆（largeHeap 生效）下，2000 万像素也该能过。 */
    @Test
    fun `两千万像素在largeHeap下能导出`() {
        // 5472×3648 = 1996 万像素，一份 76.4 MB；3 份 229 MB ≤ 307 MB ✅
        assertTrue(
            BitmapBudget.fitsFullResolution(
                5472, 3648, BitmapBudget.exportCopies(withDct = true), 512 * mb
            )
        )
    }

    /**
     * 加 `largeHeap` 后上限翻倍：同一张图可能从"放不下"变"放得下"。
     *
     * 用 6000×4500（2700 万像素，一份 103 MB）验证 —— 注意这里**不开 DCT**
     * （2 份），否则 3 份 = 309 MB 在 512MB 堆下也差一点点（预算 307 MB）。
     * 正好说明"按实际份数算"的价值：开关 DCT 会改变可行性判断。
     */
    @Test
    fun `largeHeap 让原本超限的图变可行`() {
        val copies = BitmapBudget.exportCopies(withDct = false)   // 2 份 = 206 MB

        assertFalse(
            "256MB 堆预算 153.6MB < 需要的 206MB → 应判放不下",
            BitmapBudget.fitsFullResolution(6000, 4500, copies, 256 * mb)
        )
        assertTrue(
            "512MB 堆预算 307.2MB ≥ 206MB → 应判放得下",
            BitmapBudget.fitsFullResolution(6000, 4500, copies, 512 * mb)
        )
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    /** 5000 万像素（8160×6120）三份约 598 MB → 512 MB 堆下必然放不下。 */
    @Test
    fun `五千万像素放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(
                width = 8160, height = 6120,
                copies = BitmapBudget.exportCopies(withDct = true),
                maxHeapBytes = 512 * mb
            )
        )
    }

    /** 同一张图，「导出三份」放不下、「检测两份」可能放得下。 */
    @Test
    fun `份数不同结论可以不同`() {
        val w = 7000
        val h = 5000          // 3500 万像素：一份 133.5 MB
        val heap = 512 * mb   // 预算 307 MB
        assertTrue(BitmapBudget.fitsFullResolution(w, h, BitmapBudget.DETECT_COPIES, heap))
        assertFalse(
            BitmapBudget.fitsFullResolution(
                w, h, BitmapBudget.exportCopies(withDct = true), heap
            )
        )
    }

    /** 尺寸非法（探测失败）时不能判成「放得下」。 */
    @Test
    fun `非法尺寸判为放不下`() {
        assertFalse(BitmapBudget.fitsFullResolution(0, 100, maxHeapBytes = 4L * 1024 * mb))
        assertFalse(BitmapBudget.fitsFullResolution(100, -1, maxHeapBytes = 4L * 1024 * mb))
    }

    /** 一亿像素即便堆给到 2 GB 也不该通过（3 份 = 1.2 GB）。 */
    @Test
    fun `一亿像素即使大堆也放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(
                12000, 9000, BitmapBudget.exportCopies(withDct = true), 2048 * mb
            )
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