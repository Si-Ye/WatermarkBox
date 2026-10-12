package com.watermarkbox.worker

import androidx.compose.ui.graphics.Color
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkColor
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配置序列化往返测试（单元测试，纯 JVM）。
 *
 * 重要性：预设持久化与批量 Worker 的输入都依赖这套 JSON 编解码。
 * 一旦某字段序列化失败，用户会丢配置 —— 因此必须逐字段验证。
 */
class JsonCodecTest {

    // =================================================================
    // 文字水印
    // =================================================================

    @Test
    fun `文字水印配置完整往返`() {
        val original = TextWatermarkStyle(
            text = "测试 {date}",
            fontSize = 48f,
            fontFamily = FontFamilyOption.SERIF,
            customFontName = "myfont.ttf",
            color = WatermarkColor(Color(0xFF123456)),
            bold = true,
            italic = true,
            strokeEnabled = true,
            strokeWidth = 3.5f,
            strokeColor = WatermarkColor(Color.Red),
            shadowEnabled = true,
            shadowRadius = 8f,
            shadowColor = WatermarkColor(Color.Blue),
            layoutMode = LayoutMode.TILED,
            tileCount = 7,
            rotationDegrees = -33f,
            alpha = 0.42f,
            gravity = GravityPosition.BOTTOM_CENTER,
            margin = 77f
        )

        val json = JsonCodec.encodeTextStyle(original)
        val restored = JsonCodec.decodeTextStyle(json)

        assertEquals(original, restored)
    }

    @Test
    fun `文字水印颜色 ARGB 被精确保留`() {
        val color = Color(0xFFA1B2C3)
        val original = TextWatermarkStyle(color = WatermarkColor(color))
        val restored = JsonCodec.decodeTextStyle(JsonCodec.encodeTextStyle(original))

        assertEquals(0xFFA1B2C3.toInt(), restored.color.argb)
    }

    @Test
    fun `文字水印脏数据回退到默认值`() {
        val restored = JsonCodec.decodeTextStyle("{ not valid json ][")
        assertEquals(TextWatermarkStyle.Default, restored)
    }

    @Test
    fun `文字水印 null 输入回退到默认值`() {
        assertEquals(TextWatermarkStyle.Default, JsonCodec.decodeTextStyle(null))
        assertEquals(TextWatermarkStyle.Default, JsonCodec.decodeTextStyle(""))
    }

    // =================================================================
    // 图片水印
    // =================================================================

    @Test
    fun `图片水印配置完整往返`() {
        val original = ImageWatermarkStyle(
            logoUri = "content://media/1",
            widthPercent = 0.35f,
            layoutMode = LayoutMode.DIAGONAL,
            tileCount = 12,
            rotationDegrees = 25f,
            alpha = 0.55f,
            gravity = GravityPosition.TOP_LEFT,
            margin = 90f,
            preserveAspectRatio = false
        )

        val json = JsonCodec.encodeImageStyle(original)
        val restored = JsonCodec.decodeImageStyle(json)

        assertEquals(original, restored)
    }

    @Test
    fun `图片水印 null 与脏数据回退默认`() {
        assertEquals(ImageWatermarkStyle.Default, JsonCodec.decodeImageStyle(null))
        assertEquals(ImageWatermarkStyle.Default, JsonCodec.decodeImageStyle("bad{"))
    }

    // =================================================================
    // 导出设置
    // =================================================================

    @Test
    fun `导出设置完整往返`() {
        val original = ExportSettings(
            format = ExportFormat.PNG,
            jpegQuality = 88,
            defaultAlpha = 0.33f,
            fileNameTemplate = "{原文件名}_{index}",
            customOutputDir = "Pictures/我的水印",
            preserveExif = true
        )

        val restored = JsonCodec.decodeExportSettings(
            JsonCodec.encodeExportSettings(original)
        )

        assertEquals(original, restored)
    }

    @Test
    fun `导出设置默认值往返`() {
        val original = ExportSettings()
        val restored = JsonCodec.decodeExportSettings(
            JsonCodec.encodeExportSettings(original)
        )
        assertEquals(original, restored)
        assertEquals(ExportFormat.JPEG, restored.format)
        assertEquals(95, restored.jpegQuality)
    }

    // =================================================================
    // 向前兼容：旧数据缺少新字段
    // =================================================================

    @Test
    fun `旧版文字水印 JSON 缺少新字段时可正常解析`() {
        // 模拟早期版本只存了部分字段
        val legacyJson = """{"text":"旧数据","fontSize":30.0,"alpha":0.5}"""
        val restored = JsonCodec.decodeTextStyle(legacyJson)

        assertEquals("旧数据", restored.text)
        assertEquals(30f, restored.fontSize, 0.001f)
        assertEquals(0.5f, restored.alpha, 0.001f)
        // 缺失字段应为默认值
        assertEquals(LayoutMode.SINGLE, restored.layoutMode)
        assertEquals(GravityPosition.BOTTOM_RIGHT, restored.gravity)
    }

    @Test
    fun `未知字段被忽略而不报错`() {
        val futureJson = """{"text":"新版本","futureField123":"x","alpha":0.9}"""
        val restored = JsonCodec.decodeTextStyle(futureJson)
        assertEquals("新版本", restored.text)
        assertEquals(0.9f, restored.alpha, 0.001f)
    }

    // =================================================================
    // 预设
    // =================================================================

    @Test
    fun `预设完整往返（含两套样式）`() {
        val preset = WatermarkPreset(
            id = "123",
            name = "我的预设",
            createdAt = 1700000000000L,
            textStyle = TextWatermarkStyle(text = "A", alpha = 0.66f),
            imageStyle = ImageWatermarkStyle(widthPercent = 0.25f),
            type = WatermarkType.IMAGE
        )

        val json = Json.encodeToString(WatermarkPreset.serializer(), preset)
        val restored = Json.decodeFromString(WatermarkPreset.serializer(), json)

        assertEquals(preset, restored)
    }

    @Test
    fun `预设列表往返保持顺序与内容`() {
        val list = listOf(
            WatermarkPreset("1", "A", 100L, TextWatermarkStyle(), ImageWatermarkStyle(), WatermarkType.TEXT),
            WatermarkPreset("2", "B", 200L, TextWatermarkStyle(), ImageWatermarkStyle(), WatermarkType.IMAGE)
        )

        val json = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(WatermarkPreset.serializer()),
            list
        )
        val restored = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WatermarkPreset.serializer()),
            json
        )

        assertEquals(2, restored.size)
        assertEquals("A", restored[0].name)
        assertEquals(WatermarkType.IMAGE, restored[1].type)
    }

    // =================================================================
    // 枚举完整性
    // =================================================================

    @Test
    fun `全部布局模式可序列化`() {
        LayoutMode.entries.forEach { mode ->
            val style = TextWatermarkStyle(layoutMode = mode)
            val restored = JsonCodec.decodeTextStyle(JsonCodec.encodeTextStyle(style))
            assertEquals("布局 $mode 往返失败", mode, restored.layoutMode)
        }
    }

    @Test
    fun `全部九宫格位置可序列化`() {
        GravityPosition.entries.forEach { gravity ->
            val style = TextWatermarkStyle(gravity = gravity)
            val restored = JsonCodec.decodeTextStyle(JsonCodec.encodeTextStyle(style))
            assertEquals("位置 $gravity 往返失败", gravity, restored.gravity)
        }
    }

    @Test
    fun `全部导出格式可序列化`() {
        ExportFormat.entries.forEach { format ->
            val settings = ExportSettings(format = format)
            val restored = JsonCodec.decodeExportSettings(
                JsonCodec.encodeExportSettings(settings)
            )
            assertEquals(format, restored.format)
        }
    }
}