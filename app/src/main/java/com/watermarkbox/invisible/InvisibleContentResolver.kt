package com.watermarkbox.invisible

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.data.model.InvisibleWatermarkConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 隐藏水印**内容解析器**。
 *
 * 把用户在面板里的配置（自定义文字 / 设备ID+时间戳）解析成
 * 最终要嵌入的字符串。
 *
 * ## 内容规范
 *
 * 最终字符串统一带 `WMB1:` 前缀，便于：
 * - 检测端一眼区分「本 App 的水印」与「别人塞的随机 EXIF」
 * - 人工用十六进制工具查看时能识别来源
 *
 * 格式：
 * - 自定义：`WMB1:<用户输入>`
 * - 自动：  `WMB1:DEV:<设备短ID>:<时间戳>`
 *
 * ## 设备 ID 的选择
 *
 * 用 `Settings.Secure.ANDROID_ID` 的**前 8 位十六进制**：
 * - 它对同一设备 + 同一 App 签名稳定
 * - 不涉及任何需要权限的标识符（IMEI 在 Android 10+ 拿不到，也不能拿）
 * - 截短到 8 位是隐私与唯一性的折中（约 43 亿种组合）
 */
object InvisibleContentResolver {

    /** 时间戳格式：紧凑、无空格，避免文件名/EXIF 兼容问题。 */
    private const val TIME_PATTERN = "yyyyMMddHHmmss"

    /**
     * 把配置解析为最终嵌入内容。
     *
     * @return 解析后的字符串；配置非法（如自定义但内容为空）时返回 null
     */
    fun resolve(context: Context, config: InvisibleWatermarkConfig): String? {
        if (!config.enabled) return null

        val body = when (config.source) {
            InvisibleSource.CUSTOM_TEXT -> {
                val trimmed = config.content.trim()
                if (trimmed.isEmpty()) return null
                trimmed
            }
            InvisibleSource.DEVICE_AND_TIME -> {
                val deviceId = deviceShortId(context)
                val time = SimpleDateFormat(TIME_PATTERN, Locale.US).format(Date())
                "DEV:$deviceId:$time"
            }
        }

        val full = WatermarkPayload.TAG_PREFIX + body
        // 超出负载上限时**按字符安全截断**，保证「一定能嵌入」而不是静默失败。
        //
        // ⚠️ 早期写的是 `takeIf { ... }` —— 那在超长时返回 **null**
        // （等于"什么都不写"），与注释声称的"截断"**完全相反**：
        // 用户输入超长内容时，EXIF 与 DCT 两条通道都静默不写。
        // 现在真的截断，且**不切断多字节字符**（否则 UTF-8 会解出乱码）。
        return truncateToBytes(full, WatermarkPayload.MAX_DATA_BYTES)
    }

    /**
     * 按**字符**安全截断到不超过 [maxBytes] 个 UTF-8 字节。
     *
     * ## 为什么不能直接 `take(n)`
     *
     * `take(n)` 按**字符数**截，而负载上限是按**字节**算的 ——
     * 一个汉字 3 字节，`take(255)` 可能产出 765 字节，照样超限。
     *
     * ## 为什么也不能直接按字节切
     *
     * 取前 N 个字节会把多字节字符**切成两半**，解码得到替换字符（`�`）
     * —— 溯源信息就损坏了。
     *
     * 因此逐字符累加字节数，超出即停：宁可少几个字，也不产出乱码。
     *
     * 抽成 `internal` 纯函数便于单测（见 `InvisibleContentResolverTest`）。
     */
    internal fun truncateToBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        var used = 0
        val sb = StringBuilder(s.length)
        // ⚠️ 按**码点**遍历，不能按 `Char`（`for (c in s)`）。
        //
        // Kotlin 的 `String` 是 UTF-16：emoji 等增补平面字符由**两个**
        // `Char`（代理对）组成。单独取其中一个 Char 去编码，会得到
        // 一个**替换字符**（U+FFFD，3 字节）—— 于是：
        //   · 字节数算错（4 字节的 emoji 被当成 3+3=6，或只 append 一半）
        //   · 结果被切成半个字符 → 解码出乱码
        // 按码点取（`codePointAt` + `Character.charCount`）则天然成对处理。
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = Character.charCount(cp)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes) break
            used += n
            sb.append(s, i, i + chars)
            i += chars
        }
        return sb.toString()
    }

    /**
 * 取设备短 ID（8 位十六进制）。
 *
 * 失败时回退到 Build 指纹的哈希 —— 保证**永远有值**，
 * 因为隐藏水印的可用性比 ID 的绝对唯一性更重要。
 *
 * ## 关于 lint 的 `HardwareIds`
 *
 * lint 不建议读取设备标识符（担心被拿去追踪用户）。
 * 这里**刻意**这么做，理由是用途不同：
 * 这串 ID 会被嵌进**图片文件本身**，用于回答「这张图是哪台设备导出的」——
 * 用户导出图片时的预期就是「带上我的设备标识」。
 *
 * 而且它只取 `ANDROID_ID`（可重置、非硬件序列号），再经稳定哈希压到 8 位，
 * 不落库、不上传（应用根本没有网络权限）。
 */
@SuppressLint("HardwareIds")
fun deviceShortId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        val raw = androidId?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER}-${Build.MODEL}-${Build.FINGERPRINT}"

        // 用稳定哈希压缩到 8 位十六进制
        val hash = stableHash(raw)
        return String.format(Locale.US, "%08X", hash)
    }

    /**
     * 稳定哈希（与 JVM/平台无关）。
     *
     * 不用 `String.hashCode()` 因为它在不同实现上虽然稳定，
     * 但 32 位空间对大量设备碰撞率高；这里用 FNV-1a 做一次扩散，
     * 保证同样的输入永远得到同样的输出（跨版本、跨进程一致）。
     */
    private fun stableHash(input: String): Int {
        // FNV-1a 32 位。
        //
        // 常量用**标准十六进制**而非负数：`0x811C9DC5` 与旧写法 `-0x7ee3623b`
        // 在 32 位补码下**数值等价**（行为完全不变），但前者能一眼对上 FNV 规范，
        // 后者要读者自己换算 —— 可读性差，还容易被误当成"特殊魔数"。
        var hash = FNV_OFFSET_BASIS
        for (b in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= FNV_PRIME
        }
        return hash
    }

    /** FNV-1a 32 位偏移基（标准值 `0x811C9DC5`）。 */
    private const val FNV_OFFSET_BASIS: Int = 0x811C9DC5.toInt()

    /** FNV-1a 32 位质数（标准值 `0x01000193`）。 */
    private const val FNV_PRIME: Int = 0x01000193
}