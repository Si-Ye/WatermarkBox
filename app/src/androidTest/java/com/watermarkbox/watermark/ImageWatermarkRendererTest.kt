package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图片水印渲染的仪器化测试（阶段二）。
 */
@RunWith(AndroidJUnit4::class)
class ImageWatermarkRendererTest {

    private val renderer = WatermarkRenderer()

    private fun whiteCanvas(): Bitmap =
        Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    /** 生成一个纯红色 logo，便于像素识别。 */
    private fun redLogo(): Bitmap =
        Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }

    private fun countRedPixels(bmp: Bitmap): Int {
        var c = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) > 200 && Color.green(p) < 100 && Color.blue(p) < 100) c++
            }
        }
        return c
    }

    @Test
    fun single_image_watermark_is_drawn() {
        val src = whiteCanvas()
        val result = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(layoutMode = LayoutMode.SINGLE, widthPercent = 0.3f)
        )
        assertTrue("应绘制出红色 logo 像素", countRedPixels(result) > 0)
    }

    @Test
    fun tiled_image_watermark_draws_more_than_single() {
        val src = whiteCanvas()
        val single = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(layoutMode = LayoutMode.SINGLE, widthPercent = 0.15f)
        )
        val tiled = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(layoutMode = LayoutMode.TILED, widthPercent = 0.15f, tileCount = 5)
        )
        assertTrue(
            "平铺应比单个覆盖更多像素: single=${countRedPixels(single)}, tiled=${countRedPixels(tiled)}",
            countRedPixels(tiled) > countRedPixels(single)
        )
    }

    @Test
    fun diagonal_image_watermark_produces_pixels() {
        val src = whiteCanvas()
        val result = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(layoutMode = LayoutMode.DIAGONAL, widthPercent = 0.15f, tileCount = 4)
        )
        assertTrue(countRedPixels(result) > 0)
    }

    @Test
    fun alpha_affects_image_watermark() {
        val src = whiteCanvas()
        val full = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(alpha = 1.0f, widthPercent = 0.3f)
        )
        val faint = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(alpha = 0.15f, widthPercent = 0.3f)
        )

        // alpha=1 时红色纯净；alpha=0.15 时红色会与白底混合变浅
        fun redScore(b: Bitmap): Int {
            var best = 0
            for (y in 0 until b.height step 3) {
                for (x in 0 until b.width step 3) {
                    val p = b.getPixel(x, y)
                    val score = Color.red(p) - (Color.green(p) + Color.blue(p)) / 2
                    if (score > best) best = score
                }
            }
            return best
        }

        assertTrue(
            "低透明度 logo 应比高透明度浅: full=${redScore(full)}, faint=${redScore(faint)}",
            redScore(faint) < redScore(full)
        )
    }

    @Test
    fun width_percent_affects_watermark_size() {
        val src = whiteCanvas()
        val small = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(widthPercent = 0.1f)
        )
        val big = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(widthPercent = 0.6f)
        )
        assertTrue(
            "更大宽度占比应覆盖更多像素: small=${countRedPixels(small)}, big=${countRedPixels(big)}",
            countRedPixels(big) > countRedPixels(small)
        )
    }

    @Test
    fun gravity_moves_watermark_position() {
        val src = whiteCanvas()
        val topLeft = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(gravity = GravityPosition.TOP_LEFT, widthPercent = 0.25f)
        )
        val bottomRight = renderer.renderImage(
            src, redLogo(),
            ImageWatermarkStyle(gravity = GravityPosition.BOTTOM_RIGHT, widthPercent = 0.25f)
        )
        // 两者像素分布应不同（位置不同）
        assertTrue(
            "不同九宫格位置应产生不同图像",
            !topLeft.sameAs(bottomRight)
        )
    }

    @Test
    fun render_image_does_not_modify_source() {
        val src = whiteCanvas()
        val before = src.getPixel(5, 5)
        renderer.renderImage(src, redLogo(), ImageWatermarkStyle())
        assertTrue("源 Bitmap 必须保持不变", before == src.getPixel(5, 5))
    }
}