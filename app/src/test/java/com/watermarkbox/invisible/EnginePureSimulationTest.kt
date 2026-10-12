package com.watermarkbox.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * **纯 JVM 端到端模拟**：不依赖 Android Bitmap，直接用 FloatArray 像素桶
 * 复刻引擎的 embed/extract 流程，定位确定性问题。
 *
 * 若这个测试通过而 Android 侧失败，则问题在 Bitmap 层（getPixels/setPixels）。
 * 若这个测试也失败，则问题在算法层。
 */
class EnginePureSimulationTest {

    private val BLOCK = 8
    private val JPEG_Q = InvisibleWatermarkEngine.JPEG_LUMA_QUANT

    private val midBand: List<Pair<Int, Int>> = buildList {
        for (v in 0 until 8) for (u in 0 until 8) {
            val s = u + v
            if (s < 2 || s > 6) continue
            if (JPEG_Q[v][u] > 14) continue
            add(u to v)
        }
    }

    /** 造一张 WxH 的亮度图（0~255）。 */
    private fun makeLuma(w: Int, h: Int): FloatArray {
        val rnd = Random(42)
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (80 + x * 100 / w + y * 60 / h + rnd.nextInt(40)).toFloat()
        }
    }

    private fun adaptiveStrength(dc: Float, u: Int, v: Int): Float {
        val jq = JPEG_Q[v][u].toFloat()
        val luminance = dc / 8f
        val f = when {
            luminance < 40f -> 1.0f
            luminance > 215f -> 1.0f
            else -> 0.7f
        }
        return (jq * 4f * f).coerceAtLeast(4f)
    }

    private fun bitSlotOrder(total: Int): IntArray {
        val all = IntArray(total) { it }
        val rnd = Random(0x5761746DL.toInt())
        for (i in total - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = all[i]; all[i] = all[j]; all[j] = t
        }
        return all
    }

    private fun readBlock(pixels: FloatArray, w: Int, bx: Int, by: Int): FloatArray {
        val out = FloatArray(64)
        var i = 0
        for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            out[i++] = pixels[(by + y) * w + bx + x]
        }
        return out
    }

    private fun writeBlock(
        pixels: FloatArray, w: Int, bx: Int, by: Int,
        newLuma: FloatArray, origLuma: FloatArray
    ) {
        // 与引擎一致的"安全缩放"：避免越界导致实际 ΔY 小于预期
        var scale = 1f
        var i = 0
        for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            val delta = newLuma[i] - origLuma[i]
            val cur = pixels[(by + y) * w + bx + x]
            val room = if (delta > 0) (255f - cur) else cur
            val needed = abs(delta)
            if (needed > 0.01f && room < needed) scale = minOf(scale, room / needed)
            i++
        }
        i = 0
        for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            val idx = (by + y) * w + bx + x
            val delta = (newLuma[i] - origLuma[i]) * scale
            pixels[idx] = (origLuma[i] + delta).coerceIn(0f, 255f)
            i++
        }
    }

    @Test
    fun pure_embed_extract_round_trip() {
        val w = 512
        val h = 512
        val pixels = makeLuma(w, h)
        val text = "WMB1:hello-invisible"

        // ---- embed ----
        val payload = WatermarkPayload.encode(text)!!
        val encoded = ReedSolomon.encode(payload)
        val bits = IntArray(encoded.size * 8) { i ->
            val b = encoded[i / 8].toInt() and 0xFF
            (b shr (7 - (i % 8))) and 1
        }
        val blocksX = w / BLOCK
        val total = blocksX * (h / BLOCK)
        val order = bitSlotOrder(total)

        val work = pixels.copyOf()
        for (blockIndex in 0 until total) {
            val bit = bits[order[blockIndex] % bits.size]
            val bx = (blockIndex % blocksX) * BLOCK
            val by = (blockIndex / blocksX) * BLOCK
            val orig = readBlock(work, w, bx, by)
            val coeffs = Dct8x8.forward(orig)
            val (u, v) = midBand[blockIndex % midBand.size]
            val q = adaptiveStrength(coeffs[0], u, v)
            coeffs[v * 8 + u] = Qim.embed(coeffs[v * 8 + u], bit, q)
            val out = Dct8x8.inverse(coeffs)
            val quant = FloatArray(64) { Math.round(out[it]).toFloat() }
            writeBlock(work, w, bx, by, quant, orig)
        }

        // ---- 逐位检查：嵌入端写的 bit 与提取端读的 bit 是否一致 ----
        var bitOk = 0
        var bitTotal = 0
        for (blockIndex in 0 until total) {
            val bx = (blockIndex % blocksX) * BLOCK
            val by = (blockIndex / blocksX) * BLOCK
            val blk = readBlock(work, w, bx, by)
            val coeffs = Dct8x8.forward(blk)
            val (u, v) = midBand[blockIndex % midBand.size]
            val q = adaptiveStrength(coeffs[0], u, v)
            val expected = bits[order[blockIndex] % bits.size]
            if (Qim.extract(coeffs[v * 8 + u], q) == expected) bitOk++
            bitTotal++
        }
        println("SIM bitAccuracy=all=$bitTotal ok=$bitOk (${bitOk * 100 / bitTotal}%)")

        // ---- 全量投票检查 ----
        val coeffVals = FloatArray(total)
        val coeffQs = FloatArray(total)
        val coeffConf = FloatArray(total)
        for (bi in 0 until total) {
            val bx = (bi % blocksX) * BLOCK
            val by = (bi / blocksX) * BLOCK
            val blk = readBlock(work, w, bx, by)
            val coeffs = Dct8x8.forward(blk)
            val (u, v) = midBand[bi % midBand.size]
            val q = adaptiveStrength(coeffs[0], u, v)
            coeffVals[bi] = coeffs[v * 8 + u]
            coeffQs[bi] = q
            coeffConf[bi] = Qim.confidence(coeffs[v * 8 + u], q)
        }

        // 用**真实长度**直接投票（跳过枚举），看能否还原
        val realBitCount = bits.size
        val soft = FloatArray(realBitCount)
        val wsum = FloatArray(realBitCount)
        for (blockIndex in 0 until total) {
            val bIdx = order[blockIndex] % realBitCount
            val c = coeffConf[blockIndex]
            val b = Qim.extract(coeffVals[blockIndex], coeffQs[blockIndex])
            soft[bIdx] += if (b == 1) c else -c
            wsum[bIdx] += c
        }
        val pb = encoded.size
        val voted = ByteArray(pb)
        for (i in 0 until pb) {
            var bb = 0
            for (j in 0 until 8) {
                val flat = i * 8 + j
                if (flat >= realBitCount) break
                bb = (bb shl 1) or (if (soft[flat] >= 0f) 1 else 0)
            }
            voted[i] = bb.toByte()
        }
        println("SIM votedBytes=${pb} encodedBytes=${encoded.size}")
        var match = 0
        for (i in 0 until pb) if (voted[i] == encoded[i]) match++
        println("SIM votedVsEncoded match=$match/$pb")
        val corrected = ReedSolomon.decode(voted)
        println("SIM decode(voted)=${WatermarkPayload.decode(corrected)}")
        println("SIM decode(encoded)=${WatermarkPayload.decode(ReedSolomon.decode(encoded))}")

        var found: String? = null
        var attempts = 0
        for (dataBytes in 1..255) {
            val bitsLen = WatermarkPayload.bitLength(dataBytes)
            val pbb = (bitsLen + 7) / 8
            val eb = ReedSolomon.encode(ByteArray(pbb))
            val bitCount = eb.size * 8
            if (bitCount > total) continue
            attempts++
            if (dataBytes in 12..16) {
                println("SIM try dataBytes=$dataBytes pbb=$pbb bitCount=$bitCount")
            }
            val o = bitSlotOrder(total)
            val s2 = FloatArray(bitCount)
            for (blockIndex in 0 until total) {
                val bIdx = o[blockIndex] % bitCount
                val c = coeffConf[blockIndex]
                val b = Qim.extract(coeffVals[blockIndex], coeffQs[blockIndex])
                s2[bIdx] += if (b == 1) c else -c
            }
            val v2 = ByteArray(eb.size)
            for (i in 0 until eb.size) {
                var bb = 0
                for (j in 0 until 8) {
                    val flat = i * 8 + j
                    if (flat >= bitCount) break
                    bb = (bb shl 1) or (if (s2[flat] >= 0f) 1 else 0)
                }
                v2[i] = bb.toByte()
            }
            val t = WatermarkPayload.decode(ReedSolomon.decode(v2))
            if (t != null) { found = t; break }
        }
        println("SIM attempts=$attempts found=$found")
        println("SIM realBitCount=$realBitCount (expected 384)")

        // ---- 额外：测 scale=1 的逐位准确率 ----
        for (scale in listOf(1f)) {
            // 只处理**一个块**，检查系数写入/读出是否一致
            val single = FloatArray(64) { (70 + (it * 13 % 90)).toFloat() }
            val coeffs0 = Dct8x8.forward(single)
            val (u, v) = midBand[0]
            val idx = v * 8 + u
            val q = adaptiveStrength(coeffs0[0], u, v) * scale

            val changed = coeffs0.copyOf()
            changed[idx] = Qim.embed(changed[idx], 1, q)
            println("SIM block coeff: orig=${coeffs0[idx]} -> embedded=${changed[idx]} (q=$q)")

            val px = Dct8x8.inverse(changed)
            val quant = FloatArray(64) { Math.round(px[it]).toFloat() }
            val reread = Dct8x8.forward(quant)
            println("SIM reread coeff=${reread[idx]}  extract=${Qim.extract(reread[idx], q)}")
            println("SIM maxPixelDelta=${px.indices.maxOf { abs(quant[it] - single[it]) }}")
        }

        assertNotNull("纯 JVM 模拟必须能提取成功（否则是算法层 bug）", found)
        assertEquals(text, found)
    }
}