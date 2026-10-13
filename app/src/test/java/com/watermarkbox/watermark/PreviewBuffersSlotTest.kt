package com.watermarkbox.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pickSlot] 的单元测试。
 *
 * ## 这个函数为什么值得测
 *
 * 它的唯一职责是「**绝对不要选中正在（或刚）被显示的槽位**」。
 * 选错了的后果不是崩溃，而是**画面撕裂 / 频闪** —— 那种
 * 肉眼可见、但在代码里很难一眼看出的问题。
 *
 * 纯函数化正是为了让这条不变量能被断言，而不必在真机上肉眼观察。
 */
class PreviewBuffersSlotTest {

    // ---- 基本：游标可用时就用它 ----

    @Test
    fun 游标未被保护时沿用它() {
        assertEquals(0, pickSlot(cursor = 0, protectedIndexes = setOf(1), slotCount = 2))
        assertEquals(1, pickSlot(cursor = 1, protectedIndexes = setOf(0), slotCount = 2))
    }

    @Test
    fun 无保护时沿用它() {
        assertEquals(0, pickSlot(cursor = 0, protectedIndexes = emptySet(), slotCount = 2))
        assertEquals(1, pickSlot(cursor = 1, protectedIndexes = emptySet(), slotCount = 2))
    }

    // ---- 核心：游标被保护时顺延 ----

    @Test
    fun 游标被保护时顺延到下一块() {
        assertEquals(1, pickSlot(cursor = 0, protectedIndexes = setOf(0), slotCount = 2))
        assertEquals(0, pickSlot(cursor = 1, protectedIndexes = setOf(1), slotCount = 2))
    }

    /**
     * **本次修复的核心用例**。
     *
     * 3 块缓冲下，最近发布的两块都受保护，只能选第三块。
     * 早期实现只保护「最新发布的那一块」，此时会错误地
     * 选中另一块刚发布出去的缓冲 → 画面撕裂。
     */
    @Test
    fun 三块缓冲时可选到唯一未被保护的槽位() {
        assertEquals(2, pickSlot(cursor = 0, protectedIndexes = setOf(0, 1), slotCount = 3))
        assertEquals(0, pickSlot(cursor = 1, protectedIndexes = setOf(1, 2), slotCount = 3))
        assertEquals(1, pickSlot(cursor = 2, protectedIndexes = setOf(2, 0), slotCount = 3))
    }

    /** 即使游标在保护集合外，也不该绕远路。 */
    @Test
    fun 游标可用时不做多余绕行() {
        assertEquals(1, pickSlot(cursor = 1, protectedIndexes = setOf(2), slotCount = 3))
    }

    // ---- 边界与异常 ----

    @Test
    fun 越界游标归零() {
        assertEquals(0, pickSlot(cursor = -5, protectedIndexes = emptySet(), slotCount = 2))
        assertEquals(0, pickSlot(cursor = 99, protectedIndexes = emptySet(), slotCount = 2))
    }

    /** 全部受保护属调用方违约，但函数必须给出确定结果而非抛异常。 */
    @Test
    fun 全部受保护时兜底返回游标() {
        assertEquals(0, pickSlot(cursor = 0, protectedIndexes = setOf(0, 1), slotCount = 2))
    }

    /**
     * **不变量**：只要保护窗口正确留了至少一块（窗口 ≤ slotCount-1），
     * 返回值就**永远不在保护集合里**。
     */
    @Test
    fun 返回值永远不在保护集合内() {
        var checked = 0
        for (slotCount in 2..5) {
            for (cursor in 0 until slotCount) {
                val others = (0 until slotCount).filter { it != cursor }
                // 枚举「窗口大小 = slotCount - 1」的各种保护集合
                for (excluded in others) {
                    val protected = others.filter { it != excluded }.toSet()
                    val slot = pickSlot(cursor, protected, slotCount)
                    assertTrue(
                        "slotCount=$slotCount cursor=$cursor protected=$protected " +
                            "不应选中受保护的槽位（选中了 $slot）",
                        slot !in protected
                    )
                    checked++
                }
            }
        }
        println("INVARIANT checked=$checked cases")
    }

    /** 顺延应尽量贴近游标（不做无意义的跳远），避免破坏轮换节奏。 */
    @Test
    fun 顺延贴近游标() {
        // cursor=1 被保护 → 应选 2（下一个），而不是绕到 0
        assertEquals(2, pickSlot(cursor = 1, protectedIndexes = setOf(1), slotCount = 3))
        // 下一个也被保护时继续顺延
        assertEquals(2, pickSlot(cursor = 0, protectedIndexes = setOf(0, 1), slotCount = 3))
    }
}