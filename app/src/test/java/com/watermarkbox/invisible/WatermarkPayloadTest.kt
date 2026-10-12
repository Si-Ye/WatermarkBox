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
    fun `bitLength matches actual encoded size`() {
        val text = "abc" // 3 字节数据
        val bytes = WatermarkPayload.encode(text)!!
        val bits = WatermarkPayload.bitLength(3)
        // 16(sync) + 8(len) + 24(data) + 16(crc) = 64 bit = 8 字节
        assertEquals(64, bits)
        assertEquals(8, bytes.size)
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
}