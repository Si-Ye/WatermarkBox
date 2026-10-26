package com.watermarkbox.data.model

import kotlinx.serialization.Serializable

/**
 * 批量导出时「水印位置」的作用范围。
 *
 * ## 为什么需要它（而不是干脆只做一种）
 *
 * 批量的本质是「一套配置应用给 N 张图」，因此默认应当是统一的：
 * 拖一次，所有图都挪到同一相对位置。
 *
 * 但真实场景里还有另一种诉求：这一批图构图不同（有的主体在左、有的在右），
 * 统一位置必然压住其中一部分。此时用户希望**每张图各拖各的**。
 *
 * 两种诉求都是真实存在的，且没法用一个开关推断出来 ——
 * 所以做成显式选择，由用户在预览区自行切换。
 *
 * ## 语义
 *
 * - [UNIFIED]：位置存在**共享配置**里（`TextWatermarkStyle.customX/Y`），
 *   拖动任何一张 = 改全部。
 * - [PER_IMAGE]：位置存在**每张图的覆盖表**里（[BatchPositionOverrides]），
 *   拖动只影响当前这张；没有覆盖的图继续用共享配置的位置（即"基准位置"）。
 *
 * 注意：无论哪种模式，「素材」相关的配置（字号、颜色、透明度……）始终共享。
 * 只有位置是两种模式的区别 —— 这是刻意的：位置是唯一"与单图构图相关"的参数。
 */
enum class PositionScope {
    /** 所有图共用一个位置（默认）。 */
    UNIFIED,

    /** 每张图独立位置，未单独拖过的图沿用共享配置。 */
    PER_IMAGE
}

/**
 * 单张图的归一化位置（0~1，相对图片宽高，原点在左上角）。
 *
 * 与 [TextWatermarkStyle.customX]/[customY] 同一坐标系，
 * 因此预览（缩略图）与导出（原图）能还原到同一相对位置。
 */
@Serializable
data class ImagePosition(
    val x: Float,
    val y: Float
)

/**
 * 每张图的独立位置覆盖表。
 *
 * key 用 `Uri.toString()` —— 与 Worker 的入参、预览页的识别方式保持一致，
 * 避免为了查表再造一套 id。
 *
 * ## 为什么不存 `Map<String, ImagePosition>` 而是包一层
 *
 * 因为要同时承载**文字水印**与**图片水印**两套位置，并且要带作用域 ——
 * 三者一起当参数往 Composable 里传时，包一层比传三个散参数更不容易漏。
 */
data class BatchPositionOverrides(
    val scope: PositionScope = PositionScope.UNIFIED,
    val text: Map<String, ImagePosition> = emptyMap(),
    val image: Map<String, ImagePosition> = emptyMap()
) {

    /** 某张图的文字水印位置覆盖（没有则 null）。 */
    fun textFor(uri: String): ImagePosition? =
        if (scope == PositionScope.PER_IMAGE) text[uri] else null

    /** 某张图的图片水印位置覆盖（没有则 null）。 */
    fun imageFor(uri: String): ImagePosition? =
        if (scope == PositionScope.PER_IMAGE) image[uri] else null

    /** 切到「统一」时把覆盖表清空 —— 见 [PositionScope] 的说明。 */
    fun cleared(): BatchPositionOverrides =
        BatchPositionOverrides(scope = PositionScope.UNIFIED)
}

/**
 * 单条位置记录（序列化用）。
 *
 * ## 为什么用「扁平列表」而不是直接用 Map 序列化
 *
 * WorkManager 的 `Data` 只能装基本类型，位置表得先变成 JSON 字符串。
 * 直接把 `Map<String, ImagePosition>` 交给 kotlinx 也能编，但 Map 的 key 是
 * `content://media/...` 这样的长 uri —— 作为 JSON 对象键既难读又容易被转义弄脏。
 * 扁平成 `[{uri,x,y}]` 更直白，解析失败时也好定位是哪一条坏。
 */
@Serializable
data class ImagePositionEntry(
    val uri: String,
    val x: Float,
    val y: Float
)

/** 位置表 JSON 的根对象。 */
@Serializable
data class ImagePositionTable(
    val entries: List<ImagePositionEntry> = emptyList()
)


/**
 * 把「位置覆盖」应用到水印样式上的纯函数集合。
 *
 * ## 为什么要抽成纯函数
 *
 * 这段逻辑有**三个消费方**：预览页（每帧）、Worker（导出时）、单测。
 * 三处各写一遍必然漂移 —— 尤其是"拖动后要把 gravity 切到 CUSTOM"这种细节，
 * 漏了就会出现「预览位置对了、导出回到右下角」。
 *
 * 放在这里的好处：Worker 与预览调的是同一个函数，单测也能直接覆盖。
 */
object BatchPositionResolver {

    /** 拖动时允许的坐标范围：留 5% 边距，避免水印被拖到完全贴边/出画。 */
    const val MIN_NORMALIZED = 0.05f
    const val MAX_NORMALIZED = 0.95f

    /** 把任意坐标夹到合法范围。 */
    fun clamp(value: Float): Float = value.coerceIn(MIN_NORMALIZED, MAX_NORMALIZED)

    /**
     * 应用文字水印位置覆盖。
     *
     * @param position 为 null 时原样返回（表示这张图沿用共享配置）
     */
    fun applyText(style: TextWatermarkStyle, position: ImagePosition?): TextWatermarkStyle {
        if (position == null) return style
        return style.copy(
            gravity = GravityPosition.CUSTOM,
            customX = clamp(position.x),
            customY = clamp(position.y)
        )
    }

    /** 应用图片水印位置覆盖（语义同 [applyText]）。 */
    fun applyImage(style: ImageWatermarkStyle, position: ImagePosition?): ImageWatermarkStyle {
        if (position == null) return style
        return style.copy(
            gravity = GravityPosition.CUSTOM,
            customX = clamp(position.x),
            customY = clamp(position.y)
        )
    }

    /**
     * 回到「九宫格默认位置」：把 gravity 还原成样式默认值（右下角）。
     *
     * 注意这里**只改 gravity**，不动 customX/customY ——
     * 因为 gravity 不是 CUSTOM 时它们根本不参与计算，留着可以当"上次自定义位置"
     * 的备忘（用户再切回 CUSTOM 时会回到那里）。
     */
    fun resetToDefault(style: TextWatermarkStyle): TextWatermarkStyle =
        style.copy(gravity = TextWatermarkStyle.Default.gravity)

    /** 图片水印版：[resetToDefault]。 */
    fun resetToDefault(style: ImageWatermarkStyle): ImageWatermarkStyle =
        style.copy(gravity = ImageWatermarkStyle.Default.gravity)
}
