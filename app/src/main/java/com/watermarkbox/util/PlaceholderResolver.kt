package com.watermarkbox.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印文字占位符解析器。
 *
 * 支持的占位符：
 * - {timestamp} → 当前时间戳，格式 yyyy-MM-dd HH:mm:ss
 * - {date}      → 当前日期，格式 yyyy-MM-dd
 * - {filename}  → 原图文件名（不含扩展名）
 */
object PlaceholderResolver {

    private const val KEY_TIMESTAMP = "{timestamp}"
    private const val KEY_DATE = "{date}"
    private const val KEY_FILENAME = "{filename}"

    /**
     * 按**当前** Locale 构造格式化器。
     *
     * ## 为什么不再用 ThreadLocal 缓存（曾经的做法，已废弃）
     *
     * `SimpleDateFormat(pattern, Locale.getDefault())` 的 Locale 在
     * **构造那一刻就固定**。用 ThreadLocal 缓存后，用户切换应用语言
     * （本应用支持中英切换）**不会**重建它 —— 日期格式会一直停在旧语言。
     *
     * 而 SimpleDateFormat 的构造开销只有微秒级，水印渲染远达不到
     * 「构造成为瓶颈」的频率，缓存的收益远小于它引入的正确性问题。
     */
    private fun format(pattern: String, now: Date): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(now)

    /**
     * 解析水印文本模板，替换所有占位符为真实值。
     *
     * 性能优化：
     * - 只有当模板确实含某占位符时才做替换（避免无谓的字符串扫描）
     * - 若模板不含任何占位符，直接原样返回（最常见的情况）
     *
     * @param template 原始模板，例如 "我的作品 {date}"
     * @param sourceFileName 原图文件名（可含扩展名），用于 {filename}
     * @param now 当前时间（便于测试注入），默认取系统时间
     */
    fun resolve(
        template: String,
        sourceFileName: String? = null,
        now: Date = Date()
    ): String {
        if (template.isEmpty()) return template

        // 快速路径：不含 '{' 的模板不可能有占位符，直接返回
        if (!template.contains('{')) return template

        var result = template

        if (result.contains(KEY_TIMESTAMP)) {
            result = result.replace(KEY_TIMESTAMP, format("yyyy-MM-dd HH:mm:ss", now))
        }
        if (result.contains(KEY_DATE)) {
            result = result.replace(KEY_DATE, format("yyyy-MM-dd", now))
        }
        if (result.contains(KEY_FILENAME)) {
            val name = sourceFileName?.substringBeforeLast('.') ?: ""
            result = result.replace(KEY_FILENAME, name)
        }

        return result
    }

}