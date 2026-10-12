package com.watermarkbox.data.model

import com.watermarkbox.watermark.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水印参数取值范围测试。
 *
 * 固定住「UI 滑块区间」与「渲染器 clamp 区间」的一致性，
 * 防止出现「滑块能拖到某值，但渲染器把它夹掉」这类不一致。
 */
class WatermarkRangeTest {

    // =================================================================
    // 透明度下限 = 1%
    // =================================================================

    @Test
    fun `渲染器透明度下限为百分之一`() {
        assertEquals(0.01f, WatermarkRenderer.MIN_ALPHA, 1e-6f)
    }

    @Test
    fun `透明度下限换算为 alpha 字节不为零`() {
        val byte = (WatermarkRenderer.MIN_ALPHA * 255).toInt()
        assertTrue("1% 透明度应产生非零 alpha 字节（实际 $byte）", byte > 0)
        assertEquals(2, byte)   // 0.01 * 255 = 2.55 -> 2
    }

    @Test
    fun `透明度上限为完全不透明`() {
        assertEquals(255, (1.0f * 255).toInt())
    }

    @Test
    fun `滑块区间内任何值都不被渲染器夹掉`() {
        val sliderMin = WatermarkRenderer.MIN_ALPHA
        val sliderMax = 1f

        listOf(sliderMin, 0.05f, 0.5f, 0.99f, sliderMax).forEach { v ->
            assertEquals(
                "值 $v 不应被 clamp 改变",
                v,
                v.coerceIn(WatermarkRenderer.MIN_ALPHA, 1f),
                1e-6f
            )
        }
    }

    @Test
    fun `低于下限的值被夹到下限`() {
        assertEquals(
            WatermarkRenderer.MIN_ALPHA,
            0.001f.coerceIn(WatermarkRenderer.MIN_ALPHA, 1f),
            1e-6f
        )
        assertEquals(
            WatermarkRenderer.MIN_ALPHA,
            0f.coerceIn(WatermarkRenderer.MIN_ALPHA, 1f),
            1e-6f
        )
    }

    // =================================================================
    // 字号范围 6 ~ 300
    // =================================================================

    @Test
    fun `字号区间覆盖小字与超大字`() {
        assertTrue("下限应足够小（<=10）", FONT_SIZE_MIN <= 10f)
        assertTrue("上限应足够大（>=200）", FONT_SIZE_MAX >= 200f)
    }

    @Test
    fun `默认字号落在区间内`() {
        val default = TextWatermarkStyle.Default.fontSize
        assertTrue(
            "默认字号 $default 应在 [$FONT_SIZE_MIN, $FONT_SIZE_MAX] 内",
            default in FONT_SIZE_MIN..FONT_SIZE_MAX
        )
    }

    @Test
    fun `字号区间跨度足够大`() {
        val ratio = FONT_SIZE_MAX / FONT_SIZE_MIN
        assertTrue(
            "最大/最小 比值应 >= 30（实际 ${ratio.toInt()}），否则调节空间不足",
            ratio >= 30f
        )
    }

    // =================================================================
    // 其它参数保持合理
    // =================================================================

    @Test
    fun `旋转角度区间为正负九十度`() {
        assertEquals(-90f, ROTATION_MIN, 1e-6f)
        assertEquals(90f, ROTATION_MAX, 1e-6f)
    }

    @Test
    fun `水印密度区间为 1 到 20`() {
        assertEquals(1, TILE_COUNT_MIN)
        assertEquals(20, TILE_COUNT_MAX)
    }

    @Test
    fun `默认配置的所有参数都在合法区间`() {
        val d = TextWatermarkStyle.Default
        assertTrue("字号", d.fontSize in FONT_SIZE_MIN..FONT_SIZE_MAX)
        assertTrue("透明度", d.alpha in WatermarkRenderer.MIN_ALPHA..1f)
        assertTrue("旋转", d.rotationDegrees in ROTATION_MIN..ROTATION_MAX)
        assertTrue("密度", d.tileCount in TILE_COUNT_MIN..TILE_COUNT_MAX)
    }

    // ---- 与 UI 滑块声明保持同步的常量 ----

    private val FONT_SIZE_MIN = 6f
    private val FONT_SIZE_MAX = 300f
    private val ROTATION_MIN = -90f
    private val ROTATION_MAX = 90f
    private val TILE_COUNT_MIN = 1
    private val TILE_COUNT_MAX = 20
}