package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LruCacheMap] 的单元测试。
 *
 * 用 String 当值来测 —— 缓存策略跟具体类型无关，
 * 单测里碰 `Bitmap` 反而需要 Android 运行环境。
 */
class LruCacheMapTest {

    @Test
    fun `容量内正常存取`() {
        val cache = LruCacheMap<String, String>(capacity = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals(2, cache.size)
        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("zzz"))
    }

    /** 超出容量时，**最久未使用**的那个先被淘汰。 */
    @Test
    fun `超出容量淘汰最久未使用`() {
        val cache = LruCacheMap<String, String>(capacity = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("d", "4")   // 触发淘汰：a 最久没用

        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertEquals("4", cache.get("d"))
        assertEquals(3, cache.size)
    }

    /**
     * `get` 必须算作「使用」，否则 LRU 会退化成 FIFO ——
     * 而批量页恰恰是「来回滑」的用法，退化成 FIFO 就白做了。
     */
    @Test
    fun `get 会把条目提到最近使用`() {
        val cache = LruCacheMap<String, String>(capacity = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        cache.get("a")        // a 变成最近使用 → 下一个被淘汰的应是 b
        cache.put("d", "4")

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    /** 覆盖同一个 key 不应占用两个名额，且应算作最近使用。 */
    @Test
    fun `同 key 覆盖只占一个名额`() {
        val cache = LruCacheMap<String, String>(capacity = 2)
        cache.put("a", "old")
        cache.put("a", "new")
        assertEquals(1, cache.size)
        assertEquals("new", cache.get("a"))

        cache.put("b", "2")
        cache.put("c", "3")   // 淘汰 a
        assertNull(cache.get("a"))
    }

    @Test
    fun `容量为 1 时只留最后一个`() {
        val cache = LruCacheMap<String, String>(capacity = 1)
        cache.put("a", "1")
        cache.put("b", "2")
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `remove 与 clear 生效`() {
        val cache = LruCacheMap<String, String>(capacity = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.remove("a"))
        assertNull(cache.get("a"))
        assertEquals(1, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("b"))
    }

    /** 非法容量应当**立刻**报错，而不是留着以后静默不淘汰。 */
    @Test(expected = IllegalArgumentException::class)
    fun `容量为 0 直接拒绝`() {
        LruCacheMap<String, String>(capacity = 0)
    }

    @Test
    fun `values 返回当前保留项`() {
        val cache = LruCacheMap<String, String>(capacity = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals(listOf("1", "2"), cache.values())
    }
}