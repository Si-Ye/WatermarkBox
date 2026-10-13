package com.watermarkbox.util

/**
 * 定容量的 **LRU 缓存**（最近最少使用优先淘汰）。
 *
 * ## 为什么自己写一个
 *
 * `androidx.collection.LruCache` 把淘汰动作放在 `entryRemoved` 回调里，
 * 调用时机由它决定；而本项目的淘汰**绝不能**顺手 recycle 位图 ——
 * 被淘汰的那张可能正被某一帧画着，recycle 会直接崩。
 *
 * 需求其实只有两条：「按容量淘汰」+「get 算作使用」，
 * 几十行写完还能单测，比引一个不可控回调的类更划算。
 *
 * ## 为什么是泛型的
 *
 * 单元测试跑在 JVM 上，碰不了 `android.graphics.Bitmap`。
 * 泛型之后，缓存策略可以用 String 测，业务侧照样放 Bitmap。
 *
 * ## 淘汰顺序怎么保证
 *
 * `LinkedHashMap(accessOrder = true)` 的迭代顺序是
 * 「最久未用 → 最近用过」，所以淘汰只需要干掉**第一个**。
 */
class LruCacheMap<K : Any, V : Any>(private val capacity: Int) {

    init {
        require(capacity > 0) { "容量必须为正数，收到 $capacity" }
    }

    private val entries = LinkedHashMap<K, V>(capacity, 0.75f, /* accessOrder = */ true)

    /** 命中会把它标记为「最近使用」。 */
    fun get(key: K): V? = entries[key]

    fun containsKey(key: K): Boolean = entries.containsKey(key)

    /** 写入（已存在则覆盖，并标记为最近使用）；超容量时淘汰最久未用的那个。 */
    fun put(key: K, value: V) {
        entries[key] = value
        trim()
    }

    fun remove(key: K): V? = entries.remove(key)

    fun clear() = entries.clear()

    val size: Int get() = entries.size

    /** 当前保留的全部值（顺序为「最久未用 → 最近使用」）。 */
    fun values(): List<V> = entries.values.toList()

    private fun trim() {
        while (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) return
            oldest.next()
            oldest.remove()
        }
    }
}