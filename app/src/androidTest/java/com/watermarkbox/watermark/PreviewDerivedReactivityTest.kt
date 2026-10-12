package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkColor
import com.watermarkbox.data.model.WatermarkType
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 预览「派生结果」响应式回归测试。
 *
 * ## 被测的真实 Bug（用户反馈："修改完之后预览完全失效"）
 *
 * 错误写法：
 * ```kotlin
 * val rendered by remember(bitmap) {
 *     derivedStateOf { renderer.render(bitmap, textConfig) }  // 闭包捕获普通参数
 * }
 * ```
 * `derivedStateOf` **只追踪 State 读取**。`textConfig` 是普通参数，
 * 被闭包捕获后永久停留在首次值；`remember(bitmap)` 又阻止派生状态重建
 * → 拖动任何滑块，预览画面纹丝不动。
 *
 * 正确写法（生产代码 rememberRenderedPreview 采用）：
 * 先用 `rememberUpdatedState` 把参数包成 State，再在 derivedStateOf 内读 `.value`。
 *
 * ## 本测试的验证方式
 *
 * 复刻生产函数的「状态追踪结构」，并用 Snapshot 提交修改（等价于一次重组），
 * 断言派生输出确实变化。同时包含一个「反向对照」测试，
 * 证明闭包捕获写法不会更新 —— 从而锁定 Bug 根因。
 *
 * 注：不使用 Compose 的 Espresso UI 测试框架，因为本 ROM 的
 * `InputManager.getInstance` 反射调用不兼容，会导致框架自身崩溃。
 */
@RunWith(AndroidJUnit4::class)
class PreviewDerivedReactivityTest {

    private fun whiteBitmap(): Bitmap =
        Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    private fun markedPixels(bmp: Bitmap): Int {
        var c = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                if (bmp.getPixel(x, y) != Color.WHITE) c++
            }
        }
        return c
    }

    private fun darkest(bmp: Bitmap): Int {
        var d = 255
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (lum < d) d = lum
            }
        }
        return d
    }

    private fun hasRed(bmp: Bitmap): Boolean {
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) > 180 && Color.green(p) < 100) return true
            }
        }
        return false
    }

    private fun baseText(text: String = "A", size: Float = 60f, alpha: Float = 1f) =
        TextWatermarkStyle(
            text = text,
            fontSize = size,
            color = WatermarkColor(androidx.compose.ui.graphics.Color.Black),
            alpha = alpha,
            layoutMode = LayoutMode.SINGLE,
            gravity = GravityPosition.CENTER
        )

    /** 提交快照 —— 等价于 Compose 触发一次重组。 */
    private fun recompose(block: () -> Unit) {
        Snapshot.withMutableSnapshot { block() }
    }

    // =================================================================
    // 1. 核心回归：文字变化 → 派生结果必须更新
    // =================================================================

    @Test
    fun derived_result_updates_when_text_changes() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        var config by androidx.compose.runtime.mutableStateOf(baseText("A"))
        val typeState = mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle.Default)
        val logoState = mutableStateOf<Bitmap?>(null)

        // 复刻生产代码 rememberRenderedPreview 的状态追踪结构
        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer = renderer,
                bitmap = source,
                watermarkType = typeState.value,
                textConfig = config,
                imageConfig = imageState.value,
                logoBitmap = logoState.value,
                sourceFileName = null
            )
        }

        val short = markedPixels(rendered!!)

        recompose { config = baseText("AAAAAAAAAAAAAAAA") }
        val long = markedPixels(rendered!!)

        assertNotEquals(
            "文字变化后派生结果必须更新（原 Bug 下两者相同）",
            short, long
        )
        assertTrue("更长文字覆盖更多像素: short=$short, long=$long", long > short)
    }

    // =================================================================
    // 2. 字号变化
    // =================================================================

    @Test
    fun derived_result_updates_when_font_size_changes() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        var config by androidx.compose.runtime.mutableStateOf(baseText(size = 20f))
        val typeState = mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle.Default)
        val logoState = mutableStateOf<Bitmap?>(null)

        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, typeState.value, config,
                imageState.value, logoState.value, null
            )
        }

        val small = markedPixels(rendered!!)
        recompose { config = baseText(size = 90f) }
        val large = markedPixels(rendered!!)

        assertTrue("字号变大覆盖更多像素: small=$small, large=$large", large > small)
    }

    // =================================================================
    // 3. 透明度变化
    // =================================================================

    @Test
    fun derived_result_updates_when_alpha_changes() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        var config by androidx.compose.runtime.mutableStateOf(baseText(alpha = 1.0f))
        val typeState = mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle.Default)
        val logoState = mutableStateOf<Bitmap?>(null)

        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, typeState.value, config,
                imageState.value, logoState.value, null
            )
        }

        val darkFull = darkest(rendered!!)
        recompose { config = baseText(alpha = 0.2f) }
        val lightLow = darkest(rendered!!)

        assertTrue(
            "降低透明度后应更浅: full=$darkFull, low=$lightLow",
            lightLow > darkFull
        )
    }

    // =================================================================
    // 4. 布局模式变化
    // =================================================================

    @Test
    fun derived_result_updates_when_layout_changes() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        var config by androidx.compose.runtime.mutableStateOf(baseText(size = 24f))
        val typeState = mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle.Default)
        val logoState = mutableStateOf<Bitmap?>(null)

        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, typeState.value, config,
                imageState.value, logoState.value, null
            )
        }

        val single = markedPixels(rendered!!)
        recompose {
            config = baseText(size = 24f).copy(layoutMode = LayoutMode.TILED, tileCount = 4)
        }
        val tiled = markedPixels(rendered!!)

        assertTrue("平铺覆盖更多像素: single=$single, tiled=$tiled", tiled > single)
    }

    // =================================================================
    // 5. 水印类型切换
    // =================================================================

    @Test
    fun derived_result_updates_when_type_switches() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        val logo = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val textState = mutableStateOf(
            TextWatermarkStyle(
                text = "X",
                fontSize = 60f,
                color = WatermarkColor(androidx.compose.ui.graphics.Color.Blue),
                alpha = 1f
            )
        )
        var typeState by mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle(widthPercent = 0.4f))
        val logoState = mutableStateOf<Bitmap?>(logo)

        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, typeState,
                textState.value, imageState.value, logoState.value, null
            )
        }

        assertTrue("TEXT 模式不应出现 logo 红色", !hasRed(rendered!!))
        recompose { typeState = WatermarkType.IMAGE }
        assertTrue("IMAGE 模式应出现 logo 红色", hasRed(rendered!!))
    }

    // =================================================================
    // 6. 连续滑块拖动（最终值必须生效）
    // =================================================================

    @Test
    fun derived_result_tracks_rapid_slider_changes() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()
        var config by androidx.compose.runtime.mutableStateOf(baseText(size = 20f))
        val typeState = mutableStateOf(WatermarkType.TEXT)
        val imageState = mutableStateOf(ImageWatermarkStyle.Default)
        val logoState = mutableStateOf<Bitmap?>(null)

        val rendered by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, typeState.value, config,
                imageState.value, logoState.value, null
            )
        }

        val at20 = markedPixels(rendered!!)
        listOf(40f, 60f, 80f, 100f).forEach { size ->
            recompose { config = baseText(size = size) }
        }
        val at100 = markedPixels(rendered!!)

        assertTrue(
            "滑块连续变化后最终值应生效: at20=$at20, at100=$at100",
            at100 > at20
        )
    }

    // =================================================================
    // 7. 反向对照：证明「闭包捕获普通变量」正是 Bug 根因
    // =================================================================

    @Test
    fun closure_capture_root_cause_is_reproduced() {
        val source = whiteBitmap()
        val renderer = WatermarkRenderer()

        // 普通变量（非 State）——复现错误写法
        var config = baseText("A")

        val broken by derivedStateOf {
            PreviewRenderer.render(
                renderer, source, WatermarkType.TEXT,
                config, ImageWatermarkStyle.Default, null, null
            )
        }

        val first = markedPixels(broken!!)
        // 即使改变 config，derivedStateOf 也不会失效（因为它没有 State 依赖）
        config = baseText("AAAAAAAAAAAAAAAA")
        val second = markedPixels(broken!!)

        assertTrue(
            "闭包捕获普通变量时派生结果不更新（复现原 Bug 根因）",
            first == second
        )
    }
}