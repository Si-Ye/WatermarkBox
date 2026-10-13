package com.watermarkbox.invisible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ==================================================================
    // 擦除译码（erasure decoding）
    //
    // 核心命题：同样 8 个校验符号，纠错误只能纠 4 个，
    // 而「已知位置坏了」的擦除能纠 8 个。
    // ==================================================================

    /** 构造「这 24 个符号各自有多可靠」的置信度数组。 */
    private fun confidenceWith(unreliable: List<Int>, total: Int = 24): FloatArray {
        val c = FloatArray(total) { 0.9f }
        unreliable.forEach { c[it] = 0.01f }
        return c
    }

    @Test
    fun `擦除位置正确时可纠正 8 个错误 —— 无擦除信息只能纠 4 个`() {
        val data = "ABCDEFGHIJKLMNOP".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()

        val broken = listOf(0, 3, 5, 8, 11, 14, 18, 22)   // 正好 8 个
        broken.forEach { corrupted[it] = (corrupted[it].toInt() xor 0xA5).toByte() }

        // 不给擦除信息：8 > 4，超出能力
        val withoutErasure = ReedSolomon.decode(corrupted.copyOf())
        assertFalse(
            "8 个错误超出「纠 4」的能力，不应被修好",
            data.contentEquals(withoutErasure.copyOf(16))
        )

        // 给出擦除信息：8 个擦除正好用满预算
        val withErasure = ReedSolomon.decode(corrupted, confidenceWith(broken))
        assertArrayEquals(
            "8 个擦除应可纠正 —— 这就是本轮的能力提升",
            data,
            withErasure.copyOf(16)
        )
    }

    @Test
    fun `混合场景 4 个擦除加 2 个未知错误也能纠正`() {
        val data = ByteArray(16) { (it * 11 + 5).toByte() }
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()

        val erasures = listOf(1, 6, 12, 20)
        val unknown = listOf(3, 17)

        erasures.forEach { corrupted[it] = (corrupted[it].toInt() xor 0x3C).toByte() }
        unknown.forEach { corrupted[it] = (corrupted[it].toInt() xor 0x71).toByte() }

        // 未知错误也有较低的置信度，但不如擦除位置那么低
        val confidence = confidenceWith(erasures)
        unknown.forEach { confidence[it] = 0.5f }

        // 2f + e = 2×2 + 4 = 8 ≤ 8，恰好用满
        val decoded = ReedSolomon.decode(corrupted, confidence)
        assertArrayEquals(
            "4 擦除 + 2 错误应在能力范围内",
            data,
            decoded.copyOf(16)
        )
    }

    @Test
    fun `擦除集漏掉真错误时无法纠正，也不会伪造出看似正确的数据`() {
        val data = "ABCDEFGHIJKLMNOP".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()

        // 坏 8 个位置
        val realErrors = listOf(0, 2, 5, 7, 9, 13, 17, 21)
        realErrors.forEach { corrupted[it] = (corrupted[it].toInt() xor 0x5F).toByte() }

        // 但只把其中 5 个标为最不可靠 —— 剩 3 个未知错误
        // 需要 2×3 + 5 = 11 个校验符号 > 8，必然超出能力
        val confidence = confidenceWith(listOf(0, 2, 5, 7, 13))

        val decoded = ReedSolomon.decode(corrupted, confidence)
        assertFalse(
            "超出能力时必须失败，绝不能产出与原文不同的「合法码字」",
            data.contentEquals(decoded.copyOf(16))
        )
    }

    @Test
    fun `多块数据每块 6 个擦除都能纠正`() {
        val data = ByteArray(40) { (it + 1).toByte() }   // 3 块
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()
        val broken = mutableListOf<Int>()

        // 每块坏 6 个 —— 超过「纠 4」，但有擦除就够了
        repeat(3) { block ->
            val base = block * ReedSolomon.BLOCK_SYMBOLS
            listOf(0, 4, 8, 12, 16, 20).forEach { offset ->
                val pos = base + offset
                corrupted[pos] = (corrupted[pos].toInt() xor 0x2D).toByte()
                broken.add(pos)
            }
        }

        val decoded = ReedSolomon.decode(corrupted, confidenceWith(broken, encoded.size))
        assertArrayEquals("每块 6 个擦除 ≤ 8，应全部纠正", data, decoded.copyOf(40))
    }

    @Test
    fun `不传置信度时行为与旧版保持一致`() {
        val data = "HelloRS!".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()
        // 4 个错误，旧路径能处理
        listOf(0, 6, 12, 18).forEach { corrupted[it] = (corrupted[it].toInt() xor 0x99).toByte() }

        assertArrayEquals(data, ReedSolomon.decode(corrupted).copyOf(data.size))
        // 显式传 null 必须等价
        assertArrayEquals(data, ReedSolomon.decode(corrupted, null).copyOf(data.size))
    }

    @Test
    fun `置信度长度不匹配时忽略它，仍走原路径`() {
        val data = "ABCDEFGHIJKLMNOP".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()
        listOf(1, 4, 9, 20).forEach { corrupted[it] = (corrupted[it].toInt() xor 0x77).toByte() }

        val decoded = ReedSolomon.decode(corrupted, FloatArray(5) { 0.5f })
        assertArrayEquals(
            "长度对不上宁可不做擦除，也不能错位使用",
            data,
            decoded.copyOf(16)
        )
    }

    @Test
    fun `无错时擦除信息不会造成任何改动`() {
        val data = ByteArray(16) { (it + 1).toByte() }
        val encoded = ReedSolomon.encode(data)

        val decoded = ReedSolomon.decode(encoded, FloatArray(24) { 0.5f })
        assertArrayEquals(data, decoded.copyOf(16))
    }

    @Test
    fun `置信度全为零时依然能纠正错误`() {
        // 全 0 意味着「每个符号都同样不可靠」，排序退化为位置顺序。
        // 此时擦除信息帮不上忙，但也不该把原本能纠的搞坏。
        val data = "ABCDEFGHIJKLMNOP".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = encoded.copyOf()
        listOf(0, 1, 2, 3).forEach { corrupted[it] = (corrupted[it].toInt() xor 0x11).toByte() }

        val decoded = ReedSolomon.decode(corrupted, FloatArray(24) { 0f })
        assertArrayEquals(data, decoded.copyOf(16))
    }

    @Test
    fun `擦除位置越准，能纠的错误越多（单调性）`() {
        val data = ByteArray(16) { (it * 3 + 7).toByte() }
        val encoded = ReedSolomon.encode(data)

        // 同一组错误，只改「擦除信息覆盖了几个」
        val errors = listOf(0, 4, 8, 12, 16, 20)   // 6 个

        val corrupted = encoded.copyOf()
        errors.forEach { corrupted[it] = (corrupted[it].toInt() xor 0x6B).toByte() }

        // 只覆盖 3 个 → 剩 3 个未知错误 → 2×3 + 3 = 9 > 8，失败
        val weak = ReedSolomon.decode(corrupted, confidenceWith(errors.take(3)))
        assertFalse(
            "擦除只覆盖一半时，未知错误仍需 2 倍预算，应当失败",
            data.contentEquals(weak.copyOf(16))
        )

        // 覆盖全部 6 个 → 6 ≤ 8，成功
        val strong = ReedSolomon.decode(corrupted, confidenceWith(errors))
        assertArrayEquals("擦除覆盖全部错误时应当成功", data, strong.copyOf(16))
    }

    @Test
    fun `全部符号都坏了时不会产出伪造结果`() {
        val data = "ABCDEFGHIJKLMNOP".toByteArray()
        val encoded = ReedSolomon.encode(data)
        val corrupted = ByteArray(encoded.size) { (encoded[it].toInt() xor 0x80).toByte() }

        val decoded = ReedSolomon.decode(corrupted, FloatArray(24) { 0f })
        assertFalse(
            "整块损坏远超纠错能力（最多 8），不应被修成「看似正确」的数据",
            data.contentEquals(decoded.copyOf(16))
        )
    }

    @Test
    fun `统计验证：同样 6 个错误，有擦除信息时成功率明显更高`() {
        // 单点用例容易被个案误导，这里跑一批随机样本看总体
        val rnd = Random(20261013)
        val trials = 200
        var plainOk = 0
        var eraseOk = 0

        repeat(trials) {
            val data = ByteArray(16) { rnd.nextInt(256).toByte() }
            val encoded = ReedSolomon.encode(data)
            val corrupted = encoded.copyOf()

            // 随机坏 6 个位置 —— 已超出「纠 4」的能力
            val used = mutableSetOf<Int>()
            while (used.size < 6) used.add(rnd.nextInt(24))
            used.forEach {
                corrupted[it] = (corrupted[it].toInt() xor (1 + rnd.nextInt(255))).toByte()
            }

            if (data.contentEquals(ReedSolomon.decode(corrupted.copyOf()).copyOf(16))) plainOk++
            if (data.contentEquals(
                    ReedSolomon.decode(corrupted, confidenceWith(used.toList())).copyOf(16)
                )
            ) eraseOk++
        }

        println("STAT 6 个错误：无擦除 $plainOk/$trials，有擦除 $eraseOk/$trials")

        assertTrue(
            "6 个错误超出纠 4 的能力，无擦除时不该大面积成功（实际 $plainOk/$trials）",
            plainOk <= trials / 10
        )
        assertEquals(
            "擦除位置全部正确时应当 100% 成功",
            trials,
            eraseOk
        )
    }
}