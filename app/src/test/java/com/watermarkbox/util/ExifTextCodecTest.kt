package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExifTextCodec] 的编解码测试。
 *
 * ## 背景
 *
 * AndroidX ExifInterface 1.3.7 对 UserComment 用 US-ASCII 编码，
 * 中文会被替换成 `?`（实测：`WMB1:中文测试` → `WMB1:????`）。
 *
 * 对策是 Base64 中转。本测试钉住：
 * 1. 中文/emoji 能**无损往返**
 * 2. 纯 ASCII 不做无谓编码（保持可读性）
 * 3. 向后兼容旧版明文格式
 */
class ExifTextCodecTest {

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    @Test
    fun encode_keeps_ascii_readable() {
        val encoded = ExifTextCodec.encode("WMB1:hello-world")
        // 纯 ASCII 不编码 → 人眼仍可读
        assertEquals("WMB1:hello-world", encoded)
        assertFalse(
            "纯 ASCII 不应引入 B64 标记",
            encoded.contains(ExifTextCodec.B64_MARK)
        )
    }

    @Test
    fun encode_uses_base64_for_chinese() {
        val encoded = ExifTextCodec.encode("WMB1:中文测试")
        assertTrue(
            "含中文时必须用 Base64",
            encoded.startsWith("WMB1:B64:")
        )
    }

    @Test
    fun encode_output_is_pure_ascii() {
        // 这是修复的核心：编码结果必须全 ASCII，否则会被 ExifInterface 破坏
        val cases = listOf(
            "WMB1:中文测试-水印盒",
            "WMB1:emoji🎨🔥",
            "WMB1:混合 mixed 内容 123",
            "WMB1:日本語のテスト",
            "WMB1:한국어 테스트",
            "WMB1:Ünïcödé ñ",
            "WMB1:АБВГД"
        )
        cases.forEach { text ->
            val encoded = ExifTextCodec.encode(text)
            assertTrue(
                "「$text」编码后应为纯 ASCII，实际: $encoded",
                ExifTextCodec.isPureAscii(encoded)
            )
        }
    }

    @Test
    fun encode_handles_text_without_prefix() {
        val encoded = ExifTextCodec.encode("中文内容")
        assertTrue("应自动补上前缀", encoded.startsWith("WMB1:"))
        // decode 保留前缀（检测端依赖它判断来源）
        assertEquals("WMB1:中文内容", ExifTextCodec.decode(encoded))
    }

    // ------------------------------------------------------------------
    // 解码 / 往返
    // ------------------------------------------------------------------

    @Test
    fun round_trip_chinese() {
        val text = "WMB1:中文测试-水印盒"
        assertEquals(text, ExifTextCodec.decode(ExifTextCodec.encode(text)))
    }

    @Test
    fun round_trip_emoji() {
        val text = "WMB1:emoji🎨🔥💧"
        assertEquals(text, ExifTextCodec.decode(ExifTextCodec.encode(text)))
    }

    @Test
    fun round_trip_mixed_content() {
        val text = "WMB1:© 2026 某某工作室 · 设备 ABC123 · 已导出"
        assertEquals(text, ExifTextCodec.decode(ExifTextCodec.encode(text)))
    }

    @Test
    fun round_trip_all_common_scripts() {
        val cases = listOf(
            "WMB1:中文",
            "WMB1:日本語",
            "WMB1:한국어",
            "WMB1:Русский",
            "WMB1:Ελληνικά",
            "WMB1:العربية",
            "WMB1:עברית",
            "WMB1:ไทย",
            "WMB1:हिन्दी"
        )
        cases.forEach { text ->
            assertEquals("「$text」应无损往返", text, ExifTextCodec.decode(ExifTextCodec.encode(text)))
        }
    }

    @Test
    fun round_trip_ascii_unchanged() {
        val text = "WMB1:DEV:A1B2C3D4:20260101120000"
        assertEquals(text, ExifTextCodec.decode(ExifTextCodec.encode(text)))
    }

    // ------------------------------------------------------------------
    // 向后兼容
    // ------------------------------------------------------------------

    @Test
    fun decode_accepts_legacy_plain_format() {
        // v1.3.0 写的是明文格式，必须仍能读
        val legacy = "WMB1:legacy-plain-text"
        assertEquals(legacy, ExifTextCodec.decode(legacy))
    }

    @Test
    fun decode_returns_null_for_null() {
        assertNull(ExifTextCodec.decode(null))
    }

    @Test
    fun decode_passes_through_foreign_content() {
        // 别人写的 UserComment（无本应用前缀）→ 原样返回，不擅自"解码"
        val foreign = "Some other app's comment"
        assertEquals(foreign, ExifTextCodec.decode(foreign))
    }

    @Test
    fun decode_survives_corrupted_base64() {
        // Base64 被破坏（如手动改过 EXIF）→ 不应崩溃，返回原始串
        val broken = "WMB1:B64:@@@not-valid-base64@@@"
        assertEquals(broken, ExifTextCodec.decode(broken))
    }

    // ------------------------------------------------------------------
    // 边界（code-review-2026-10 的 M3）
    // ------------------------------------------------------------------

    @Test
    fun decode_rejects_empty_base64_payload() {
        // ⚠️ `Base64.decode("")` **不抛异常**，返回空数组 →
        // 若不显式挡掉，会「成功解码」出空字符串，
        // 检测端 `startsWith(WMB1:)` 就会误报「检测到水印」。
        val emptyPayload = "WMB1:B64:"
        assertEquals(
            "空 payload 不应被当成有效水印",
            emptyPayload,
            ExifTextCodec.decode(emptyPayload)
        )
        assertFalse(
            "尤其不得解码成裸前缀 WMB1:",
            ExifTextCodec.decode(emptyPayload) == "WMB1:"
        )
    }

    @Test
    fun round_trip_content_starting_with_b64_mark() {
        // ⚠️ 用户的合法文本恰好以 `B64:` 开头时，不能与「编码标记」撞车。
        // 若不特殊处理：encode 产出 `WMB1:B64:hello`，
        // decode 再把 `hello` 当 Base64 解 → 往返不成立。
        val tricky = "WMB1:B64:hello"
        assertEquals(
            "以 B64: 开头的内容必须能无损往返",
            tricky,
            ExifTextCodec.decode(ExifTextCodec.encode(tricky))
        )
    }

    @Test
    fun encode_forces_base64_when_body_starts_with_mark() {
        val encoded = ExifTextCodec.encode("B64:payload")
        assertTrue(
            "以 B64: 开头的内容必须走 Base64 分支",
            encoded.startsWith("WMB1:B64:")
        )
        assertTrue(
            "且确实编码过（不是裸拼接）",
            encoded != "WMB1:B64:payload"
        )
    }

    // ------------------------------------------------------------------
    // 辅助判定
    // ------------------------------------------------------------------

    @Test
    fun is_pure_ascii_works() {
        assertTrue(ExifTextCodec.isPureAscii("abcXYZ019"))
        assertTrue(ExifTextCodec.isPureAscii(""))
        assertFalse(ExifTextCodec.isPureAscii("中文"))
        assertFalse(ExifTextCodec.isPureAscii("a中b"))
        assertFalse(ExifTextCodec.isPureAscii("emoji🎨"))
    }

    @Test
    fun base64_expansion_is_acceptable() {
        // 记录体积代价：Base64 膨胀约 4/3
        val text = "WMB1:中文测试"
        val encoded = ExifTextCodec.encode(text)
        // 原文 9 字符 / 15 字节（UTF-8）→ Base64 后 20 字符 + 前缀
        assertTrue(
            "编码后长度应在合理范围内，实际 ${encoded.length}",
            encoded.length < 60
        )
    }
}