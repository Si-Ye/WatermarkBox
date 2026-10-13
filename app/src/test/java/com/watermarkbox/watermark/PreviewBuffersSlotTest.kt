package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [nextSlotIndex] 的单元测试。
 *
 * `PreviewBuffers` 本身碰 `Bitmap`（JVM 单测里构造不了），
 * 所以把「绝不算正在显示的那块」这条**唯一的规则**抽出来单独测 ——
 * 它错了的表现是「画面偶尔撕裂」，肉眼极难复现、更难归因。
 */
class PreviewBuffersSlotTest {

    @Test
    fun `游标与发布位不同时沿用游标`() {
        assertEquals(0, nextSlotIndex(cursor = 0, publishedIndex = 1, slotCount = 2))
        assertEquals(1, nextSlotIndex(cursor = 1, publishedIndex = 0, slotCount = 2))
    }

    /** 核心规则：撞上「正在显示的那块」就换一块。 */
    @Test
    fun `游标撞上发布位时跳到下一块`() {
        assertEquals(1, nextSlotIndex(cursor = 0, publishedIndex = 0, slotCount = 2))
        assertEquals(0, nextSlotIndex(cursor = 1, publishedIndex = 1, slotCount = 2))
    }

    /** 还没发布过（publishedIndex = -1）时不该被误判成「撞上」。 */
    @Test
    fun `没有发布位时沿用游标`() {
        assertEquals(0, nextSlotIndex(cursor = 0, publishedIndex = -1, slotCount = 2))
        assertEquals(1, nextSlotIndex(cursor = 1, publishedIndex = -1, slotCount = 2))
    }

    @Test
    fun `三块缓冲时循环正确`() {
        assertEquals(1, nextSlotIndex(cursor = 0, publishedIndex = 0, slotCount = 3))
        assertEquals(2, nextSlotIndex(cursor = 1, publishedIndex = 1, slotCount = 3))
        // 最后一块撞上发布位 → 回到第一块
        assertEquals(0, nextSlotIndex(cursor = 2, publishedIndex = 2, slotCount = 3))
    }

    /** 越界游标要能自愈，不能拿它去索引数组。 */
    @Test
    fun `越界游标回到 0`() {
        assertEquals(0, nextSlotIndex(cursor = -1, publishedIndex = -1, slotCount = 2))
        assertEquals(0, nextSlotIndex(cursor = 5, publishedIndex = -1, slotCount = 2))
    }

    /** 结果必须始终是合法下标 —— 否则 acquire 会越界。 */
    @Test
    fun `结果始终落在合法范围内`() {
        for (slots in 2..4) {
            for (cursor in 0 until slots) {
                for (published in -1 until slots) {
                    val idx = nextSlotIndex(cursor, published, slots)
                    assertFalse("下标越界：$idx (slots=$slots)", idx !in 0 until slots)
                }
            }
        }
    }
}