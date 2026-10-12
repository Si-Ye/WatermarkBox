package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义字体解析器的可插拔性测试（纯 JVM）。
 *
 * 渲染器不直接依赖 Android Context，而是通过
 * [WatermarkRenderer.CustomFontResolver] 注入 —— 这里验证该接口契约。
 */
class CustomFontResolverTest {

    @Test
    fun `默认未注入解析器`() {
        val renderer = WatermarkRenderer()
        assertNull("初始应无解析器", renderer.customFontResolver)
    }

    @Test
    fun `可注入并替换解析器`() {
        val renderer = WatermarkRenderer()
        var calledWith: String? = null

        renderer.customFontResolver = WatermarkRenderer.CustomFontResolver { name ->
            calledWith = name
            null   // 返回 null 表示解析失败 → 渲染器应回退
        }

        assertNotNull("注入后应可读取", renderer.customFontResolver)

        // 手动触发一次（不经过渲染，验证 lambda 契约）
        renderer.customFontResolver?.resolve("myfont.ttf")
        assertEquals("myfont.ttf", calledWith)
    }

    @Test
    fun `解析器可被替换为另一个实现`() {
        val renderer = WatermarkRenderer()

        renderer.customFontResolver = WatermarkRenderer.CustomFontResolver { _ -> null }
        val first = renderer.customFontResolver

        renderer.customFontResolver = WatermarkRenderer.CustomFontResolver { _ -> null }
        val second = renderer.customFontResolver

        assertTrue("应能再次替换（不同实例）", first !== second)
    }

    @Test
    fun `解析器可置空以恢复默认行为`() {
        val renderer = WatermarkRenderer()
        renderer.customFontResolver = WatermarkRenderer.CustomFontResolver { _ -> null }
        renderer.customFontResolver = null
        assertNull(renderer.customFontResolver)
    }

    @Test
    fun `解析器接口为 fun interface 支持 lambda`() {
        // 若接口不是 fun interface，下面的写法无法编译
        val r = WatermarkRenderer.CustomFontResolver { name -> null }
        assertNull(r.resolve("x.ttf"))
    }
}