package com.watermarkbox.util

import com.watermarkbox.data.model.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 导出文件名解析器单元测试。
 */
class FileNameResolverTest {

    /** 固定时间：2024-03-15 14:30:45 UTC。 */
    private val fixedDate: Date = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2024, Calendar.MARCH, 15, 14, 30, 45)
        set(Calendar.MILLISECOND, 0)
    }.time

    @Test
    fun `中文占位符替换原文件名并追加扩展名`() {
        val name = FileNameResolver.build(
            template = "{原文件名}_watermarked",
            sourceName = "photo.jpg",
            format = ExportFormat.JPEG,
            now = fixedDate
        )
        assertEquals("photo_watermarked.jpg", name)
    }

    @Test
    fun `英文占位符同样可用`() {
        val name = FileNameResolver.build(
            template = "{filename}_wm",
            sourceName = "a.png",
            format = ExportFormat.PNG,
            now = fixedDate
        )
        assertEquals("a_wm.png", name)
    }

    @Test
    fun `无扩展名的源文件名保持原样`() {
        val name = FileNameResolver.build(
            template = "{原文件名}",
            sourceName = "noext",
            format = ExportFormat.JPEG,
            now = fixedDate
        )
        assertEquals("noext.jpg", name)
    }

    @Test
    fun `源文件名为空时回退为 image`() {
        val name = FileNameResolver.build(
            template = "{原文件名}",
            sourceName = null,
            format = ExportFormat.PNG,
            now = fixedDate
        )
        assertEquals("image.png", name)
    }

    @Test
    fun `date 占位符按 yyyyMMdd 替换`() {
        val name = FileNameResolver.build(
            template = "wm_{date}",
            sourceName = "x.jpg",
            format = ExportFormat.JPEG,
            now = fixedDate
        )
        assertEquals("wm_20240315.jpg", name)
    }

    @Test
    fun `index 占位符用于批量序号`() {
        val name = FileNameResolver.build(
            template = "{原文件名}_{index}",
            sourceName = "photo.jpg",
            format = ExportFormat.JPEG,
            index = 7,
            now = fixedDate
        )
        assertEquals("photo_7.jpg", name)
    }

    @Test
    fun `index 为 null 时占位符被移除`() {
        val name = FileNameResolver.build(
            template = "a{index}b",
            sourceName = "x.jpg",
            format = ExportFormat.JPEG,
            index = null,
            now = fixedDate
        )
        assertEquals("ab.jpg", name)
    }

    @Test
    fun `非法字符被替换为下划线`() {
        val name = FileNameResolver.build(
            template = "a/b:c*d?e",
            sourceName = "x.jpg",
            format = ExportFormat.JPEG,
            now = fixedDate
        )
        assertFalse("不应包含非法字符", name.contains("/") || name.contains(":") || name.contains("*"))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun `源文件名中的非法字符也会被清理`() {
        val name = FileNameResolver.build(
            template = "{原文件名}",
            sourceName = "a/b*c.jpg",
            format = ExportFormat.JPEG,
            now = fixedDate
        )
        assertEquals("a_b_c.jpg", name)
    }

    @Test
    fun `模板全为空占位符时生成兜底名称`() {
        // 模板只剩分隔符，trim 后为空 → 走兜底逻辑
        val name = FileNameResolver.build(
            template = "___",
            sourceName = null,
            format = ExportFormat.PNG,
            now = fixedDate
        )
        assertTrue("应有兜底名称且带扩展名", name.endsWith(".png"))
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun `扩展名随格式变化`() {
        val png = FileNameResolver.build("x", "a.jpg", ExportFormat.PNG, now = fixedDate)
        val jpg = FileNameResolver.build("x", "a.jpg", ExportFormat.JPEG, now = fixedDate)
        assertEquals("x.png", png)
        assertEquals("x.jpg", jpg)
    }

    @Test
    fun `hasPlaceholder 正确识别占位符`() {
        assertTrue(FileNameResolver.hasPlaceholder("{原文件名}_wm"))
        assertTrue(FileNameResolver.hasPlaceholder("a_{date}"))
        assertTrue(FileNameResolver.hasPlaceholder("{index}"))
        assertFalse(FileNameResolver.hasPlaceholder("plain_name"))
    }
}