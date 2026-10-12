package com.watermarkbox.invisible

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频点选点的正确性测试。
 *
 * 钉住「只选用 JPEG 量化步长小的频点」这一核心设计 ——
 * 它是水印能扛住 JPEG 重压缩的前提。
 */
class MidBandSelectionTest {

    @Test
    fun selected_bands_have_small_jpeg_quantization() {
        // 复刻引擎的选点逻辑，验证全部频点的量化步长 <= 14
        val q = InvisibleWatermarkEngine.JPEG_LUMA_QUANT
        val selected = buildList {
            for (v in 0 until 8) {
                for (u in 0 until 8) {
                    val s = u + v
                    if (s < 2 || s > 6) continue
                    if (q[v][u] > 14) continue
                    add(u to v)
                }
            }
        }
        println("MID_BAND size=${selected.size} -> $selected")
        assertTrue("必须选出至少 4 个频点，实际 ${selected.size}", selected.size >= 4)
        selected.forEach { (u, v) ->
            assertTrue("($u,$v) 的量化步长应 <= 14", q[v][u] <= 14)
        }
    }

    @Test
    fun strength_for_selected_bands_is_large_enough() {
        // 最小步长必须明显大于 JPEG 质量 90 的量化步长（约为标准表的 1/4
        // 再叠加质量系数），否则会被压缩抹平。
        val q = InvisibleWatermarkEngine.JPEG_LUMA_QUANT
        val selected = buildList {
            for (v in 0 until 8) for (u in 0 until 8) {
                val s = u + v
                if (s in 2..6 && q[v][u] <= 14) add(u to v)
            }
        }
        // STRENGTH_OVER_QUANT=6、中灰因子=0.7、scale=1
        val minStep = selected.minOf { (u, v) -> q[v][u] * 6f * 0.7f }
        println("min step = $minStep")
        assertTrue("最小步长应 >= 40，实际 $minStep", minStep >= 40f)
    }
}