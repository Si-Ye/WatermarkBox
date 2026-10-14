package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- L1：控制字符（XML 1.0 不允许）----

    @Test
    fun `控制字符被剔除 —— 否则整包会变成非法 XML`() {
        // XML 1.0 不允许 < 0x20 的控制字符（除 \t \n \r）。
        // 连 `&#x0;` 这种数字实体引用也非法（规范明确排除该区间）。
        // 若原样写入，别的工具解析时可能**直接丢弃**整个 XMP。
        val packed = XmpCodec.pack("WMB1:含\u0000控制\u0007字符")
        assertFalse("不该含 NUL", packed.contains('\u0000'))
        assertFalse("不该含 BEL", packed.contains('\u0007'))
        // 正常文本必须保留（别误伤）
        assertTrue("正常文字不该被误伤", packed.contains("含"))
        assertTrue(packed.contains("控制"))
    }

    @Test
    fun `允许的空白（tab 换行 回车）被保留`() {
        val packed = XmpCodec.pack("a\tb\nc\rd")
        assertTrue("tab 应保留", packed.contains("a\tb"))
        assertTrue("回车应保留", packed.contains("c\rd"))
    }

    @Test
    fun `剔除控制字符后仍能往返`() {
        val original = "WMB1:正常文本"
        assertEquals(original, XmpCodec.unpack(XmpCodec.pack(original)))
    }

    // ---- L2：unpack 用 indexOf 定位，补边界测试 ----

    @Test
    fun `内容里含闭标签字面量也不会提前截断`() {
        // `unpack` 用 indexOf 找 `</wmb:watermark>`，若内容里真出现这个串就会被截断。
        // 但 `escape` 会把内容里的 `<` 转成 `&lt;`，所以 **pack → unpack 往返是安全的**。
        // 这条测试把这个「隐式依赖」显式钉住：将来若有人动 escape，这里会先红。
        val tricky = "WMB1:前面</wmb:watermark>后面"
        assertEquals(tricky, XmpCodec.unpack(XmpCodec.pack(tricky)))
    }

    @Test
    fun `多个同名标签时取第一个`() {
        // 故意不做完整 XML 解析（避免引入解析器开销），所以行为定义为「取第一个」。
        val xmp = "<x:xmpmeta><wmb:watermark>第一个</wmb:watermark>" +
            "<wmb:watermark>第二个</wmb:watermark></x:xmpmeta>"
        assertEquals("第一个", XmpCodec.unpack(xmp))
    }

    @Test
    fun `标签在开头或在结尾都能取出`() {
        assertEquals("头", XmpCodec.unpack("<wmb:watermark>头</wmb:watermark>"))
        assertEquals(
            "尾",
            XmpCodec.unpack("<rdf>无关前缀</rdf><wmb:watermark>尾</wmb:watermark>")
        )
    }

    @Test
    fun `空内容返回空串而不是 null`() {
        // 空标签说明「有人签过，只是没写内容」，与「压根找不到标签」是两回事，
        // 不能都返回 null —— 否则检测端无法区分「没水印」和「水印是空的」。
        assertEquals("", XmpCodec.unpack("<wmb:watermark></wmb:watermark>"))
    }
}