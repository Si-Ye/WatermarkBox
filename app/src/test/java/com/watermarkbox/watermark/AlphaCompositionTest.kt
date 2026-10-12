package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 水印透明度合成算法单元测试。
 *
 * 背景（真实 Bug）：
 * Android 的 Paint.setColor() 会整体写入 ARGB，包括 alpha 通道。
 * 因此若先 setColor(0xFFFFFFFF) 再 setAlpha(128)，或反过来，
 * 后者都会覆盖前者，导致「透明度滑块无效」。
 *
 * 正确做法：把 RGB 与 alpha 合成为一个 ARGB 再一次性 setColor()。
 * 这里通过 compose() 副本验证该合成算法。
 */
class AlphaCompositionTest {

    /** 与 WatermarkRenderer.withAlpha() 等价的纯函数副本，用于单测验证算法。 */
    private fun compose(argb: Int, alphaByte: Int): Int {
        return (argb and 0x00FFFFFF) or (alphaByte shl 24)
    }

    @Test
    fun `alpha 255 保持完全不透明`() {
        val white = 0xFFFFFFFF.toInt()
        assertEquals(0xFFFFFFFF.toInt(), compose(white, 255))
    }

    @Test
    fun `alpha 0 得到完全透明`() {
        val white = 0xFFFFFFFF.toInt()
        assertEquals(0x00FFFFFF, compose(white, 0))
    }

    @Test
    fun `alpha 128 得到半透明且 RGB 不变`() {
        val black = 0xFF000000.toInt()
        val result = compose(black, 128)
        // RGB 仍为 0，alpha 变成 128
        assertEquals(128, (result ushr 24) and 0xFF)
        assertEquals(0, result and 0x00FFFFFF)
    }

    @Test
    fun `合成会剥离原颜色的 alpha 通道`() {
        // 原颜色自带 alpha=0xFF，但目标 alpha=64，应以目标为准
        val semi = 0xFF3366CC.toInt()
        val result = compose(semi, 64)
        assertEquals(64, (result ushr 24) and 0xFF)
        assertEquals(0x3366CC, result and 0x00FFFFFF)
    }

    @Test
    fun `RGB 三通道完整保留`() {
        val color = 0xFF123456.toInt()
        val result = compose(color, 200)
        assertEquals(0x12, (result ushr 16) and 0xFF)
        assertEquals(0x34, (result ushr 8) and 0xFF)
        assertEquals(0x56, result and 0xFF)
    }

    @Test
    fun `滑块百分比换算为 alpha 字节`() {
        // 面板范围 0.1 ~ 1.0。
        // 注意：浮点乘法后 toInt() 是截断（非四舍五入），
        // 例如 0.1f * 255 = 25.5 -> 25，0.5f * 255 = 127.5 -> 127。
        assertEquals(25, (0.1f * 255).toInt())
        assertEquals(127, (0.5f * 255).toInt())
        assertEquals(178, (0.7f * 255).toInt())
        assertEquals(255, (1.0f * 255).toInt())
    }

    @Test
    fun `不同透明度产生不同结果`() {
        val color = 0xFFFFFFFF.toInt()
        val a = compose(color, (0.3f * 255).toInt())
        val b = compose(color, (0.9f * 255).toInt())
        // 若两者相等，说明透明度未生效（即原 Bug）
        assert(a != b) { "0.3 与 0.9 的透明度必须产生不同的 ARGB" }
    }
}