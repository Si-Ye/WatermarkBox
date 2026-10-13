package com.watermarkbox.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「拖动水印」的坐标换算单元测试。
 *
 * ## 为什么单独测这个纯函数
 *
 * 手势本身在本环境里**测不了** —— Espresso 的输入注入一初始化就崩
 * （见 `PreviewFitMeasurementTest` 的说明），所以 Compose 的手势 UI 测试用不上。
 *
 * 于是把手势里唯一容易算错的部分（Fit 居中留白 → 0~1 归一化坐标）抽出来，
 * 用纯函数单测覆盖；手势部分只剩「取值 + 转交」两行，出错空间很小。
 *
 * 换算错了不会崩、不会报错，只会让水印「跟手偏一点」——
 * 正是那种必须靠断言而不是靠肉眼发现的 bug。
 */
class WatermarkDragMathTest {

    /** 无留白：容器与图片同比例，中心触摸点应落在正中。 */
    @Test
    fun `同比例时中心点归一化为 0_5`() {
        val center = normalizeToImageSpace(
            position = Offset(500f, 400f),
            containerSize = IntSize(1000, 800),
            imageWidth = 1000f,
            imageHeight = 800f
        )
        assertEquals(0.5f, center.x, 1e-4f)
        assertEquals(0.5f, center.y, 1e-4f)
    }

    /**
     * 左右留白：图片比容器更「高瘦」，Fit 后左右各留一条空白。
     *
     * 触摸点落在**留白区**时必须夹到 0 / 1，而不是算出负数或超过 1 ——
     * 否则水印会被拖出画面（导出后跑到图外）。
     */
    @Test
    fun `左右留白时按图片实际绘制区换算并夹紧`() {
        val container = IntSize(1000, 1000)
        // fitScale = min(1000/500, 1000/1000) = 1.0 → 绘制区 500x1000，居中 → left = 250
        val topLeft = normalizeToImageSpace(Offset(250f, 0f), container, 500f, 1000f)
        assertEquals(0.0f, topLeft.x, 1e-4f)
        assertEquals(0.0f, topLeft.y, 1e-4f)

        val bottomRight = normalizeToImageSpace(Offset(750f, 1000f), container, 500f, 1000f)
        assertEquals(1.0f, bottomRight.x, 1e-4f)
        assertEquals(1.0f, bottomRight.y, 1e-4f)

        // 左边那条留白（x < 250）应该被夹到 0，而不是负值
        val inLeftGutter = normalizeToImageSpace(Offset(10f, 500f), container, 500f, 1000f)
        assertEquals(0.0f, inLeftGutter.x, 1e-4f)
        assertEquals(0.5f, inLeftGutter.y, 1e-4f)
    }

    /** 上下留白：宽图为 1000x500 放进 1000x1000，垂直居中。 */
    @Test
    fun `上下留白时垂直方向正确居中`() {
        val container = IntSize(1000, 1000)
        // fitScale = 1.0 → 绘制区 1000x500，top = 250
        val center = normalizeToImageSpace(Offset(500f, 500f), container, 1000f, 500f)
        assertEquals(0.5f, center.x, 1e-4f)
        assertEquals(0.5f, center.y, 1e-4f)

        val bottom = normalizeToImageSpace(Offset(500f, 750f), container, 1000f, 500f)
        assertEquals(1.0f, bottom.y, 1e-4f)

        // 上方留白（y < 250）夹到 0
        val inTopGutter = normalizeToImageSpace(Offset(500f, 20f), container, 1000f, 500f)
        assertEquals(0.0f, inTopGutter.y, 1e-4f)
    }

    /** 真实比例：手机竖屏预览区 1080x600 显示 1024x768 的图（宽度富余，高度受限）。 */
    @Test
    fun `手机预览区的真实比例`() {
        val container = IntSize(1080, 600)
        // fitScale = min(1080/1024, 600/768) = 0.78125 → 绘制区 800x600，left = 140, top = 0
        val topLeft = normalizeToImageSpace(Offset(140f, 0f), container, 1024f, 768f)
        assertEquals(0.0f, topLeft.x, 1e-4f)
        assertEquals(0.0f, topLeft.y, 1e-4f)

        val bottomRight = normalizeToImageSpace(Offset(940f, 600f), container, 1024f, 768f)
        assertEquals(1.0f, bottomRight.x, 1e-4f)
        assertEquals(1.0f, bottomRight.y, 1e-4f)

        val center = normalizeToImageSpace(Offset(540f, 300f), container, 1024f, 768f)
        assertEquals(0.5f, center.x, 1e-4f)
        assertEquals(0.5f, center.y, 1e-4f)
    }

    /** 手指划出容器外：两个方向都必须夹紧，不能产生越界坐标。 */
    @Test
    fun `超出容器的触摸点被夹到 0 和 1`() {
        val container = IntSize(1000, 1000)
        val outside = normalizeToImageSpace(Offset(5000f, 5000f), container, 1000f, 1000f)
        assertEquals(1.0f, outside.x, 1e-4f)
        assertEquals(1.0f, outside.y, 1e-4f)

        val negative = normalizeToImageSpace(Offset(-500f, -500f), container, 1000f, 1000f)
        assertEquals(0.0f, negative.x, 1e-4f)
        assertEquals(0.0f, negative.y, 1e-4f)
    }

    /**
     * 退化输入返回中心点，而不是崩或算出 NaN。
     *
     * 首帧（尺寸还是 0）和位图被回收后都可能走到这里 ——
     * 返回 0.5 意味着「不动水印」，比把水印扔到画面外安全得多。
     */
    @Test
    fun `退化输入返回中心点`() {
        val zeroImage = normalizeToImageSpace(Offset(10f, 10f), IntSize(100, 100), 0f, 0f)
        assertEquals(0.5f, zeroImage.x, 1e-4f)
        assertEquals(0.5f, zeroImage.y, 1e-4f)

        val zeroContainer = normalizeToImageSpace(Offset(10f, 10f), IntSize(0, 0), 100f, 100f)
        assertEquals(0.5f, zeroContainer.x, 1e-4f)
        assertEquals(0.5f, zeroContainer.y, 1e-4f)
    }
}