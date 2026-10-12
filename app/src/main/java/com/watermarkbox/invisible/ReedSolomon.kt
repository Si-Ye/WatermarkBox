package com.watermarkbox.invisible

/**
 * Reed-Solomon 纠错编码（GF(2^8)，本原多项式 0x11D）。
 *
 * ## 为什么必须要有它
 *
 * 频域水印面对的破坏是**随机翻转若干比特**（压缩、加噪、轻微缩放）。
 * 没有纠错时，只要错 1 个比特，CRC 就会失败 → 整个水印读不出来。
 * 有了 RS，可以在 N 个符号里纠正 t = (N-K)/2 个错误符号。
 *
 * ## 参数选择
 *
 * 本实现用 **(K=数据符号数, N=K+ECC)** 的固定 ECC 比例：
 * 每 16 个数据符号配 8 个校验符号 → 可纠 4 个符号错（25% 冗余，
 * 抗 12.5% 符号错误率）。这对 75~90% 质量的 JPEG 重编码足够。
 *
 * ## 精度说明
 *
 * 这里只做**符号级纠错**，不做擦除（erasure）解码。
 * 对水印场景（不知道哪些坏了）这是正确的选择。
 */
object ReedSolomon {

    /** GF(256) 本原多项式。 */
    private const val PRIMITIVE = 0x11D

    /** 每块数据符号数。 */
    const val DATA_SYMBOLS = 16

    /** 每块校验符号数。8 个校验 → 可纠 4 个符号。 */
    const val ECC_SYMBOLS = 8

    /** 每块总符号数。 */
    const val BLOCK_SYMBOLS = DATA_SYMBOLS + ECC_SYMBOLS

    /** GF(256) 的对数表与反对数表（懒初始化）。 */
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE
        }
        // 扩展一份，避免乘法时下标溢出需要取模
        for (i in 255 until 512) expTable[i] = expTable[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a] + logTable[b]]
    }

    private fun gfInv(a: Int): Int = expTable[255 - logTable[a]]

    // ------------------------------------------------------------------
    // 生成多项式
    // ------------------------------------------------------------------

    /** 生成多项式 g(x) = ∏ (x - α^i)，i = 0..ECC_SYMBOLS-1。 */
    private val generator: IntArray by lazy {
        var g = intArrayOf(1)
        for (i in 0 until ECC_SYMBOLS) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor gfMul(g[j], 1)
                next[j + 1] = next[j + 1] xor gfMul(g[j], expTable[i])
            }
            g = next
        }
        g
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    /**
     * 对数据进行分块 RS 编码。
     *
     * 输入不足一个数据块时会**零填充**到 [DATA_SYMBOLS]。
     * 解码端通过负载里的 LEN 字段就能知道真实长度，
     * 因此填充不会造成歧义。
     *
     * @param data 原始字节
     * @return 编码后的符号序列（长度 = ceil(n/16) * 24）
     */
    fun encode(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val blockCount = (data.size + DATA_SYMBOLS - 1) / DATA_SYMBOLS
        val out = ByteArray(blockCount * BLOCK_SYMBOLS)

        for (b in 0 until blockCount) {
            val block = IntArray(DATA_SYMBOLS)
            for (i in 0 until DATA_SYMBOLS) {
                val srcIndex = b * DATA_SYMBOLS + i
                block[i] = if (srcIndex < data.size) data[srcIndex].toInt() and 0xFF else 0
            }
            val ecc = computeEcc(block)
            val base = b * BLOCK_SYMBOLS
            for (i in 0 until DATA_SYMBOLS) out[base + i] = block[i].toByte()
            for (i in 0 until ECC_SYMBOLS) out[base + DATA_SYMBOLS + i] = ecc[i].toByte()
        }
        return out
    }

    /** 计算一个数据块的 ECC 符号。 */
    private fun computeEcc(data: IntArray): IntArray {
        val remainder = IntArray(ECC_SYMBOLS)
        for (i in data.indices) {
            val factor = data[i] xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ECC_SYMBOLS - 1)
            remainder[ECC_SYMBOLS - 1] = 0
            for (j in 0 until ECC_SYMBOLS) {
                remainder[j] = remainder[j] xor gfMul(generator[j + 1], factor)
            }
        }
        return remainder
    }

    // ------------------------------------------------------------------
    // 解码（Berlekamp-Massey + Chien + Forney）
    // ------------------------------------------------------------------

    /**
     * 解码并纠错。
     *
     * @param encoded 接收到的符号序列（长度必须是 [BLOCK_SYMBOLS] 的整数倍）
     * @return 纠错后的数据字节；块数不整或无法纠正的块会被原样保留
     */
    fun decode(encoded: ByteArray): ByteArray {
        if (encoded.isEmpty() || encoded.size % BLOCK_SYMBOLS != 0) return ByteArray(0)
        val blockCount = encoded.size / BLOCK_SYMBOLS
        val out = ByteArray(blockCount * DATA_SYMBOLS)

        for (b in 0 until blockCount) {
            val block = IntArray(BLOCK_SYMBOLS) {
                encoded[b * BLOCK_SYMBOLS + it].toInt() and 0xFF
            }
            val corrected = correctBlock(block)
            for (i in 0 until DATA_SYMBOLS) {
                out[b * DATA_SYMBOLS + i] = corrected[i].toByte()
            }
        }
        return out
    }

    /** 对单个块做纠错；失败时返回原块（让上层 CRC 来决定是否接受）。 */
    private fun correctBlock(block: IntArray): IntArray {
        val syndromes = IntArray(ECC_SYMBOLS)
        var hasError = false
        for (i in 0 until ECC_SYMBOLS) {
            var s = 0
            for (j in block.indices) {
                s = gfMul(s, expTable[i]) xor block[j]
            }
            syndromes[i] = s
            if (s != 0) hasError = true
        }
        if (!hasError) return block

        // ---- Berlekamp-Massey 求错误位置多项式 ----
        var lambda = intArrayOf(1)
        var bPoly = intArrayOf(1)
        var l = 0
        var m = 1
        var bCoef = 1

        for (n in 0 until ECC_SYMBOLS) {
            var d = syndromes[n]
            for (i in 1..l) {
                if (i < lambda.size) d = d xor gfMul(lambda[i], syndromes[n - i])
            }
            if (d == 0) {
                m++
            } else if (2 * l <= n) {
                val t = lambda.copyOf()
                val scale = gfMul(d, gfInv(bCoef))
                val shifted = IntArray(bPoly.size + m)
                for (i in bPoly.indices) shifted[i + m] = gfMul(bPoly[i], scale)
                lambda = addPoly(lambda, shifted)
                l = n + 1 - l
                bPoly = t
                bCoef = d
                m = 1
            } else {
                val scale = gfMul(d, gfInv(bCoef))
                val shifted = IntArray(bPoly.size + m)
                for (i in bPoly.indices) shifted[i + m] = gfMul(bPoly[i], scale)
                lambda = addPoly(lambda, shifted)
                m++
            }
        }

        // ---- Chien 搜索求错误位置 ----
        val errorPositions = ArrayList<Int>()
        for (i in 0 until BLOCK_SYMBOLS) {
            var sum = 0
            // 评估 lambda(α^-i)
            for (j in lambda.indices) {
                if (lambda[j] == 0) continue
                val idx = (255 - i) * j % 255
                sum = sum xor gfMul(lambda[j], expTable[idx])
            }
            if (sum == 0) errorPositions.add(BLOCK_SYMBOLS - 1 - i)
        }

        if (errorPositions.isEmpty() || errorPositions.size > ECC_SYMBOLS / 2) {
            // 超出纠错能力，原样返回
            return block
        }

        // ---- Forney 求错误值 ----
        val result = block.copyOf()
        val omega = computeOmega(syndromes, lambda)
        val lambdaPrime = formalDerivative(lambda)

        for (pos in errorPositions) {
            if (pos < 0 || pos >= BLOCK_SYMBOLS) return block
            // X = α^(位置)
            val xIndex = (BLOCK_SYMBOLS - 1 - pos) % 255
            val x = expTable[xIndex]
            val xInv = gfInv(x)

            val num = evalPoly(omega, xInv)
            val den = evalPoly(lambdaPrime, xInv)
            if (den == 0) return block
            val magnitude = gfMul(x, gfMul(num, gfInv(den)))
            result[pos] = result[pos] xor magnitude
        }
        return result
    }

    private fun addPoly(a: IntArray, b: IntArray): IntArray {
        val size = maxOf(a.size, b.size)
        val out = IntArray(size)
        for (i in a.indices) out[i] = out[i] xor a[i]
        for (i in b.indices) out[i] = out[i] xor b[i]
        return out
    }

    private fun formalDerivative(poly: IntArray): IntArray {
        if (poly.size <= 1) return intArrayOf(0)
        val out = IntArray(poly.size - 1)
        for (i in 1 until poly.size) {
            // GF(2^8) 中，奇数次项求导后系数保留
            out[i - 1] = if (i % 2 == 1) poly[i] else 0
        }
        return out
    }

    private fun computeOmega(syndromes: IntArray, lambda: IntArray): IntArray {
        val s = intArrayOf(0) + syndromes
        val product = polyMul(s, lambda)
        val omega = IntArray(ECC_SYMBOLS)
        for (i in 1..ECC_SYMBOLS) {
            if (i < product.size) omega[i - 1] = product[i]
        }
        return omega
    }

    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            if (a[i] == 0) continue
            for (j in b.indices) {
                out[i + j] = out[i + j] xor gfMul(a[i], b[j])
            }
        }
        return out
    }

    private fun evalPoly(poly: IntArray, x: Int): Int {
        var result = 0
        for (i in poly.indices.reversed()) {
            result = gfMul(result, x) xor poly[i]
        }
        return result
    }

    /** 供测试：直接暴露 gf 乘法。 */
    internal fun multiply(a: Int, b: Int): Int = gfMul(a, b)
}