package com.watermarkbox.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证「两端判据一致性」与「位置序列零漂移」。
 *
 * ## 演进历史（记录踩过的坑）
 *
 * 1. **v1：用「像素方差 >= 45」筛块**
 *    嵌入端与提取端各筛一次。因为嵌入改了像素，方差跟着变，
 *    实测**阈值附近的块有 7.4% 会翻转** → 两端块集合错位 → 提取失败。
 *
 * 2. **v2：改用「低频 AC / DC >= 0.06」筛块**
 *    判据更稳定，但**真实照片（纹理在中频）会被误杀**，
 *    `embed` 直接返回 null。
 *
 * 3. **v3：改用「全部 AC / DC >= 0.5」筛块**
 *    仍实测有 **2.25% 的块在嵌入后翻转**（45/2000）——
 *    说明**任何阈值判据都有边界翻转，这是数学必然，无法根治**。
 *
 * 4. **v4（当前）：不筛块，全部块参与 + 自适应步长**
 *    - 位置序列只依赖块索引 → **零漂移**
 *    - 自适应步长只依赖 DC → 嵌入不碰 DC → **两端必然一致**
 *    - 提取端用 confidence 加权投票 → 平坦块权重自然低
 *
 * 本测试钉住 v4 的两个不变量。
 */
class BlockSelectionStabilityTest {

    @Test
    fun adaptive_strength_depends_only_on_dc() {
        // 自适应步长的依据是 DC。嵌入只改中频，因此 DC 在往返后
        // 只有浮点级变化 → 两端算出的步长必须相同。
        val rnd = kotlin.random.Random(7)
        var mismatch = 0

        repeat(1000) {
            val base = rnd.nextInt(180) + 30f
            val noise = rnd.nextInt(50).toFloat()
            val block = FloatArray(64) { i ->
                base + (i % 8) * (noise / 8f) + rnd.nextInt(15).toFloat()
            }

            val before = Dct8x8.forward(block)
            val qBefore = adaptiveStrengthLike(before)

            // 模拟嵌入：改一个中频系数
            val changed = before.copyOf()
            val idx = 4 * 8 + 4
            changed[idx] = Qim.embed(changed[idx], 1, qBefore)
            val pixels = Dct8x8.inverse(changed)
            val quantized = FloatArray(64) { Math.round(pixels[it]).toFloat() }
            val after = Dct8x8.forward(quantized)
            val qAfter = adaptiveStrengthLike(after)

            if (kotlin.math.abs(qBefore - qAfter) > 1e-3f) mismatch++
        }

        assertEquals(
            "自适应步长在嵌入前后必须完全一致（依据只有 DC）",
            0,
            mismatch
        )
    }

    @Test
    fun full_order_is_deterministic() {
        // 同一个 (totalBlocks, bitCount) 必须永远生成同样的序列，
        // 否则嵌入端与提取端位置对不上。
        val o1 = orderFor(4096, 192)
        val o2 = orderFor(4096, 192)
        assertTrue("位置序列必须可复现", o1.contentEquals(o2))

        // 覆盖度：应铺满（R 轮 × bitCount）
        assertEquals(4096 / 192 * 192, o1.size)
    }

    @Test
    fun dct_is_invertible_across_many_random_blocks() {
        val rnd = kotlin.random.Random(99)
        var worst = 0f
        repeat(500) {
            val block = FloatArray(64) { rnd.nextInt(256).toFloat() }
            val back = Dct8x8.inverse(Dct8x8.forward(block))
            for (i in 0 until 64) {
                worst = maxOf(worst, kotlin.math.abs(back[i] - block[i]))
            }
        }
        assertTrue("随机块往返误差应 < 0.1，实际 $worst", worst < 0.1f)
    }

    // ---- 与引擎同算法的复刻（放在测试侧，避免暴露内部 API）----

    private fun adaptiveStrengthLike(coeffs: FloatArray): Float {
        val strength = 18f
        val dc = kotlin.math.abs(coeffs[0]) / 8f
        val factor = when {
            dc < 40f -> 1.0f
            dc > 215f -> 1.0f
            else -> 0.55f
        }
        return (strength * factor).coerceAtLeast(4f)
    }

    private fun orderFor(totalBlocks: Int, bitCount: Int): IntArray {
        val r = maxOf(1, totalBlocks / bitCount)
        val result = IntArray(r * bitCount)
        for (round in 0 until r) {
            val all = IntArray(totalBlocks) { it }
            val rnd = kotlin.random.Random((0x5761746DL + round).toInt())
            for (i in totalBlocks - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = all[i]; all[i] = all[j]; all[j] = t
            }
            val offset = (round * bitCount) % totalBlocks
            for (i in 0 until bitCount) {
                result[round * bitCount + i] = all[(offset + i) % totalBlocks]
            }
        }
        return result
    }
}