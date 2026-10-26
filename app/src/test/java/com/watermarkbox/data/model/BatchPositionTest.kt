package com.watermarkbox.data.model

import com.watermarkbox.worker.JsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量「水印位置」覆盖的测试。
 *
 * ## 为什么这段必须有测试
 *
 * 位置覆盖同时被**预览**（每帧）和 **Worker 导出**消费。两者只要有一处判定不同，
 * 就会出现「预览里拖好了、导出又回到右下角」这种最难查的问题 ——
 * 因为它不会崩、不会报错，只是结果不对。
 *
 * 因此测试盯三件事：
 * 1. 覆盖为空时**绝不动**原样式（否则统一模式会被悄悄改掉）
 * 2. 有覆盖时必须切到 CUSTOM（否则 customX/Y 根本不参与计算）
 * 3. 坐标必须被夹进合法范围（防止水印被拖到画面外）
 */
class BatchPositionTest {

    private val textStyle = TextWatermarkStyle.Default
    private val imageStyle = ImageWatermarkStyle.Default

    // =================================================================
    // 作用域判定
    // =================================================================

    @Test
    fun `统一作用域下查不到任何覆盖`() {
        val overrides = BatchPositionOverrides(
            scope = PositionScope.UNIFIED,
            text = mapOf("a" to ImagePosition(0.1f, 0.2f))
        )
        // 表里明明有值，但作用域是统一 → 必须当作没有
        assertNull(overrides.textFor("a"))
    }

    @Test
    fun `每张独立作用域下按 uri 取到覆盖`() {
        val overrides = BatchPositionOverrides(
            scope = PositionScope.PER_IMAGE,
            text = mapOf("a" to ImagePosition(0.1f, 0.2f))
        )
        val found = overrides.textFor("a")
        assertNotNull(found)
        assertEquals(0.1f, found!!.x, 0.0001f)
        assertEquals(0.2f, found.y, 0.0001f)
        // 没记过的图仍然没有覆盖 → 沿用共享配置
        assertNull(overrides.textFor("b"))
    }

    @Test
    fun `切回统一会清空覆盖表`() {
        val overrides = BatchPositionOverrides(
            scope = PositionScope.PER_IMAGE,
            text = mapOf("a" to ImagePosition(0.1f, 0.2f)),
            image = mapOf("a" to ImagePosition(0.3f, 0.4f))
        )
        val cleared = overrides.cleared()
        assertEquals(PositionScope.UNIFIED, cleared.scope)
        assertTrue(cleared.text.isEmpty())
        assertTrue(cleared.image.isEmpty())
    }

    // =================================================================
    // 应用覆盖
    // =================================================================

    @Test
    fun `没有覆盖时原样返回`() {
        val same = BatchPositionResolver.applyText(textStyle, null)
        assertEquals(textStyle, same)
        assertEquals(textStyle.gravity, same.gravity)

        val sameImage = BatchPositionResolver.applyImage(imageStyle, null)
        assertEquals(imageStyle, sameImage)
    }

    @Test
    fun `有覆盖时切换为 CUSTOM 并写入坐标`() {
        val applied = BatchPositionResolver.applyText(textStyle, ImagePosition(0.25f, 0.75f))
        // ⚠️ 关键：必须是 CUSTOM —— 否则 customX/Y 在渲染时被忽略
        assertEquals(GravityPosition.CUSTOM, applied.gravity)
        assertEquals(0.25f, applied.customX, 0.0001f)
        assertEquals(0.75f, applied.customY, 0.0001f)
        // 其他字段不能被动到
        assertEquals(textStyle.fontSize, applied.fontSize, 0.0001f)
        assertEquals(textStyle.alpha, applied.alpha, 0.0001f)
    }

    @Test
    fun `图片水印同样切换为 CUSTOM`() {
        val applied = BatchPositionResolver.applyImage(
            imageStyle,
            ImagePosition(0.4f, 0.6f)
        )
        assertEquals(GravityPosition.CUSTOM, applied.gravity)
        assertEquals(0.4f, applied.customX, 0.0001f)
        assertEquals(0.6f, applied.customY, 0.0001f)
        assertEquals(imageStyle.widthPercent, applied.widthPercent, 0.0001f)
    }

    @Test
    fun `坐标越界会被夹回合法范围`() {
        val applied = BatchPositionResolver.applyText(textStyle, ImagePosition(-3f, 8f))
        assertEquals(BatchPositionResolver.MIN_NORMALIZED, applied.customX, 0.0001f)
        assertEquals(BatchPositionResolver.MAX_NORMALIZED, applied.customY, 0.0001f)
    }

    @Test
    fun `clamp 边界值`() {
        assertEquals(0.05f, BatchPositionResolver.clamp(0f), 0.0001f)
        assertEquals(0.05f, BatchPositionResolver.clamp(-1f), 0.0001f)
        assertEquals(0.5f, BatchPositionResolver.clamp(0.5f), 0.0001f)
        assertEquals(0.95f, BatchPositionResolver.clamp(1f), 0.0001f)
    }

    // =================================================================
    // 恢复默认
    // =================================================================

    @Test
    fun `恢复默认只改 gravity 不动自定义坐标`() {
        val dragged = textStyle.copy(
            gravity = GravityPosition.CUSTOM,
            customX = 0.2f,
            customY = 0.8f
        )
        val reset = BatchPositionResolver.resetToDefault(dragged)
        // 回到九宫格默认方位
        assertEquals(TextWatermarkStyle.Default.gravity, reset.gravity)
        // 自定义坐标保留当"备忘"，重力不是 CUSTOM 时它们不参与计算
        assertEquals(0.2f, reset.customX, 0.0001f)
        assertEquals(0.8f, reset.customY, 0.0001f)
    }

    // =================================================================
    // Worker 侧序列化（跨进程传递位置的唯一通道）
    // =================================================================

    @Test
    fun `位置表 JSON 往返一致`() {
        val source = mapOf(
            "content://media/external/images/media/1" to ImagePosition(0.11f, 0.22f),
            "content://media/external/images/media/2" to ImagePosition(0.33f, 0.44f)
        )
        val decoded = JsonCodec.decodePositions(JsonCodec.encodePositions(source))
        assertEquals(source.size, decoded.size)
        source.forEach { (uri, pos) ->
            val back = decoded[uri]
            assertNotNull("$uri 丢失", back)
            assertEquals(pos.x, back!!.x, 0.0001f)
            assertEquals(pos.y, back.y, 0.0001f)
        }
    }

    @Test
    fun `空表与脏数据都退化成空表`() {
        assertTrue(JsonCodec.decodePositions(null).isEmpty())
        assertTrue(JsonCodec.decodePositions("").isEmpty())
        assertTrue(JsonCodec.decodePositions("{不是 JSON").isEmpty())
        // 空表 = 所有图都用共享配置 → 等价于统一模式，而不是整批失败
        assertTrue(JsonCodec.decodePositions(JsonCodec.encodePositions(emptyMap())).isEmpty())
    }

    @Test
    fun `位置作用域编解码`() {
        assertEquals(PositionScope.PER_IMAGE, JsonCodec.decodePositionScope("PER_IMAGE"))
        assertEquals(PositionScope.UNIFIED, JsonCodec.decodePositionScope("UNIFIED"))
        // 无法识别的值退回统一模式（向后兼容老版本入参）
        assertEquals(PositionScope.UNIFIED, JsonCodec.decodePositionScope(null))
        assertEquals(PositionScope.UNIFIED, JsonCodec.decodePositionScope("PER_IMAGE_V2"))
    }
}
