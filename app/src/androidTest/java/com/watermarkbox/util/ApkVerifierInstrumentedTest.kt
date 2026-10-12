package com.watermarkbox.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [ApkVerifier] 的真机校验测试。
 *
 * ## 为什么要跑真机
 *
 * `ApkVerifier.verify` 依赖 `PackageManager` 解析 APK 的包名与签名，
 * 这部分**无法在纯 JVM 里测**（Android 框架类在单测里是 stub，会抛异常）。
 * 因此这里用 instrumented 测试，在真机/模拟器上跑真实逻辑。
 *
 * ## 覆盖的关键场景
 *
 * 1. **自己构建的 debug APK 应当通过** —— 包名和签名都与当前应用一致。
 *    若这条挂了，说明校验逻辑会误伤正常包，功能不可用。
 * 2. **错误的 SHA-256 必须被拒绝** —— 模拟 CDN 返回损坏文件。
 * 3. **非 APK 文件必须被拒绝** —— 模拟下载到了 HTML 错误页。
 * 4. **大小不符必须被拒绝** —— 模拟下载被截断。
 */
@RunWith(AndroidJUnit4::class)
class ApkVerifierInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 找到本次构建产出的 APK。
     *
     * debug 构建时 Gradle 会把 APK 路径写进 BuildConfig，但这里更直接：
     * 从「当前已安装应用的 APK 路径」取 —— `sourceDir` 指向设备上真实的
     * 已安装 APK 文件，包名与签名必然与当前应用一致，是最好的正样本。
     */
    private fun currentApkFile(): File {
        val src = context.applicationInfo.sourceDir
        return File(src)
    }

    @Test
    fun `自己的APK能通过完整校验`() = runBlocking {
        val apk = currentApkFile()
        assertTrue("找不到已安装 APK: ${apk.absolutePath}", apk.exists())

        // 用真实哈希 + 真实大小，模拟「GitHub 提供了正确 digest」的情况
        val realHash = ApkVerifier.sha256(apk)
        val result = ApkVerifier.verify(
            context = context,
            apk = apk,
            expectedSha256 = realHash,
            expectedSize = apk.length()
        )
        assertEquals(
            "自己的 APK 必须通过校验，否则会误伤正常更新。结果=$result",
            ApkVerifier.Result.Ok,
            result
        )
    }

    @Test
    fun `没有提供digest时跳过哈希层仍能通过`() = runBlocking {
        val apk = currentApkFile()
        // 模拟 GitHub 未提供 digest（旧附件）的情况
        val result = ApkVerifier.verify(
            context = context,
            apk = apk,
            expectedSha256 = null,
            expectedSize = 0L
        )
        assertEquals(
            "digest 缺失时应仅跳过哈希层，签名校验仍须通过",
            ApkVerifier.Result.Ok,
            result
        )
    }

    @Test
    fun `哈希不符必须被拒绝`() = runBlocking {
        val apk = currentApkFile()
        val wrongHash = "0".repeat(64)
        val result = ApkVerifier.verify(
            context = context,
            apk = apk,
            expectedSha256 = wrongHash,
            expectedSize = 0L
        )
        assertTrue("哈希不符必须失败，实际=$result", result is ApkVerifier.Result.Failed)
        assertEquals(
            ApkVerifier.FailureReason.HASH_MISMATCH,
            (result as ApkVerifier.Result.Failed).reason
        )
    }

    @Test
    fun `大小不符必须被拒绝`() = runBlocking {
        val apk = currentApkFile()
        val result = ApkVerifier.verify(
            context = context,
            apk = apk,
            expectedSha256 = null,
            expectedSize = apk.length() + 1
        )
        assertTrue("大小不符必须失败，实际=$result", result is ApkVerifier.Result.Failed)
        assertEquals(
            ApkVerifier.FailureReason.SIZE_MISMATCH,
            (result as ApkVerifier.Result.Failed).reason
        )
    }

    @Test
    fun `空文件必须被拒绝`() = runBlocking {
        val tmp = File(context.cacheDir, "empty-test.apk")
        tmp.writeBytes(ByteArray(0))
        try {
            val result = ApkVerifier.verify(context, tmp, null, 0L)
            assertTrue("空文件必须失败，实际=$result", result is ApkVerifier.Result.Failed)
            assertEquals(
                ApkVerifier.FailureReason.EMPTY_FILE,
                (result as ApkVerifier.Result.Failed).reason
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `非APK内容必须被拒绝`() = runBlocking {
        // 模拟「下载到了 HTML 错误页」——网络劫持/代理的典型表现
        val tmp = File(context.cacheDir, "not-an-apk.apk")
        tmp.writeText("<html><body>404 Not Found</body></html>")
        try {
            val result = ApkVerifier.verify(context, tmp, null, 0L)
            assertTrue("非 APK 必须失败，实际=$result", result is ApkVerifier.Result.Failed)
            // 可能被判为 UNPARSEABLE（解析失败）或 PACKAGE_MISMATCH（占位包名），
            // 两者都算正确拒绝 —— 关键是**不能通过**
            val reason = (result as ApkVerifier.Result.Failed).reason
            assertTrue(
                "拒绝原因应是 UNPARSEABLE 或 PACKAGE_MISMATCH，实际=$reason",
                reason == ApkVerifier.FailureReason.UNPARSEABLE ||
                    reason == ApkVerifier.FailureReason.PACKAGE_MISMATCH
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `截断的APK必须被拒绝`() = runBlocking {
        // 模拟下载中途断开：取原 APK 的前 1KB
        val apk = currentApkFile()
        val tmp = File(context.cacheDir, "truncated.apk")
        apk.inputStream().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1024)
                val n = input.read(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        }
        try {
            val result = ApkVerifier.verify(context, tmp, null, 0L)
            assertTrue("截断的 APK 必须失败，实际=$result", result is ApkVerifier.Result.Failed)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `sha256对已安装APK稳定可复现`() {
        val apk = currentApkFile()
        val h1 = ApkVerifier.sha256(apk)
        val h2 = ApkVerifier.sha256(apk)
        assertNotNull(h1)
        assertEquals("相同文件两次哈希应一致", h1, h2)
        assertEquals("应为 64 位", 64, h1.length)
    }
}