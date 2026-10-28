package com.watermarkbox.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出设置的序列化兼容性测试。
 *
 * 重点守一件事：**老安装升级后不能因为迁移而报错或丢配置** ——
 * `ExportSettings` 是整块 JSON 存在 DataStore 里的，加字段必须能读旧数据。
 */
class ExportSettingsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `老格式 JSON 缺 indexStart 时回落为 1`() {
        // 升级前写下的真实形态：没有 indexStart 字段
        val legacy = """
            {
              "format": "JPEG",
              "jpegQuality": 92,
              "defaultAlpha": 0.6,
              "fileNameTemplate": "{原文件名}_wm",
              "preserveExif": true,
              "defaultWatermarkText": "hello",
              "invisibleDctByDefault": false
            }
        """.trimIndent()

        val settings = json.decodeFromString(ExportSettings.serializer(), legacy)

        assertEquals(1, settings.indexStart)
        // 老字段一个都不能丢
        assertEquals(92, settings.jpegQuality)
        assertEquals(0.6f, settings.defaultAlpha, 0.0001f)
        assertEquals("{原文件名}_wm", settings.fileNameTemplate)
        assertTrue(settings.preserveExif)
        assertEquals("hello", settings.defaultWatermarkText)
    }

    @Test
    fun `新格式 JSON 能往返`() {
        val original = ExportSettings(
            fileNameTemplate = "{原文件名}_{index:04}",
            indexStart = 37
        )
        val text = json.encodeToString(ExportSettings.serializer(), original)
        val decoded = json.decodeFromString(ExportSettings.serializer(), text)

        assertEquals(original, decoded)
        assertEquals(37, decoded.indexStart)
    }

    @Test
    fun `未来版本多出的字段不影响解析`() {
        val fromFuture = """{"format":"PNG","indexStart":5,"someFutureField":{"a":1}}"""
        val settings = json.decodeFromString(ExportSettings.serializer(), fromFuture)

        assertEquals(ExportFormat.PNG, settings.format)
        assertEquals(5, settings.indexStart)
    }

    @Test
    fun `默认构造的起始号为 1 且模板不含序号`() {
        val settings = ExportSettings()
        assertEquals(1, settings.indexStart)
        assertEquals("{原文件名}_watermarked", settings.fileNameTemplate)
    }

    // ------------------------------------------------------------------
    // maxEdge（导出尺寸档位）—— 2.3.1 新增
    // ------------------------------------------------------------------

    /**
     * 老安装（2.3.0 及更早）存下的 JSON 没有 `maxEdge`，
     * 升级后必须回落为 **原图**，而不是某个缩小档。
     *
     * 这是「不要擅自降低老用户画质」的底线：若回落成 2048，
     * 用户升级后所有导出会**静默变小**，且他根本不知道发生了什么。
     */
    @Test
    fun `老格式 JSON 缺 maxEdge 时回落为原图`() {
        val legacy = """
            {
              "format": "JPEG",
              "jpegQuality": 92,
              "fileNameTemplate": "{原文件名}_wm",
              "indexStart": 3
            }
        """.trimIndent()

        val settings = json.decodeFromString(ExportSettings.serializer(), legacy)

        assertEquals(ExportMaxEdge.ORIGINAL, settings.maxEdge)
        assertEquals(0, settings.maxEdge.edge)
        assertEquals("原图档不缩放", false, settings.maxEdge.scalesDown)
        // 老字段仍然完好
        assertEquals(3, settings.indexStart)
        assertEquals(92, settings.jpegQuality)
    }

    @Test
    fun `档位能往返且按常量名序列化`() {
        val original = ExportSettings(maxEdge = ExportMaxEdge.P2048)
        val text = json.encodeToString(ExportSettings.serializer(), original)

        // 按枚举常量名存（不是序号），改名会立刻暴露而不是静默错位
        assertTrue("应存常量名：$text", text.contains("\"P2048\""))

        val decoded = json.decodeFromString(ExportSettings.serializer(), text)
        assertEquals(ExportMaxEdge.P2048, decoded.maxEdge)
        assertEquals(original, decoded)
    }

    @Test
    fun `各档位的长边取值固定`() {
        assertEquals(0, ExportMaxEdge.ORIGINAL.edge)
        assertEquals(4096, ExportMaxEdge.P4096.edge)
        assertEquals(2048, ExportMaxEdge.P2048.edge)
        assertEquals(1080, ExportMaxEdge.P1080.edge)

        // 只有「原图」不缩放，其余三档都要缩放
        assertEquals(1, ExportMaxEdge.entries.count { !it.scalesDown })
    }
}
