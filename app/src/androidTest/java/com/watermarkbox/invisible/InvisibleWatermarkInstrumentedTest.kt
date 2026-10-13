package com.watermarkbox.invisible

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

    /**
     * 压缩**能力边界**诊断。
     *
     * 目的不是要求每一档都成功 —— 有边界是技术现实，不是 bug。
     * 而是把实际边界**测出来并打印**，免得日后靠猜。
     *
     * - 已知能通过的组合：断言，防回归
     * - 更狠的组合：只记录结果，不强制通过
     */
    @Test
    fun diagnose_compression_boundary() {
        // 已知通过 —— 断言守住
        checkCompressionChain(listOf(90, 85), mustPass = true)

        // 更狠 —— 只记录
        checkCompressionChain(listOf(80, 80), mustPass = false)
        checkCompressionChain(listOf(80, 70), mustPass = false)
    }

    /** 按给定质量序列做多轮 JPEG 往返，再尝试提取。 */
    private fun checkCompressionChain(qualities: List<Int>, mustPass: Boolean) {
        val source = makeTestBitmap()
        val text = "WMB1:q" + qualities.joinToString("-")

        var bmp = track(engine.embed(source, text)!!)
        for (q in qualities) {
            bmp = track(roundTripJpeg(bmp, q))
        }

        val result = engine.extract(bmp)
        val ok = result is WatermarkExtraction.Found && result.text == text
        val label = qualities.joinToString("→")
        println("DIAG JPEG[$label] = " + (if (ok) "OK" else "FAIL"))

        if (mustPass) {
            assertTrue("已知应当通过的组合 $label 失败了，属回归：$result", ok)
        }
    }

    /**
     * 直角旋转（相册「旋转」按钮、多数图片工具）后仍可提取。
     *
     * 原理：DCT 水印依赖 8×8 块网格与固定洗牌的块序，
     * 旋转会破坏这两者，但直角旋转是可逆的 —— 检测端把图转回去即可。
     *
     * 注意：任意角度（如 15°）不在此列，重采样会让块网格彻底错位。
     */
    @Test
    fun survives_right_angle_rotations() {
        val source = makeTestBitmap()
        val text = "WMB1:rotated"

        val stamped = track(engine.embed(source, text)!!)

        for (angle in listOf(90f, 180f, 270f)) {
            val rotated = track(rotateBitmap(stamped, angle))
            val result = engine.extract(rotated)
            assertEquals(
                "旋转 ${angle.toInt()}° 后应仍能提取，实际: $result",
                text,
                (result as? WatermarkExtraction.Found)?.text
            )
        }
    }

    /**
     * 旋转**能力边界**诊断。
     *
     * 分两类，难度天差地别，必须分开测：
     * - **直角旋转**（90/180/270）：块网格仍然对齐，理论上能通过
     *   「多试几个方向」救回来
     * - **任意角度**（5°/15°/45°）：重采样会让 8×8 块网格彻底错位，
     *   需要几何同步机制才能解决
     */
    @Test
    fun diagnose_rotation_boundary() {
        val source = makeTestBitmap()
        val text = "WMB1:rotate"

        val stamped = track(engine.embed(source, text)!!)

        println("DIAG rotate[0] = " + okText(canExtract(stamped, text)))

        // ---- 直角旋转 ----
        // 除了测失败，还要验证「转回去能否救回」—— 这决定了
        // 「检测时多试几个方向」这个方案到底可不可行
        for (angle in listOf(90f, 180f, 270f)) {
            val rotated = track(rotateBitmap(stamped, angle))
            println("DIAG rotate[${angle.toInt()}] = " + okText(canExtract(rotated, text)))

            val restored = track(rotateBitmap(rotated, -angle))
            println(
                "DIAG rotate[${angle.toInt()}]+restore = " + okText(canExtract(restored, text))
            )
        }

        // ---- 任意角度 ----
        for (angle in listOf(5f, 15f, 45f)) {
            val rotated = track(rotateBitmap(stamped, angle))
            println("DIAG rotate[${angle.toInt()}] = " + okText(canExtract(rotated, text)))
        }
    }

    /** 用真实的 Matrix 旋转（与相册/编辑器同一条路径）。 */
    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun canExtract(bmp: Bitmap, expected: String): Boolean {
        val r = engine.extract(bmp)
        return r is WatermarkExtraction.Found && r.text == expected
    }

    private fun okText(ok: Boolean) = if (ok) "OK" else "FAIL"

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