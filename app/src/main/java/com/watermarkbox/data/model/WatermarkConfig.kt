package com.watermarkbox.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.watermarkbox.R
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 水印类型：文字水印 / 图片水印。
 */
@Serializable
enum class WatermarkType { TEXT, IMAGE }

/**
 * 水印布局模式。
 * - SINGLE   单个水印，位置由九宫格 + 边距决定
 * - TILED    平铺，密度由数量控制
 * - DIAGONAL 对角线平铺，在平铺基础上整体旋转
 */
@Serializable
enum class LayoutMode { SINGLE, TILED, DIAGONAL }

/**
 * 水印位置。
 *
 * - 九宫格九个方位：按边距放置（**仅单个模式**生效）
 * - [CUSTOM]：使用 [TextWatermarkStyle.customX] / [customY] 指定的**归一化坐标**
 *   （0~1，相对图片宽高），由「在预览图上拖动水印」产生。
 *
 * ## 为什么用「归一化坐标」而不是像素
 *
 * 预览是按缩略图尺寸渲染的、导出是按原图尺寸渲染的，两者尺寸差几十倍。
 * 若存像素，拖动后在导出时会跑到画面外。存 0~1 的比例，
 * 两种尺寸下都能还原到相同相对位置。
 */
@Serializable
enum class GravityPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,

    /** 自定义位置（拖动产生），坐标见 customX / customY。 */
    CUSTOM;

    /** 是否为九宫格方位（非 CUSTOM）。 */
    val isGrid: Boolean get() = this != CUSTOM
}

/**
 * 字体种类（系统内置族 + 自定义入口）。
 *
 * labelRes 存资源 ID：界面需按当前语言显示（中文「衬线体」/ 英文 "Serif"）。
 *
 * **序列化兼容性**：kotlinx.serialization 对 enum 默认按**常量名**（如 `SERIF`）
 * 序列化，与 labelRes 无关。因此把 displayName 换成资源 ID **不会**
 * 破坏已保存的用户预设。
 */
@Serializable
enum class FontFamilyOption(@param:StringRes val labelRes: Int) {
    DEFAULT(R.string.font_family_default),
    SERIF(R.string.font_family_serif),
    MONOSPACE(R.string.font_family_monospace),
    CURSIVE(R.string.font_family_cursive),
    CUSTOM(R.string.font_family_custom)
}

/**
 * 单个水印的文字样式配置。
 *
 * 说明：所有尺寸相关的数值（textSize、strokeWidth、shadowRadius、margin）
 * 均以「相对原图短边的千分比」为基准的绝对像素值存储，
 * 这样预览缩略图与导出原图能获得完全一致的视觉比例。
 * 为简化阶段一实现，这里直接存储像素值，渲染时按目标位图做等比缩放。
 */
@Serializable
data class TextWatermarkStyle(
    /** 水印文字内容，支持 {timestamp} / {date} / {filename} 占位符 */
    val text: String = "水印盒 WatermarkBox",
    /** 字号（px，基于导出原图坐标系） */
    val fontSize: Float = 36f,
    /** 字体种类 */
    val fontFamily: FontFamilyOption = FontFamilyOption.DEFAULT,
    /** 自定义字体文件名（阶段二预留入口；null 表示使用内置字体） */
    val customFontName: String? = null,
    /** 字体颜色 */
    val color: WatermarkColor = WatermarkColor(Color.White),
    /** 加粗 */
    val bold: Boolean = false,
    /** 斜体 */
    val italic: Boolean = false,
    /** 是否描边 */
    val strokeEnabled: Boolean = false,
    /** 描边宽度（px） */
    val strokeWidth: Float = 2f,
    /** 描边颜色 */
    val strokeColor: WatermarkColor = WatermarkColor(Color.Black),
    /** 是否阴影 */
    val shadowEnabled: Boolean = false,
    /** 阴影模糊半径（px） */
    val shadowRadius: Float = 4f,
    /** 阴影颜色 */
    val shadowColor: WatermarkColor = WatermarkColor(Color.Black),
    /** 布局模式 */
    val layoutMode: LayoutMode = LayoutMode.SINGLE,
    /** 平铺数量/密度（1~20，仅平铺/对角线模式生效） */
    val tileCount: Int = 4,
    /** 旋转角度（-90° ~ 90°） */
    val rotationDegrees: Float = 0f,
    /** 不透明度（0.1 ~ 1.0） */
    val alpha: Float = 0.7f,
    /** 位置（九宫格方位，或 [GravityPosition.CUSTOM] 自定义） */
    val gravity: GravityPosition = GravityPosition.BOTTOM_RIGHT,
    /** 边距（px，仅九宫格位置生效） */
    val margin: Float = 48f,

    /**
     * 自定义位置：水印中心的归一化 X（0~1，相对图片宽度）。
     * 仅当 [gravity] == [GravityPosition.CUSTOM] 时生效。
     */
    val customX: Float = 0.5f,
    /** 自定义位置：水印中心的归一化 Y（0~1，相对图片高度）。 */
    val customY: Float = 0.5f
) {
    companion object {
        /** 默认配置，供编辑页初始化使用 */
        val Default = TextWatermarkStyle()
    }
}

/**
 * 图片水印的样式配置（阶段二新增）。
 *
 * @param logoUri        选中的 logo 图片 Uri（本地或相册）
 * @param widthPercent   logo 宽度占原图宽度的百分比（0.05 ~ 0.8）
 * @param layoutMode     复用布局模式（单个/平铺/对角线）
 * @param tileCount      平铺密度
 * @param rotationDegrees 旋转角度
 * @param alpha          不透明度
 * @param gravity        位置（九宫格方位或 CUSTOM 自定义）
 * @param margin         边距（px，仅九宫格位置生效）
 * @param customX        自定义位置：logo 中心的归一化 X（0~1）
 * @param customY        自定义位置：logo 中心的归一化 Y（0~1）
 * @param preserveAspectRatio 是否保持 logo 原始宽高比
 */
@Serializable
data class ImageWatermarkStyle(
    val logoUri: String? = null,
    val widthPercent: Float = 0.2f,
    val layoutMode: LayoutMode = LayoutMode.SINGLE,
    val tileCount: Int = 4,
    val rotationDegrees: Float = 0f,
    val alpha: Float = 0.7f,
    val gravity: GravityPosition = GravityPosition.BOTTOM_RIGHT,
    val margin: Float = 48f,
    /** 自定义位置：logo 中心的归一化 X（0~1），仅 [GravityPosition.CUSTOM] 生效 */
    val customX: Float = 0.5f,
    /** 自定义位置：logo 中心的归一化 Y（0~1），仅 [GravityPosition.CUSTOM] 生效 */
    val customY: Float = 0.5f,
    val preserveAspectRatio: Boolean = true
) {
    companion object {
        val Default = ImageWatermarkStyle()
    }
}

/**
 * 隐藏水印（不可见水印）配置。
 *
 * ## 与「可见水印」的区别
 *
 * 可见水印（[TextWatermarkStyle] / [ImageWatermarkStyle]）改变画面外观；
 * 隐藏水印**不改外观**（像素算法上只有 ±3/255 的亮度扰动，肉眼不可辨），
 * 用于**溯源**：图片流出后仍能证明"是我导出的"。
 *
 * ## 两层载体
 *
 * 1. **EXIF UserComment**：写入 `WMB1:<内容>`，像素完全不变。
 *    优点是零失真；缺点是任何压缩/转格式都会丢失。
 * 2. **DCT 频域**：把内容编码后嵌进亮度中频系数。
 *    优点是能扛 JPEG 重压缩；缺点是严格说改动了像素（差 ±3/255）。
 *
 * 两层同时写入 → 未压缩时走 EXIF（绝对无损），
 * 被压缩后还能靠 DCT 恢复。这就是「方案 C」。
 *
 * @param enabled    是否启用
 * @param content    水印内容（用户自定义）
 * @param source     内容来源：自定义 / 设备 ID+时间戳自动生成
 * @param strength   嵌入强度倍数（1~8）。1 = 最隐蔽，8 = 最顽固。
 *                   实际量化步长 = 该频点的 JPEG 量化步长 × 强度 × 4
 */
@Serializable
data class InvisibleWatermarkConfig(
    val enabled: Boolean = false,
    val content: String = "",
    val source: InvisibleSource = InvisibleSource.CUSTOM_TEXT,
    val strength: Int = 2,
    /**
     * 是否启用 DCT 频域嵌入。
     *
     * ## 为什么单独给开关
     *
     * 隐藏水印有**两条独立通道**：
     * 1. EXIF `UserComment` —— 零像素改动，但一压缩/转格式就没
     * 2. DCT 频域 —— 能扛 JPEG 重压缩，但会**轻微改动画质**
     *    （亮度中频系数被扰动，严格说像素有 ±3/255 的变化）
     *
     * 只写 EXIF 时画质完全无损；只有需要「抗压缩溯源」才值得付出
     * 那点画质代价。所以交给用户按场景决定，默认关闭。
     *
     * 关闭时**仍然写 EXIF**（[enabled] 为 true 的前提下）——
     * 这只是不写频域，不是"完全不要隐藏水印"。
     */
    val dctEnabled: Boolean = false
) {
    companion object {
        val Default = InvisibleWatermarkConfig()
    }
}

/**
 * 隐藏水印内容的来源。
 *
 * labelRes 存资源 ID，理由同 [FontFamilyOption]：界面需跟随语言显示。
 */
@Serializable
enum class InvisibleSource(@param:StringRes val labelRes: Int) {
    /** 用户在面板里自己输入的文字。 */
    CUSTOM_TEXT(R.string.editor_invisible_source_custom),
    /** 自动生成「设备 ID + 时间戳」，便于溯源到设备与时间。 */
    DEVICE_AND_TIME(R.string.editor_invisible_source_device)
}

/**
 * 用一个可序列化友好的包装来表示颜色。
 *
 * 序列化时存标准 ARGB Int，反序列化时还原为 Compose [Color]。
 * 使用自定义 [KSerializer] 而非直接序列化 Compose Color，
 * 因为 Compose Color 的内部结构不稳定，跨版本可能不兼容。
 */
@Serializable(with = WatermarkColorSerializer::class)
data class WatermarkColor(val color: Color) {

    /** 标准 ARGB 整数值。 */
    val argb: Int get() = color.toArgb()

    companion object {
        /** 从持久化的 ARGB Int 还原。 */
        fun fromArgb(argb: Int): WatermarkColor = WatermarkColor(Color(argb))
    }
}

/**
 * [WatermarkColor] 的自定义序列化器：以 ARGB Int 形式存取。
 */
object WatermarkColorSerializer : KSerializer<WatermarkColor> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WatermarkColor", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: WatermarkColor) {
        encoder.encodeInt(value.argb)
    }

    override fun deserialize(decoder: Decoder): WatermarkColor {
        return WatermarkColor.fromArgb(decoder.decodeInt())
    }
}
