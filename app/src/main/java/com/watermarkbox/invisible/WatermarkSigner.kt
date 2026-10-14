package com.watermarkbox.invisible

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 隐藏水印的**签名器**。
 *
 * ## 它解决什么问题
 *
 * 没有签名时，水印内容是**明文**：任何工具往图里塞一段
 * `WMB1:我是肆叶`，检测端就会照单全收 —— 你只能看到「有个水印」，
 * 却无法判断它**是不是本应用写的**。
 *
 * 加上 HMAC 之后，检测端能回答一个更强的问题：
 * **「这段内容是用本应用导出的吗？」**
 *
 * ## 安全边界（必须说清楚，别高估它）
 *
 * 本项目是**开源**的，所以 [DEFAULT_KEY] 对读过源码的人是公开的。
 * 它能挡住**随手伪造**，挡不住**照抄源码伪造**。
 *
 * 这是**刻意的设计选择**，不是疏漏：
 *
 * | 密钥策略 | 谁能验证 | 谁能伪造 |
 * |---|---|---|
 * | **内置密钥**（本项目） | **任何人** | 任何人（照抄源码） |
 * | 私有密钥（如 Keystore） | **只有持密钥者** | 无人 |
 *
 * 「可公开验证」正是本功能的核心价值 —— 别人拿到图，能**自己**确认
 * 「这是水印盒签发的」。若改成私有密钥，防伪是强了，但**溯源就废了**
 * （别人没有你的密钥，验不了）。
 *
 * 因此本项目**选择保留内置密钥**，并通过 UI 文案如实说明能力边界，不夸大。
 *
 * ## 和「抗生成式 AI 去除」是两件事
 *
 * 签名解决**真伪**，不解决**存活**。水印被洗掉了，
 * 再强的签名也无从验证（见 docs/invisible-watermark-research.md）。
 *
 * ## 为什么用 HMAC 而不是 CRC
 *
 * 已有的 CRC16 只负责「传输出错」，它对**任何改动**都会失败，
 * 但攻击者可以**重算** CRC —— 所以 CRC 不能防伪造。
 * HMAC 需要密钥，没有密钥就算不出正确的签名。
 *
 * ## 为什么签名要覆盖 LEN
 *
 * 如果只对 DATA 签名，攻击者可以截取一段合法的 DATA+签名，
 * 只改 LEN 来改变解析结果。把 LEN 一起签进去就没这个口子。
 *
 * **长度按定长 4 字节大端参与签名**（见 [sign]）—— 旧实现只签低 8 位，
 * 那是「恰好成立」而非「设计保证」。
 */
object WatermarkSigner {

    /**
     * 签名长度：8 字节（64 bit）。
     *
     * 权衡：64 bit 足以抗穷举伪造（2^64 次尝试），
     * 又不会把载荷撑得太长（多占 8 个字节，折合 64 个比特位、
     * 约 3 个 8×8 块的嵌入量）。
     */
    const val SIGNATURE_BYTES = 8

    private const val ALGORITHM = "HmacSHA256"

    /**
     * 默认密钥。
     *
     * 和 `SYNC` 一样，它的作用是「让格式自洽」，不是「保密」——
     * 见类注释的安全边界。
     */
    private val DEFAULT_KEY: ByteArray =
        "WatermarkBox::invisible-watermark::v2".toByteArray(Charsets.UTF_8)

    /** 用默认密钥签名。 */
    fun sign(len: Int, data: ByteArray): ByteArray = sign(DEFAULT_KEY, len, data)

    /** 用默认密钥验签。 */
    fun verify(len: Int, data: ByteArray, signature: ByteArray): Boolean =
        verify(DEFAULT_KEY, len, data, signature)

    /**
     * 用指定密钥签名。
     *
     * ## 长度用**定长 4 字节**参与签名（v2.2 起）
     *
     * 旧实现是 `mac.update(len.toByte())` —— 只签了长度的**低 8 位**。
     * 当前 `len ≤ 255` 时看不出问题，但那是「恰好成立」而非「设计保证」：
     * 一旦上限上调，高位会被静默丢弃，签名就覆盖不全了。
     *
     * 现在按大端写入完整 4 字节，长度信息被**完整**覆盖。
     *
     * ## 失败时**抛异常**，不再静默降级
     *
     * 旧实现在 `Mac` 不可用时返回**全 0** 签名 —— 两个问题：
     * 1. 掩盖了「环境坏了」（调用方以为签名成功了）
     * 2. 若验签方也返回全 0，两者相等 → **假签名会被判为有效**
     *
     * 现在直接抛：签名不可用是环境级故障，应当**显式失败**，
     * 而不是写一份假的进去。
     *
     * @return 长度恒为 [SIGNATURE_BYTES] 的签名
     * @throws IllegalStateException 当 HmacSHA256 不可用时
     */
    fun sign(key: ByteArray, len: Int, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        mac.update(
            byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte(),
            )
        )
        mac.update(data)
        return mac.doFinal().copyOf(SIGNATURE_BYTES)
    }

    /**
     * 用指定密钥验签。
     *
     * ## 兼容旧图（v2.1 及更早）
     *
     * v2.2 改了签名输入（长度从 1 字节变 4 字节），**旧图的签名会验不过**。
     * 若直接返回 false，旧图会被标成「可能被伪造」—— 那是**错误的指控**
     * （旧图确实是本应用签发的）。
     *
     * 因此这里**先试新格式，失败再回退旧格式**（单字节长度）。
     * 回退只多一次 MAC 计算，成本极低，却能让旧图继续正确显示。
     *
     * ## 失败时返回 false（fail-closed）
     *
     * 与 [sign] 不同，验签失败不抛异常（否则会把整个检测流程带崩）。
     * 且「验不过」是**安全**的默认：宁可拒绝，不可放过。
     *
     * ## 为什么用定长比较
     *
     * 一旦提前 `return false`，比较耗时就会泄露「前几位对上多少」，
     * 理论上可被时序侧信道逐步推导出正确签名。这里无条件跑完全程
     * （见 [constantTimeEquals]）。
     */
    fun verify(key: ByteArray, len: Int, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false

        // 1) 当前格式（定长 4 字节长度）
        val current = runCatching { sign(key, len, data) }.getOrNull()
        if (current != null && constantTimeEquals(current, signature)) return true

        // 2) 旧格式（单字节长度）—— 兼容 v2.1 及更早导出的图
        val legacy = runCatching { signLegacy(key, len, data) }.getOrNull()
        return legacy != null && constantTimeEquals(legacy, signature)
    }

    /**
     * **旧格式**签名：长度只取低 8 位。
     *
     * 仅用于**验签回退**，不要用于新写入 —— 见 [sign] 的说明。
     */
    private fun signLegacy(key: ByteArray, len: Int, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        mac.update(len.toByte())
        mac.update(data)
        return mac.doFinal().copyOf(SIGNATURE_BYTES)
    }

    /** 定长比较（防时序侧信道）：长度必须相等，逐字节累积差异，不提前返回。 */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}