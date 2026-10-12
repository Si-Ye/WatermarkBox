package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkColor
import com.watermarkbox.ui.component.PanelDragBehavior
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 渲染性能与缓存正确性回归测试（流畅度）。
 *
 * 拖滑块时预览每帧都要重渲染。若每帧新建 Paint / 查找 Typeface，
 * 会产生大量临时对象并触发 GC，表现为卡顿。
 *
 * 阈值刻意设得宽松（只抓数量级回退，不做精确基准），
 * 避免不同机器性能差异导致误报。
 */
@RunWith(AndroidJUnit4::class)
class RenderPerformanceTest {

    private val renderer = WatermarkRenderer()

    private fun bmp(w: Int = 512, h: Int = 512) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun cfg(size: Float) = TextWatermarkStyle(
        text = "水印盒 WatermarkBox 测试",
        fontSize = size,
        color = WatermarkColor(androidx.compose.ui.graphics.Color.Black),
        alpha = 0.7f,
        layoutMode = LayoutMode.SINGLE,
        gravity = GravityPosition.CENTER
    )

    private fun marked(b: Bitmap): Int {
        var c = 0
        for (y in 0 until b.height step 3) {
            for (x in 0 until b.width step 3) {
                if (b.getPixel(x, y) != Color.WHITE) c++
            }
        }
        return c
    }

    private fun dark(b: Bitmap): Int {
        var c = 0
        for (y in 0 until b.height step 3) {
            for (x in 0 until b.width step 3) {
                val p = b.getPixel(x, y)
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 128) c++
            }
        }
        return c
    }

    /** 模拟拖字号滑块：连续 60 帧。 */
    @Test
    fun sixty_frame_font_size_drag_is_fast() {
        val src = bmp()
        repeat(3) { renderer.render(src, cfg(30f + it)) }   // 预热缓存

        val t0 = System.nanoTime()
        repeat(60) { i -> renderer.render(src, cfg(30f + (i % 40))) }
        val perFrame = (System.nanoTime() - t0) / 1e6 / 60

        assertTrue(
            "512px 预览每帧应远低于 16ms，实际 %.2fms".format(perFrame),
            perFrame < 16.0
        )
    }

    /** 反复切换粗体/斜体 —— 验证 Typeface 缓存有效。 */
    @Test
    fun typeface_switching_is_cheap() {
        val src = bmp()
        val list = listOf(
            cfg(40f).copy(bold = true),
            cfg(40f).copy(italic = true),
            cfg(40f).copy(bold = true, italic = true),
            cfg(40f)
        )
        list.forEach { renderer.render(src, it) }

        val t0 = System.nanoTime()
        repeat(120) { i -> renderer.render(src, list[i % list.size]) }
        val perCall = (System.nanoTime() - t0) / 1e6 / 120

        assertTrue(
            "切换字形应因缓存而廉价，实际 %.2fms/次".format(perCall),
            perCall < 16.0
        )
    }

    /** 平铺模式（元素最多）也要撑得住。 */
    @Test
    fun tiled_mode_stays_responsive() {
        val src = bmp()
        val tiled = cfg(28f).copy(layoutMode = LayoutMode.TILED, tileCount = 8)
        repeat(3) { renderer.render(src, tiled) }

        val t0 = System.nanoTime()
        repeat(30) { renderer.render(src, tiled) }
        val perFrame = (System.nanoTime() - t0) / 1e6 / 30

        assertTrue(
            "平铺 8 个水印每帧应 < 24ms，实际 %.2fms".format(perFrame),
            perFrame < 24.0
        )
    }

    /** 拖动面板时预览尺寸持续变化 —— 该路径也要快。 */
    @Test
    fun panel_drag_renders_stay_responsive() {
        val beh = PanelDragBehavior()
        val frames = 40
        val span = beh.shrinkEndFraction - beh.minFraction
        val fractions = (0 until frames).map { beh.minFraction + span * it / frames }

        repeat(3) { renderer.render(bmp(), cfg(36f)) }

        val t0 = System.nanoTime()
        fractions.forEach { f ->
            val h = (512 * beh.previewHeightFraction(f)).toInt().coerceAtLeast(16)
            renderer.render(bmp(512, h), cfg(36f))
        }
        val perFrame = (System.nanoTime() - t0) / 1e6 / frames

        assertTrue(
            "拖动面板每帧应 < 16ms，实际 %.2fms".format(perFrame),
            perFrame < 16.0
        )
    }

    /**
     * 缓存不能串味：不同颜色必须产生不同输出。
     * 若缓存 key 设计错误，本测试立刻失败。
     */
    @Test
    fun cache_does_not_leak_between_configs() {
        val src = bmp()
        val white = renderer.render(
            src, cfg(80f).copy(
                color = WatermarkColor(androidx.compose.ui.graphics.Color.White)
            )
        )
        val black = renderer.render(
            src, cfg(80f).copy(
                color = WatermarkColor(androidx.compose.ui.graphics.Color.Black)
            )
        )
        assertTrue("黑字应留下暗像素", dark(black) > 0)
        assertTrue("白字不应产生更多暗像素", dark(white) < dark(black))
    }

    /** 缓存预热后，字号变化仍必须生效。 */
    @Test
    fun font_size_still_applies_after_warmup() {
        val src = bmp()
        val small = marked(renderer.render(src, cfg(16f)))
        val large = marked(renderer.render(src, cfg(120f)))
        assertTrue(
            "大字号覆盖像素应远多于小字号：$small -> $large",
            large > small * 3
        )
    }
}