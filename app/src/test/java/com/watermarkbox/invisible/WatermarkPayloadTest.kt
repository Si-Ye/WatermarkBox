package com.watermarkbox.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatermarkPayload] 负载编解码测试。
 *
 * 钉住的行为：
 * - 编码→解码 必须无损往返
 * - CRC 必须能拒绝被篡改的数据
 * - 同步字必须能拒绝随机噪声
 */
class WatermarkPayloadTest {

    @Test
    fun `encode then decode round trips ascii text`() {
        val text = "WMB1:test-device:20260101"
        val bytes = WatermarkPayload.encode(text)
        assertNotNull("编码不应失败", bytes)
        assertEquals(text, WatermarkPayload.decode(bytes!!))
    }

    @Test
    fun `round trips chinese text`() {
        val text = "水印盒-隐藏水印测试"
        val bytes = WatermarkPayload.encode(text)!!
        assertEquals(text, WatermarkPayload.decode(bytes))
    }

    @Test
    fun `rejects empty text`() {
        assertNull(WatermarkPayload.encode(""))
    }

    @Test
    fun `rejects oversized text`() {
        val huge = "a".repeat(WatermarkPayload.MAX_DATA_BYTES + 1)
        assertNull("超过 255 字节必须拒绝", WatermarkPayload.encode(huge))
    }

    @Test
    fun `accepts max length text`() {
        val max = "a".repeat(WatermarkPayload.MAX_DATA_BYTES)
        val bytes = WatermarkPayload.encode(max)!!
        assertEquals(max, WatermarkPayload.decode(bytes))
    }

    @Test
    fun `decode rejects corrupted data via crc`() {
        val text = "WMB1:integrity"
        val bytes = WatermarkPayload.encode(text)!!
        // 翻转数据区一个 bit（跳过 sync 2 字节 + len 1 字节）
        val corrupted = bytes.copyOf()
        corrupted[4] = (corrupted[4].toInt() xor 0x01).toByte()
        assertNull("CRC 必须拒绝被篡改的数据", WatermarkPayload.decode(corrupted))
    }

    @Test
    fun `decode rejects wrong sync word`() {
        val bytes = ByteArray(20) { 0x55 }
        assertNull("同步字不符必须返回 null", WatermarkPayload.decode(bytes))
    }

    @Test
    fun `bitLength 已计入签名开销`() {
        val text = "abc" // 3 字节数据
        val bytes = WatermarkPayload.encode(text)!!
        // 16(sync) + 8(len) + 24(data) + 64(签名) + 16(crc) = 128 bit = 16 字节
        assertEquals(128, WatermarkPayload.bitLength(3))
        assertEquals(16, bytes.size)
    }

    @Test
    fun `crc16 is deterministic and sensitive to single bit change`() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 5)
        assertEquals(WatermarkPayload.crc16(a), WatermarkPayload.crc16(a.copyOf()))
        assertTrue(
            "单 bit 改变必须导致 CRC 不同",
            WatermarkPayload.crc16(a) != WatermarkPayload.crc16(b)
        )
    }

    // ==================================================================
    // 签名（防伪造）
    //
    // 核心命题：光有内容不够，还要能回答「这是不是本应用写的」。
    // ==================================================================

    @Test
    fun `新格式解码后签名状态为 true`() {
        val decoded = WatermarkPayload.decodeDetailed(
            WatermarkPayload.encode("WMB1:signed")!!
        )!!
        assertEquals("WMB1:signed", decoded.text)
        assertEquals(true, decoded.signatureValid)
    }

    @Test
    fun `篡改签名会被标为无效，但内容仍能读出`() {
        val bytes = WatermarkPayload.encode("WMB1:forge-me")!!
        // 布局：SYNC(2) + LEN(1) + DATA(len) + SIG(8) + CRC(2)
        val len = bytes[2].toInt() and 0xFF
        val sigStart = 3 + len
        val tampered = bytes.copyOf()
        tampered[sigStart] = (tampered[sigStart].toInt() xor 0x01).toByte()

        val decoded = WatermarkPayload.decodeDetailed(tampered)!!
        assertEquals("内容没动，应当仍能读出", "WMB1:forge-me", decoded.text)
        assertEquals("签名被动过 → false", false, decoded.signatureValid)
    }

    @Test
    fun `篡改内容会被 CRC 拦下（整体拒绝）`() {
        val bytes = WatermarkPayload.encode("WMB1:content")!!
        val tampered = bytes.copyOf()
        tampered[3] = (tampered[3].toInt() xor 0x01).toByte()   // 数据区首字节
        assertNull(
            "内容被改 → CRC 不过 → 整个负载作废（不会给出「签名无效」这种半信半疑的结论）",
            WatermarkPayload.decodeDetailed(tampered)
        )
    }

    @Test
    fun `旧格式（无签名）仍能读出，签名状态为 null`() {
        val decoded = WatermarkPayload.decodeDetailed(legacyEncode("WMB1:old-version"))!!
        assertEquals("WMB1:old-version", decoded.text)
        assertNull("旧格式没有签名 → 无法判断真伪", decoded.signatureValid)
    }

    @Test
    fun `篡改旧格式的内容同样会被拒绝`() {
        val legacy = legacyEncode("WMB1:old")
        legacy[3] = (legacy[3].toInt() xor 0x01).toByte()
        assertNull(WatermarkPayload.decodeDetailed(legacy))
    }

    /**
     * 手工构造 v1 格式（无签名），用于验证**向后兼容**。
     *
     * 不能复用 [WatermarkPayload.encode] —— 那是新格式。
     * 这里用同一套 [BitWriter] 拼出旧布局，才真正测到「回退路径」。
     */
    private fun legacyEncode(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val w = BitWriter()
        w.writeBits(WatermarkPayload.SYNC, 16)
        w.writeBits(data.size, 8)
        data.forEach { w.writeBits(it.toInt() and 0xFF, 8) }
        w.writeBits(WatermarkPayload.crc16(data), 16)
        return w.toByteArray()
    }

    @Test
    fun `新格式 n 字节的位长等于旧格式 n+8 字节 —— 这是旧图仍可读的前提`() {
        // 旧图是按 legacyBitLength(m) 嵌入的；而解码时枚举的是 newBitLength(n)。
        //
        // 因为 newBitLength(n) == legacyBitLength(n + 8)，
        // 旧图会在候选 n = m - 8 上被**完整重建**：
        //   - 位长相同 → 负载字节数相同 → RS 分块数相同 → 投票位序一致
        //   - 于是解出的 encoded 字节与当初嵌入的完全一致
        //   - 最后一层 payload 解码走 legacy 回退，签名状态为 null
        //
        // 也就是说：**旧图不会因为加了签名而读不出来**。
        //
        // 这个等式是兼容性的全部依据，所以固定下来 ——
        // 将来若改签名长度却忘了同步兼容逻辑，这里会立刻红。
        fun legacyBitLength(dataBytes: Int) = 16 + 8 + dataBytes * 8 + 16

        for (n in 1..200) {
            assertEquals(
                "newBitLength($n) 必须等于 legacyBitLength(${n + 8})",
                legacyBitLength(n + 8),
                WatermarkPayload.bitLength(n)
            )
        }
    }
}