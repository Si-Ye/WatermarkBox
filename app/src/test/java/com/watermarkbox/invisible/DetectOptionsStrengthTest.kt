package com.watermarkbox.invisible

import com.watermarkbox.data.model.InvisibleWatermarkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检测端强度常量与**导出侧**保持一致的不变量测试。
 *
 * ## 为什么值得单独钉住
 *
 * 「导出用什么强度」与「检测试哪些强度」分处两个文件，靠**约定**对齐 ——
 * 而约定会漂移。本项目真实发生过**两次**：
 *
 * 1. `DEFAULT_STRENGTH` 是 `1f`，注释却声称"与导出默认值一致"，
 *    实际导出默认是 `2` → 用户关掉「自动强度」后**自己导出的图检不出来**；
 * 2. 导出侧 M4 收紧到 `coerceIn(1, 2)` 后，检测端候选里仍留着
 *    `3f`/`4f` → 每次自动检测**白跑 50%** 的全图 DCT。
 *
 * 这类"改了 A 没同步 B"的缺陷**不会编译失败、也不会崩**，
 * 只会表现为「莫名其妙检不出来」或「莫名奇妙很慢」—— 正是测试该守的地方。
 *
 * 这里把「两处必须一致」写成断言：以后任一侧单独改档位，测试立刻红。
 */
class DetectOptionsStrengthTest {

    /** 导出侧的默认强度（`InvisibleWatermarkConfig` 的默认值）。 */
    private val exportDefault: Float = InvisibleWatermarkConfig().strength.toFloat()

    @Test
    fun `默认强度与导出默认值一致`() {
        assertEquals(
            "检测端 DEFAULT_STRENGTH 必须等于导出侧默认强度" +
                "（否则关掉「自动强度」时会漏检自己的图）",
            exportDefault,
            DetectOptions.DEFAULT_STRENGTH,
            0f
        )
    }

    @Test
    fun `候选强度只含导出可能产生的档位`() {
        // 导出侧 updateInvisibleStrength 已 coerceIn(1, 2)
        val candidates = DetectOptions.STRENGTH_CANDIDATES
        assertTrue(
            "候选里出现了导出端不可能产生的档位：$candidates",
            candidates.all { it == 1f || it == 2f }
        )
    }

    @Test
    fun `候选强度第一个是导出默认值`() {
        assertEquals(
            "命中率最高的应排最前（自动模式能少跑一轮）",
            exportDefault,
            DetectOptions.STRENGTH_CANDIDATES.first(),
            0f
        )
    }

    @Test
    fun `候选强度不含重复`() {
        val c = DetectOptions.STRENGTH_CANDIDATES
        assertEquals("候选里有重复档位：$c", c.size, c.distinct().size)
    }

    @Test
    fun `关闭自动强度时试的值在导出可能范围内`() {
        val d = DetectOptions.DEFAULT_STRENGTH
        assertTrue("DEFAULT_STRENGTH=$d 不在导出可能档位（1、2）内", d == 1f || d == 2f)
    }
}