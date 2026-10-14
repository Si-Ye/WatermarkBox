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
     * SimpleDateFormat 不是线程安全的，且构造开销大。
     * 用 ThreadLocal 缓存，避免每次解析都重新构造
     * （拖滑块时水印文本若含占位符会被高频解析）。
     */
    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

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
            val ts = timestampFormat.get()!!.format(now)
            result = result.replace(KEY_TIMESTAMP, ts)
        }
        if (result.contains(KEY_DATE)) {
            val date = dateFormat.get()!!.format(now)
            result = result.replace(KEY_DATE, date)
        }
        if (result.contains(KEY_FILENAME)) {
            val name = sourceFileName?.substringBeforeLast('.') ?: ""
            result = result.replace(KEY_FILENAME, name)
        }

        return result
    }

}