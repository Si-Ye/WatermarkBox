package com.watermarkbox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RepositoryPureLogic] 的单元测试。
 *
 * ## 这组测试补的是什么
 *
 * 五个 Repository 都持有 `Context`，纯 JVM 跑不起来，因此此前
 * **data 层是零单测**。这里测的不是「调系统 API 调对了没」
 * （那属于集成测试），而是仓库里那些**判断逻辑**：
 * 哪些文件名能进、体积卡在哪、缓存什么时候过期。
 *
 * 这些逻辑抽成了 [RepositoryPureLogic]，生产代码调用同一份，
 * 所以测试不会跟实现「各自漂移」。
 */
class RepositoryPureLogicTest {

    // -----------------------------------------------------------------
    // 字体扩展名
    // -----------------------------------------------------------------

    @Test
    fun `允许 ttf otf ttc`() {
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("myfont.ttf"))
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("a.otf"))
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("b.ttc"))
    }

    /** 用户从「最近文件」里挑的可能就是大写扩展名，不该被拒。 */
    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("FONT.TTF"))
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("Font.Otf"))
    }

    @Test
    fun `拒绝非字体扩展名`() {
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("malware.exe"))
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("photo.png"))
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("archive.zip"))
    }

    /** 没有扩展名 → 拒绝（不能靠猜）。 */
    @Test
    fun `拒绝无扩展名`() {
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("noext"))
        assertFalse(RepositoryPureLogic.isAllowedFontExtension(""))
    }

    /**
     * 只取**最后一段**扩展名。
     *
     * `my.font.v2.ttf` 是合法名字；若实现写成 `substringAfter('.')`，
     * 会取出 `font.v2.ttf` 这种鬼东西然后拒掉。
     */
    @Test
    fun `取最后一段扩展名`() {
        assertTrue(RepositoryPureLogic.isAllowedFontExtension("my.font.v2.ttf"))
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("weird.ttf.exe"))
    }

    /** 结尾一个点（`font.`）没有有效扩展名。 */
    @Test
    fun `结尾孤点视为无扩展名`() {
        assertFalse(RepositoryPureLogic.isAllowedFontExtension("font."))
    }

    // -----------------------------------------------------------------
    // 字体体积
    // -----------------------------------------------------------------

    @Test
    fun `上限内通过`() {
        assertTrue(RepositoryPureLogic.isFontSizeAllowed(0))
        assertTrue(RepositoryPureLogic.isFontSizeAllowed(1024))
        assertTrue(RepositoryPureLogic.isFontSizeAllowed(8L * 1024 * 1024 - 1))
    }

    /** 边界：正好等于 8MB **放行**（与 `> MAX 才拒绝` 的实现一致）。 */
    @Test
    fun `正好等于上限通过`() {
        assertTrue(RepositoryPureLogic.isFontSizeAllowed(RepositoryPureLogic.MAX_FONT_BYTES))
    }

    @Test
    fun `超上限拒绝`() {
        assertFalse(RepositoryPureLogic.isFontSizeAllowed(RepositoryPureLogic.MAX_FONT_BYTES + 1))
    }

    @Test
    fun `负体积拒绝`() {
        assertFalse(RepositoryPureLogic.isFontSizeAllowed(-1))
    }

    // -----------------------------------------------------------------
    // 文件名清理
    // -----------------------------------------------------------------

    @Test
    fun `路径分隔符被替换`() {
        assertEquals("a_b", RepositoryPureLogic.sanitizeFileName("a/b"))
        assertEquals("a_b", RepositoryPureLogic.sanitizeFileName("a\\b"))
    }

    /**
     * 换行必须清掉。
     *
     * 文件名里带 `\n` 在文件管理器里看起来是「多了一行」，
     * 用户完全不知道发生了什么，也没法重命名回来。
     */
    @Test
    fun `换行与制表符被替换`() {
        assertEquals("a_b", RepositoryPureLogic.sanitizeFileName("a\nb"))
        assertEquals("a_b", RepositoryPureLogic.sanitizeFileName("a\tb"))
        assertEquals("a_b", RepositoryPureLogic.sanitizeFileName("a\rb"))
    }

    @Test
    fun `保留合法字符`() {
        assertEquals("My Font v2.0.ttf",
            RepositoryPureLogic.sanitizeFileName("My Font v2.0.ttf"))
        assertEquals("字体_测试.ttf",
            RepositoryPureLogic.sanitizeFileName("字体_测试.ttf"))
    }

    @Test
    fun `一次性替换多个非法字符`() {
        assertEquals("a_b_c_d", RepositoryPureLogic.sanitizeFileName("a<b>c:d"))
    }

    // -----------------------------------------------------------------
    // 相册路径
    // -----------------------------------------------------------------

    @Test
    fun `去掉尾部斜杠`() {
        assertEquals("Pictures/WatermarkBox",
            RepositoryPureLogic.normalizeAlbumPath("Pictures/WatermarkBox/", "DEF"))
        assertEquals("Pictures/WatermarkBox",
            RepositoryPureLogic.normalizeAlbumPath("Pictures/WatermarkBox///", "DEF"))
    }

    @Test
    fun `空值退回默认`() {
        assertEquals("DEF", RepositoryPureLogic.normalizeAlbumPath(null, "DEF"))
        assertEquals("DEF", RepositoryPureLogic.normalizeAlbumPath("", "DEF"))
        assertEquals("DEF", RepositoryPureLogic.normalizeAlbumPath("   ", "DEF"))
        assertEquals("DEF", RepositoryPureLogic.normalizeAlbumPath("/", "DEF"))
    }

    @Test
    fun `正常路径原样返回`() {
        assertEquals("Pictures/WatermarkBox",
            RepositoryPureLogic.normalizeAlbumPath("Pictures/WatermarkBox", "DEF"))
    }

    // -----------------------------------------------------------------
    // 存储可写状态
    // -----------------------------------------------------------------

    @Test
    fun `mounted 表示可写`() {
        assertTrue(RepositoryPureLogic.isStorageWritableState("mounted"))
    }

    @Test
    fun `其它状态一律不可写`() {
        assertFalse(RepositoryPureLogic.isStorageWritableState("unmounted"))
        assertFalse(RepositoryPureLogic.isStorageWritableState("removed"))
        assertFalse(RepositoryPureLogic.isStorageWritableState("shared"))
        assertFalse(RepositoryPureLogic.isStorageWritableState(null))
    }

    // -----------------------------------------------------------------
    // 在线档案缓存
    // -----------------------------------------------------------------

    @Test
    fun `从未缓存过 -- 不可用`() {
        assertFalse(RepositoryPureLogic.isCacheableProfile(cachedAtMs = 0L, nowMs = 1_000_000L))
    }

    @Test
    fun `缓存新鲜 -- 可用`() {
        val now = 100 * 60 * 60 * 1000L
        // 1 小时前缓存
        assertTrue(RepositoryPureLogic.isCacheableProfile(now - 60 * 60 * 1000L, now))
    }

    /** 边界：正好到 6 小时 → 失效（`age < ttl`）。 */
    @Test
    fun `正好到期即失效`() {
        val ttl = RepositoryPureLogic.PROFILE_CACHE_TTL_MS
        assertFalse(RepositoryPureLogic.isCacheableProfile(cachedAtMs = 0L + 1L, nowMs = 1L + ttl))
    }

    @Test
    fun `超过 6 小时 -- 失效`() {
        val ttl = RepositoryPureLogic.PROFILE_CACHE_TTL_MS
        val cachedAt = 5_000L
        assertFalse(RepositoryPureLogic.isCacheableProfile(cachedAt, cachedAt + ttl + 1))
    }

    /**
     * 时钟回拨。
     *
     * 用户手动改系统时间、或 NTP 校正导致「现在」早于「缓存时刻」时，
     * 视作缓存仍有效 —— 否则会不断重打接口，而且每次都"成功"，
     * 变成死循环式的网络请求。
     */
    @Test
    fun `时钟回拨时视为缓存有效`() {
        val cachedAt = 10_000_000L
        val now = 9_000_000L   // 比缓存时刻还早
        assertTrue(RepositoryPureLogic.isCacheableProfile(cachedAt, now))
    }

    @Test
    fun `可自定义 TTL`() {
        assertTrue(RepositoryPureLogic.isCacheableProfile(1000L, 1500L, ttlMs = 1000L))
        assertFalse(RepositoryPureLogic.isCacheableProfile(1000L, 2500L, ttlMs = 1000L))
    }
}