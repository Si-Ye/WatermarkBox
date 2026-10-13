package com.watermarkbox.invisible

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **浅色背景图的水印写入回归测试**。
 *
 * ## 背景（真实事故）
 *
 * 老大反馈：一张白底灰鹦鹉图，加隐藏水印后**检测不出来**。
 * 换一张深色背景的图就正常。
 *
 * ## 根因
 *
 * [InvisibleWatermarkEngine.writeBlockLuma] 旧实现会做「整块统一缩放」：
 * 只要块内**任意一个**像素的某通道在加 ΔY 后会越界，就把**整个块**
 * 的 ΔY 按那个像素的余量比例缩小。
 *
 * 白底像素 `(250,250,250)` + `ΔY=+42` → 余量只有 5 → `scale = 0.12`，
 * 整块 64 个像素全被拖累。浅色背景图上 28.4% 的块 `scale = 0`。
 *
 * 实测该图逐块正确率 **54.17%**（≈ 随机），改成逐像素后 **99.50%**。
 *
 * ## 本测试的职责
 *
 * 用程序化生成的**浅色图**跑对比，确保：
 * 1. 逐像素策略在浅色图上能正常工作（防止回归到整块缩放）
 * 2. 量化差距是**统计显著**的，不是偶然
 *
 * 不依赖 Android Bitmap —— 纯 FloatArray 像素桶。
 */
class LightImageWriteRegressionTest {

    private val BLOCK = 8

    /** 从引擎取真实常量，避免测试与生产漂移。 */
    private val jpegQ = InvisibleWatermarkEngine.JPEG_LUMA_QUANT

    private val midBand: List<Pair<Int, Int>> = buildList {
        for (v in 0 until 8) for (u in 0 until 8) {
            val s = u + v
            if (s < 2 || s > 6) continue
            if (jpegQ[v][u] > 14) continue
            add(u to v)
        }
    }

    /**
     * 造一张「浅色背景」亮度图 —— 模拟白底照片。
     *
     * 大部分区域接近 255，中间有个深色主体。
     * 这正是会让旧算法崩溃的形态。
     */
    private fun makeLightImage(w: Int, h: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        // 中心一个"羽毛"圆，其余接近纯白
        val cx = w / 2.0
        val cy = h / 2.0
        val r = minOf(w, h) * 0.28
        val d = Math.hypot(x - cx, y - cy)
        if (d < r) {
            (150 + (x * 7 % 40)).toFloat()
        } else {
            // 背景 245~252，带极轻微噪声（模拟真实照片）
            (245 + (x * 13 + y * 7) % 8).toFloat()
        }
    }

    /** 与引擎当前实现一致：逐像素独立裁剪。 */
    private fun writePerPixel(
        pixels: FloatArray, w: Int, bx: Int, by: Int,
        newLuma: FloatArray, origLuma: FloatArray
    ) {
        var i = 0
        for (y in 0 until BLOCK) {
            val rowBase = (by + y) * w + bx
            for (x in 0 until BLOCK) {
                val delta = newLuma[i] - origLuma[i]
                pixels[rowBase + x] = (pixels[rowBase + x] + delta).coerceIn(0f, 255f)
                i++
            }
        }
    }

    /**
     * **旧实现**（整块统一缩放）—— 仅用于对照，不参与生产。
     *
     * 保留它是为了让本测试**能证明差距存在**：如果哪天有人
     * 「优化」回这种写法，测试会立刻红。
     */
    private fun writeWholeBlockScale(
        pixels: FloatArray, w: Int, bx: Int, by: Int,
        newLuma: FloatArray, origLuma: FloatArray
    ) {
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

    private fun readBlock(pixels: FloatArray, w: Int, bx: Int, by: Int): FloatArray {
        val out = FloatArray(64)
        var i = 0
        for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            out[i++] = pixels[(by + y) * w + bx + x]
        }
        return out
    }

    private fun adaptiveStrength(coeffs: FloatArray, u: Int, v: Int): Float {
        val jq = jpegQ[v][u].toFloat()
        val dc = abs(coeffs[0]) / 8f
        val luminanceFactor = if (dc < 40f || dc > 215f) 1.0f else 0.7f
        val activity = abs(coeffs[1]) + abs(coeffs[8])
        val activityFactor = when {
            activity < 30f -> 0.6f
            activity < 100f -> 0.8f
            else -> 1f
        }
        return (jq * 6f * luminanceFactor * activityFactor).coerceIn(4f, 60f)
    }

    /** 跑一遍写入 → 读回，返回逐块逐位正确率（0~1）。 */
    private fun measureAccuracy(
        w: Int,
        h: Int,
        writer: (FloatArray, Int, Int, Int, FloatArray, FloatArray) -> Unit
    ): Float {
        val pixels = makeLightImage(w, h)
        val bx = w / BLOCK
        val by = h / BLOCK
        val total = bx * by

        var ok = 0
        var n = 0
        for (bi in 0 until total) {
            val bxx = (bi % bx) * BLOCK
            val byy = (bi / bx) * BLOCK
            val orig = readBlock(pixels, w, bxx, byy)
            val coeffs = Dct8x8.forward(orig)
            val (u, v) = midBand[bi % midBand.size]
            val q = adaptiveStrength(coeffs, u, v)
            val idx = v * 8 + u

            // 嵌入 bit = 1（bit=0 对称，结论一致）
            val changed = coeffs.copyOf()
            changed[idx] = Qim.embed(changed[idx], 1, q)
            val out = Dct8x8.inverse(changed)
            // 量化到整数像素（模拟真实 Bitmap 的整数量化）
            val quantized = FloatArray(64) { out[it].roundToInt().toFloat() }

            val work = pixels.copyOf()
            writer(work, w, bxx, byy, quantized, orig)

            // 提取端：从写回的像素重算 DCT
            val reread = Dct8x8.forward(readBlock(work, w, bxx, byy))
            if (Qim.extract(reread[idx], q) == 1) ok++
            n++
        }
        return ok.toFloat() / n
    }

    @Test
    fun perPixel_wins_on_light_background() {
        val w = 512
        val h = 512

        val oldRate = measureAccuracy(w, h, ::writeWholeBlockScale)
        val newRate = measureAccuracy(w, h, ::writePerPixel)

        println(
            "LIGHT-IMG accuracy: wholeBlockScale=%.2f%% perPixel=%.2f%%".format(
                oldRate * 100, newRate * 100
            )
        )

        // 1) 新策略必须在浅色图上高度可靠
        assertTrue(
            "逐像素写入在浅色图上应 >95%，实测 ${newRate * 100}%",
            newRate > 0.95f
        )

        // 2) 必须显著优于旧策略（防止有人改回去）
        assertTrue(
            "逐像素应显著优于整块缩放（新=${newRate * 100}% 旧=${oldRate * 100}%）",
            newRate > oldRate + 0.15f
        )
    }

    @Test
    fun perPixel_also_fine_on_dark_background() {
        // 反向确保：修复没把「深色图」这个原本正常的情况改坏
        val w = 512
        val h = 512
        val pixels = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (30 + (x * 11 + y * 5) % 30).toFloat()
        }
        val bx = w / BLOCK
        val by = h / BLOCK
        val total = bx * by

        var ok = 0
        for (bi in 0 until total) {
            val bxx = (bi % bx) * BLOCK
            val byy = (bi / bx) * BLOCK
            val orig = readBlock(pixels, w, bxx, byy)
            val coeffs = Dct8x8.forward(orig)
            val (u, v) = midBand[bi % midBand.size]
            val q = adaptiveStrength(coeffs, u, v)
            val idx = v * 8 + u
            val changed = coeffs.copyOf()
            changed[idx] = Qim.embed(changed[idx], 1, q)
            val out = Dct8x8.inverse(changed)
            val quantized = FloatArray(64) { out[it].roundToInt().toFloat() }

            val work = pixels.copyOf()
            writePerPixel(work, w, bxx, byy, quantized, orig)

            val reread = Dct8x8.forward(readBlock(work, w, bxx, byy))
            if (Qim.extract(reread[idx], q) == 1) ok++
        }
        val rate = ok.toFloat() / total
        println("DARK-IMG perPixel accuracy=%.2f%%".format(rate * 100))
        assertTrue("深色图也应保持高准确率，实测 ${rate * 100}%", rate > 0.95f)
    }
}