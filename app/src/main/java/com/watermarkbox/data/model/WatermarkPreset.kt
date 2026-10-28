package com.watermarkbox.data.model

import androidx.annotation.StringRes
import com.watermarkbox.R
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
 * @param indexStart           批量导出时序号占位符 `{index}` 的起始值（每批从它开始）
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
     * 批量导出时 `{index...}` 占位符的起始序号；第 i 张（0 基）用 `indexStart + i`。
     *
     * 放在导出设置里而不是全局设置：它必须跟着 `fileNameTemplate` 一起保存、
     * 一起随预设导入导出，否则会出现「模板里有序号，起始号却是另一套」的错位。
     * 默认 1 —— 老预设没有这个字段时反序列化就是 1（`ignoreUnknownKeys` 已开）。
     *
     * 语义是「每批从它开始」，不记忆上一批跑到几号。
     */
    val indexStart: Int = 1,
    /**
     * 开启隐藏水印时，是否**默认**同时启用 DCT 频域嵌入。
     *
     * 这只是给编辑页一个初始值 —— 用户在编辑页仍可单独关掉。
     * 默认 `false`：DCT 有画质代价，不该替用户默认接受。
     */
    val invisibleDctByDefault: Boolean = false,
    /**
     * 导出尺寸：**长边上限**档位。
     *
     * ## 为什么要这个开关
     *
     * 导出链路按原图分辨率走，一张 5000 万像素的照片要占约 200 MB 位图内存。
     * 超过 [com.watermarkbox.util.BitmapBudget] 的预算时，过去只能整单失败
     * 并提示「图太大」—— 用户除了换台手机没有任何出路。
     *
     * 而绝大多数用途（发朋友圈、发群、存手机相册）根本不需要原图分辨率。
     * 给出「缩到长边 2048」这一档，大图就从**必然失败**变成**正常可用**：
     * 预检按缩小后的尺寸算，解码时也用 `inSampleSize` 直接少解像素。
     *
     * ## 为什么默认 [ExportMaxEdge.ORIGINAL]
     *
     * 导出工具的本分是「不要擅自降低画质」。老预设没有这个字段，
     * 反序列化后也是 ORIGINAL —— 行为与升级前完全一致。
     */
    val maxEdge: ExportMaxEdge = ExportMaxEdge.ORIGINAL
)

/**
 * 导出尺寸档位：长边像素上限。
 *
 * ## 为什么用枚举而不是裸 Int
 *
 * 档位是**产品决策**（提供哪几档），不是自由参数。裸 Int 会让 UI、
 * 校验、文案三处各自维护一份「合法值」清单，迟早不一致；
 * 枚举把它们收成一处，且序列化按常量名走，改名会立刻暴露。
 *
 * ## [edge] 的 0 表示「不限制」
 *
 * 0 而不是 Int.MAX_VALUE：0 可以直接作为「要不要降采样」的判据
 * （`if (edge > 0)`），也不参与任何尺寸计算，避免溢出。
 *
 * @param labelRes 界面文案资源 ID（需跟随语言，理由同 [FontFamilyOption]）
 * @param edge     长边上限（像素）；0 = 保持原图
 */
@Serializable
enum class ExportMaxEdge(@param:StringRes val labelRes: Int, val edge: Int) {
    /** 原图分辨率（不缩放）。 */
    ORIGINAL(R.string.export_max_edge_original, 0),

    /** 长边不超过 4096（约 1200 万像素，够 4K 屏与大幅打印）。 */
    P4096(R.string.export_max_edge_4096, 4096),

    /** 长边不超过 2048（约 300 万像素，手机相册/社交分享的常用档）。 */
    P2048(R.string.export_max_edge_2048, 2048),

    /** 长边不超过 1080（约 200 万像素，只为聊天工具/网页发送）。 */
    P1080(R.string.export_max_edge_1080, 1080);

    /** 是否需要对源图降采样。 */
    val scalesDown: Boolean get() = edge > 0
}

/**
 * 导出图片格式。
 */
@Serializable
enum class ExportFormat(val displayName: String, val extension: String, val mimeType: String) {
    PNG("PNG（无损）", "png", "image/png"),
    JPEG("JPEG（质量 95%）", "jpg", "image/jpeg")
}