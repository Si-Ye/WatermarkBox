package com.watermarkbox.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **失真补偿 QIM（DC-QIM）的回归测试**。
 *
 * ## 背景
 *
 * 普通 QIM 把系数强行拉到格子中心 `k·q`，带来不必要的嵌入失真。
 * DC-QIM 只朝中心推 α 比例（`目标 = 中心 + α×(原值−中心)`），
 * 在画质与鲁棒性之间取得更好的折中。
 *
 * 实测（512×512 真实纹理图）：
 * | α | PSNR | 逐位正确率 |
 * |---|---|---|
 * | 0.0 | 38.82 dB | 100.00% |
 * | 0.4 | 43.16 dB | 99.58% |  ← 采用值
 * | 0.6 | 46.54 dB | 83.28% |  ← 开始崩
 *
 * ## 本测试守住三条底线
 *
 * 1. **判决一致**：α=0.4 下，提取端仍必须读出正确的 bit
 *    （这是整件事的前提 —— 画质再好，读不出来也没用）
 * 2. **画质确实提升**：α=0.4 的失真必须显著小于 α=0
 * 3. **不许越过安全线**：α ≥ 0.6 时会崩，所以默认值必须 < 0.5
 */
class DcQimTest {

    private val q = 48f

    /** α=0 必须与普通 embed 完全等价 —— 保证旧图兼容。 */
    @Test
    fun alpha_zero_equals_plain_embed() {
        for (coeff in listOf(-150f, -47f, -1f, 0f, 1f, 33f, 96f, 200f)) {
            for (bit in listOf(0, 1)) {
                val plain = Qim.embed(coeff, bit, q)
                val dc = Qim.embedCompensated(coeff, bit, q, alpha = 0f)
                assertEquals(
                    "α=0 必须退化成普通 QIM（旧图兼容的前提）",
                    plain, dc, 1e-4f
                )
            }
        }
    }

    /** 核心：α=0.4 下提取端必须读出正确 bit。 */
    @Test
    fun defaultAlpha_keeps_extraction_correct() {
        val alpha = Qim.DEFAULT_ALPHA
        var ok = 0
        var total = 0
        // 覆盖各种系数值（含中间值、边界值、负数）
        for (i in -400..400) {
            val coeff = i * 1.7f
            for (bit in listOf(0, 1)) {
                val embedded = Qim.embedCompensated(coeff, bit, q, alpha)
                if (Qim.extract(embedded, q) == bit) ok++
                total++
            }
        }
        val rate = ok.toFloat() / total
        assertTrue(
            "α=${alpha} 下提取正确率应 ≥99%，实测 ${rate * 100}%",
            rate >= 0.99f
        )
    }

    /** 画质：α=0.4 的失真应显著小于 α=0。 */
    @Test
    fun defaultAlpha_reduces_distortion() {
        val alpha = Qim.DEFAULT_ALPHA
        var sumPlain = 0.0
        var sumDc = 0.0
        var n = 0
        for (i in -400..400) {
            val coeff = i * 1.7f
            val bit = i and 1
            val plain = Qim.embed(coeff, bit, q)
            val dc = Qim.embedCompensated(coeff, bit, q, alpha)
            sumPlain += abs(plain - coeff).toDouble()
            sumDc += abs(dc - coeff).toDouble()
            n++
        }
        val plainAvg = sumPlain / n
        val dcAvg = sumDc / n
        println("DISTORTION plain=%.3f dc=%.3f (%.1f%%)".format(
            plainAvg, dcAvg, 100.0 * dcAvg / plainAvg
        ))
        assertTrue(
            "α=$alpha 的平均失真应明显小于普通 QIM（plain=$plainAvg dc=$dcAvg）",
            dcAvg < plainAvg * 0.75
        )
    }

    /** 安全线：默认 α 必须 < 0.5（实测 ≥0.6 时正确率雪崩）。 */
    @Test
    fun defaultAlpha_belowDangerZone() {
        assertTrue(
            "默认 α 必须 <0.5，实测 ${Qim.DEFAULT_ALPHA}",
            Qim.DEFAULT_ALPHA < 0.5f
        )

        // 顺便记录一下越线后的实际退化，作为文档证据
        for (alpha in listOf(0.6f, 0.8f)) {
            var ok = 0
            var total = 0
            for (i in -400..400) {
                val coeff = i * 1.7f
                for (bit in listOf(0, 1)) {
                    val e = Qim.embedCompensated(coeff, bit, q, alpha)
                    if (Qim.extract(e, q) == bit) ok++
                    total++
                }
            }
            println("DANGER-ZONE alpha=%.1f accuracy=%.2f%%".format(
                alpha, 100.0 * ok / total
            ))
        }
    }

    /** 端到端：DC-QIM 下完整嵌入→提取闭环仍能还原文本。 */
    @Test
    fun endToEnd_roundTrip() {
        val w = 256
        val h = 256
        val text = "WMB1:dcqim-roundtrip"

        // 造一张带纹理的亮度图（纯色图会让 q 退化，测不出东西）
        val rnd = kotlin.random.Random(5)
        val base = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            (70f + 90f * x / w + 30f * y / h + rnd.nextInt(60)).coerceIn(0f, 255f)
        }
        val px = base.copyOf()
        val BLOCK = 8
        val jpegQ = InvisibleWatermarkEngine.JPEG_LUMA_QUANT
        val midBand = buildList {
            for (v in 0 until 8) for (u in 0 until 8) {
                val s = u + v
                if (s < 2 || s > 6) continue
                if (jpegQ[v][u] > 14) continue
                add(u to v)
            }
        }
        val payload = WatermarkPayload.encode(text)!!
        val encoded = ReedSolomon.encode(payload)
        val bits = IntArray(encoded.size * 8) { i ->
            ((encoded[i / 8].toInt() and 0xFF) shr (7 - (i % 8))) and 1
        }
        val bx = w / BLOCK
        val total = bx * (h / BLOCK)
        // 洗牌（与引擎同种子）
        val order = IntArray(total) { it }
        val rnd2 = kotlin.random.Random(0x5761746DL.toInt())
        for (i in total - 1 downTo 1) {
            val j = rnd2.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }

        fun readBlock(bx0: Int, by0: Int) = FloatArray(64).also {
            var i = 0
            for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
                it[i++] = px[(by0 + y) * w + bx0 + x]
            }
        }

        for (bi in 0 until total) {
            val bxx = (bi % bx) * BLOCK
            val byy = (bi / bx) * BLOCK
            val orig = readBlock(bxx, byy)
            val coeffs = Dct8x8.forward(orig)
            val (u, v) = midBand[bi % midBand.size]
            val jq = jpegQ[v][u].toFloat()
            val dc = abs(coeffs[0]) / 8f
            val lf = if (dc < 40f || dc > 215f) 1.0f else 0.7f
            val act = abs(coeffs[1]) + abs(coeffs[8])
            val af = when { act < 30f -> 0.6f; act < 100f -> 0.8f; else -> 1f }
            val qq = (jq * 6f * lf * af).coerceIn(4f, 60f)
            val idx = v * 8 + u
            val bit = bits[order[bi] % bits.size]
            val ch = coeffs.copyOf()
            ch[idx] = Qim.embedCompensated(ch[idx], bit, qq)
            val full = Dct8x8.inverse(ch)
            val quant = FloatArray(64) { full[it].roundToInt().toFloat() }
            var i = 0
            for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
                val pIdx = (byy + y) * w + bxx + x
                px[pIdx] = (px[pIdx] + (quant[i] - orig[i])).coerceIn(0f, 255f)
                i++
            }
        }

        // 提取：软判决投票
        val bitCount = bits.size
        val soft = FloatArray(bitCount)
        val wsum = FloatArray(bitCount)
        for (bi in 0 until total) {
            val bxx = (bi % bx) * BLOCK
            val byy = (bi / bx) * BLOCK
            val c = Dct8x8.forward(readBlock(bxx, byy))
            val (u, v) = midBand[bi % midBand.size]
            val jq = jpegQ[v][u].toFloat()
            val dc = abs(c[0]) / 8f
            val lf = if (dc < 40f || dc > 215f) 1.0f else 0.7f
            val act = abs(c[1]) + abs(c[8])
            val af = when { act < 30f -> 0.6f; act < 100f -> 0.8f; else -> 1f }
            val qq = (jq * 6f * lf * af).coerceIn(4f, 60f)
            val val0 = c[v * 8 + u]
            val conf = Qim.confidence(val0, qq)
            val b = Qim.extract(val0, qq)
            val slot = order[bi] % bitCount
            soft[slot] += if (b == 1) conf else -conf
            wsum[slot] += conf
        }
        val voted = ByteArray(encoded.size)
        for (i in encoded.indices) {
            var b = 0
            for (j in 0 until 8) {
                val flat = i * 8 + j
                if (flat >= bitCount) break
                b = (b shl 1) or (if (soft[flat] >= 0f) 1 else 0)
            }
            voted[i] = b.toByte()
        }
        val decoded = WatermarkPayload.decode(ReedSolomon.decode(voted))
        println("ROUND-TRIP decoded=$decoded")
        assertEquals("DC-QIM 闭环必须还原文本", text, decoded)
    }
}