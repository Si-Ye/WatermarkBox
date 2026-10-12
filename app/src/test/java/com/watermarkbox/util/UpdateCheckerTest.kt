package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的纯逻辑单元测试。
 *
 * 只测不依赖 Android / 网络的部分：版本号归一化与比较。
 * （网络请求与 JSON 解析需要集成测试，这里不覆盖。）
 */
class UpdateCheckerTest {

    // ------------------------------------------------------------------
    // normalizeVersion：剥离各种前缀
    // ------------------------------------------------------------------

    @Test
    fun `剥离小写 v 前缀`() {
        assertEquals("1.5.0", UpdateChecker.normalizeVersion("v1.5.0"))
    }

    @Test
    fun `剥离大写 V 前缀`() {
        assertEquals("1.5.0", UpdateChecker.normalizeVersion("V1.5.0"))
    }

    @Test
    fun `剥离 release- 前缀`() {
        assertEquals("1.5.0", UpdateChecker.normalizeVersion("release-1.5.0"))
    }

    @Test
    fun `无前缀时原样返回`() {
        assertEquals("1.5.0", UpdateChecker.normalizeVersion("1.5.0"))
    }

    @Test
    fun `去除首尾空白`() {
        assertEquals("1.5.0", UpdateChecker.normalizeVersion("  v1.5.0  "))
    }

    // ------------------------------------------------------------------
    // isNewer：版本比较
    // ------------------------------------------------------------------

    @Test
    fun `主版本更高的更`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `主版本更低的不更新的`() {
        assertFalse(UpdateChecker.isNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun `次版本更高判定为新`() {
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.4.1"))
    }

    @Test
    fun `补丁版本更高判定为新`() {
        assertTrue(UpdateChecker.isNewer("1.4.2", "1.4.1"))
    }

    @Test
    fun `完全相同的版本不算新`() {
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.4.1"))
    }

    @Test
    fun `段数不同时缺失段视为零`() {
        // 1.4 等价于 1.4.0，不比 1.4.0 新
        assertFalse(UpdateChecker.isNewer("1.4", "1.4.0"))
        // 但 1.4.1 比 1.4 新
        assertTrue(UpdateChecker.isNewer("1.4.1", "1.4"))
    }

    @Test
    fun `双段版本与三段版本比较正确`() {
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.9", "1.9.9"))
    }

    @Test
    fun `带预发布后缀时取前导数字`() {
        // 1.5.0-beta 的前导数字是 1.5.0，比 1.4.1 新
        assertTrue(UpdateChecker.isNewer("1.5.0-beta", "1.4.1"))
        // 与同版本号比较：前导数字相同 → 不算新
        assertFalse(UpdateChecker.isNewer("1.5.0-beta", "1.5.0"))
    }

    @Test
    fun `完全无法解析的版本视为零`() {
        // 非法版本号不应当被判定为「比当前新」
        assertFalse(UpdateChecker.isNewer("abc", "1.0.0"))
        // 反过来，当前是非法的，任何正常版本都算新
        assertTrue(UpdateChecker.isNewer("1.0.0", "abc"))
    }

    @Test
    fun `连续调用两次结果一致（无副作用）`() {
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.4.1"))
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.4.1"))
    }

    // ------------------------------------------------------------------
    // 组合场景：normalize + isNewer
    // ------------------------------------------------------------------

    @Test
    fun `真实场景 tag 带 v 时能正确比较`() {
        val remote = UpdateChecker.normalizeVersion("v1.5.0")
        assertTrue(UpdateChecker.isNewer(remote, "1.4.1"))
    }

    @Test
    fun `远端与本地同版本时不提示更新`() {
        val remote = UpdateChecker.normalizeVersion("v1.4.1")
        assertFalse(UpdateChecker.isNewer(remote, "1.4.1"))
    }
}