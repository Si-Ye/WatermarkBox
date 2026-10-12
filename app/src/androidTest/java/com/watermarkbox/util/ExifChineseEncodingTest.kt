package com.watermarkbox.util

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EXIF UserComment **中文编码**的端到端真机测试。
 *
 * ## 背景（已由字节码反编译确认）
 *
 * AndroidX ExifInterface 1.3.7 的 `saveAttributes()` 对 UserComment
 * 使用 `String.getBytes(US_ASCII)`，非 ASCII 字符（中文/emoji）
 * 会被替换为 `?`：
 *
 * ```
 * 写入：WMB1:中文测试-水印盒🎨
 * 读回：WMB1:????-?????      ← 信息永久丢失
 * ```
 *
 * 且该版本没有 `setAttributeBytes`，无法绕过。
 *
 * ## 本测试验证的修复
 *
 * [ExifTextCodec] 用 Base64 中转（结果保证纯 ASCII），
 * [ExifCopier.writeUserComment] / [readUserComment] 自动编解码。
 *
 * 这里走**真实文件路径**（cacheDir 下的 JPEG），确认修复有效。
 */
@RunWith(AndroidJUnit4::class)
class ExifChineseEncodingTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 测试日志落盘目录。
     *
     * 用 `cacheDir` 而非外部存储路径：不依赖存储权限，任何设备都能跑。
     * 需要查看结果时，从 `/data/data/com.watermarkbox/cache/` 取出即可。
     */
    private val dumpTarget: File get() = ctx.cacheDir

    private val sb = StringBuilder()

    private fun note(line: String) {
        sb.append(line).append('\n')
    }

    /** 造一个真实 JPEG 文件并返回其 Uri（用 FileProvider 之外的方式：
     *  直接写应用私有目录，再通过 `file://` 之外的路径读取 —— 
     *  这里为简化，直接用绝对路径的 File API 做测试，
     *  因为 ExifInterface 支持 `File` 与 `InputStream` 两种入口。 */
    private fun newJpegFile(name: String): File {
        val f = File(ctx.cacheDir, name)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }

    @Test
    fun chinese_survives_round_trip_through_real_exif() {
        val cases = listOf(
            "WMB1:中文测试-水印盒",
            "WMB1:emoji🎨🔥",
            "WMB1:© 2026 某某工作室",
            "WMB1:纯净模式：设备 A1B2C3D4",
            "WMB1:日本語のテスト",
            "WMB1:한국어 테스트"
        )

        try {
            cases.forEach { original ->
                val f = newJpegFile("wmb_cn_${original.hashCode()}.jpg")
                try {
                    // ---- 用生产代码的编码逻辑写入 ----
                    val encoded = ExifTextCodec.encode(original)
                    ExifInterface(f.absolutePath).apply {
                        setAttribute(
                            ExifInterface.TAG_USER_COMMENT,
                            encoded
                        )
                        saveAttributes()
                    }

                    // ---- 读回并解码 ----
                    val raw = ExifInterface(f.absolutePath)
                        .getAttribute(
                            ExifInterface.TAG_USER_COMMENT
                        )
                    val decoded = ExifTextCodec.decode(raw)

                    note("原文   = $original")
                    note("  编码 = $encoded")
                    note("  读回 = $raw")
                    note("  解码 = $decoded")
                    note("  ✅ 无损? ${decoded == original}")
                    note("")

                    assertEquals("「$original」必须无损往返", original, decoded)
                    // 且读回的原始串必须是纯 ASCII（否则说明被破坏了）
                    assertNotNull(raw)
                    assertTrue(
                        "写入 EXIF 的串必须是纯 ASCII，实际: $raw",
                        ExifTextCodec.isPureAscii(raw!!)
                    )
                } finally {
                    f.delete()
                }
            }
        } catch (t: Throwable) {
            note("EXCEPTION: ${t.stackTraceToString()}")
            throw t
        } finally {
            runCatching {
                File(dumpTarget, "wmb_exif_cn.txt").writeText(sb.toString())
            }
        }
    }

    @Test
    fun without_codec_chinese_would_break() {
        // **对照实验**：证明不做 Base64 中转的话中文确实会变 `?`。
        // 这钉住「为什么需要 ExifTextCodec」这个前提。
        val original = "WMB1:中文测试"
        val f = newJpegFile("wmb_broken.jpg")
        try {
            ExifInterface(f.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    original  // 直接用原文，不经 codec
                )
                saveAttributes()
            }
            val read = ExifInterface(f.absolutePath)
                .getAttribute(
                    ExifInterface.TAG_USER_COMMENT
                )
            note("【对照】不经 codec 直接写中文")
            note("  原文 = $original")
            note("  读回 = $read")

            // 期望：确实被破坏（含 ? 或不等于原文）
            val broken = read != original
            note("  是否被破坏? $broken")
            assertTrue(
                "若这里不再被破坏，说明 ExifInterface 行为变了，" +
                    "可以移除 ExifTextCodec 的 Base64 中转",
                broken
            )
        } finally {
            f.delete()
            runCatching {
                File(dumpTarget, "wmb_exif_cn.txt").writeText(sb.toString())
            }
        }
    }
}