package com.watermarkbox.ui.screen.presets

import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.screen.editor.toPanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设编辑页与参数面板之间的**映射契约**测试。
 *
 * 这些测试的价值在于锁定「两个页面共用同一个面板」这件事的**差异点**：
 * 编辑页要显示隐藏水印分区，预设页不该显示。如果哪天有人改坏了
 * `showInvisibleSection`，这里会立刻红。
 */
class PresetEditMappingTest {

    @Test
    fun `预设编辑页的面板状态不显示隐藏水印分区`() {
        val state = PresetEditUiState(
            loaded = true,
            name = "测试预设",
            watermarkType = WatermarkType.TEXT
        )
        val panel = state.toPanelState()
        assertFalse(
            "预设不含隐藏水印（属导出选项），该分区必须隐藏",
            panel.showInvisibleSection
        )
    }

    @Test
    fun `预设编辑页的面板状态不携带 logo 位图`() {
        val state = PresetEditUiState(loaded = true)
        assertNull(
            "本页无法选 logo，面板不该拿到位图",
            state.toPanelState().logoBitmap
        )
    }

    @Test
    fun `样式与类型被原样传递给面板`() {
        val textStyle = TextWatermarkStyle.Default.copy(
            text = "肆叶",
            gravity = GravityPosition.BOTTOM_RIGHT,
            rotationDegrees = 25f
        )
        val imageStyle = ImageWatermarkStyle.Default
        val state = PresetEditUiState(
            loaded = true,
            config = textStyle,
            imageConfig = imageStyle,
            watermarkType = WatermarkType.IMAGE
        )
        val panel = state.toPanelState()
        assertEquals(textStyle, panel.config)
        assertEquals(imageStyle, panel.imageConfig)
        assertEquals(WatermarkType.IMAGE, panel.watermarkType)
    }

    @Test
    fun `自定义字体列表被传递给面板`() {
        val state = PresetEditUiState(
            loaded = true,
            customFonts = listOf("font_a.ttf", "font_b.otf")
        )
        assertEquals(
            listOf("font_a.ttf", "font_b.otf"),
            state.toPanelState().customFonts
        )
    }

    /**
     * 编辑页与预设页的**关键差异**：同一个面板组件，两种宿主给不同的
     * `showInvisibleSection`。这条测试锁住"预设页不显示隐藏水印"的意图，
     * 防止将来有人图省事把两边统一成 true。
     */
    @Test
    fun `编辑页与预设页对隐藏水印分区的取值相反`() {
        val editorSide = com.watermarkbox.ui.screen.editor.WatermarkPanelState(
            showInvisibleSection = true
        )
        val presetSide = PresetEditUiState(loaded = true).toPanelState()
        assertTrue("编辑页必须显示隐藏水印分区", editorSide.showInvisibleSection)
        assertFalse("预设页必须隐藏该分区", presetSide.showInvisibleSection)
    }

    /**
     * 设置页与预设页的面板状态不一致，但**字体列表必须一致传递** ——
     * 否则用户在设置页导入的字体在预设里选不到。
     */
    @Test
    fun `隐藏水印分区隐藏时其它字段不受影响`() {
        val state = PresetEditUiState(
            loaded = true,
            customFonts = listOf("a.ttf")
        )
        val panel = state.toPanelState()
        assertTrue("字体列表不该被分区开关连带清空", panel.customFonts.isNotEmpty())
    }
}