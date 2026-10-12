package com.watermarkbox.data.model

import kotlinx.serialization.Serializable

/**
 * 一个可复用的水印预设。
 *
 * 预设同时保存文字水印与图片水印两套配置，
 * 应用预设时按当前水印类型取对应的一份。
 *
 * @param id        唯一标识（时间戳生成）
 * @param name      用户可见名称
 * @param createdAt 创建时间（毫秒）
 * @param textStyle  文字水印配置
 * @param imageStyle 图片水印配置
 * @param type       该预设默认使用的类型
 */
@Serializable
data class WatermarkPreset(
    val id: String,
    val name: String,
    val createdAt: Long,
    val textStyle: TextWatermarkStyle,
    val imageStyle: ImageWatermarkStyle,
    val type: WatermarkType
)

/**
 * 导出相关设置。
 *
 * @param format           导出格式
 * @param jpegQuality      JPEG 质量（1~100）
 * @param defaultAlpha     新水印的默认透明度
 * @param fileNameTemplate 批量导出的文件名模板
 * @param customOutputDir  自定义输出目录（相册相对路径；null 用默认）
 * @param preserveExif     是否保留原图 EXIF
 * @param defaultWatermarkText 新图片的默认水印文字（设置页可改）
 * @param defaultLogoUri       新图片的默认图片水印 logo（设置页可选；null = 无）
 */
@Serializable
data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val jpegQuality: Int = 95,
    val defaultAlpha: Float = 0.7f,
    val fileNameTemplate: String = "{原文件名}_watermarked",
    val customOutputDir: String? = null,
    val preserveExif: Boolean = false,
    val defaultWatermarkText: String = TextWatermarkStyle.Default.text,
    val defaultLogoUri: String? = null,
    /**
     * 开启隐藏水印时，是否**默认**同时启用 DCT 频域嵌入。
     *
     * 这只是给编辑页一个初始值 —— 用户在编辑页仍可单独关掉。
     * 默认 `false`：DCT 有画质代价，不该替用户默认接受。
     */
    val invisibleDctByDefault: Boolean = false
)

/**
 * 导出图片格式。
 */
@Serializable
enum class ExportFormat(val displayName: String, val extension: String, val mimeType: String) {
    PNG("PNG（无损）", "png", "image/png"),
    JPEG("JPEG（质量 95%）", "jpg", "image/jpeg")
}