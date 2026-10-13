package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预览与导出的水印**视觉占比**一致」的不变量测试。
 *
 * ## 背景（这是一个真实修过的 bug）
 *
 * 老大反馈：导出图里的水印大小和预览里看到的不一样，差很多。
 *
 * 原因是预览渲染时把 `baseWidth` 取成了**降采样图**的宽度：
 *
 * ```
 * 字号 36，预览图宽 540   →  scale = 540/540   = 1  →  占画面 6.7%
 * 字号 36，导出图宽 4032  →  scale = 4032/4032 = 1  →  占画面 0.9%
 *                                                    ↑ 差 7.5 倍
 * ```
 *
 * 修法：预览时把**原图尺寸**当基准传下去，
 * 让 `scale = 预览宽 / 原图宽`，字号等比缩小。
 *
 * ## 本文件测的是「不变量」而非「具体数值」
 *
 * 不变量：**同一个配置，在两个不同尺寸上渲染出的水印，
 * 占画面的比例应当相同**。
 *
 * 这条断言能同时挡住两类错误：
 * - 预览漏传基准（scale 恒为 1）→ 比例不一致
 * - 导出漏传基准 → 反向不一致
 *
 * 纯计算逻辑，不需要真 Bitmap。
 */
class PreviewExportScaleConsistencyTest {

    /** 原始配置（假设用户在预览里调好这些值）。 */
    private val fontSizePx = 36f
    private val marginPx = 20f

    /**
     * 计算某个输出尺寸下，水印实际占画面的比例。
     *
     * 复刻 `RenderContext.scale` 的算法：
     * `scale = targetWidth / baseWidth`
     */
    private fun renderedRatio(
        targetWidth: Int,
        targetHeight: Int,
        baseWidth: Int,
        baseHeight: Int
    ): Pair<Float, Float> {
        val ctx = WatermarkRenderer.RenderContext(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        val actualFontSize = fontSizePx * ctx.scale
        val actualMargin = marginPx * ctx.scale
        // 字号占宽度的比例、边距占宽度的比例
        return (actualFontSize / targetWidth) to (actualMargin / targetWidth)
    }

    // ------------------------------------------------------------------
    // 核心不变量
    // ------------------------------------------------------------------

    /**
     * **回归防护**：预览（540px）与导出（4032px）的水印占比必须一致。
     *
     * 这里预览侧**正确**地传了原图尺寸当基准。
     */
    @Test
    fun `预览与导出的水印占比一致（正确传基准）`() {
        val originalWidth = 4032
        val originalHeight = 3024
        val previewWidth = 540
        val previewHeight = 405

        // 预览：基准 = 原图，目标 = 预览图
        val (previewFontRatio, previewMarginRatio) = renderedRatio(
            targetWidth = previewWidth,
            targetHeight = previewHeight,
            baseWidth = originalWidth,
            baseHeight = originalHeight
        )
        // 导出：基准 = 原图，目标 = 原图（scale 恒为 1）
        val (exportFontRatio, exportMarginRatio) = renderedRatio(
            targetWidth = originalWidth,
            targetHeight = originalHeight,
            baseWidth = originalWidth,
            baseHeight = originalHeight
        )

        assertEquals(
            "字号占比必须一致（预览 $previewFontRatio vs 导出 $exportFontRatio）",
            exportFontRatio,
            previewFontRatio,
            1e-6f
        )
        assertEquals(
            "边距占比必须一致",
            exportMarginRatio,
            previewMarginRatio,
            1e-6f
        )
    }

    /**
     * **反向验证**：如果预览漏传基准（旧行为，baseWidth = 预览图宽），
     * 占比就会差出「原图宽 / 预览宽」倍 —— 证明这条不变量确实能抓到 bug。
     */
    @Test
    fun `漏传基准时占比会差出缩放倍数（旧 bug 的复现）`() {
        val originalWidth = 4032
        val previewWidth = 540

        // 旧行为：预览的基准 = 预览图自己
        val (buggyPreviewRatio, _) = renderedRatio(
            targetWidth = previewWidth,
            targetHeight = 405,
            baseWidth = previewWidth,       // ← 错在这里
            baseHeight = 405
        )
        val (exportRatio, _) = renderedRatio(
            targetWidth = originalWidth,
            targetHeight = 3024,
            baseWidth = originalWidth,
            baseHeight = 3024
        )

        val expectedRatioDiff = originalWidth.toFloat() / previewWidth
        assertEquals(
            "旧行为下差异应等于原图/预览的宽比（$expectedRatioDiff）",
            expectedRatioDiff,
            buggyPreviewRatio / exportRatio,
            1e-3f
        )
        // 确认这个差异是"很大"的 —— 用户能一眼看出
        assertTrue(
            "差异应当显著（>5 倍），否则用户不会察觉",
            buggyPreviewRatio / exportRatio > 5f
        )
    }

    /** 同一张图，不同预览尺寸下比例仍一致（缩放不影响视觉占比）。 */
    @Test
    fun `不同预览尺寸下占比都一致`() {
        val originalWidth = 4032
        val originalHeight = 3024

        val (r720, _) = renderedRatio(720, 540, originalWidth, originalHeight)
        val (r540, _) = renderedRatio(540, 405, originalWidth, originalHeight)
        val (r360, _) = renderedRatio(360, 270, originalWidth, originalHeight)

        assertEquals(r720, r540, 1e-6f)
        assertEquals(r540, r360, 1e-6f)
    }

    /** 竖图与横图走同一套逻辑（基准用宽度，高度只参与渲染尺寸）。 */
    @Test
    fun `竖图横图都满足不变量`() {
        // 横图
        val (h1, _) = renderedRatio(540, 405, 4032, 3024)
        val (h2, _) = renderedRatio(4032, 3024, 4032, 3024)
        assertEquals(h2, h1, 1e-6f)

        // 竖图 3024×4032
        val (v1, _) = renderedRatio(405, 540, 3024, 4032)
        val (v2, _) = renderedRatio(3024, 4032, 3024, 4032)
        assertEquals(v2, v1, 1e-6f)
    }

    /** baseWidth 非法（<=0）时 scale 退化为 1 —— 不能崩，也不能算出 NaN。 */
    @Test
    fun `基准非法时退化为 1`() {
        val ctx = WatermarkRenderer.RenderContext(
            baseWidth = 0,
            baseHeight = 0,
            targetWidth = 540,
            targetHeight = 405
        )
        assertEquals(1f, ctx.scale, 1e-6f)
    }
}