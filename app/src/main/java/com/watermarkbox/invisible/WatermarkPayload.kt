package com.watermarkbox.invisible

/**
 * 隐藏水印的**负载编解码**（Payload Layer）。
 *
 * ## 数据布局
 *
 * ```
 * ┌────────┬────────┬──────────┬──────────┬────────┐
 * │ SYNC   │ LEN    │ DATA     │ SIG      │ CRC16  │
 * │ 16 bit │ 8 bit  │ N*8 bit  │ 64 bit   │ 16 bit │
 * └────────┴────────┴──────────┴──────────┴────────┘
 * ```
 *
 * - `SYNC`：同步字 `0xA5C3`。提取时先扫同步字，避免把「普通图片的
 *   随机频域噪声」误判成水印起点。这是抗误报的第一道闸门。
 * - `LEN`：数据字节数（0~255）。上限 255 字节足够放
 *   「WMB:xxx:yyy」这类文本。
 * - `DATA`：UTF-8 字节。
 * - `SIG`：HMAC 签名（见 [WatermarkSigner]），覆盖 **LEN + DATA**。
 *   用来回答「这段内容是不是本应用写的」—— 明文时代任何人都能
 *   往图里塞一段 `WMB1:xxx`，检测端只能照单全收。
 * - `CRC16`：只对 `DATA` 的校验，抗「能读出但读错」的情况。
 *   注意它**不能防伪造**（攻击者可以重算 CRC），防伪靠 `SIG`。
 *
 * ## 旧格式兼容
 *
 * v2 之前没有 `SIG` 字段。解码时**先按新格式试，失败再回退旧格式**，
 * 因此升级前导出的图仍能正常读出，只是签名状态为 `null`
 * （表示「无法判断真伪」）。
 *
 * ## 为什么先编码再纠错
 *
 * Reed-Solomon 按符号纠错，把「字节」直接当符号最省事。
 * 因此这里只负责把字符串变成**字节序列**，纠错交给 [ReedSolomon]。
 */
object WatermarkPayload {

    /** 同步字：提取时的「锚点」。选 0xA5C3 是因为它在比特上足够交错。 */
    const val SYNC: Int = 0xA5C3

    /** 最大数据字节数。 */
    const val MAX_DATA_BYTES: Int = 255

    /**
     * 负载前缀（写进 EXIF 时用于快速识别 + 人类可读）。
     *
     * **唯一来源**：[com.watermarkbox.util.ExifTextCodec.TAG_PREFIX]。
     * 不在此处重复定义，避免两处常量漂移导致检测失配。
     */
    val TAG_PREFIX: String get() = com.watermarkbox.util.ExifTextCodec.TAG_PREFIX

    /**
     * 解码结果。
     *
     * @param text 还原出的文本
     * @param signatureValid 签名状态：
     *        `true`  = 本应用签发，且未被篡改；
     *        `false` = 带了签名但校验失败（**可能是伪造**）；
     *        `null`  = 旧格式，没有签名，无法判断真伪
     */
    data class Decoded(val text: String, val signatureValid: Boolean?)

    /**
     * 把文本编码为负载字节（**带签名**）。
     *
     * @return 编码后的字节数组；文本过长时返回 null
     */
    fun encode(text: String): ByteArray? {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.isEmpty() || data.size > MAX_DATA_BYTES) return null
        return encode(data)
    }

    /** 把已编码好的字节装进负载帧（带签名）。 */
    fun encode(data: ByteArray): ByteArray {
        val bits = BitWriter()
        bits.writeBits(SYNC, 16)
        bits.writeBits(data.size, 8)
        data.forEach { bits.writeBits(it.toInt() and 0xFF, 8) }
        // 签名覆盖「长度 + 数据」：只签数据的话，
        // 攻击者可以截取合法的一段再改 LEN 来改变解析结果
        WatermarkSigner.sign(data.size, data).forEach {
            bits.writeBits(it.toInt() and 0xFF, 8)
        }
        bits.writeBits(crc16(data), 16)
        return bits.toByteArray()
    }

    /**
     * 从负载字节解码文本（**只取文本**，忽略签名状态）。
     *
     * 保留这个入口是为了让既有调用方与测试继续可用；
     * 需要真伪信息时用 [decodeDetailed]。
     */
    fun decode(bytes: ByteArray): String? = decodeDetailed(bytes)?.text

    /**
     * 从负载字节解码，并给出**签名状态**。
     *
     * 先按新格式（带签名）解析，失败再回退旧格式 ——
     * 这样升级前导出的图仍能读出，只是签名状态为 `null`。
     */
    fun decodeDetailed(bytes: ByteArray): Decoded? =
        decodeSigned(bytes) ?: decodeLegacy(bytes)

    /** 新格式：`SYNC + LEN + DATA + SIG(8) + CRC(2)` */
    private fun decodeSigned(bytes: ByteArray): Decoded? {
        val r = BitReader(bytes)
        if (r.remaining < 16 + 8) return null
        if (r.readBits(16) != SYNC) return null
        val len = r.readBits(8)
        if (len <= 0 || len > MAX_DATA_BYTES) return null
        if (r.remaining < len * 8 + WatermarkSigner.SIGNATURE_BYTES * 8 + 16) return null

        val data = ByteArray(len) { (r.readBits(8) and 0xFF).toByte() }
        val signature = ByteArray(WatermarkSigner.SIGNATURE_BYTES) {
            (r.readBits(8) and 0xFF).toByte()
        }
        val crc = r.readBits(16)

        if (crc != crc16(data)) return null

        return Decoded(
            text = toText(data) ?: return null,
            signatureValid = WatermarkSigner.verify(len, data, signature)
        )
    }

    /** 旧格式（无签名）：`SYNC + LEN + DATA + CRC(2)` */
    private fun decodeLegacy(bytes: ByteArray): Decoded? {
        val r = BitReader(bytes)
        if (r.remaining < 16 + 8) return null
        if (r.readBits(16) != SYNC) return null
        val len = r.readBits(8)
        if (len <= 0 || len > MAX_DATA_BYTES) return null
        if (r.remaining < len * 8 + 16) return null

        val data = ByteArray(len) { (r.readBits(8) and 0xFF).toByte() }
        val crc = r.readBits(16)

        if (crc != crc16(data)) return null

        // 旧格式没有签名 → 用 null 表示「无法判断」，
        // 而不是 false（false 的语义是「签名验不过 = 可能伪造」）
        return Decoded(text = toText(data) ?: return null, signatureValid = null)
    }

    private fun toText(data: ByteArray): String? =
        runCatching { String(data, Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 负载的**总比特数**（含同步/长度/签名/CRC 开销）。
     * 用于计算嵌入需要多少个 8×8 块。
     */
    fun bitLength(dataBytes: Int): Int =
        16 + 8 + dataBytes * 8 + WatermarkSigner.SIGNATURE_BYTES * 8 + 16

    /** CRC-16/CCITT-FALSE。选它是因为实现简单且分布均匀。 */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}

/**
 * 位写入器：把不定长的比特串按 MSB-first 攒成字节数组。
 */
internal class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var current = 0
    private var bitsFilled = 0

    fun writeBits(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            val bit = (value shr i) and 1
            current = (current shl 1) or bit
            bitsFilled++
            if (bitsFilled == 8) {
                bytes.add(current.toByte())
                current = 0
                bitsFilled = 0
            }
        }
    }

    fun toByteArray(): ByteArray {
        if (bitsFilled > 0) {
            // 末字节左对齐补齐
            bytes.add((current shl (8 - bitsFilled)).toByte())
        }
        return bytes.toByteArray()
    }
}

/**
 * 位读取器：从字节数组按 MSB-first 逐位读取。
 */
internal class BitReader(private val bytes: ByteArray) {
    private var bitPos = 0

    val remaining: Int get() = bytes.size * 8 - bitPos

    fun readBits(count: Int): Int {
        var value = 0
        repeat(count) {
            if (bitPos >= bytes.size * 8) {
                bitPos++
                return@repeat
            }
            val byteIndex = bitPos / 8
            val bitIndex = 7 - (bitPos % 8)
            val bit = (bytes[byteIndex].toInt() shr bitIndex) and 1
            value = (value shl 1) or bit
            bitPos++
        }
        return value
    }
}