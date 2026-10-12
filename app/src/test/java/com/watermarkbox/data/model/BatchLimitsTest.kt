package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量上限与制作人名单测试。
 */
class BatchLimitsTest {

    // =================================================================
    // 批量上限
    // =================================================================

    @Test
    fun `请求上限为 100`() {
        assertEquals(100, BatchLimits.REQUEST_MAX_ITEMS)
    }

    @Test
    fun `UI 上限与请求上限一致 —— 防止选得多但存得少`() {
        assertEquals(
            "MAX_BATCH_SIZE 必须等于 REQUEST_MAX_ITEMS",
            BatchLimits.REQUEST_MAX_ITEMS,
            BatchLimits.MAX_BATCH_SIZE
        )
    }

    @Test
    fun `兼容别名指向同一值`() {
        assertEquals(BatchLimits.MAX_BATCH_SIZE, BatchLimits.DEFAULT_MAX_BATCH)
    }

    @Test
    fun `上限比原先的 50 更大`() {
        assertTrue(BatchLimits.MAX_BATCH_SIZE > 50)
    }

    @Test
    fun `上限不超过 Photo Picker 通行的系统值 150`() {
        assertTrue(
            "超过系统上限会导致 Picker 行为异常",
            BatchLimits.MAX_BATCH_SIZE <= 150
        )
    }

    @Test
    fun `上限为正数`() {
        assertTrue(BatchLimits.MAX_BATCH_SIZE > 0)
    }

    // =================================================================
    // 制作人名单
    // =================================================================

    @Test
    fun `制作人名单第一项是肆叶`() {
        assertEquals("肆叶🍃", AppCredits.entries.first().name)
    }

    @Test
    fun `主创标记在第 1 位`() {
        assertTrue("第 1 位应标记为主创", AppCredits.lead.isLead)
        assertEquals(
            "lead 必须是列表第 1 项",
            AppCredits.entries.first(),
            AppCredits.lead
        )
    }

    @Test
    fun `名单不为空且无重复`() {
        val names = AppCredits.entries.map { it.name }
        assertTrue("名单不应为空", names.isNotEmpty())
        assertEquals("名单不应有重复项", names.size, names.toSet().size)
    }

    @Test
    fun `只有第 1 位是主创`() {
        val leads = AppCredits.entries.filter { it.isLead }
        assertEquals("应恰好有 1 位主创", 1, leads.size)
        assertEquals(AppCredits.entries.first(), leads.first())
    }
}