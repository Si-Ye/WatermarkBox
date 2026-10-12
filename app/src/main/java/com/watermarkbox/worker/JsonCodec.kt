package com.watermarkbox.worker

import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import kotlinx.serialization.json.Json

/**
 * 水印配置 ↔ JSON 编解码。
 *
 * 用途：WorkManager 的 Data 只能传基本类型，
 * 因此把配置对象序列化成 JSON 字符串传递，Worker 侧再解回来。
 *
 * 容错策略：解析失败时返回默认配置，保证 Worker 不会因脏数据崩溃。
 */
object JsonCodec {

    /**
     * 复用全局共享的 Json 实例（见 [com.watermarkbox.data.repository.AppJson]）。
     * 避免每处各自 `Json { }` 造成的重复初始化与配置漂移。
     */
    private val json: Json get() = com.watermarkbox.data.repository.AppJson.instance

    // ---- 文字水印 ----

    fun encodeTextStyle(style: TextWatermarkStyle): String =
        json.encodeToString(TextWatermarkStyle.serializer(), style)

    fun decodeTextStyle(raw: String?): TextWatermarkStyle {
        if (raw.isNullOrBlank()) return TextWatermarkStyle.Default
        return runCatching { json.decodeFromString(TextWatermarkStyle.serializer(), raw) }
            .getOrDefault(TextWatermarkStyle.Default)
    }

    // ---- 图片水印 ----

    fun encodeImageStyle(style: ImageWatermarkStyle): String =
        json.encodeToString(ImageWatermarkStyle.serializer(), style)

    fun decodeImageStyle(raw: String?): ImageWatermarkStyle {
        if (raw.isNullOrBlank()) return ImageWatermarkStyle.Default
        return runCatching { json.decodeFromString(ImageWatermarkStyle.serializer(), raw) }
            .getOrDefault(ImageWatermarkStyle.Default)
    }

    // ---- 导出设置 ----

    fun encodeExportSettings(settings: ExportSettings): String =
        json.encodeToString(ExportSettings.serializer(), settings)

    fun decodeExportSettings(raw: String?): ExportSettings {
        if (raw.isNullOrBlank()) return ExportSettings()
        return runCatching { json.decodeFromString(ExportSettings.serializer(), raw) }
            .getOrDefault(ExportSettings())
    }
}