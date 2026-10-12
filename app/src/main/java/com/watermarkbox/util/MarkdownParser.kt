package com.watermarkbox.util

/**
 * Markdown 解析的**纯逻辑**层（不依赖 Compose / Android，可直接单测）。
 *
 * 渲染层 [com.watermarkbox.ui.screen.update.MarkdownText] 只负责
 * 「把解析结果画出来」，所有判断逻辑都在这里 —— 这样逻辑可被
 * 单元测试覆盖，而不必依赖设备或 Compose 测试环境。
 *
 * 支持的语法见各类的 KDoc。
 */
object MarkdownParser {

    /** 一行的类型。渲染层据此选择不同的 Composable。 */
    enum class LineKind {
        /** ```` ``` ```` 围栏代码块的边界行。 */
        FENCE,

        /** 空行。 */
        BLANK,

        /** `---` / `***` / `___` 分隔线。 */
        HORIZONTAL_RULE,

        /** `#` ~ `######` 标题。 */
        HEADING,

        /** `> ` 引用。 */
        QUOTE,

        /** `| a | b |` 表格行。 */
        TABLE,

        /** `- ` / `* ` 无序列表。 */
        UNORDERED_LIST,

        /** `1. ` 有序列表。 */
        ORDERED_LIST,

        /** 普通段落。 */
        PARAGRAPH
    }

    /**
     * 判断一行的类型。
     *
     * ## 判断顺序很重要
     *
     * 必须**先**判断分隔线，再判断无序列表 —— 因为 `***` 同时满足
     * 「全是 * 且长度≥3」（分隔线）和「以 * 开头」（列表）两个条件。
     * 若顺序反了，`***` 会被当成带内容的列表项。
     *
     * 同理，空行必须在分隔线之前排除：空串 `""` 的 `all {}` 恒为 true，
     * 会误判成分隔线，渲染出一堆横线。
     */
    fun classify(line: String): LineKind {
        val t = line.trimEnd()

        // 围栏代码块边界：``` 或 ```kotlin
        if (t.trimStart().startsWith("```")) return LineKind.FENCE

        // 空行最先排除（否则空串会被 isHorizontalRule 误判）
        if (t.isBlank()) return LineKind.BLANK

        // 分隔线必须在无序列表之前判断
        if (isHorizontalRule(t)) return LineKind.HORIZONTAL_RULE

        if (t.startsWith("#")) return LineKind.HEADING
        if (t.startsWith(">")) return LineKind.QUOTE
        if (t.trimStart().startsWith("|")) return LineKind.TABLE
        if (isUnorderedListItem(t)) return LineKind.UNORDERED_LIST
        if (isOrderedListItem(t)) return LineKind.ORDERED_LIST

        return LineKind.PARAGRAPH
    }

    /** 标题层级：`## x` → 2；非标题返回 0。 */
    fun headingLevel(line: String): Int {
        if (!line.trimStart().startsWith("#")) return 0
        return line.trimStart().takeWhile { it == '#' }.length
    }

    /** 去掉标题的 `#` 前缀。 */
    fun headingContent(line: String): String =
        line.trimStart().dropWhile { it == '#' }.trim()

    /** 去掉引用的 `> ` 前缀。 */
    fun quoteContent(line: String): String =
        line.trimStart().removePrefix(">").trim()

    /** 去掉无序列表标记。 */
    fun unorderedContent(line: String): String =
        line.trimStart().removePrefix("- ").removePrefix("* ").trim()

    /** 有序列表：返回 `编号 to 内容`；非有序列表返回 null。 */
    fun orderedParts(line: String): Pair<String, String>? {
        if (!isOrderedListItem(line)) return null
        val t = line.trimStart()
        val marker = t.substringBefore(' ').trimEnd('.')
        val content = t.substringAfter(' ').trim()
        return marker to content
    }

    /**
     * 拆表格行。
     *
     * @return 单元格列表；若是分隔行（`|---|---|`）或不是表格行则返回 null
     */
    fun splitTableRow(line: String): List<String>? {
        if (!line.trimStart().startsWith("|")) return null
        val cells = line.trim().trim('|').split('|').map { it.trim() }
        val isSeparator = cells.all { c -> c.isEmpty() || c.all { it == '-' || it == ':' } }
        return if (isSeparator) null else cells
    }

    /** `***` / `---` / `___` 分隔线。 */
    fun isHorizontalRule(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        return t.all { it == '-' || it == '*' || it == '_' }
    }

    /** `- xxx` 或 `* xxx`。 */
    fun isUnorderedListItem(line: String): Boolean {
        val t = line.trimStart()
        return (t.startsWith("- ") || t.startsWith("* ")) && t.length > 2
    }

    /** `1. xxx`。 */
    fun isOrderedListItem(line: String): Boolean {
        val t = line.trimStart()
        val dot = t.indexOf('.')
        if (dot <= 0 || dot >= t.length - 1) return false
        if (t[dot + 1] != ' ') return false
        return t.substring(0, dot).all { it.isDigit() }
    }

    // ------------------------------------------------------------------
    // 行内解析
    // ------------------------------------------------------------------

    /** 行内片段：普通文本 / 加粗 / 代码。 */
    sealed interface Span {
        /** 普通文本。 */
        data class Text(val text: String) : Span

        /** `**加粗**`。 */
        data class Bold(val text: String) : Span

        /** `` `代码` ``。 */
        data class Code(val text: String) : Span
    }

    /**
     * 解析一行内的 Markdown 标记，产出片段列表。
     *
     * ## 为什么返回片段而非 AnnotatedString
     *
     * `AnnotatedString` 属于 Compose 类型，单测里不好断言。
     * 返回自定义的 [Span] 列表后，「**推荐**」会被解析成
     * `[Bold("推荐")]` —— 测试可以精确断言，渲染层再把它转成
     * `AnnotatedString`（见 MarkdownText 里的 `toAnnotatedString`）。
     *
     * ## 支持的标记
     *
     * - `**粗体**` / `__粗体__` → [Span.Bold]
     * - `` `代码` `` → [Span.Code]
     * - `[文字](链接)` → 只保留 `文字`（[Span.Text]，应用内不点外链）
     *
     * 未闭合的标记按普通文本处理（不吞掉字符）。
     */
    fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val sb = StringBuilder()
        var i = 0

        fun flush() {
            if (sb.isNotEmpty()) {
                spans.add(Span.Text(sb.toString()))
                sb.clear()
            }
        }

        while (i < text.length) {
            when {
                // **粗体** 或 __粗体__
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i) {
                        flush()
                        // 粗体内部可能还有代码等，递归解析后合并：
                        // 这里简化处理 —— 嵌套的标记统一并入粗体文本，
                        // release notes 里没有「粗体里套代码」的写法。
                        val inner = text.substring(i + 2, end)
                        spans.add(Span.Bold(stripMarkers(inner)))
                        i = end + 2
                    } else {
                        sb.append(text, i, i + 2)
                        i += 2
                    }
                }

                // `代码`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans.add(Span.Code(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        sb.append('`')
                        i++
                    }
                }

                // [文字](链接) → 只留文字
                text[i] == '[' -> {
                    val close = text.indexOf(']', i)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val parenClose = text.indexOf(')', close + 2)
                        if (parenClose > close) {
                            flush()
                            spans.add(Span.Text(text.substring(i + 1, close)))
                            i = parenClose + 1
                        } else {
                            sb.append(text[i]); i++
                        }
                    } else {
                        sb.append(text[i]); i++
                    }
                }

                else -> {
                    sb.append(text[i]); i++
                }
            }
        }
        flush()
        return spans
    }

    /** 去掉字符串里的行内标记，只保留纯文本（用于粗体内部的兜底）。 */
    private fun stripMarkers(s: String): String =
        parseInline(s).joinToString("") { span ->
            when (span) {
                is Span.Text -> span.text
                is Span.Bold -> span.text
                is Span.Code -> span.text
            }
        }
}