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
 * 想要真正的防伪造，需要用户自定义密钥 —— 各方法都留了 `key` 参数，
 * 上层目前用默认密钥，将来可平滑升级。
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
     * @return 长度恒为 [SIGNATURE_BYTES] 的签名
     */
    fun sign(key: ByteArray, len: Int, data: ByteArray): ByteArray = runCatching {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        mac.update(len.toByte())
        mac.update(data)
        mac.doFinal().copyOf(SIGNATURE_BYTES)
    }.getOrElse {
        // Mac 理论上总是可用；真出问题也不能让整个水印流程崩掉，
        // 返回全 0 会让验签失败（对方也得出全 0 才可能通过，极不可能）
        ByteArray(SIGNATURE_BYTES)
    }

    /**
     * 用指定密钥验签。
     *
     * ## 为什么用定长比较
     *
     * 一旦提前 `return false`，比较耗时就会泄露「前几位对上多少」，
     * 理论上可被时序侧信道逐步推导出正确签名。这里无条件跑完全程。
     */
    fun verify(key: ByteArray, len: Int, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        val expected = sign(key, len, data)
        var diff = 0
        for (i in 0 until SIGNATURE_BYTES) {
            diff = diff or (expected[i].toInt() xor signature[i].toInt())
        }
        return diff == 0
    }
}