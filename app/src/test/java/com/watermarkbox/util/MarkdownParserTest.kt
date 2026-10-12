package com.watermarkbox.util

import com.watermarkbox.util.MarkdownParser.LineKind
import com.watermarkbox.util.MarkdownParser.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器单元测试 —— 用**真实的 v1.4.1 release notes 内容**。
 *
 * 覆盖两个曾经的线上 Bug：
 * 1. 围栏代码块未识别 → ` ``` ` 原样显示
 * 2. 表格单元格未解析行内语法 → `**推荐**`、`` `xxx.apk` `` 原样显示
 */
class MarkdownParserTest {

    // ------------------------------------------------------------------
    // 行类型判断
    // ------------------------------------------------------------------

    @Test
    fun `识别围栏代码块边界`() {
        assertEquals(LineKind.FENCE, MarkdownParser.classify("```"))
        assertEquals(LineKind.FENCE, MarkdownParser.classify("```kotlin"))
    }

    @Test
    fun `识别空行`() {
        assertEquals(LineKind.BLANK, MarkdownParser.classify(""))
        assertEquals(LineKind.BLANK, MarkdownParser.classify("   "))
    }

    @Test
    fun `识别分隔线`() {
        assertEquals(LineKind.HORIZONTAL_RULE, MarkdownParser.classify("---"))
        assertEquals(LineKind.HORIZONTAL_RULE, MarkdownParser.classify("***"))
    }

    @Test
    fun `空行不被误判为分隔线`() {
        // 关键回归：空串的 all{} 恒真，若顺序写错会渲染出一堆横线
        assertEquals(LineKind.BLANK, MarkdownParser.classify(""))
    }

    @Test
    fun `识别标题`() {
        assertEquals(LineKind.HEADING, MarkdownParser.classify("## 水印盒 v1.4.1"))
        assertEquals(LineKind.HEADING, MarkdownParser.classify("### 下载说明"))
    }

    @Test
    fun `识别引用`() {
        assertEquals(
            LineKind.QUOTE,
            MarkdownParser.classify("> **注意**：两个包签名不同。")
        )
    }

    @Test
    fun `识别表格`() {
        assertEquals(LineKind.TABLE, MarkdownParser.classify("| 文件 | 大小 | 适用 |"))
        assertEquals(LineKind.TABLE, MarkdownParser.classify("|---|---|---|"))
    }

    @Test
    fun `识别无序列表`() {
        assertEquals(
            LineKind.UNORDERED_LIST,
            MarkdownParser.classify("- **水印**：文字 / logo")
        )
    }

    @Test
    fun `识别有序列表`() {
        assertEquals(LineKind.ORDERED_LIST, MarkdownParser.classify("1. 第一步"))
        assertEquals(LineKind.ORDERED_LIST, MarkdownParser.classify("10. 第十步"))
    }

    @Test
    fun `识别普通段落`() {
        assertEquals(
            LineKind.PARAGRAPH,
            MarkdownParser.classify("轻量 Android 图片水印工具，支持实时预览。")
        )
    }

    // ------------------------------------------------------------------
    // 标题 / 引用 / 列表 内容提取
    // ------------------------------------------------------------------

    @Test
    fun `提取标题层级与内容`() {
        assertEquals(2, MarkdownParser.headingLevel("## 水印盒 v1.4.1"))
        assertEquals(3, MarkdownParser.headingLevel("### 下载说明"))
        assertEquals("水印盒 v1.4.1", MarkdownParser.headingContent("## 水印盒 v1.4.1"))
        assertEquals("下载说明", MarkdownParser.headingContent("###  下载说明"))
    }

    @Test
    fun `提取引用内容`() {
        assertEquals(
            "**注意**：两个包签名不同。",
            MarkdownParser.quoteContent("> **注意**：两个包签名不同。")
        )
    }

    @Test
    fun `提取列表内容`() {
        assertEquals(
            "**水印**：文字 / logo",
            MarkdownParser.unorderedContent("- **水印**：文字 / logo")
        )
    }

    @Test
    fun `提取有序列表编号与内容`() {
        val parts = MarkdownParser.orderedParts("1. 第一步")
        assertEquals("1", parts?.first)
        assertEquals("第一步", parts?.second)
    }

    // ------------------------------------------------------------------
    // 表格拆分
    // ------------------------------------------------------------------

    @Test
    fun `表格分隔行返回 null`() {
        assertNull(MarkdownParser.splitTableRow("|---|---|---|"))
    }

    @Test
    fun `表格数据行拆分出单元格`() {
        val cells = MarkdownParser.splitTableRow(
            "| `WatermarkBox-1.4.1-release.apk` | 2.8 MB | **推荐**，正常使用装这个 |"
        )
        assertEquals(3, cells?.size)
        assertEquals("`WatermarkBox-1.4.1-release.apk`", cells?.get(0))
        assertEquals("2.8 MB", cells?.get(1))
        assertEquals("**推荐**，正常使用装这个", cells?.get(2))
    }

    @Test
    fun `非表格行返回 null`() {
        assertNull(MarkdownParser.splitTableRow("普通文本"))
    }

    // ------------------------------------------------------------------
    // 行内解析 —— 这是「加粗/代码不渲染」的直接原因所在
    // ------------------------------------------------------------------

    @Test
    fun `解析加粗`() {
        val spans = MarkdownParser.parseInline("**推荐**")
        assertEquals(1, spans.size)
        assertEquals(Span.Bold("推荐"), spans[0])
    }

    @Test
    fun `解析加粗与尾部文字`() {
        val spans = MarkdownParser.parseInline("**推荐**，正常使用装这个")
        assertEquals(2, spans.size)
        assertEquals(Span.Bold("推荐"), spans[0])
        assertEquals(Span.Text("，正常使用装这个"), spans[1])
    }

    @Test
    fun `解析行内代码`() {
        val spans = MarkdownParser.parseInline("`WatermarkBox-1.4.1-release.apk`")
        assertEquals(1, spans.size)
        assertEquals(Span.Code("WatermarkBox-1.4.1-release.apk"), spans[0])
    }

    @Test
    fun `解析表格单元格里的代码`() {
        // 这是「表格内代码没渲染」的回归测试
        val spans = MarkdownParser.parseInline("`xxx.apk`")
        assertEquals(1, spans.size)
        assertTrue(spans[0] is Span.Code)
    }

    @Test
    fun `解析代码与普通文本混合`() {
        val spans = MarkdownParser.parseInline("文件 `a.apk` 已就绪")
        assertEquals(3, spans.size)
        assertEquals(Span.Text("文件 "), spans[0])
        assertEquals(Span.Code("a.apk"), spans[1])
        assertEquals(Span.Text(" 已就绪"), spans[2])
    }

    @Test
    fun `解析下划线加粗`() {
        val spans = MarkdownParser.parseInline("__重要__")
        assertEquals(1, spans.size)
        assertEquals(Span.Bold("重要"), spans[0])
    }

    @Test
    fun `解析链接只保留文字`() {
        val spans = MarkdownParser.parseInline("见[文档](https://example.com)")
        assertEquals(2, spans.size)
        assertEquals(Span.Text("见"), spans[0])
        assertEquals(Span.Text("文档"), spans[1])
    }

    @Test
    fun `未闭合的加粗原样保留`() {
        val spans = MarkdownParser.parseInline("**未闭合")
        // 不应吞掉字符，原文照旧
        val joined = spans.joinToString("") {
            when (it) {
                is Span.Text -> it.text
                is Span.Bold -> it.text
                is Span.Code -> it.text
            }
        }
        assertEquals("**未闭合", joined)
    }

    @Test
    fun `纯文本不产生样式片段`() {
        val spans = MarkdownParser.parseInline("Android 8.0（API 26）及以上")
        assertEquals(1, spans.size)
        assertTrue(spans[0] is Span.Text)
    }

    // ------------------------------------------------------------------
    // 整篇 release notes 逐行验收
    // ------------------------------------------------------------------

    @Test
    fun `真实 release notes 每一行都能正确分类`() {
        val notes = """
            ## 水印盒 v1.4.1

            轻量 Android 图片水印工具，支持实时预览、批量处理与 DCT 频域隐藏水印溯源。

            ### 下载说明

            | 文件 | 大小 | 适用 |
            |---|---|---|
            | `WatermarkBox-1.4.1-release.apk` | 2.8 MB | **推荐**，正常使用装这个 |
            | `WatermarkBox-1.4.1-debug.apk` | 21 MB | 调试用，含额外日志 |

            > **注意**：两个包签名不同。

            - **水印**：文字 / logo、字体
            - 完全离线，不联网

            ### 校验值（SHA-256）

            ```
            73592e0c7d715856  WatermarkBox-1.4.1-release.apk
            ```

            ### 系统要求

            Android 8.0（API 26）及以上
        """.trimIndent()

        val lines = notes.split("\n")

        // 用「内容包含」而非硬编码行号：行号会因缩进/空行调整而错位，
        // 按内容断言更健壮，意图也更清晰。
        fun kindOf(predicate: (String) -> Boolean): LineKind {
            val line = lines.firstOrNull(predicate)
                ?: error("找不到匹配的行")
            return MarkdownParser.classify(line)
        }

        assertEquals(LineKind.HEADING, kindOf { it.startsWith("## 水印盒") })
        assertEquals(LineKind.PARAGRAPH, kindOf { it.contains("轻量 Android 图片水印工具") })
        assertEquals(LineKind.HEADING, kindOf { it.startsWith("### 下载说明") })
        assertEquals(LineKind.TABLE, kindOf { it.contains("WatermarkBox-1.4.1-release.apk") })
        assertEquals(LineKind.QUOTE, kindOf { it.startsWith(">") })
        assertEquals(LineKind.UNORDERED_LIST, kindOf { it.startsWith("- **水印**") })

        // 代码块边界必须能识别（否则 SHA 那些行会原样显示反引号）
        assertTrue(lines.any { MarkdownParser.classify(it) == LineKind.FENCE })

        // 表格数据行里的加粗与代码必须能被解析出样式片段
        val tableLine = lines.first { it.contains("release.apk") }
        val cells = MarkdownParser.splitTableRow(tableLine)!!
        assertEquals(3, cells.size)
        // 第 1 格是 `代码`
        assertTrue(MarkdownParser.parseInline(cells[0]).first() is Span.Code)
        // 第 3 格含 **加粗**
        assertTrue(MarkdownParser.parseInline(cells[2]).any { it is Span.Bold })
    }
}