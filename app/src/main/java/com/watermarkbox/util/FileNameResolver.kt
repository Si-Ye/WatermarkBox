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
 * - {index}                → 序号（批量时使用）
 */
object FileNameResolver {

    private const val KEY_FILENAME_CN = "{原文件名}"
    private const val KEY_FILENAME_EN = "{filename}"
    private const val KEY_DATE = "{date}"
    private const val KEY_TIME = "{time}"
    private const val KEY_TIMESTAMP = "{timestamp}"
    private const val KEY_INDEX = "{index}"

    /**
     * 生成最终文件名（含扩展名）。
     *
     * @param template   文件名模板，例如 "{原文件名}_watermarked"
     * @param sourceName 原图文件名（可含扩展名）
     * @param format     导出格式（决定扩展名）
     * @param index      序号（批量导出时从 1 开始；单张传 null）
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
        if (result.contains(KEY_INDEX)) {
            result = result.replace(KEY_INDEX, index?.toString() ?: "")
        }

        // 清理非法字符与空白，避免 MediaStore 拒绝
        result = sanitize(result).trim().trim('.', '_', '-')
        if (result.isEmpty()) {
            result = "watermarked_${System.currentTimeMillis()}"
        }

        return "$result.${format.extension}"
    }

    /** 移除文件系统不允许的字符。 */
    private fun sanitize(input: String): String {
        return input.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
    }

    /** 模板是否包含至少一个有效占位符（用于设置页校验提示）。 */
    fun hasPlaceholder(template: String): Boolean {
        return listOf(
            KEY_FILENAME_CN, KEY_FILENAME_EN, KEY_DATE, KEY_TIME, KEY_TIMESTAMP, KEY_INDEX
        ).any { template.contains(it) }
    }
}