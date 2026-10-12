package com.watermarkbox.util

import java.util.Base64

/**
 * EXIF `UserComment` 的**编码中转**工具。
 *
 * ## 为什么需要它
 *
 * AndroidX ExifInterface 1.3.7 在 `saveAttributes()` 时对 UserComment
 * 使用 **US-ASCII** 编码（已由字节码反编译确认，见
 * `ExifEncodingDiagnosticTest`）。ASCII 编码器会把所有非 ASCII 字符
 * （中文、emoji 等）**替换为 `?`（0x3F）**，导致信息永久丢失：
 *
 * ```
 * 写入：WMB1:中文测试-水印盒🎨
 * 读回：WMB1:????-?????
 * ```
 *
 * 且 1.3.7 **没有** `setAttribute(String, byte[])` 或 `setAttributeBytes`，
 * 无法用库写入 UTF-16 原始字节绕开这个限制。
 *
 * ## 方案：Base64 中转
 *
 * ```
 * 原文 ──UTF-8──► 字节 ──Base64──► 纯 ASCII 文本 ──► UserComment
 *                                                        │
 * 原文 ◄──UTF-8── 字节 ◄──Base64 解码 ◄──────────────────┘
 * ```
 *
 * Base64 的字符集是 `A-Z a-z 0-9 + / =`，**全部是 ASCII**，
 * 因此不会被替换。中文、emoji 都能完整往返（实测验证通过）。
 *
 * ## 格式
 *
 * `WMB1:B64:<base64 内容>`
 *
 * - 保留 `WMB1:` 前缀 → 检测端能快速识别「是本应用写的」
 * - 加 `B64:` 标记 → 区分「Base64 编码」与「旧的明文格式」，
 *   使老图（v1.3.0 写的明文）仍可被读取
 *
 * ## 体积代价
 *
 * Base64 会让长度膨胀 4/3 倍（44 字符 vs 原 15 字符）。
 * 由于负载上限 255 字节且 EXIF 字段本身可长，这个代价可接受。
 */
object ExifTextCodec {

    /** 隐藏水印的固定前缀（与 `WatermarkPayload.TAG_PREFIX` 保持一致）。 */
    const val TAG_PREFIX = "WMB1:"

    /** Base64 模式标记。 */
    const val B64_MARK = "B64:"

    /**
     * 把任意文本编码为可安全写入 EXIF UserComment 的 **纯 ASCII** 字符串。
     *
     * @return 形如 `WMB1:B64:xxxx`；若输入已是纯 ASCII 则直接返回 `WMB1:原文`
     *         （省去 33% 的体积膨胀，且人眼可读）
     */
    fun encode(text: String): String {
        val body = text.removePrefix(TAG_PREFIX)
        return if (isPureAscii(body)) {
            // 纯 ASCII：不编码，保持可读性
            TAG_PREFIX + body
        } else {
            val b64 = Base64.getEncoder()
                .encodeToString(body.toByteArray(Charsets.UTF_8))
            TAG_PREFIX + B64_MARK + b64
        }
    }

    /**
     * 把从 EXIF 读到的字符串解码回原文（**保留 `WMB1:` 前缀**）。
     *
     * 为什么保留前缀：检测端要用它判断「这是本应用写的水印」
     * （`WatermarkDetector` 里做的是 `startsWith(TAG_PREFIX)`）。
     *
     * 兼容三种情况：
     * 1. `WMB1:B64:xxxx` → Base64 解码后拼回前缀
     * 2. `WMB1:明文`     → 直接返回（旧版本写的）
     * 3. 其他            → 原样返回
     *
     * @return 解码后的文本（含前缀）；输入为 null 时返回 null
     */
    fun decode(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith(TAG_PREFIX)) return trimmed

        val body = trimmed.substring(TAG_PREFIX.length)
        if (!body.startsWith(B64_MARK)) return trimmed // 明文格式，原样返回

        val payload = body.substring(B64_MARK.length)
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
            ?: return trimmed // 解码失败，返回原始串（至少不丢信息）
        val decodedBody = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return trimmed
        return TAG_PREFIX + decodedBody
    }

    /**
     * 判断字符串是否全部为 ASCII（0x00~0x7F）。
     *
     * 用于决定是否需要 Base64 —— 纯 ASCII 不需要，避免无谓膨胀。
     */
    fun isPureAscii(s: String): Boolean = s.all { it.code in 0..0x7F }
}