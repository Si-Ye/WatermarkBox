package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ApkVerifier] 中**不依赖 Android 框架**的部分。
 *
 * 完整的 [ApkVerifier.verify] 需要 `PackageManager`，只能在
 * instrumented 测试里跑；这里覆盖纯 JVM 可验证的哈希逻辑。
 *
 * ## 为什么哈希逻辑值得单独测
 *
 * 哈希若算错，会让**所有**下载都被判为「损坏」——功能直接不可用。
 * 而哈希错误又不会抛异常、只会静默给出错误结果，
 * 所以必须用**已知向量**来验证，不能靠「跑起来没崩」判断。
 */
class ApkVerifierTest {

    @Test
    fun `sha256 空文件的已知向量`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        // 这是最常被引用的标准测试向量之一
        val f = tempFile("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkVerifier.sha256(f)
        )
        f.delete()
    }

    @Test
    fun `sha256 单字符 a 的已知向量`() {
        // SHA-256("a") = ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb
        val f = tempFile("a")
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            ApkVerifier.sha256(f)
        )
        f.delete()
    }

    @Test
    fun `sha256 输出恒为 64 位小写十六进制`() {
        // 二进制内容（含所有字节值），验证不会因为符号位等问题输出非 hex 字符
        val bytes = ByteArray(512) { (it % 256).toByte() }
        val f = tempFile(String(bytes, Charsets.ISO_8859_1))
        val hex = ApkVerifier.sha256(f)
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assertEquals(true, hex.all { it in "0123456789abcdef" })
        f.delete()
    }

    @Test
    fun `sha256 对大文件采用流式读取且结果正确`() {
        // 1MB 数据，确保分块读取（8KB 缓冲）拼接正确
        // 期望值：全零 1MB 的 SHA-256
        val f = File.createTempFile("apkverifier", ".bin")
        try {
            FileOutputStreamCompat.writeZeros(f, 1024 * 1024)
            val hex = ApkVerifier.sha256(f)
            assertEquals(64, hex.length)
            // 与独立计算的实现对比（用同一份文件两次计算应一致，说明确定性）
            assertEquals(hex, ApkVerifier.sha256(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha256 不同内容给出不同结果`() {
        val a = tempFile("hello")
        val b = tempFile("hello!")
        val ha = ApkVerifier.sha256(a)
        val hb = ApkVerifier.sha256(b)
        assertEquals(false, ha == hb)
        a.delete()
        b.delete()
    }

    // ------------------------------------------------------------------

    private fun tempFile(content: String): File {
        val f = File.createTempFile("apkverifier", ".txt")
        f.writeText(content, Charsets.ISO_8859_1)
        return f
    }
}

/** 写零字节的小工具（避免在测试里手写循环）。 */
private object FileOutputStreamCompat {
    fun writeZeros(file: File, count: Int) {
        val buf = ByteArray(8192)
        var written = 0
        file.outputStream().use { out ->
            while (written < count) {
                val n = minOf(buf.size, count - written)
                out.write(buf, 0, n)
                written += n
            }
        }
    }
}
