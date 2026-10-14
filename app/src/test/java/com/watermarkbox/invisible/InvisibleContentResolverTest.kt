package com.watermarkbox.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InvisibleContentResolver.truncateToBytes] 的单元测试。
 *
 * ## 为什么值得测
 *
 * 负载上限是**按字节**算的（`MAX_DATA_BYTES`），而字符串是**按字符**的 ——
 * 两者的换算错一点，就会出现两种坏结果：
 *
 * | 错法 | 后果 |
 * |---|---|
 * | 按字符截（`take(n)`）| 中文超限，嵌入仍然失败 |
 * | 按字节切 | 多字节字符被**切成两半** → 解码出 `�` → 溯源信息损坏 |
 *
 * 而这段逻辑只在「用户输入超长」时才走到，**平时测不到** ——
 * 正是需要单测钉住的那类边界。
 *
 * 只测纯函数部分：`resolve` 需要 `Context`，属仪器化测试范畴。
 */
class InvisibleContentResolverTest {

    @Test
    fun `未超限时原样返回`() {
        assertEquals("abc", InvisibleContentResolver.truncateToBytes("abc", 10))
        assertEquals("中文", InvisibleContentResolver.truncateToBytes("中文", 6))
        assertEquals("", InvisibleContentResolver.truncateToBytes("", 5))
    }

    /** 边界恰好相等时**不应**截断。 */
    @Test
    fun `恰好等于上限时不截断`() {
        assertEquals("中文", InvisibleContentResolver.truncateToBytes("中文", 6))
        assertEquals("abc", InvisibleContentResolver.truncateToBytes("abc", 3))
    }

    /** ASCII：1 字符 = 1 字节，按字节截与按字符截等价。 */
    @Test
    fun `ASCII 按字节截断`() {
        assertEquals("abc", InvisibleContentResolver.truncateToBytes("abcdef", 3))
        assertEquals("ab", InvisibleContentResolver.truncateToBytes("abcdef", 2))
    }

    /**
     * **关键回归**：中文（3 字节/字）必须**按字节**截，而不是按字符。
     *
     * 若按字符截（`take(2)`）会得到 6 字节 —— 仍然超限。
     */
    @Test
    fun `中文按字节截断而非按字符`() {
        // "中文" = 6 字节；上限 3 只能放下一个字
        assertEquals("中", InvisibleContentResolver.truncateToBytes("中文", 3))
        // 上限 5：第二个字要 3 字节，放不下 → 只留第一个
        assertEquals("中", InvisibleContentResolver.truncateToBytes("中文", 5))
        // 上限 4 同理
        assertEquals("中", InvisibleContentResolver.truncateToBytes("中文", 4))
    }

    /**
     * **关键回归**：截断结果绝不能**切断多字节字符**。
     *
     * 判据：结果能安全往返（UTF-8 编解码不出现替换字符 `�`），
     * 且字节数不超上限。
     */
    @Test
    fun `截断结果不产生乱码且不超限`() {
        val samples = listOf("中文测试内容", "混合 mixed 中英 123", "🎨 emoji 也算多字节")
        for (s in samples) {
            for (limit in 1..s.toByteArray(Charsets.UTF_8).size) {
                val out = InvisibleContentResolver.truncateToBytes(s, limit)
                assertTrue(
                    "字节数超限：limit=$limit out=${out.toByteArray(Charsets.UTF_8).size}",
                    out.toByteArray(Charsets.UTF_8).size <= limit
                )
                assertTrue(
                    "出现替换字符（切断了多字节字符）：s=$s limit=$limit out=$out",
                    !out.contains('\uFFFD')
                )
                assertTrue("截断结果必须是原文的前缀", s.startsWith(out))
            }
        }
    }

    /** 上限为 0 时只能得到空串（不崩）。 */
    @Test
    fun `上限为零返回空串`() {
        assertEquals("", InvisibleContentResolver.truncateToBytes("abc", 0))
        assertEquals("", InvisibleContentResolver.truncateToBytes("中文", 0))
    }
}
