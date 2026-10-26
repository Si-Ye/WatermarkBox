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

    // ── 带位数的序号占位符 `{index:NN}` ────────────────────────────────

    @Test
    fun `index 带位数时补零`() {
        val name = FileNameResolver.build(
            template = "{原文件名}_{index:04}",
            sourceName = "photo.jpg",
            format = ExportFormat.JPEG,
            index = 7,
            now = fixedDate
        )
        assertEquals("photo_0007.jpg", name)
    }

    @Test
    fun `index 位数两位时补零到两位`() {
        val name = FileNameResolver.build(
            template = "wm_{index:2}",
            sourceName = "x.jpg",
            format = ExportFormat.JPEG,
            index = 7,
            now = fixedDate
        )
        assertEquals("wm_07.jpg", name)
    }

    @Test
    fun `index 超过位数时不截断`() {
        // 宁可格式溢出，也不能让 3 号和 103 号撞成同一个文件名
        val name = FileNameResolver.build(
            template = "wm_{index:02}",
            sourceName = "x.jpg",
            format = ExportFormat.JPEG,
            index = 103,
            now = fixedDate
        )
        assertEquals("wm_103.jpg", name)
    }

    @Test
    fun `序号位置由占位符决定`() {
        val name = FileNameResolver.build(
            template = "photo{index:04}_压缩",
            sourceName = "a.png",
            format = ExportFormat.PNG,
            index = 12,
            now = fixedDate
        )
        assertEquals("photo0012_压缩.png", name)
    }

    @Test
    fun `不带位数的 index 与带位数并存`() {
        val name = FileNameResolver.build(
            template = "{index}_{index:03}",
            sourceName = "a.jpg",
            format = ExportFormat.JPEG,
            index = 5,
            now = fixedDate
        )
        assertEquals("5_005.jpg", name)
    }

    // ── 单张导出：删段后必须收敛分隔符 / 空括号 ───────────────────────

    @Test
    fun `单张导出时序号占位符整段删除且不留空括号`() {
        val name = FileNameResolver.build(
            template = "照片({index:04})_压缩",
            sourceName = "a.jpg",
            format = ExportFormat.JPEG,
            index = null,
            now = fixedDate
        )
        assertEquals("照片_压缩.jpg", name)
    }

    @Test
    fun `单张导出时不留下重复分隔符`() {
        val name = FileNameResolver.build(
            template = "照片_{index:04}_压缩",
            sourceName = "a.jpg",
            format = ExportFormat.JPEG,
            index = null,
            now = fixedDate
        )
        assertEquals("照片_压缩.jpg", name)
    }

    @Test
    fun `单张导出时中方括号也被收敛`() {
        val name = FileNameResolver.build(
            template = "[{index}]照片",
            sourceName = "a.jpg",
            format = ExportFormat.JPEG,
            index = null,
            now = fixedDate
        )
        assertEquals("照片.jpg", name)
    }

    @Test
    fun `单张导出时结尾占位符删除后尾部分隔符被清掉`() {
        val name = FileNameResolver.build(
            template = "{原文件名}_{index:04}",
            sourceName = "photo.jpg",
            format = ExportFormat.JPEG,
            index = null,
            now = fixedDate
        )
        assertEquals("photo.jpg", name)
    }

    // ── 模板辅助函数（设置页开关直接依赖它们）─────────────────────────

    @Test
    fun `hasIndex 与 indexDigits`() {
        assertTrue(FileNameResolver.hasIndex("a_{index:04}"))
        assertTrue(FileNameResolver.hasIndex("{index}"))
        assertFalse(FileNameResolver.hasIndex("a_{date}"))

        assertEquals(4, FileNameResolver.indexDigits("a_{index:04}"))
        assertEquals(null, FileNameResolver.indexDigits("a_{index}"))
        assertEquals(null, FileNameResolver.indexDigits("plain"))
    }

    @Test
    fun `appendIndex 追加占位符且不叠分隔符`() {
        assertEquals(
            "{原文件名}_watermarked_{index:04}",
            FileNameResolver.appendIndex("{原文件名}_watermarked")
        )
        // 已以分隔符结尾时不再叠加
        assertEquals("照片_{index:04}", FileNameResolver.appendIndex("照片_"))
        // 已有序号时是幂等的
        assertEquals("a_{index:04}", FileNameResolver.appendIndex("a_{index:04}"))
    }

    @Test
    fun `removeIndex 移除占位符并收敛分隔符`() {
        assertEquals("照片_压缩", FileNameResolver.removeIndex("照片_{index:04}_压缩"))
        assertEquals("照片", FileNameResolver.removeIndex("照片_{index}"))
        assertEquals("plain", FileNameResolver.removeIndex("plain"))
    }

    @Test
    fun `replaceIndexDigits 只改位数不动位置`() {
        assertEquals("photo{index:02}_x", FileNameResolver.replaceIndexDigits("photo{index:04}_x", 2))
        assertEquals("photo{index}_x", FileNameResolver.replaceIndexDigits("photo{index:04}_x", null))
        // 没有占位符时原样返回
        assertEquals("photo_x", FileNameResolver.replaceIndexDigits("photo_x", 4))
    }

    @Test
    fun `appendIndex 与 removeIndex 互逆`() {
        val original = "{原文件名}_wm"
        val appended = FileNameResolver.appendIndex(original)
        assertEquals(original, FileNameResolver.removeIndex(appended))
    }
}