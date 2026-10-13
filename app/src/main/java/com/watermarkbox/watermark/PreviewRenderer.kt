package com.watermarkbox.watermark

import android.graphics.Bitmap
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType

/**
 * 预览渲染的纯逻辑封装（从 Compose 组件中抽出，便于单元/仪器化测试）。
 *
 * 组件层负责把参数包成 State 并调用本类；
 * 本类只关心「给定输入 → 输出 Bitmap」，不依赖任何 Compose 运行时。
 */
object PreviewRenderer {

    /**
     * 根据当前水印类型渲染预览。
     *
     * @return 渲染结果；输入非法或渲染失败时返回 null
     */
    fun render(
        renderer: WatermarkRenderer,
        bitmap: Bitmap?,
        watermarkType: WatermarkType,
        textConfig: TextWatermarkStyle,
        imageConfig: ImageWatermarkStyle,
        logoBitmap: Bitmap?,
        sourceFileName: String?,
        /**
         * **原图**宽高（不是 [bitmap] 的宽高）。
         *
         * ## 为什么必须传（这是一个真实的「预览与导出不一致」bug）
         *
         * `TextWatermarkStyle.fontSize` / `margin` 等数值是**绝对像素**，
         * 而 `WatermarkRenderer` 靠 `RenderContext.scale =
         * targetWidth / baseWidth` 把它们换算到实际输出尺寸。
         *
         * 默认调用（不传 baseWidth）时 `baseWidth = source.width`，
         * 于是 scale 恒为 1 —— 也就是**字号在两个尺寸下各自按原值渲染**：
         *
         * ```
         * 字号 36，预览图宽 540  →  36/540  = 6.7% 画面宽
         * 字号 36，导出图宽 4032 →  36/4032 = 0.9% 画面宽
         *                                    ↑ 差 7.5 倍
         * ```
         *
         * 用户在预览里调好的大小，导出后小得几乎看不见。
         *
         * 修法：预览时把**原图尺寸**作为基准传进来，
         * 让 `scale = 预览宽 / 原图宽`（如 540/4032 = 0.134），
         * 字号就会按同比例缩小 —— 视觉占比与导出结果一致。
         *
         * 导出侧 `baseWidth == targetWidth`（都是原图），scale 仍为 1，不受影响。
         *
         * 传 0 或负数表示「不知道原图尺寸」，退回旧行为（scale = 1）。
         */
        sourceBaseWidth: Int = 0,
        sourceBaseHeight: Int = 0,
        /**
         * 取消检查点，透传给 [WatermarkRenderer] 的平铺循环。
         *
         * 为什么必须一路传下来：渲染链路**完全同步**，
         * 协程取消无法中断它（详见 [RenderCancelCheck]）。
         */
        cancel: RenderCancelCheck = RenderCancelCheck.Never
    ): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null

        return runCatching {
            when (watermarkType) {
                WatermarkType.TEXT -> renderer.render(
                    source = bitmap,
                    config = textConfig,
                    sourceFileName = sourceFileName,
                    baseWidth = sourceBaseWidth.takeIf { it > 0 } ?: bitmap.width,
                    baseHeight = sourceBaseHeight.takeIf { it > 0 } ?: bitmap.height,
                    cancel = cancel
                )

                WatermarkType.IMAGE -> {
                    if (logoBitmap == null || logoBitmap.isRecycled) {
                        // 未选择 logo 时直接回显原图
                        bitmap
                    } else {
                        renderer.renderImage(
                            source = bitmap,
                            logo = logoBitmap,
                            config = imageConfig,
                            // 图片水印同样要按原图坐标系换算宽高百分比
                            baseWidth = sourceBaseWidth.takeIf { it > 0 } ?: bitmap.width,
                            baseHeight = sourceBaseHeight.takeIf { it > 0 } ?: bitmap.height
                        )
                    }
                }
            }
        }.getOrNull()
    }
}