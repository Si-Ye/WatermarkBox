package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 水印渲染引擎的仪器化测试（在真实 Android Canvas 上运行）。
 *
 * 关键验证：透明度确实影响最终像素值。
 */
@RunWith(AndroidJUnit4::class)
class WatermarkRendererInstrumentedTest {

    private val renderer = WatermarkRenderer()

    private fun makeSource(): Bitmap {
        // 纯白底 512x512，便于检测黑色水印的像素
        return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    /** 统计非纯白像素数量（即水印覆盖到的像素）。 */
    private fun countMarkedPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                if (bitmap.getPixel(x, y) != Color.WHITE) count++
            }
        }
        return count
    }

    /** 统计最深的像素（水印中心处的实际颜色）。 */
    private fun darkestLuminance(bitmap: Bitmap): Int {
        var darkest = 255
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val p = bitmap.getPixel(x, y)
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (lum < darkest) darkest = lum
            }
        }
        return darkest
    }

    private fun baseConfig() = TextWatermarkStyle(
        text = "TEST",
        fontSize = 80f,
        color = WatermarkColor(
            androidx.compose.ui.graphics.Color.Black
        ),
        layoutMode = LayoutMode.SINGLE,
        gravity = GravityPosition.CENTER,
        margin = 0f,
        shadowEnabled = false,
        strokeEnabled = false
    )

    @Test
    fun watermark_is_actually_drawn() {
        val source = makeSource()
        val result = renderer.render(source, baseConfig())
        assertTrue(
            "应有水印像素被绘制",
            countMarkedPixels(result) > 0
        )
    }

    @Test
    fun alpha_one_produces_darkest_watermark() {
        val source = makeSource()
        val config = baseConfig().copy(alpha = 1.0f)
        val result = renderer.render(source, config)
        // alpha=1.0 的黑色文字，最深像素应接近纯黑
        assertTrue(
            "alpha=1.0 时应有接近纯黑的像素，实际=${darkestLuminance(result)}",
            darkestLuminance(result) < 40
        )
    }

    @Test
    fun lower_alpha_makes_watermark_lighter_core_fix() {
        val source = makeSource()
        val full = renderer.render(source, baseConfig().copy(alpha = 1.0f))
        val faint = renderer.render(source, baseConfig().copy(alpha = 0.2f))

        val fullDark = darkestLuminance(full)
        val faintDark = darkestLuminance(faint)

        // alpha=0.2 的黑色文字在白色背景上应明显比 alpha=1.0 浅
        assertTrue(
            "低透明度必须比高透明度浅：alpha=1.0 -> $fullDark, alpha=0.2 -> $faintDark",
            faintDark > fullDark + 50
        )
    }

    @Test
    fun different_alpha_produces_distinguishable_results() {
        val source = makeSource()
        val a = renderer.render(source, baseConfig().copy(alpha = 0.3f))
        val b = renderer.render(source, baseConfig().copy(alpha = 0.9f))
        assertTrue(
            "0.3 与 0.9 的渲染结果必须不同（原 Bug 下会相同）",
            darkestLuminance(a) != darkestLuminance(b)
        )
    }

    @Test
    fun tiled_mode_also_applies_alpha() {
        val source = makeSource()
        val tiled = baseConfig().copy(
            layoutMode = LayoutMode.TILED,
            tileCount = 3,
            alpha = 0.25f
        )
        val result = renderer.render(source, tiled)
        assertTrue(
            "平铺水印应比纯黑浅，实际=${darkestLuminance(result)}",
            darkestLuminance(result) > 100
        )
    }

    @Test
    fun diagonal_mode_produces_watermark_pixels() {
        val source = makeSource()
        val diagonal = baseConfig().copy(layoutMode = LayoutMode.DIAGONAL, tileCount = 3)
        val result = renderer.render(source, diagonal)
        assertTrue(countMarkedPixels(result) > 0)
    }

    @Test
    fun stroke_and_fill_use_own_colors_with_alpha() {
        val source = makeSource()
        val config = baseConfig().copy(
            strokeEnabled = true,
            strokeWidth = 4f,
            strokeColor = WatermarkColor(androidx.compose.ui.graphics.Color.Red),
            color = WatermarkColor(androidx.compose.ui.graphics.Color.Blue),
            alpha = 1.0f
        )
        val result = renderer.render(source, config)

        var hasReddish = false
        var hasBluish = false
        for (y in 0 until result.height step 2) {
            for (x in 0 until result.width step 2) {
                val p = result.getPixel(x, y)
                if (Color.red(p) > Color.blue(p) + 40) hasReddish = true
                if (Color.blue(p) > Color.red(p) + 40) hasBluish = true
            }
        }
        assertTrue("应存在描边红色像素", hasReddish)
        assertTrue("应存在填充蓝色像素", hasBluish)
    }

    @Test
    fun render_does_not_modify_source() {
        val source = makeSource()
        val before = source.getPixel(10, 10)
        renderer.render(source, baseConfig())
        assertEquals("源 Bitmap 必须保持不变", before, source.getPixel(10, 10))
    }
}