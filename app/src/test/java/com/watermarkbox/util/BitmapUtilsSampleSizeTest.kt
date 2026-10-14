package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitmap 降采样算法单元测试。
 *
 * 注意：BitmapUtils 中除 calculateInSampleSize 外都依赖 Android 框架类，
 * 无法在纯 JVM 单测中运行，因此此处只测试纯计算逻辑。
 *
 * ## 这个文件曾被"错误的正确行为"污染过（重要教训）
 *
 * 旧版本里有一条测试叫 `同时受限于宽和高`：
 *
 * ```kotlin
 * // 宽够但高不够时，不能继续降采样：5000x1500 目标 1024
 * assertEquals(1, BitmapUtils.calculateInSampleSize(5000, 1500, 1024))
 * ```
 *
 * 它把「5000×1500 完全不降采样」当成了**期望行为** ——
 * 而这恰恰是 bug 本身（`&&` 写法的后果）。
 * **一个把 bug 写成断言的测试，比没有测试更危险**：
 * 它会阻止后来者修复。本文件重写时已删除该断言。
 *
 * ## 现在的不变量（缺陷的判定标准）
 *
 * 降采样后，**最长边**必须落在 `[target, 2*target)`：
 * - 下界：不能降过头（否则预览糊得像马赛克）
 * - 上界：不能降不够（否则白花内存/CPU，正是本次修复的问题）
 */
class BitmapUtilsSampleSizeTest {

    /** 降采样后的实际尺寸（传入 sampleSize 取整除法，与解码器行为一致）。 */
    private fun scaled(w: Int, h: Int, sample: Int): Pair<Int, Int> =
        (w / sample) to (h / sample)

    /**
     * 核心不变量：降采样后最长边 ∈ [target, 2*target)。
     *
     * 这条断言能同时挡住「降不够」和「降过头」两类错误。
     */
    private fun assertLongestEdgeInRange(rawW: Int, rawH: Int, target: Int) {
        val sample = BitmapUtils.calculateInSampleSize(rawW, rawH, target)
        val (w, h) = scaled(rawW, rawH, sample)
        val longest = maxOf(w, h)
        assertTrue(
            "降采样后最长边 $longest 应 >= target($target)，" +
                "实际 raw=${rawW}x$rawH sample=$sample -> ${w}x$h",
            longest >= target
        )
        assertTrue(
            "降采样后最长边 $longest 应 < 2*target(${target * 2})，" +
                "实际 raw=${rawW}x$rawH sample=$sample -> ${w}x$h",
            longest < target * 2
        )
    }

    @Test
    fun `目标为 0 时不降采样`() {
        assertEquals(1, BitmapUtils.calculateInSampleSize(4000, 3000, 0))
    }

    @Test
    fun `目标为负时不降采样`() {
        assertEquals(1, BitmapUtils.calculateInSampleSize(4000, 3000, -1))
    }

    @Test
    fun `小图不降采样`() {
        assertEquals(1, BitmapUtils.calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun `大图降采样为 2 的幂`() {
        assertEquals(2, BitmapUtils.calculateInSampleSize(4000, 3000, 1024))
    }

    @Test
    fun `超高分辨率图片降采样`() {
        assertEquals(4, BitmapUtils.calculateInSampleSize(8000, 6000, 1024))
    }

    @Test
    fun `正好等于两倍目标时降采样一次`() {
        assertEquals(2, BitmapUtils.calculateInSampleSize(2048, 2048, 1024))
    }

    /**
     * **回归防护（本次修复的核心用例）**。
     *
     * 2048×1536、目标 1024。旧实现因为 `&&` 而返回 1（完全不降），
     * 导致预览每帧要处理 12 MB 的位图 —— 这就是「预览不实时」的根因。
     */
    @Test
    fun `非正方形图_宽达标高不达标时仍应降采样`() {
        // 2048/2=1024 达标，1536/2=768 不达标。
        // 正确行为：只要最长边还超，就继续降 → sampleSize=2
        // 旧行为：1（错误）
        assertEquals(2, BitmapUtils.calculateInSampleSize(2048, 1536, 1024))
        assertLongestEdgeInRange(2048, 1536, 1024)
    }

    /**
     * 另一条曾经被写错的用例。
     *
     * 旧测试断言它是 1（不降采样），但那会让 5000×1500 的图
     * 以 7.5 倍于目标的尺寸进入预览渲染。
     */
    @Test
    fun `极端宽幅图_也应降采样到目标附近`() {
        // 5000x1500，target=1024：最长边 5000，折两次 → 1250，落在 [1024, 2048)
        val sample = BitmapUtils.calculateInSampleSize(5000, 1500, 1024)
        assertEquals(4, sample)
        assertLongestEdgeInRange(5000, 1500, 1024)
    }

    /** 竖图（高 > 宽）走同一套逻辑。 */
    @Test
    fun `竖图_最长边为高`() {
        assertEquals(2, BitmapUtils.calculateInSampleSize(1536, 2048, 1024))
        assertLongestEdgeInRange(1536, 2048, 1024)
    }

    /** 手机拍摄的常见大图（5760×3840 = 2200 万像素）。 */
    @Test
    fun `手机常见大图_降采样到目标附近`() {
        val sample = BitmapUtils.calculateInSampleSize(5760, 3840, 1024)
        // 5760 -> 2880 -> 1440 (<=2048 停)，sampleSize=4
        assertEquals(4, sample)
        assertLongestEdgeInRange(5760, 3840, 1024)
    }

    /**
     * 遍历一批典型尺寸，逐一验证不变量。
     *
     * 比零散用例更能覆盖边界（尤其是「恰好等于目标」「恰好等于两倍目标」）。
     */
    @Test
    fun `所有典型尺寸都满足最长边不变量`() {
        val sizes = listOf(
            800 to 600,       // 小图
            1024 to 1024,     // 恰好等于目标
            1025 to 1024,     // 略超目标
            2048 to 1536,     // 本次修复的触发场景
            2048 to 2048,     // 恰好两倍
            2049 to 2048,     // 略超两倍
            4000 to 3000,     // 常见相机
            3000 to 4000,     // 竖拍
            5000 to 1500,     // 极端宽幅
            1500 to 5000,     // 极端长幅
            5760 to 3840,     // 2200 万像素
            8000 to 6000,     // 4800 万像素
            12000 to 9000,    // 一亿像素（部分机型）
        )
        for ((w, h) in sizes) {
            if (maxOf(w, h) < 1024) continue  // 小图不降是预期内的，跳过
            assertLongestEdgeInRange(w, h, 1024)
        }
    }

    /** sampleSize 必须是 2 的幂（保证解码器走整数移位路径）。 */
    @Test
    fun `返回值始终是 2 的幂`() {
        val sizes = listOf(2048 to 1536, 5760 to 3840, 12000 to 9000, 9999 to 1111)
        for ((w, h) in sizes) {
            val s = BitmapUtils.calculateInSampleSize(w, h, 1024)
            assertTrue(
                "sampleSize=$s 不是 2 的幂（raw=${w}x$h）",
                s > 0 && (s and (s - 1)) == 0
            )
        }
    }

    // ---- L8：极端宽高比（长条形图）----
    //
    // 原报告怀疑「对 10000×10 这类图会降很多次、可能降过头」。
    // 实测**不会**：循环条件每次都重新检查最长边，所以停在「再降一档就不够」的位置。
    // 这三条测试把这个结论钉住，防止将来有人把条件改成 `maxOf(w, h) >= target`。

    @Test
    fun `极端长条形图不会降过头`() {
        // 10000×10、目标 1000：3 次折半后最长边 1250（≥ 1000），再降一档就 625（< 1000）。
        val s = BitmapUtils.calculateInSampleSize(10000, 10, 1000)
        assertEquals(8, s)
        assertTrue("最长边应仍 ≥ 目标", 10000 / s >= 1000)
        assertTrue("再降一档就不够了（说明没降过头）", 10000 / (s * 2) < 1000)
    }

    @Test
    fun `细高图与长条图结果对称`() {
        // 算法只取 maxOf(w, h)，横竖旋转不该有差别
        assertEquals(
            BitmapUtils.calculateInSampleSize(10000, 10, 1000),
            BitmapUtils.calculateInSampleSize(10, 10000, 1000)
        )
    }

    @Test
    fun `短边被折到 0 也不崩`() {
        // 10000×1 这类退化输入：循环里 h/2 会一路变成 0。
        // 但循环条件只看最长边，所以既不会死循环也不会出负数。
        val s = BitmapUtils.calculateInSampleSize(10000, 1, 1000)
        assertTrue("sampleSize 应为正", s > 0)
        assertTrue("最长边应仍 ≥ 目标", 10000 / s >= 1000)
    }
}
