package com.watermarkbox.invisible

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 钉住 **提取端「循环不变量外提」优化前后语义完全一致**。
 *
 * ## 背景
 *
 * `InvisibleWatermarkEngine.extractOriented` 的枚举循环会跑最多 255 轮
 * （枚举 1..MAX_DATA_BYTES 个候选负载长度）。原实现把两件**与候选长度
 * 无关**的事放在了循环内，每轮重算：
 *
 * 1. `bitSlotOrder(totalBlocks)` —— 固定种子的确定性洗牌，255 轮结果相同
 * 2. `Qim.extract(coeff, q)` —— 每块的判决比特，只依赖缓存的系数
 *
 * 优化后把它们提到循环外（见引擎里的「枚举循环之前」注释块）。
 *
 * ## 这个测试要回答什么
 *
 * 「提出来后，投票出的字节序列，是否与逐轮重算的朴素版本**逐位相同**？」
 *
 * 因为两者都是纯函数重复计算，答案**必须是"完全相同"** ——
 * 若不同，说明我改错了（比如洗牌种子、取模顺序被动过）。
 *
 * ## 为什么用"复刻"而不是直接调私有方法
 *
 * 与同目录其他测试一致：引擎的 `extractOriented` 需要 Android Bitmap，
 * 纯 JVM 测试跑不了。所以这里复刻**投票这一段纯逻辑**，
 * 用同一组随机系数分别跑"朴素版"与"优化版"，比对结果。
 */
class ExtractLoopInvariantTest {

    /** 与引擎 `bitSlotOrder` 完全一致的复刻（同一固定种子）。 */
    private fun bitSlotOrder(total: Int): IntArray {
        val all = IntArray(total) { it }
        val rnd = Random(InvisibleWatermarkEngine.POSITION_SEED.toInt())
        for (i in total - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = all[i]; all[i] = all[j]; all[j] = t
        }
        return all
    }

    /**
     * 造一批「系数 + 步长 + 置信度」缓存，模拟引擎第一遍的产物。
     * 用固定种子保证可复现。
     */
    private fun makeBlockCache(total: Int, seed: Long): Triple<FloatArray, FloatArray, FloatArray> {
        val rnd = Random(seed)
        val values = FloatArray(total)
        val qs = FloatArray(total)
        val confs = FloatArray(total)
        for (i in 0 until total) {
            // 系数在 ±120 区间，步长 4~60（与真实自适应步长同量级）
            values[i] = (rnd.nextFloat() * 240f - 120f)
            qs[i] = 4f + rnd.nextFloat() * 56f
            confs[i] = Qim.confidence(values[i], qs[i])
        }
        return Triple(values, qs, confs)
    }

    /**
     * **朴素版**：忠实复刻原实现 —— 每轮重新洗牌、每轮重新 `Qim.extract`。
     */
    private fun voteNaive(
        total: Int,
        bitCount: Int,
        values: FloatArray,
        qs: FloatArray,
        confs: FloatArray
    ): ByteArray {
        val slotOrder = bitSlotOrder(total)          // ← 原实现里这在循环内
        val soft = FloatArray(bitCount)
        for (bi in 0 until total) {
            val bitIndex = slotOrder[bi] % bitCount
            val conf = confs[bi]
            val bit = Qim.extract(values[bi], qs[bi]) // ← 原实现里这也每轮算
            soft[bitIndex] += if (bit == 1) conf else -conf
        }
        // 硬判决成字节
        val out = ByteArray((bitCount + 7) / 8)
        for (i in out.indices) {
            var b = 0
            for (j in 0 until 8) {
                val flat = i * 8 + j
                if (flat >= bitCount) break
                b = (b shl 1) or (if (soft[flat] >= 0f) 1 else 0)
            }
            out[i] = b.toByte()
        }
        return out
    }

    /**
     * **优化版**：忠实复刻新实现 —— `slotOrder` 与判决比特都提前算一次。
     */
    private fun voteHoisted(
        total: Int,
        bitCount: Int,
        values: FloatArray,
        qs: FloatArray,
        confs: FloatArray
    ): ByteArray {
        // 提取到循环外（新实现的核心）
        val slotOrder = bitSlotOrder(total)
        val blockBits = IntArray(total) { i -> Qim.extract(values[i], qs[i]) }

        val soft = FloatArray(bitCount)
        for (bi in 0 until total) {
            val bitIndex = slotOrder[bi] % bitCount
            val conf = confs[bi]
            soft[bitIndex] += if (blockBits[bi] == 1) conf else -conf
        }
        val out = ByteArray((bitCount + 7) / 8)
        for (i in out.indices) {
            var b = 0
            for (j in 0 until 8) {
                val flat = i * 8 + j
                if (flat >= bitCount) break
                b = (b shl 1) or (if (soft[flat] >= 0f) 1 else 0)
            }
            out[i] = b.toByte()
        }
        return out
    }

    @Test
    fun hoisted_vote_matches_naive_vote_exactly() {
        // 覆盖多组 (totalBlocks, bitCount)，含真实规模与边界
        val cases = listOf(
            4096 to 384,
            1200 to 192,
            3000 to 384,
            2000 to 24,
            4096 to 8,
            500 to 240
        )

        for ((total, bitCount) in cases) {
            val (v, q, c) = makeBlockCache(total, seed = total * 31L + bitCount)
            val naive = voteNaive(total, bitCount, v, q, c)
            val hoisted = voteHoisted(total, bitCount, v, q, c)
            assertArrayEquals(
                "优化后的投票字节必须与朴素版逐位相同（total=$total, bitCount=$bitCount）",
                naive,
                hoisted
            )
        }
    }

    @Test
    fun slot_order_is_identical_across_255_calls() {
        // 这是"能外提"的**前提**：洗牌是确定性的。
        // 若这条不成立，外提就是错的。
        val total = 4096
        val first = bitSlotOrder(total)
        repeat(254) {
            val again = bitSlotOrder(total)
            assertArrayEquals("洗牌必须可复现（固定种子）", first, again)
        }
    }

    @Test
    fun block_bit_is_pure_function() {
        // 第二个外提前提：判决比特只依赖 (系数, 步长)，无隐藏状态。
        val rnd = Random(2026)
        repeat(2000) {
            val value = rnd.nextFloat() * 240f - 120f
            val q = 4f + rnd.nextFloat() * 56f
            val a = Qim.extract(value, q)
            val b = Qim.extract(value, q)
            assertEquals("同一 (系数, 步长) 必须得到同一比特", a, b)
        }
    }

    @Test
    fun vote_result_is_stable_when_recomputed() {
        // 端到端不变量：同一份缓存跑两次（一次朴素、一次优化），
        // 且优化版连跑两次，三者必须全部一致。
        val total = 4096
        val bitCount = 384
        val (v, q, c) = makeBlockCache(total, seed = 777)
        val a = voteHoisted(total, bitCount, v, q, c)
        val b = voteHoisted(total, bitCount, v, q, c)
        val n = voteNaive(total, bitCount, v, q, c)
        assertArrayEquals("优化版必须可复现", a, b)
        assertArrayEquals("优化版必须等价于朴素版", a, n)
    }

    @Test
    fun hoisting_does_not_change_soft_sum_magnitude() {
        // 更强的不变量：不只字节相同，连**软值本身**都应逐位相同。
        // 这能抓到"字节碰巧相同、但中间值其实漂了"的隐蔽错误。
        val total = 3000
        val bitCount = 384
        val (v, q, c) = makeBlockCache(total, seed = 12345)

        fun softNaive(): FloatArray {
            val order = bitSlotOrder(total)
            val soft = FloatArray(bitCount)
            for (bi in 0 until total) {
                val idx = order[bi] % bitCount
                val bit = Qim.extract(v[bi], q[bi])
                soft[idx] += if (bit == 1) c[bi] else -c[bi]
            }
            return soft
        }

        fun softHoisted(): FloatArray {
            val order = bitSlotOrder(total)
            val bits = IntArray(total) { i -> Qim.extract(v[i], q[i]) }
            val soft = FloatArray(bitCount)
            for (bi in 0 until total) {
                val idx = order[bi] % bitCount
                soft[idx] += if (bits[bi] == 1) c[bi] else -c[bi]
            }
            return soft
        }

        val sn = softNaive()
        val sh = softHoisted()
        for (i in 0 until bitCount) {
            assertTrueAbsEqual(sn[i], sh[i])
        }
    }

    private fun assertTrueAbsEqual(expected: Float, actual: Float) {
        assertEquals(
            "软值必须逐位一致（期望 $expected，实际 $actual）",
            0f,
            abs(expected - actual),
            0f
        )
    }
}
