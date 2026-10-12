package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 占位符解析器单元测试。
 */
class PlaceholderResolverTest {

    /** 固定一个时间点，便于断言（2024-01-15 10:30:45 UTC）。 */
    private val fixedDate: Date = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2024, Calendar.JANUARY, 15, 10, 30, 45)
        set(Calendar.MILLISECOND, 0)
    }.time

    @Test
    fun `纯文本不做任何替换`() {
        val result = PlaceholderResolver.resolve("水印盒", now = fixedDate)
        assertEquals("水印盒", result)
    }

    @Test
    fun `filename 占位符替换为不含扩展名的文件名`() {
        val result = PlaceholderResolver.resolve(
            "来源：{filename}",
            sourceFileName = "photo_2024.png",
            now = fixedDate
        )
        assertEquals("来源：photo_2024", result)
    }

    @Test
    fun `filename 无扩展名时保持原样`() {
        val result = PlaceholderResolver.resolve(
            "{filename}",
            sourceFileName = "noext",
            now = fixedDate
        )
        assertEquals("noext", result)
    }

    @Test
    fun `filename 为空时不崩溃`() {
        val result = PlaceholderResolver.resolve("{filename}", sourceFileName = null, now = fixedDate)
        assertEquals("", result)
    }

    @Test
    fun `date 占位符按 yyyy-MM-dd 替换且不含时间`() {
        val result = PlaceholderResolver.resolve("拍摄于 {date}", now = fixedDate)
        // 只断言格式特征，避免受本地时区影响
        assertTrue(result.startsWith("拍摄于 "))
        assertTrue(Regex("""拍摄于 \d{4}-\d{2}-\d{2}""").matches(result))
    }

    @Test
    fun `timestamp 占位符包含日期与时间`() {
        val result = PlaceholderResolver.resolve("{timestamp}", now = fixedDate)
        assertTrue(
            "实际值：$result",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(result)
        )
    }

    @Test
    fun `多个占位符可同时替换`() {
        val result = PlaceholderResolver.resolve(
            "{filename} @ {date}",
            sourceFileName = "a.jpg",
            now = fixedDate
        )
        assertTrue(result.startsWith("a @ "))
        assertFalse(result.contains("{filename}"))
        assertFalse(result.contains("{date}"))
    }
}