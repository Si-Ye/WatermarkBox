package com.watermarkbox.util

import com.watermarkbox.data.model.ExportFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批量/单张导出的文件名解析器。
 *
 * 支持模板占位符：
 * - {原文件名} / {filename} → 源文件名（不含扩展名）
 * - {date}                 → 当前日期 yyyyMMdd
 * - {time}                 → 当前时间 HHmmss
 * - {timestamp}            → yyyyMMdd_HHmmss
 * - {index}                → 序号（不补零）
 * - {index:04}             → 序号，至少 4 位（不足补零，**超过不截断**）
 *
 * 序号的**位置和位数都由占位符本身决定**（`photo{index:04}_x` 可用），
 * 因此 UI 只需要插入/移除占位符，不需要另存「序号开关 + 位数」两份状态 ——
 * 两份状态必然与模板不同步。
 */
object FileNameResolver {

    private const val KEY_FILENAME_CN = "{原文件名}"
    private const val KEY_FILENAME_EN = "{filename}"
    private const val KEY_DATE = "{date}"
    private const val KEY_TIME = "{time}"
    private const val KEY_TIMESTAMP = "{timestamp}"

    /** `{index}` 与 `{index:04}` 共用一条正则；位数上限 6 位，防止拼出荒谬长度。 */
    private val INDEX_REGEX = Regex("""\{index(?::(\d{1,6}))?\}""")

    /** 设置页插入序号时默认用几位（4 位：`0001`）。 */
    const val DEFAULT_INDEX_DIGITS = 4

    /**
     * 生成最终文件名（含扩展名）。
     *
     * @param template   文件名模板，例如 "{原文件名}_watermarked_{index:04}"
     * @param sourceName 原图文件名（可含扩展名）
     * @param format     导出格式（决定扩展名）
     * @param index      序号（批量导出时由调用方算好：起始号 + 下标；单张传 null）
     * @param now        时间基准（便于测试注入）
     */
    fun build(
        template: String,
        sourceName: String?,
        format: ExportFormat,
        index: Int? = null,
        now: Date = Date()
    ): String {
        val baseName = sanitize(sourceName?.substringBeforeLast('.') ?: "image")

        var result = template
            .replace(KEY_FILENAME_CN, baseName)
            .replace(KEY_FILENAME_EN, baseName)

        if (result.contains(KEY_TIMESTAMP)) {
            result = result.replace(
                KEY_TIMESTAMP,
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
            )
        }
        if (result.contains(KEY_DATE)) {
            result = result.replace(
                KEY_DATE,
                SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
            )
        }
        if (result.contains(KEY_TIME)) {
            result = result.replace(
                KEY_TIME,
                SimpleDateFormat("HHmmss", Locale.getDefault()).format(now)
            )
        }

        // 序号单独处理：单张（index == null）时**整段删除**，并收敛删除后残留的分隔符。
        // 老实现是替换成空串、不做收敛 —— 于是 `照片({index})` 会留下 `照片()`，
        // `照片_{index}_压缩` 会留下 `照片__压缩`，都是肉眼可见的脏文件名。
        val hadIndexPlaceholder = INDEX_REGEX.containsMatchIn(result)
        result = INDEX_REGEX.replace(result) { match ->
            if (index == null) "" else formatIndex(index, match.groupValues[1].toIntOrNull())
        }
        if (hadIndexPlaceholder && index == null) {
            result = collapseSeparators(result)
        }

        // 清理非法字符与空白，避免 MediaStore 拒绝
        result = sanitize(result).trim().trim('.', '_', '-')
        if (result.isEmpty()) {
            result = "watermarked_${System.currentTimeMillis()}"
        }

        return "$result.${format.extension}"
    }

    /**
     * 把序号格式化成字符串。
     *
     * @param digits 位数；null 表示不补零
     *
     * 注意：超过位数**不截断**。`{index:02}` 遇到 100 号时输出 `100` 而不是 `00` ——
     * 宁可格式略微溢出，也不能让两个不同的号撞成同一个文件名。
     */
    private fun formatIndex(index: Int, digits: Int?): String {
        val raw = index.toString()
        return if (digits == null || raw.length >= digits) raw else raw.padStart(digits, '0')
    }

    /**
     * 收敛「删除占位符」留下的空括号与重复分隔符。
     *
     * 只用于单张导出删掉 `{index...}` 之后，不作用于用户自己写的模板内容。
     */
    private fun collapseSeparators(input: String): String {
        var out = input
        // 空括号：照片() / 照片[] / 照片{} / 照片（）
        //
        // ⚠️ 四种括号**两边都要转义**，尤其 `]` 和 `}`。
        // 曾经的写法是 `\[\s*]`、`\{\s*}`（右侧裸括号），
        // **OpenJDK 能编译、Android 的 ICU 正则直接抛 PatternSyntaxException**
        // —— 于是「设置 → 导出」一打开就崩（实时预览会以 index=null 调到这里）。
        // 单测跑在桌面 JVM 上，抓不到这类差异，所以这里另有设备侧用例兜底。
        out = out.replace(Regex("""\(\s*\)"""), "")
        out = out.replace(Regex("""\[\s*\]"""), "")
        out = out.replace(Regex("""\{\s*\}"""), "")
        out = out.replace(Regex("""（\s*）"""), "")
        // 连续同族分隔符：__ / -- / .. / _-_. 保留第一个
        out = out.replace(Regex("""([._\-])[._\-\s]+""")) { m -> m.groupValues[1] }
        return out
    }

    /** 移除文件系统不允许的字符。 */
    private fun sanitize(input: String): String {
        return input.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
    }

    /** 模板是否包含至少一个有效占位符（用于设置页校验提示）。 */
    fun hasPlaceholder(template: String): Boolean {
        return listOf(
            KEY_FILENAME_CN, KEY_FILENAME_EN, KEY_DATE, KEY_TIME, KEY_TIMESTAMP
        ).any { template.contains(it) } || INDEX_REGEX.containsMatchIn(template)
    }

    /** 模板里是否有序号占位符（设置页开关的状态**直接由它反推**）。 */
    fun hasIndex(template: String): Boolean = INDEX_REGEX.containsMatchIn(template)

    /** 模板里序号占位符声明的位数；`{index}` 返回 null；没有占位符也返回 null。 */
    fun indexDigits(template: String): Int? {
        val match = INDEX_REGEX.find(template) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** 生成序号占位符文本（设置页插入/改位数时用，避免 UI 里手写字符串）。 */
    fun indexPlaceholder(digits: Int?): String {
        val d = digits ?: return "{index}"
        val clamped = d.coerceIn(1, 6)
        // `{index:4}` 与 `{index:04}` 解析结果相同，但自动插入的文本要好看。
        return "{index:${clamped.toString().padStart(2, '0')}}"
    }

    /**
     * 打开序号：在模板末尾插入 `_{index:NN}`。
     *
     * 模板若已以分隔符结尾（`照片_`），直接追加占位符，不再叠一个分隔符。
     */
    fun appendIndex(template: String, digits: Int? = DEFAULT_INDEX_DIGITS): String {
        if (hasIndex(template)) return template
        val sep = if (template.isEmpty() || template.last() in "_-.") "" else "_"
        return template + sep + indexPlaceholder(digits)
    }

    /** 关闭序号：移除占位符并收敛分隔符（与单张导出同一套收敛规则）。 */
    fun removeIndex(template: String): String {
        if (!hasIndex(template)) return template
        return collapseSeparators(INDEX_REGEX.replace(template, ""))
            .trim()
            .trim('.', '_', '-')
    }

    /** 改位数：在原位置替换占位符，前后分隔符不动。 */
    fun replaceIndexDigits(template: String, digits: Int?): String {
        if (!hasIndex(template)) return template
        val placeholder = indexPlaceholder(digits)
        return INDEX_REGEX.replace(template, Regex.escapeReplacement(placeholder))
    }
}
