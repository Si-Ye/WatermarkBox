package com.watermarkbox.ui.screen.editor

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.WatermarkType

/**
 * 水印参数面板（[WatermarkPanel]）对宿主的依赖契约。
 *
 * ## 为什么需要这个接口
 *
 * 参数面板最初直接依赖 [EditorViewModel]，导致它只能用在「编辑页」。
 * 但预设编辑页（`PresetsEditorScreen`）需要**完全相同的参数 UI**，却
 * 不需要编辑页的图片加载 / 导出等能力。
 *
 * 与其把 [EditorViewModel] 塞给预设页（会带来一堆无意义的依赖），
 * 不如把面板真正用到的东西抽成这个接口：
 *
 * - **编辑页**：[EditorViewModel] 直接实现它（方法本来就都有）
 * - **预设编辑页**：`PresetEditViewModel` 实现它，内部只维护一份配置
 *
 * ## 维护须知
 *
 * 往面板里**新增参数项**时，必须同步在此接口加方法 —— 否则编译失败。
 * 这是刻意的设计：让「面板 ↔ 宿主」的契约始终显式、可编译期校验，
 * 避免出现「编辑页能改、预设页改不了」的不一致。
 *
 * @see WatermarkPanel
 * @see com.watermarkbox.ui.screen.presets.PresetEditViewModel
 */
/**
 * ## 为什么标 `@Stable`
 *
 * 这是个**只有回调方法的接口**。Compose 编译器分析不了接口的实现，
 * 默认按 unstable 处理 —— 后果是所有把本接口当参数的 Composable
 * （首当其冲的是 [WatermarkPanel]）都**不可跳过**，每次重组都要重跑整个面板。
 *
 * 面板里有几十个控件，而拖动面板高度时每帧都会重组 ——
 * 于是每帧全量重建一次面板，手感就是「拖起来发涩」。
 *
 * 标注 `@Stable` 是安全的：实现只有 ViewModel 一个，实例恒定、
 * `equals` 就是引用相等；真正的状态变化走 StateFlow 通知，不靠这儿。
 */
@Stable
interface WatermarkPanelHost {

    // ---------------- 水印类型 ----------------

    /** 切换文字 / 图片水印。 */
    fun updateWatermarkType(type: WatermarkType)

    // ---------------- 文字水印 ----------------

    /** 水印文字内容。 */
    fun updateText(text: String)

    /** 字号（相对图片宽度的百分比）。 */
    fun updateFontSize(size: Float)

    /** 字体。 */
    fun updateFontFamily(family: FontFamilyOption)

    /** 文字颜色。 */
    fun updateColor(color: Color)

    /** 加粗开关。 */
    fun toggleBold()

    /** 斜体开关。 */
    fun toggleItalic()

    /** 描边开关。 */
    fun toggleStroke()

    /** 描边宽度。 */
    fun updateStrokeWidth(width: Float)

    /** 描边颜色。 */
    fun updateStrokeColor(color: Color)

    /** 阴影开关。 */
    fun toggleShadow()

    /** 阴影半径。 */
    fun updateShadowRadius(radius: Float)

    /** 布局：单个 / 平铺。 */
    fun updateLayoutMode(mode: LayoutMode)

    /** 平铺数量。 */
    fun updateTileCount(count: Int)

    /** 旋转角度。 */
    fun updateRotation(degrees: Float)

    /** 透明度。 */
    fun updateAlpha(alpha: Float)

    /** 位置（九宫格）。 */
    fun updateGravity(gravity: GravityPosition)

    /** 边距。 */
    fun updateMargin(margin: Float)

    // ---------------- 图片水印 ----------------

    /** logo 宽度百分比。 */
    fun updateLogoWidth(percent: Float)

    /** logo 布局模式。 */
    fun updateImageLayoutMode(mode: LayoutMode)

    /** logo 平铺数量。 */
    fun updateImageTileCount(count: Int)

    /** logo 旋转角度。 */
    fun updateImageRotation(degrees: Float)

    /** logo 透明度。 */
    fun updateImageAlpha(alpha: Float)

    /** logo 位置。 */
    fun updateImageGravity(gravity: GravityPosition)

    /** logo 边距。 */
    fun updateImageMargin(margin: Float)

    /** logo 是否保持宽高比。 */
    fun togglePreserveAspectRatio()

    // ---------------- 自定义字体 ----------------

    /** 选中某个已导入的自定义字体。 */
    fun selectCustomFont(fileName: String)

    /** 删除某个已导入的自定义字体。 */
    fun deleteCustomFont(fileName: String)

    // ---------------- logo ----------------

    /** 清除已选 logo。 */
    fun clearLogo()

    // ---------------- 隐藏水印 ----------------

    /** 隐藏水印总开关。 */
    fun toggleInvisibleWatermark()

    /** 隐藏水印内容。 */
    fun updateInvisibleContent(text: String)

    /** 隐藏水印内容来源（自定义 / 设备 ID+时间戳）。 */
    fun updateInvisibleSource(source: InvisibleSource)

    /** 隐藏水印强度。 */
    fun updateInvisibleStrength(strength: Int)

    /**
     * 是否启用 EXIF 通道（元数据写入）。
     *
     * 零画质代价，但只在「图片未被压缩 / 转格式」时可读。
     * 见 [com.watermarkbox.data.model.InvisibleWatermarkConfig.exifEnabled]。
     */
    fun toggleInvisibleExif()

    /**
     * 是否启用 DCT 频域嵌入。
     *
     * 有画质代价（±3/255），但能扛 JPEG 重压缩。
     * 见 [com.watermarkbox.data.model.InvisibleWatermarkConfig.dctEnabled]。
     */
    fun toggleInvisibleDct()
}
