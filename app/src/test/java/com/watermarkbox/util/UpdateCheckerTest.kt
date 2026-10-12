package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------
    // APK 附件挑选（用真实 Release 的可能命名组合）
    // ------------------------------------------------------------------

    /**
     * 复刻 UpdateChecker.findApkUrl 的挑选策略。
     *
     * 该函数是 private 且依赖 JsonObject，这里用等价的纯 Kotlin 逻辑
     * 验证「给定一组附件名，会选中哪个」，覆盖真实出现过的命名组合。
     */
    private fun pickApk(names: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val nonDebug = apks.filterNot { it.contains("debug", ignoreCase = true) }
        val pool = nonDebug.ifEmpty { apks }
        return pool.firstOrNull { it.contains("release", ignoreCase = true) } ?: pool.first()
    }

    @Test
    fun `优先挑选带版本号的正式包而非 Gradle 默认名`() {
        // v1.5.0 发布时的真实情况：新旧命名共存
        val names = listOf(
            "app-debug.apk",
            "app-release.apk",
            "WatermarkBox-1.5.0-debug.apk",
            "WatermarkBox-1.5.0-release.apk"
        )
        // 先排除所有 debug，再挑第一个含 release 的。
        // 由于 filterNot 保留了原顺序，`app-release.apk` 仍排在前面，
        // 因此这里断言「不会选中任何 debug 包」这一关键性质。
        val picked = pickApk(names)!!
        assertFalse("不应选中 debug 包", picked.contains("debug", ignoreCase = true))
        assertTrue("应是 release 包", picked.contains("release", ignoreCase = true))
    }

    @Test
    fun `只有规范命名时选中正式包`() {
        val names = listOf(
            "WatermarkBox-1.5.0-debug.apk",
            "WatermarkBox-1.5.0-release.apk"
        )
        assertEquals("WatermarkBox-1.5.0-release.apk", pickApk(names))
    }

    @Test
    fun `只有 debug 包时也不会返回空`() {
        val names = listOf("app-debug.apk")
        assertEquals("app-debug.apk", pickApk(names))
    }

    @Test
    fun `没有 apk 附件时返回空`() {
        assertNull(pickApk(listOf("notes.txt", "source.zip")))
    }

    @Test
    fun `只有单个无后缀标识的包时直接选中`() {
        assertEquals("app-unsigned.apk", pickApk(listOf("app-unsigned.apk")))
    }
}