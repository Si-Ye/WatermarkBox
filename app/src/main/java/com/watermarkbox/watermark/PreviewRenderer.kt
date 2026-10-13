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
                            config = imageConfig
                        )
                    }
                }
            }
        }.getOrNull()
    }
}