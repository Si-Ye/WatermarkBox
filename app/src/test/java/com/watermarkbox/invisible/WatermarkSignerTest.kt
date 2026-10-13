package com.watermarkbox.invisible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatermarkSigner] 单元测试。
 *
 * 签名是「防伪造」的地基，必须证明三件事：
 * 1. 同样的输入永远得到同样的签名（可复现，否则解码端永远验不过）
 * 2. 换密钥 / 改数据 / **改长度**，验签都必须失败
 * 3. 长度不对的签名直接拒绝，不去猜
 */
class WatermarkSignerTest {

    private val keyA = "key-A".toByteArray()
    private val keyB = "key-B".toByteArray()
    private val data = "WMB1:hello".toByteArray()

    @Test
    fun `同密钥同输入签名可复现`() {
        val a = WatermarkSigner.sign(keyA, data.size, data)
        val b = WatermarkSigner.sign(keyA, data.size, data)
        assertArrayEquals("同样输入必须得到同样签名", a, b)
    }

    @Test
    fun `签名长度固定为 8 字节`() {
        assertEquals(8, WatermarkSigner.SIGNATURE_BYTES)
        assertEquals(
            WatermarkSigner.SIGNATURE_BYTES,
            WatermarkSigner.sign(keyA, data.size, data).size
        )
    }

    @Test
    fun `不同密钥产生不同签名`() {
        val a = WatermarkSigner.sign(keyA, data.size, data)
        val b = WatermarkSigner.sign(keyB, data.size, data)
        assertFalse("换密钥后签名必须不同", a.contentEquals(b))
    }

    @Test
    fun `正确签名能通过校验`() {
        val sig = WatermarkSigner.sign(keyA, data.size, data)
        assertTrue(WatermarkSigner.verify(keyA, data.size, data, sig))
    }

    @Test
    fun `换密钥后验签失败`() {
        val sig = WatermarkSigner.sign(keyA, data.size, data)
        assertFalse(
            "用别人的密钥不该验得过 —— 这正是「防伪造」的含义",
            WatermarkSigner.verify(keyB, data.size, data, sig)
        )
    }

    @Test
    fun `数据改一位验签失败`() {
        val sig = WatermarkSigner.sign(keyA, data.size, data)
        val tampered = data.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(WatermarkSigner.verify(keyA, data.size, tampered, sig))
    }

    @Test
    fun `长度参与签名 —— 只改长度也验不过`() {
        // 若签名不含长度，攻击者可以截取一段合法的「数据+签名」，
        // 只把 LEN 改大/改小来改变解析结果
        val sig = WatermarkSigner.sign(keyA, data.size, data)
        assertFalse(
            "长度必须参与签名",
            WatermarkSigner.verify(keyA, data.size + 1, data, sig)
        )
        assertFalse(
            "长度必须参与签名",
            WatermarkSigner.verify(keyA, data.size - 1, data, sig)
        )
    }

    @Test
    fun `签名长度不对直接拒绝`() {
        assertFalse(WatermarkSigner.verify(keyA, data.size, data, ByteArray(4)))
        assertFalse(WatermarkSigner.verify(keyA, data.size, data, ByteArray(0)))
        assertFalse(WatermarkSigner.verify(keyA, data.size, data, ByteArray(16)))
    }

    @Test
    fun `默认密钥自洽`() {
        val sig = WatermarkSigner.sign(data.size, data)
        assertTrue(WatermarkSigner.verify(data.size, data, sig))
    }

    @Test
    fun `空数据也能签名且不崩`() {
        val sig = WatermarkSigner.sign(keyA, 0, ByteArray(0))
        assertEquals(WatermarkSigner.SIGNATURE_BYTES, sig.size)
        assertTrue(WatermarkSigner.verify(keyA, 0, ByteArray(0), sig))
    }
}