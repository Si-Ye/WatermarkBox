package com.watermarkbox.invisible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [ReedSolomon] 纠错编码测试。
 *
 * 这是隐藏水印「抗损伤」能力的数学基础，
 * 必须先用单元测试证明它真的能纠错、且不会误改正确数据。
 */
class ReedSolomonTest {

    @Test
    fun `encode then decode round trips without errors`() {
        val data = "HelloRS!".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val decoded = ReedSolomon.decode(encoded)
        assertArrayEquals(data, decoded.copyOf(data.size))
    }

    @Test
    fun `encoded size follows block formula`() {
        // 16 字节数据 → 1 块 → 24 字节
        assertEquals(24, ReedSolomon.encode(ByteArray(16)).size)
        // 17 字节数据 → 2 块 → 48 字节
        assertEquals(48, ReedSolomon.encode(ByteArray(17)).size)
        // 32 字节 → 2 块
        assertEquals(48, ReedSolomon.encode(ByteArray(32)).size)
    }

    @Test
    fun `corrects up to 4 symbol errors per block`() {
        val data = "ABCDEFGHIJKLMNOP".toByteArray() // 恰好 16 字节
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()

        // 每块最多纠 4 个符号，故意弄坏 4 个
        val positions = intArrayOf(0, 5, 10, 20)
        positions.forEach { corrupted[it] = (corrupted[it].toInt() xor 0xFF).toByte() }

        val decoded = ReedSolomon.decode(corrupted)
        assertArrayEquals("4 个符号错必须能纠正", data, decoded.copyOf(16))
    }

    @Test
    fun `corrects random symbol errors within capacity`() {
        val rnd = Random(20260101)
        repeat(30) {
            val data = ByteArray(16) { rnd.nextInt(256).toByte() }
            val encoded = ReedSolomon.encode(data)
            val corrupted = encoded.copyOf()

            // 随机挑 1~4 个位置弄坏
            val errorCount = 1 + rnd.nextInt(4)
            val used = mutableSetOf<Int>()
            while (used.size < errorCount) used.add(rnd.nextInt(24))
            used.forEach { corrupted[it] = (corrupted[it].toInt() xor (1 + rnd.nextInt(255))).toByte() }

            val decoded = ReedSolomon.decode(corrupted)
            assertArrayEquals(
                "随机 $errorCount 个符号错应能纠正",
                data,
                decoded.copyOf(16)
            )
        }
    }

    @Test
    fun `no errors means no modification`() {
        val data = ByteArray(16) { (it * 7 + 3).toByte() }
        val encoded = ReedSolomon.encode(data)
        val decoded = ReedSolomon.decode(encoded)
        assertArrayEquals(data, decoded.copyOf(16))
    }

    @Test
    fun `multi block data survives errors in every block`() {
        val data = ByteArray(40) { (it + 1).toByte() } // 3 块
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()

        // 每块都弄坏 2 个符号
        for (block in 0 until 3) {
            val base = block * ReedSolomon.BLOCK_SYMBOLS
            corrupted[base + 1] = (corrupted[base + 1].toInt() xor 0x5A).toByte()
            corrupted[base + 7] = (corrupted[base + 7].toInt() xor 0x33).toByte()
        }

        val decoded = ReedSolomon.decode(corrupted)
        assertArrayEquals("每块 2 错应可纠正", data, decoded.copyOf(40))
    }

    @Test
    fun `gf multiplication is commutative and has identity`() {
        assertEquals(0, ReedSolomon.multiply(0, 123))
        assertEquals(123, ReedSolomon.multiply(1, 123))
        assertEquals(
            ReedSolomon.multiply(77, 133),
            ReedSolomon.multiply(133, 77)
        )
    }

    @Test
    fun `decode rejects malformed input length`() {
        // 不是块大小整数倍 → 返回空
        assertTrue(ReedSolomon.decode(ByteArray(7)).isEmpty())
        assertTrue(ReedSolomon.decode(ByteArray(0)).isEmpty())
    }
}