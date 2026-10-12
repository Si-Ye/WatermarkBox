package com.watermarkbox.invisible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Dct8x8] 数学正确性测试。
 *
 * ## 为什么必须先测这个
 *
 * 隐藏水印的整个鲁棒性建立在「DCT → 改系数 → IDCT 还原」这条链上。
 * 如果 forward/inverse 不是**真正的逆变换对**，那么：
 * - 即使不改任何系数，IDCT(DCT(x)) 也不等于 x → 图像整体失真
 * - 改了系数后，实际像素改动量级无法预估 → 要么暴露要么读不出
 *
 * 实测症状（改动达 18、提取失败）高度符合"逆变换不配对"，
 * 因此先钉住这个基础不变量。
 */
class DctMathTest {

    @Test
    fun forward_inverse_is_identity() {
        // 造一个确定的 8×8 块
        val block = FloatArray(64) { (it * 7 % 200 + 20).toFloat() }

        val coeffs = Dct8x8.forward(block)
        val restored = Dct8x8.inverse(coeffs)

        var maxErr = 0f
        for (i in 0 until 64) {
            maxErr = maxOf(maxErr, kotlin.math.abs(restored[i] - block[i]))
        }
        assertTrue(
            "IDCT(DCT(x)) 必须还原 x，最大误差应 < 1e-2，实际 $maxErr",
            maxErr < 1e-2f
        )
    }

    @Test
    fun dc_coefficient_of_constant_block() {
        // 常数块：只有 DC 分量应非零
        val block = FloatArray(64) { 128f }
        val coeffs = Dct8x8.forward(block)

        // DC = 8 * mean（因为 c(0)=1/√2，两次归一化后 DC = 8*mean）
        assertEquals(8f * 128f, coeffs[0], 0.5f)

        // 所有 AC 分量应接近 0
        var maxAc = 0f
        for (i in 1 until 64) maxAc = maxOf(maxAc, kotlin.math.abs(coeffs[i]))
        assertTrue("常数块的 AC 应接近 0，实际最大 $maxAc", maxAc < 0.01f)
    }

    @Test
    fun energy_is_concentrated_in_dc_for_smooth_block() {
        // 平缓渐变块：能量应集中在低频
        val block = FloatArray(64) { i -> (100 + (i % 8) * 2).toFloat() }
        val coeffs = Dct8x8.forward(block)
        var lowEnergy = 0f
        var highEnergy = 0f
        for (v in 0 until 8) {
            for (u in 0 until 8) {
                val e = kotlin.math.abs(coeffs[v * 8 + u])
                if (u + v <= 2) lowEnergy += e else highEnergy += e
            }
        }
        assertTrue("平缓块的能量应主要在低频", lowEnergy > highEnergy * 3)
    }

    @Test
    fun modifying_one_coefficient_changes_pixels_by_predictable_amount() {
        // 关键实验：把一个中频系数加 delta，看像素最大改动多少
        val block = FloatArray(64) { i -> (60 + (i % 8) * 12 + (i / 8) * 5).toFloat() }
        val coeffs = Dct8x8.forward(block)

        // 选一个中频位置 (u=3, v=3)
        val idx = 3 * 8 + 3
        coeffs[idx] += 20f

        val restored = Dct8x8.inverse(coeffs)
        var maxDiff = 0f
        for (i in 0 until 64) {
            maxDiff = maxOf(maxDiff, kotlin.math.abs(restored[i] - block[i]))
        }
        // 单个中频系数改 20，像素最大改动应远小于 20（能量扩散到 64 个像素）
        assertTrue(
            "单系数改 20 的像素最大改动应 < 10，实际 $maxDiff",
            maxDiff < 10f
        )
    }

    @Test
    fun qim_round_trip_is_exact() {
        // QIM 嵌入后立即提取，必须 100% 正确
        val q = 18f
        for (raw in -100..100 step 7) {
            val coeff = raw.toFloat()
            val embedded0 = Qim.embed(coeff, 0, q)
            val embedded1 = Qim.embed(coeff, 1, q)
            assertEquals("bit=0 应立即读回 0", 0, Qim.extract(embedded0, q))
            assertEquals("bit=1 应立即读回 1", 1, Qim.extract(embedded1, q))
        }
    }

    @Test
    fun full_block_qim_round_trip_through_idct() {
        // **最关键**的实验：QIM 改系数 → IDCT → 重新 DCT → 读 bit
        val q = 18f
        val block = FloatArray(64) { i -> (70 + (i * 13 % 90)).toFloat() }

        val originalCoeffs = Dct8x8.forward(block)
        val idx = 3 * 8 + 3

        var correct = 0
        var total = 0
        for (bit in listOf(0, 1, 0, 1)) {
            val newCoeffs = originalCoeffs.copyOf()
            newCoeffs[idx] = Qim.embed(newCoeffs[idx], bit, q)

            val pixels = Dct8x8.inverse(newCoeffs)
            // 量化到整数（模拟真实像素存储）
            val quantized = FloatArray(64) { Math.round(pixels[it]).toFloat() }
            // 重新做 DCT（模拟提取端）
            val reread = Dct8x8.forward(quantized)

            val readBit = Qim.extract(reread[idx], q)
            if (readBit == bit) correct++
            total++
        }
        assertEquals(
            "经 IDCT + 像素量化 + 重新 DCT 后，QIM bit 必须仍可正确读出",
            total,
            correct
        )
    }
}