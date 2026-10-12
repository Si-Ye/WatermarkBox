package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PresetNaming.uniqueName] 的命名冲突测试。
 *
 * ## 背景
 *
 * 用户保存预设时若名称重复，会弹出「替换 / 保留两者 / 取消」。
 * 选「保留两者」时需要一个不重名的新名称 —— 就是本函数负责生成的。
 *
 * `uniqueName` 是纯函数、零依赖，因此**直接调用真实实现**测试。
 * （不"复刻"一份算法来测 —— 那样测的不是真实代码。）
 */
class PresetNamingTest {

    @Test
    fun returns_base_when_no_conflict() {
        assertEquals("我的水印", PresetNaming.uniqueName("我的水印", emptyList()))
        assertEquals("我的水印", PresetNaming.uniqueName("我的水印", listOf("别的水印")))
    }

    @Test
    fun appends_2_on_first_conflict() {
        assertEquals(
            "我的水印 (2)",
            PresetNaming.uniqueName("我的水印", listOf("我的水印"))
        )
    }

    @Test
    fun increments_until_free() {
        assertEquals(
            "我的水印 (3)",
            PresetNaming.uniqueName("我的水印", listOf("我的水印", "我的水印 (2)"))
        )
        assertEquals(
            "水印 (5)",
            PresetNaming.uniqueName(
                "水印",
                listOf("水印", "水印 (2)", "水印 (3)", "水印 (4)")
            )
        )
    }

    @Test
    fun does_not_confuse_user_named_variants() {
        // 用户自己命名为 "水印2"（无空格）时，不应被当成 "水印 (2)" 的冲突
        val taken = listOf("水印", "水印2")
        assertEquals("水印 (2)", PresetNaming.uniqueName("水印", taken))
    }

    @Test
    fun handles_unicode_and_emoji() {
        assertEquals(
            "中文 名称 (2)",
            PresetNaming.uniqueName("中文 名称", listOf("中文 名称"))
        )
        assertEquals(
            "emoji🎨 (2)",
            PresetNaming.uniqueName("emoji🎨", listOf("emoji🎨"))
        )
    }

    @Test
    fun handles_many_duplicates() {
        // 构造 100 个同名，验证能找到第 101 个
        val taken = mutableListOf("批量")
        for (i in 2..100) taken.add("批量 ($i)")
        assertEquals("批量 (101)", PresetNaming.uniqueName("批量", taken))
    }

    @Test
    fun result_is_guaranteed_absent_from_taken() {
        // 不变量：返回值一定不在 taken 中（否则会覆盖别人）
        val rnd = kotlin.random.Random(42)
        repeat(200) {
            val base = "预设${rnd.nextInt(10)}"
            val taken = List(rnd.nextInt(20)) { "预设${rnd.nextInt(12)}" }
            val result = PresetNaming.uniqueName(base, taken)
            assert(!result.equals(base) || base !in taken) {
                "返回值「$result」与 taken 冲突: $taken"
            }
        }
    }
}