package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XmpCodec] 单元测试。
 *
 * XMP 是 XML，**转义是最容易出错的地方** —— 水印内容里出现
 * `<` 或 `&` 时若没处理好，整包 XMP 会变成非法 XML，
 * 对方工具解析失败后可能连整块元数据一起丢掉。
 */
class XmpCodecTest {

    @Test
    fun `往返保持普通内容`() {
        val content = "WMB1:肆叶"
        assertEquals(content, XmpCodec.unpack(XmpCodec.pack(content)))
    }

    @Test
    fun `打包结果是合法 XML 形态`() {
        val xmp = XmpCodec.pack("WMB1:x")
        assertTrue("应含 xpacket 头", xmp.contains("<?xpacket begin="))
        assertTrue("应含 xpacket 尾", xmp.contains("<?xpacket end="))
        assertTrue("应含我们的字段", xmp.contains(XmpCodec.FIELD_TAG))
        assertTrue("应闭合", xmp.contains("</wmb:watermark>"))
    }

    @Test
    fun `尖括号与和号被转义`() {
        val content = "a<b&c>d"
        val xmp = XmpCodec.pack(content)
        assertTrue("原文的 < 不能直接出现在 XML 里", !xmp.contains("a<b"))
        assertTrue("应被转义", xmp.contains("&lt;"))
        assertTrue("应被转义", xmp.contains("&amp;"))
        assertEquals("取回后必须与原文完全一致", content, XmpCodec.unpack(xmp))
    }

    @Test
    fun `引号被转义且能还原`() {
        val content = """他说"你好"和'再见'"""
        assertEquals(content, XmpCodec.unpack(XmpCodec.pack(content)))
    }

    @Test
    fun `不会二次解码 —— 内容本身含实体样式文本`() {
        // 内容里就有字面的 "&lt;"，处理不当会在还原时变成 "<"
        val content = "WMB1:&lt;不是标签&gt;"
        assertEquals(content, XmpCodec.unpack(XmpCodec.pack(content)))
    }

    @Test
    fun `中文与 emoji 完整保留`() {
        val content = "WMB1:水印盒🎨-溯源"
        assertEquals(content, XmpCodec.unpack(XmpCodec.pack(content)))
    }

    @Test
    fun `带 Base64 的 EXIF 中转格式也能装进 XMP`() {
        // 实际写入 XMP 的就是 ExifTextCodec.encode 之后的 ASCII 串
        val encoded = ExifTextCodec.encode("WMB1:中文内容")
        assertEquals(encoded, XmpCodec.unpack(XmpCodec.pack(encoded)))
    }

    @Test
    fun `别人的 XMP 取不出内容`() {
        val foreign = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:creator>别人</dc:creator>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        assertNull(XmpCodec.unpack(foreign))
    }

    @Test
    fun `空输入与 null 都返回 null`() {
        assertNull(XmpCodec.unpack(null))
        assertNull(XmpCodec.unpack(""))
        assertNull(XmpCodec.unpack("   "))
    }

    @Test
    fun `字段被别的工具挪到别的节点下仍能取回`() {
        // 我们按标签定位，不依赖节点层级 —— 对方重排结构也能读出来
        val reshuffled = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <other>随便什么</other>
              <wmb:watermark>WMB1:还在</wmb:watermark>
            </x:xmpmeta>
        """.trimIndent()
        assertEquals("WMB1:还在", XmpCodec.unpack(reshuffled))
    }

    @Test
    fun `只有开标签没有闭标签时返回 null 而不是崩`() {
        assertNull(XmpCodec.unpack("<wmb:watermark>开了没关"))
    }
}