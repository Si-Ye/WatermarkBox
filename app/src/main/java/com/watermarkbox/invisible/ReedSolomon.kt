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
 * ## 擦除译码（erasure decoding）
 *
 * 除了纠正「未知位置的错误」，本实现还支持**擦除译码**：
 * 当调用方知道「哪些符号最可能坏」时（水印提取端的软判决置信度就是），
 * 把这些位置标为擦除即可。RS 里擦除比错误便宜一半 ——
 * 同样 8 个校验符号，能纠 4 个错误，却能纠 8 个擦除。
 *
 * 详见 [decode] 的重载与 [correctBlock]。
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
     * 解码并纠错（不带擦除信息）。
     *
     * 行为与旧版完全一致：只纠正「未知位置」的错误，每块最多 4 个。
     *
     * @param encoded 接收到的符号序列（长度必须是 [BLOCK_SYMBOLS] 的整数倍）
     * @return 纠错后的数据字节；块数不整或无法纠正的块会被原样保留
     */
    fun decode(encoded: ByteArray): ByteArray = decode(encoded, null)

    /**
     * 解码并纠错，**可利用「擦除」信息把纠错能力翻倍**。
     *
     * ## 为什么擦除比错误便宜
     *
     * RS 的校验符号预算里，纠正 1 个**未知位置**的错误要花 2 个符号，
     * 而「已知坏了、只是不知道值」的**擦除**只要 1 个。所以同样 8 个校验符号：
     *
     * ```
     * 只有错误：可纠 8 / 2 = 4 个
     * 只有擦除：可纠 8     = 8 个
     * 混合    ：2f + e ≤ 8
     * ```
     *
     * 水印提取端天然知道**哪些符号最不可靠** —— 软判决投票时算出的
     * 归一化置信度。这就是现成的擦除信息，此前被白白丢掉了。
     *
     * ## 重试策略
     *
     * 每块按置信度升序排列符号，然后**依次尝试 0、1、2 … [ECC_SYMBOLS] 个擦除**，
     * 第一个通过自检的结果即为答案。
     *
     * 之所以逐个试而不是取固定值：真错误恰好落在「最不可靠的 k 个」里，
     * 而 k 是几事先并不知道。从 0 开始还有个好处 —— 原路径能成功时它最先
     * 命中，等于「不提供擦除信息时行为完全不变」。
     *
     * @param symbolConfidence 每个符号的置信度（越大越可靠），长度需与
     *        [encoded] 相同；传 null 等价于不做擦除译码
     */
    fun decode(encoded: ByteArray, symbolConfidence: FloatArray?): ByteArray {
        if (encoded.isEmpty() || encoded.size % BLOCK_SYMBOLS != 0) return ByteArray(0)
        // 长度对不上时宁可不做擦除，也不能错位
        if (symbolConfidence != null && symbolConfidence.size != encoded.size) {
            return decode(encoded, null)
        }

        val blockCount = encoded.size / BLOCK_SYMBOLS
        val out = ByteArray(blockCount * DATA_SYMBOLS)

        for (b in 0 until blockCount) {
            val base = b * BLOCK_SYMBOLS
            val block = IntArray(BLOCK_SYMBOLS) {
                encoded[base + it].toInt() and 0xFF
            }
            val corrected = if (symbolConfidence == null) {
                correctBlock(block, emptyList())
            } else {
                correctWithErasureRetry(block, symbolConfidence, base)
            }
            for (i in 0 until DATA_SYMBOLS) {
                out[b * DATA_SYMBOLS + i] = corrected[i].toByte()
            }
        }
        return out
    }

    /**
     * 逐个尝试不同数量的擦除，返回第一个通过校验的结果。
     *
     * 从 0 个擦除开始（即旧路径），再 1 个、2 个 …… 直到 [ECC_SYMBOLS] 个。
     * 全部失败则返回原块，交由上层 CRC 裁决。
     */
    private fun correctWithErasureRetry(
        block: IntArray,
        confidence: FloatArray,
        base: Int
    ): IntArray {
        // 置信度升序 —— 最不可靠的排在最前，优先被当成擦除
        val ranked = (0 until BLOCK_SYMBOLS).sortedBy { confidence[base + it] }

        for (e in 0..ECC_SYMBOLS) {
            val erasures = if (e == 0) emptyList() else ranked.take(e)
            val result = correctBlock(block, erasures)
            // correctBlock 失败时返回的就是传入的同一个对象
            if (result !== block) return result
        }
        return block
    }

    /**
     * 对单个块做纠错，支持可选的擦除信息。
     *
     * @param erasurePositions 已知不可靠的符号位置；空列表 = 不做擦除译码
     * @return 纠正后的块。**无法纠正、或本来就没有错时，返回传入的同一个
     *         数组对象** —— 调用方靠 `result !== block` 判断「是否真的改动了」。
     */
    private fun correctBlock(block: IntArray, erasurePositions: List<Int>): IntArray {
        val syndromes = computeSyndromes(block)
        if (syndromes.all { it == 0 }) return block   // 本来就没坏

        val e = erasurePositions.size
        if (e > ECC_SYMBOLS) return block

        // ---- 擦除定位多项式 Γ(x) = ∏ (1 - X_k·x)，X_k = α^(N-1-pos) ----
        var gamma = intArrayOf(1)
        for (pos in erasurePositions) {
            if (pos < 0 || pos >= BLOCK_SYMBOLS) return block
            val x = expTable[positionToXIndex(pos)]
            val next = IntArray(gamma.size + 1)
            for (i in gamma.indices) {
                next[i] = next[i] xor gamma[i]              // ×1
                next[i + 1] = next[i + 1] xor gfMul(gamma[i], x)   // ×(X·x)
            }
            gamma = next
        }

        // ---- 求「剩余未知错误」的定位多项式 ----
        val lambdaF = if (e == 0) {
            berlekampMassey(syndromes)
        } else {
            // 修正 syndrome：T(x) = Γ(x)·S(x) mod x^(2t)
            //
            // Γ 会「抵消」掉擦除位置对 syndrome 的贡献，于是剩下的序列
            // 等价于「只有 f 个未知错误」的情形，可以照常跑 BM。
            //
            // 但只能用 T_e … T_(2t-1)：下标小于 e 的项因为缺少更低阶的
            // syndrome 而不完整，参与运算会污染结果。
            val product = polyMul(gamma, syndromes)
            val modified = IntArray(ECC_SYMBOLS - e) { j ->
                val k = j + e
                if (k < product.size) product[k] else 0
            }
            berlekampMassey(modified)
        }

        // ---- 总定位多项式 Λ = Γ · Λ_f ----
        val lambda = if (e == 0) lambdaF else polyMul(gamma, lambdaF)

        // ---- 能力检查：e + 2f ≤ 2t ----
        // deg(Λ) = e + f，故等价于 2·deg(Λ) ≤ 2t + e
        val deg = degreeOf(lambda)
        if (deg == 0 || 2 * deg > ECC_SYMBOLS + e) return block

        // ---- Chien 搜索求错误位置 ----
        val errorPositions = chienSearch(lambda)
        // 根数必须恰好等于次数，否则说明 Λ 不是真正的定位多项式
        if (errorPositions.size != deg) return block

        // ---- Forney 求错误值 ----
        val result = block.copyOf()
        val omega = computeOmega(syndromes, lambda)
        val lambdaPrime = formalDerivative(lambda)

        for (pos in errorPositions) {
            if (pos < 0 || pos >= BLOCK_SYMBOLS) return block
            // X = α^(位置)
            val x = expTable[positionToXIndex(pos)]
            val xInv = gfInv(x)

            val num = evalPoly(omega, xInv)
            val den = evalPoly(lambdaPrime, xInv)
            if (den == 0) return block
            val magnitude = gfMul(x, gfMul(num, gfInv(den)))
            result[pos] = result[pos] xor magnitude
        }

        // ---- 自检：修正后必须真的成为合法码字 ----
        //
        // 这是擦除译码得以安全启用的前提。若擦除位置猜错了（真错误落在
        // 擦除集之外），解方程会算出一组「看着合理实际是错的」修正值 ——
        // 只有重算 syndrome 才能拦住它。少了这一步，越纠越错会直接
        // 污染上层 CRC，把一个本来能报错的场景变成静默的错误数据。
        if (computeSyndromes(result).any { it != 0 }) return block

        return result
    }

    /** 计算 syndrome：S_i = r(α^i)。全 0 表示接受序列已经是合法码字。 */
    private fun computeSyndromes(block: IntArray): IntArray {
        val s = IntArray(ECC_SYMBOLS)
        for (i in 0 until ECC_SYMBOLS) {
            var acc = 0
            for (j in block.indices) {
                acc = gfMul(acc, expTable[i]) xor block[j]
            }
            s[i] = acc
        }
        return s
    }

    /** 多项式真实次数（最高非零系数的下标）。 */
    private fun degreeOf(poly: IntArray): Int {
        var d = poly.size - 1
        while (d > 0 && poly[d] == 0) d--
        return d
    }

    /**
     * 符号位置 → 求值点 X = α^(N-1-pos) 在指数表中的下标。
     *
     * ## 为什么是「下标」而不是「幂次」
     *
     * [expTable] 是**循环**的：α 是 GF(2⁸) 的本原元，α^255 = α^0 = 1。
     * 所以指数只要落在 `[0, 255)` 内就够了，取模正是为了把它压回这个区间。
     *
     * ## 当前 `% 255` 是恒等 —— 但**别删**
     *
     * `BLOCK_SYMBOLS = 24`，调用方已校验 `pos ∈ [0, 24)`，
     * 因此 `BLOCK_SYMBOLS - 1 - pos ∈ [0, 23]`，**永远小于 255**，取模不生效。
     *
     * 保留它是一根「廉价的安全带」：RS 码的符号上限就是 255，
     * 一旦将来把 `BLOCK_SYMBOLS` 调大，没有取模就会**数组越界崩溃**。
     * 属于「现在无用、将来救命」的防御性写法 —— 看到它别当成冗余删掉。
     */
    private fun positionToXIndex(pos: Int): Int = (BLOCK_SYMBOLS - 1 - pos) % 255

    /**
     * Berlekamp-Massey：由一段 syndrome 序列求最短线性反馈移位寄存器，
     * 即错误定位多项式 Λ(x)（常数项恒为 1）。
     *
     * 输入长度即迭代次数。有擦除时传入的是「修正后的 syndrome」，
     * 长度自然缩为 2t - e，正好对应剩余未知错误的可纠能力。
     */
    private fun berlekampMassey(seq: IntArray): IntArray {
        var lambda = intArrayOf(1)
        var bPoly = intArrayOf(1)
        var l = 0
        var m = 1
        var bCoef = 1

        for (n in seq.indices) {
            var d = seq[n]
            for (i in 1..l) {
                if (i < lambda.size) d = d xor gfMul(lambda[i], seq[n - i])
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
        return lambda
    }

    /**
     * Chien 搜索：遍历全部位置，返回使 Λ(α^-(N-1-pos)) = 0 的位置。
     *
     * Λ 的根与错误位置一一对应（根为 x = 1/X）。
     */
    private fun chienSearch(lambda: IntArray): List<Int> {
        val positions = ArrayList<Int>()
        for (i in 0 until BLOCK_SYMBOLS) {
            var sum = 0
            // 评估 lambda(α^-i)
            for (j in lambda.indices) {
                if (lambda[j] == 0) continue
                val idx = (255 - i) * j % 255
                sum = sum xor gfMul(lambda[j], expTable[idx])
            }
            if (sum == 0) positions.add(BLOCK_SYMBOLS - 1 - i)
        }
        return positions
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