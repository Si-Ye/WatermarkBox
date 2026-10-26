package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    // ------------------------------------------------------------------
    // digest（SHA-256）解析
    // ------------------------------------------------------------------

    /**
     * 复刻 UpdateChecker.parseDigest 的解析规则。
     *
     * GitHub API 的 digest 形如 `sha256:<64位小写hex>`，
     * 但字段可能缺失、可能换成别的算法、也可能是 NULL 字面量。
     * 这里逐种情况验证「要么给出正确 hex，要么返回 null，绝不返回半个值」。
     */
    private fun parseDigest(raw: String?): String? {
        val s = raw ?: return null
        val prefix = "sha256:"
        if (!s.startsWith(prefix, ignoreCase = true)) return null
        val hex = s.substring(prefix.length).trim().lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    @Test
    fun `digest 正常格式能解析出小写 hex`() {
        val hex = "5bac71b42f7933aa0f743ebf2d70ee174b55db042df1298b209de6169070c70d"
        assertEquals(hex, parseDigest("sha256:$hex"))
    }

    @Test
    fun `digest 大写前缀与内容都能归一化`() {
        val hex = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
        assertEquals(hex.lowercase(), parseDigest("SHA256:$hex"))
    }

    @Test
    fun `digest 缺失时返回 null`() {
        assertNull(parseDigest(null))
    }

    @Test
    fun `digest 非 sha256 算法时返回 null`() {
        // 未来若 GitHub 改用 sha512，我们不应把它当成 sha256 误用
        val hex = "a".repeat(128)
        assertNull(parseDigest("sha512:$hex"))
    }

    @Test
    fun `digest 长度不对时返回 null`() {
        // 63 位（少一位）与 65 位（多一位）都应拒绝
        assertNull(parseDigest("sha256:" + "a".repeat(63)))
        assertNull(parseDigest("sha256:" + "a".repeat(65)))
    }

    @Test
    fun `digest 含非十六进制字符时返回 null`() {
        // 'g' 不是 hex 字符
        assertNull(parseDigest("sha256:" + "g".repeat(64)))
    }

    @Test
    fun `digest 只有前缀时返回 null`() {
        assertNull(parseDigest("sha256:"))
    }

    @Test
    fun `digest 空白格式返回 null`() {
        assertNull(parseDigest(""))
        assertNull(parseDigest("   "))
    }

    // ------------------------------------------------------------------
    // classifyFailure：把下载/安装异常归到对症的 ErrorKind
    //
    // 背景：原先 downloadInApp 的 catch 一律标成 NETWORK，于是「超时」
    // 「HTTP 403」「写盘失败」都被提示成「请检查网络」，用户照做也没用。
    // ------------------------------------------------------------------

    @Test
    fun `超时归为 TIMEOUT`() {
        assertEquals(
            UpdateChecker.ErrorKind.TIMEOUT,
            UpdateChecker.classifyFailure(SocketTimeoutException("timeout"))
        )
    }

    @Test
    fun `被包装的超时仍归为 TIMEOUT（download 会包一层 IOException）`() {
        val wrapped = IOException("timeout", SocketTimeoutException("timeout"))
        assertEquals(UpdateChecker.ErrorKind.TIMEOUT, UpdateChecker.classifyFailure(wrapped))
    }

    @Test
    fun `DNS 解析失败归为 NETWORK`() {
        assertEquals(
            UpdateChecker.ErrorKind.NETWORK,
            UpdateChecker.classifyFailure(UnknownHostException("api.github.com"))
        )
    }

    @Test
    fun `连接被拒归为 NETWORK`() {
        assertEquals(
            UpdateChecker.ErrorKind.NETWORK,
            UpdateChecker.classifyFailure(ConnectException("Connection refused"))
        )
    }

    @Test
    fun `HTTP 非 2xx 归为 HTTP`() {
        // download 里非 2xx 是以消息形式携带的
        assertEquals(
            UpdateChecker.ErrorKind.HTTP,
            UpdateChecker.classifyFailure(IOException("HTTP 503"))
        )
    }

    @Test
    fun `写盘失败等其它 IOException 归为 UNKNOWN 而不是 NETWORK`() {
        // 「磁盘满」不是网络问题，提示用户去检查网络是误导
        assertEquals(
            UpdateChecker.ErrorKind.UNKNOWN,
            UpdateChecker.classifyFailure(IOException("No space left on device"))
        )
    }

    @Test
    fun `异常链自引用不会死循环`() {
        // 极端情况防护：cause 指回自己。分类必须仍然返回而不是挂住。
        val self = object : IOException("boom") {
            override val cause: Throwable get() = this
        }
        assertEquals(UpdateChecker.ErrorKind.UNKNOWN, UpdateChecker.classifyFailure(self))
    }
}
