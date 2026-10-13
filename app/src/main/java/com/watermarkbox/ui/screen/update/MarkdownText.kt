package com.watermarkbox.ui.screen.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.watermarkbox.util.MarkdownParser
import com.watermarkbox.util.MarkdownParser.LineKind

/**
 * 把 GitHub Release 的 Markdown 说明渲染成 Compose UI。
 *
 * ## 职责划分
 *
 * - **解析**（判断行类型、拆行内标记）在 [MarkdownParser]（纯逻辑，可单测）
 * - **渲染**（本文件）：只把解析结果画出来，不包含任何判断逻辑
 *
 * 这样「加粗/代码块没渲染」这类问题可以在单测里被发现，
 * 而不必每次都装到设备上肉眼验证。
 *
 * ## 为什么不引入 Markdown 库
 *
 * `compose-markdown` / `Markwon` 会带来几百 KB 体积，
 * 而我们只需渲染 release notes，手写足够且零依赖。
 *
 * ## 两个曾经的 Bug（已修）
 *
 * 1. **围栏代码块未实现** —— release notes 的 SHA-256 校验值用
 *    ```` ``` ```` 包裹，早期版本把三反引号原样显示。
 * 2. **表格单元格未解析行内语法** —— 早期把表格行 join 成字符串
 *    直接 Text()，格内的 `**推荐**`、`` `xxx.apk` `` 全成了字面量。
 *    现在逐格解析。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val lines = markdown.replace("\r\n", "\n").split("\n")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 围栏代码块：连续收集到下一个 ``` 为止
            if (MarkdownParser.classify(line) == LineKind.FENCE) {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && MarkdownParser.classify(lines[i]) != LineKind.FENCE) {
                    code.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // 跳过结束的 ```

                CodeBlock(code.joinToString("\n"))
                continue
            }

            when (MarkdownParser.classify(line)) {
                LineKind.FENCE -> Unit // 上面已处理

                LineKind.BLANK -> Spacer(Modifier.height(4.dp))

                LineKind.HORIZONTAL_RULE -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                LineKind.HEADING -> {
                    val level = MarkdownParser.headingLevel(line)
                    val style = when (level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        3 -> MaterialTheme.typography.bodyLarge
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    Text(
                        text = MarkdownParser.headingContent(line).toAnnotatedString(),
                        style = style,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                LineKind.QUOTE -> Text(
                    text = MarkdownParser.quoteContent(line).toAnnotatedString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )

                LineKind.TABLE -> {
                    // 逐格渲染：每格都解析行内语法，修复「表格内加粗不显示」
                    val cells = MarkdownParser.splitTableRow(line)
                    if (cells != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            cells.forEach { cell ->
                                Text(
                                    text = cell.toAnnotatedString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                LineKind.UNORDERED_LIST -> {
                    val content = MarkdownParser.unorderedContent(line)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 嵌套列表按层缩进 —— 否则父项与子项并排，层级直接丢失
                            .padding(start = (MarkdownParser.listIndent(line) * 16).dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            text = content.toAnnotatedString(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                LineKind.ORDERED_LIST -> {
                    val parts = MarkdownParser.orderedParts(line)
                    if (parts != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // 同无序列表：保留原文的缩进层级
                                .padding(start = (MarkdownParser.listIndent(line) * 16).dp)
                        ) {
                            Text(
                                text = "${parts.first}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                text = parts.second.toAnnotatedString(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                LineKind.PARAGRAPH -> Text(
                    text = line.trimEnd().toAnnotatedString(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            i++
        }
    }
}

/** 代码块：等宽 + 底色 + 圆角，保留原始换行与空格。 */
@Composable
private fun CodeBlock(code: String) {
    Text(
        text = code,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/**
 * 把解析出的片段列表转成 [AnnotatedString]。
 *
 * 关键点：[Span.Bold] 必须**真的带上** `FontWeight.Bold`，
 * [Span.Code] 必须带上 `FontFamily.Monospace` —— 这就是
 * 「加粗/代码有没有生效」的最终落点。
 */
private fun String.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    MarkdownParser.parseInline(this@toAnnotatedString).forEach { span ->
        when (span) {
            is MarkdownParser.Span.Text -> append(span.text)
            is MarkdownParser.Span.Bold ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MarkdownParser.Span.Code ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
        }
    }
}