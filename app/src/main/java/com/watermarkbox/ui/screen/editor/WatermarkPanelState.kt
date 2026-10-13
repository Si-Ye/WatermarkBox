package com.watermarkbox.ui.screen.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.InvisibleWatermarkConfig
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType

/**
 * 水印参数面板（[WatermarkPanel]）所需的**全部只读数据**。
 *
 * ## 为什么单独抽出来
 *
 * 面板最初直接读 [EditorUiState]，但那个类有 20+ 个字段，其中
 * `imageUri` / `previewBitmap` / `isExporting` / `panelFraction`…
 * 全都是**编辑页专有**、面板根本不用的。
 *
 * 预设编辑页没有图片，自然也没有 `EditorUiState`。如果面板坚持签名为
 * `EditorUiState`，预设页就只能造一个「字段全填假值」的假 state ——
 * 那是典型的类型误用：**类型系统不再表达真实约束**，
 * 以后谁给面板加个字段，预设页那边就会静默拿到假值。
 *
 * 因此这里只列面板**真正需要**的 6 项。两种宿主各自做一次映射：
 *
 * - 编辑页：`EditorUiState.toPanelState()`
 * - 预设页：`PresetEditUiState.toPanelState()`
 *
 * 映射是纯函数，可单测；新增面板字段时编译器会强制两边都处理。
 *
 * @property watermarkType    当前编辑的是文字还是图片水印
 * @property config           文字水印样式
 * @property imageConfig      图片水印样式
 * @property logoBitmap       已解码的 logo（仅用于面板里显示缩略图；可空）
 * @property customFonts      已导入的自定义字体文件名（供下拉选择）
 * @property invisibleConfig  隐藏水印配置
 * @property showInvisibleSection 是否显示「隐藏水印」分区。
 *   隐藏水印属于**导出选项**，预设里不存它，所以预设编辑页传 `false`。
 *   用标志位而非「传 null 就是不支持」，是为了让 UI 语义显式：
 *   "这里不该出现这个分区" 和 "这个分区暂时没有数据" 是两回事。
 */
/**
 * ## 为什么标 `@Immutable`
 *
 * 类里有 `logoBitmap: Bitmap?`，而 `Bitmap` 是外部类型、编译器无法证明其稳定性 ——
 * 一颗位图就把整个类拖成 unstable，于是 [WatermarkPanel] 不可跳过
 * （每次重组都要重建整个面板，拖动时每帧一次）。
 *
 * `@Immutable` 在本类是成立的：这是**只读快照**，
 * 位图变更时调用方会换一个新实例进来，不会原地修改。
 */
@Immutable
data class WatermarkPanelState(
    val watermarkType: WatermarkType = WatermarkType.TEXT,
    val config: TextWatermarkStyle = TextWatermarkStyle.Default,
    val imageConfig: ImageWatermarkStyle = ImageWatermarkStyle.Default,
    val logoBitmap: Bitmap? = null,
    val customFonts: List<String> = emptyList(),
    val invisibleConfig: InvisibleWatermarkConfig = InvisibleWatermarkConfig.Default,
    val showInvisibleSection: Boolean = true
)

/**
 * [EditorUiState] → [WatermarkPanelState] 的映射（编辑页用）。
 *
 * 只取面板需要的字段，编辑页独有的图片 / 导出信息在这里被自然丢弃。
 */
fun EditorUiState.toPanelState(): WatermarkPanelState = WatermarkPanelState(
    watermarkType = watermarkType,
    config = config,
    imageConfig = imageConfig,
    logoBitmap = logoBitmap,
    customFonts = customFonts,
    invisibleConfig = invisibleConfig,
    showInvisibleSection = true
)