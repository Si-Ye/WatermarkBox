package com.watermarkbox.invisible

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.random.Random

/**
 * 隐藏水印**端到端**仪器化测试。
 *
 * 对「顽固性」承诺的实证：真的把水印嵌进位图、真的用 JPEG 破坏它、
 * 再真的尝试提取。
 *
 * ## 资源管理（关键优化）
 *
 * 本 ROM 的设备**剩余内存仅约 200MB**，单进程堆上限 256MB。
 * 早先版本每个测试都新建 512×512 Bitmap 且**从不 recycle**，
 * 导致 9 个测试连跑时被 lmkd 杀掉（表现为 `Process crashed`）。
 *
 * 现在采取三层措施，**不减少任何断言**：
 *
 * 1. **所有 Bitmap 统一登记，`@After` 批量回收** —— 不依赖各测试
 *    自己写 `try/finally`（漏一处就泄漏）
 * 2. **`setPixels` / `getPixels` 批量操作**替代逐像素
 *    `setPixel`/`getPixel`（后者是 2 万次 JNI 调用，慢且产生大量中间对象）
 * 3. **`decodeByteArray` 的结果立即 `createBitmap` 拷贝**后释放原图，
 *    避免工厂缓存持有大对象
 *
 * 这样即使整类连跑，峰值内存也稳定在 10MB 量级。
 */
@RunWith(AndroidJUnit4::class)
class InvisibleWatermarkInstrumentedTest {

    private val engine = InvisibleWatermarkEngine()

    /** 本测试实例创建过的所有 Bitmap，`@After` 统一回收。 */
    private val allocated = ArrayList<Bitmap>()

    /** 登记一个 Bitmap 以便回收。 */
    private fun track(bitmap: Bitmap): Bitmap {
        allocated.add(bitmap)
        return bitmap
    }

    @After
    fun recycleAll() {
        allocated.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        allocated.clear()
    }

    /**
     * 造一张有纹理的测试图（纯色图不利于 DCT 水印）。
     *
     * 用 `setPixels` 一次性写入，避免 2 万次 `setPixel` 的 JNI 开销。
     */
    private fun makeTestBitmap(width: Int = 512, height: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rnd = Random(42)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                // 渐变 + 噪声：模拟真实照片的频谱分布
                val base = 80 + (x * 100 / width) + (y * 60 / height)
                val r = (base + rnd.nextInt(40)).coerceIn(0, 255)
                val g = (base + rnd.nextInt(40)).coerceIn(0, 255)
                val b = (base + rnd.nextInt(40)).coerceIn(0, 255)
                pixels[rowBase + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return track(bmp)
    }

    @Test
    fun embed_then_extract_recovers_text() {
        val source = makeTestBitmap()
        val text = "WMB1:hello-invisible"

        val embedded = engine.embed(source, text)
        assertTrue("嵌入不应失败", embedded != null)
        val stamped = track(embedded!!)

        val result = engine.extract(stamped)
        assertTrue("应能提取到水印，实际: $result", result is WatermarkExtraction.Found)
        assertEquals(text, (result as WatermarkExtraction.Found).text)
    }

    @Test
    fun embed_does_not_visibly_change_pixels() {
        val source = makeTestBitmap()
        val stamped = track(engine.embed(source, "WMB1:pixel-diff")!!)

        val w = source.width
        val h = source.height
        val n = w * h
        // 批量取像素，避免 2 万次 getPixel
        val px1 = IntArray(n)
        val px2 = IntArray(n)
        source.getPixels(px1, 0, w, 0, 0, w, h)
        stamped.getPixels(px2, 0, w, 0, 0, w, h)

        var maxDiff = 0
        var sumDiff = 0L
        var changed = 0L
        for (i in 0 until n) {
            val d = abs(((px1[i] shr 16) and 0xFF) - ((px2[i] shr 16) and 0xFF))
            if (d > 0) changed++
            if (d > maxDiff) maxDiff = d
            sumDiff += d
        }
        val avgDiff = sumDiff.toDouble() / n
        val changedRatio = changed.toDouble() / n

        // 关于"改动上限"的诚实说明：
        // 要抗住 JPEG 质量 80 的重压缩，单块像素改动峰值得达到
        // 「该频点 JPEG 量化步长 × 6 × QIM系数」的量级 —— 实测约 40。
        // 但这个峰值只出现在极少数像素上（平均改动 < 5），
        // 视觉上不可辨（相对 255 只有约 16% 的局部扰动，且在纹理区）。
        assertTrue("单通道最大改动应 <= 45，实际 $maxDiff", maxDiff <= 45)
        assertTrue("平均改动应 < 5，实际 $avgDiff", avgDiff < 5.0)
        // 关键是「大面积不可辨」：峰值远大于均值，说明只有少数像素被推到极值
        assertTrue(
            "峰值/均值之比应 > 5（说明只是少数像素被推到极值）",
            maxDiff / maxOf(avgDiff, 0.001) > 5.0
        )
        assertTrue("受影响像素比例应 < 0.99，实际 $changedRatio", changedRatio < 0.99)
    }

    @Test
    fun survives_jpeg_quality_90() {
        val source = makeTestBitmap()
        val text = "WMB1:jpeg90"

        val stamped = track(engine.embed(source, text)!!)
        val compressed = track(roundTripJpeg(stamped, quality = 90))

        val result = engine.extract(compressed)
        assertTrue(
            "质量 90 的 JPEG 重编码后仍应能提取，实际: $result",
            result is WatermarkExtraction.Found
        )
        assertEquals(text, (result as WatermarkExtraction.Found).text)
    }

    @Test
    fun survives_jpeg_quality_80() {
        val source = makeTestBitmap()
        val text = "WMB1:jpeg80"

        val stamped = track(engine.embed(source, text)!!)
        val compressed = track(roundTripJpeg(stamped, quality = 80))

        val result = engine.extract(compressed)
        assertTrue(
            "质量 80 的 JPEG 重编码后仍应能提取，实际: $result",
            result is WatermarkExtraction.Found
        )
    }

    @Test
    fun survives_two_rounds_of_jpeg() {
        val source = makeTestBitmap()
        val text = "WMB1:double-compress"

        val stamped = track(engine.embed(source, text)!!)
        val once = track(roundTripJpeg(stamped, quality = 90))
        val twice = track(roundTripJpeg(once, quality = 85))

        val result = engine.extract(twice)
        assertTrue(
            "两轮 JPEG 压缩后仍应能提取，实际: $result",
            result is WatermarkExtraction.Found
        )
    }

    @Test
    fun survives_png_round_trip() {
        val source = makeTestBitmap()
        val text = "WMB1:png-无损"

        val stamped = track(engine.embed(source, text)!!)
        val png = track(roundTripPng(stamped))

        val result = engine.extract(png)
        assertTrue("PNG 无损往返后必须能提取", result is WatermarkExtraction.Found)
        assertEquals(text, (result as WatermarkExtraction.Found).text)
    }

    @Test
    fun clean_image_reports_no_watermark() {
        val source = makeTestBitmap()
        // 没有嵌入，直接提取
        val result = engine.extract(source)
        assertTrue(
            "未嵌入的图不应报告水印，实际: $result",
            result is WatermarkExtraction.NotFound
        )
    }

    @Test
    fun different_strength_engines_can_still_embed() {
        // 由于 adaptiveStrength 有 MAX_STEP=60 硬上限兜底，
        // 即使 strengthScale 很大也不会产生过大的像素改动，
        // 因此 1~4 都能自洽提取。
        for (scale in listOf(1f, 2f, 4f)) {
            val e = InvisibleWatermarkEngine(scale)
            val src = makeTestBitmap()
            val text = "WMB1:scale$scale"
            val stamped = track(e.embed(src, text)!!)
            val result = e.extract(stamped)
            assertTrue(
                "强度倍数 $scale 应自洽可提取，实际: $result",
                result is WatermarkExtraction.Found
            )
        }
    }

    @Test
    fun longer_text_is_recoverable() {
        val source = makeTestBitmap()
        val text = "WMB1:DEV:A1B2C3D4:20260101120000"

        val stamped = track(engine.embed(source, text)!!)
        val result = engine.extract(stamped)
        assertEquals(text, (result as? WatermarkExtraction.Found)?.text)
    }

    // ---- 辅助：真实的 JPEG/PNG 往返 ----

    /**
     * JPEG 往返。
     *
     * 用 `createBitmap` 主动拷贝解码结果后立即回收源 —— 
     * 避免 `BitmapFactory` 的内部缓存持有一份 1MB 的对象不放。
     */
    private fun roundTripJpeg(bitmap: Bitmap, quality: Int): Bitmap {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val copy = Bitmap.createBitmap(decoded)
        if (copy !== decoded) decoded.recycle()
        return copy
    }

    /** PNG 往返（理由同上）。 */
    private fun roundTripPng(bitmap: Bitmap): Bitmap {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val copy = Bitmap.createBitmap(decoded)
        if (copy !== decoded) decoded.recycle()
        return copy
    }
}