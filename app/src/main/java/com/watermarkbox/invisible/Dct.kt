package com.watermarkbox.invisible

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 8×8 块 DCT / IDCT（浮点实现）。
 *
 * ## 为什么用 DCT 而不是 LSB
 *
 * | | LSB | DCT 中频 |
 * |---|---|---|
 * | 像素改动 | 1 个最低位 | 系数上 ±Q/4 |
 * | JPEG 压缩后 | **全丢** | **保留** |
 * | 缩放后 | 全丢 | 部分保留 |
 *
 * JPEG 本身就是「分块 DCT + 量化」，所以把水印写进 **DCT 中频系数**
 * 等于住在 JPEG 的"母语"里 —— 重编码时低频和高频先死，中频最耐。
 *
 * ## 为什么是浮点而非整数
 *
 * 整数 DCT 快但精度低，水印需要**跨多次压缩仍可辨**
 * （量化误差必须远小于嵌入幅度）。这里用浮点保证精度，
 * 性能开销用「只处理被选中的块」来抵消。
 */
internal object Dct8x8 {

    /** 余弦查找表：cos((2x+1)uπ/16)，预计算避免重复三角函数调用。 */
    private val cosTable = Array(8) { x ->
        FloatArray(8) { u ->
            cos(((2 * x + 1) * u * Math.PI) / 16.0).toFloat()
        }
    }

    /** 归一化系数 c(u) = (u == 0) ? 1/√2 : 1。 */
    private val c = FloatArray(8) { u -> if (u == 0) (1f / sqrt(2f)) else 1f }

    /**
     * 对 8×8 像素块（长度 64，行主序）做 DCT。
     *
     * @param block 输入像素（0~255 或其他取值域）
     * @return 64 个 DCT 系数，同样行主序
     */
    fun forward(block: FloatArray): FloatArray {
        val out = FloatArray(64)
        forwardInto(block, out)
        return out
    }
    /**
     * 对 8×8 像素块做 DCT，**结果写入调用方提供的数组**（避免每次分配）。
     *
     * @param block 输入像素（长度 64）
     * @param out   输出系数（长度 64，会被完全覆盖）
     */
    fun forwardInto(block: FloatArray, out: FloatArray) {
        for (v in 0 until 8) {
            for (u in 0 until 8) {
                var sum = 0f
                for (y in 0 until 8) {
                    val cy = cosTable[y][v]
                    for (x in 0 until 8) {
                        sum += block[y * 8 + x] * cosTable[x][u] * cy
                    }
                }
                out[v * 8 + u] = 0.25f * c[u] * c[v] * sum
            }
        }
    }

    /**
     * 逆 DCT：把频域系数还原回 8×8 像素块。
     */
    fun inverse(coeffs: FloatArray): FloatArray {
        val out = FloatArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                var sum = 0f
                for (v in 0 until 8) {
                    val cy = cosTable[y][v]
                    for (u in 0 until 8) {
                        sum += c[u] * c[v] * coeffs[v * 8 + u] * cosTable[x][u] * cy
                    }
                }
                out[y * 8 + x] = 0.25f * sum
            }
        }
        return out
    }
}

/**
 * QIM（Quantization Index Modulation）单系数调制器。
 *
 * ## 原理
 *
 * 要把 1 bit 写进某个 DCT 系数 `c`：
 * - bit=0 → 把 `c` 量化到最近的 **偶数倍** 的 q
 * - bit=1 → 把 `c` 量化到最近的 **奇数倍** 的 q
 *
 * 提取时只要看 `round(c/q)` 的奇偶即可。`q` 越大，
 * 抗压缩越强，但像素改动越大。
 *
 * ## 为什么用「最近格子」而不是强行搬到固定值
 *
 * 强行搬到固定值（c = k*q）会破坏系数与真实像素的统计关系，
 * 压缩后失真明显。用"最近的合法格子"改动最小（≤ q/2），
 * 对视觉与压缩都友好。
 *
 * ## 关于「q 不能无限大」的实测边界
 *
 * q 越大像素改动越大，当 q 超过约 60 时：
 * - 大量 RGB 值被 clamp 到 0/255 → 嵌入信号失真
 * - 提取端读到的系数与嵌入时不符 → 反而更差
 *
 * 实测：strengthScale=3（q≈112）的提取成功率**低于** scale=2（q≈75）。
 * 因此上层把 strengthScale 限制在 1~2。
 */
internal object Qim {

    /** 嵌入强度（量化步长）默认值，仅作兜底；实际用按频点自适应。 */
    const val DEFAULT_STRENGTH: Float = 18f

    /** 把系数 c 调制为携带 bit 的系数。 */
    fun embed(coeff: Float, bit: Int, q: Float): Float {
        val ratio = coeff / q
        val rounded = Math.round(ratio)
        val targetParity = bit and 1
        var adjusted = rounded
        if ((adjusted and 1) != targetParity) {
            val lower = adjusted - 1
            val upper = adjusted + 1
            val distLower = kotlin.math.abs(coeff - lower * q)
            val distUpper = kotlin.math.abs(coeff - upper * q)
            adjusted = if (distLower <= distUpper) lower else upper
        }
        return adjusted * q
    }

    /** 从系数中读出 bit。 */
    fun extract(coeff: Float, q: Float): Int {
        val rounded = Math.round(coeff / q)
        return rounded and 1
    }

    /** 计算 soft 置信度：读取值与网格的距离越近，越可信。 */
    fun confidence(coeff: Float, q: Float): Float {
        val ratio = coeff / q
        val rounded = Math.round(ratio)
        val distance = kotlin.math.abs(ratio - rounded)
        // distance ∈ [0, 0.5]，映射到 [1, 0]
        return 1f - (distance / 0.5f)
    }
}