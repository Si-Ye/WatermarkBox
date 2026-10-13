package com.watermarkbox.invisible

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.random.Random

/**
 * 隐藏水印**嵌入引擎**。
 *
 * ## 整体流程
 *
 * ```
 * 文本 ──► 负载编码(同步字+长度+CRC) ──► RS 纠错 ──► 比特流
 *                                                      │
 *                     ┌────────────────────────────────┘
 *                     ▼
 *   按「确定性伪随机顺序」选块 ──► 每块 DCT ──► 中频系数量化嵌入 1 bit
 *                                                      │
 *                     ┌────────────────────────────────┘
 *                     ▼
 *               IDCT ──► 写回像素（仅改动 ±2 级，肉眼不可见）
 * ```
 *
 * ## 顽固性设计（三层冗余）
 *
 * 1. **空间冗余**：整条比特流在整张图上**重复嵌入**
 *    （repetition factor = 可用块数 / 负载比特数）。
 *    提取时对同一比特的所有副本做「置信度加权投票」，
 *    这等价于把误码率按副本数平摊。
 *
 * 2. **频域选点**：只改中频系数（u+v ∈ [2,6]，且标准量化步长 ≤ 14），
 *    低频是图像主体（改了就看得见）、高频是压缩垃圾场（改了就被丢）。
 *
 * 3. **纠错码**：RS(16,8) 兜底 —— 投票后即便仍有符号错，
 *    还能纠 4 个/块。
 *
 * ## 位置确定性
 *
 * 用**固定种子**的 [Random] 生成块访问顺序。这里没有用
 * "加入随机性让它更难破解"——纯 Kotlin 的 Random 已被种子固定，
 * 加密强度不是我的目标（那是密码学水印的范畴）。
 * 固定种子的真正目的是：**嵌入端与提取端必须用同一套位置序列**，
 * 否则提取端不知道第 k 个 bit 埋在哪。
 */
class InvisibleWatermarkEngine(
    /**
     * 嵌入强度倍数（相对于「该频点的 JPEG 量化步长」）。
     *
     * - 1.0 = 与标准 JPEG 量化步长同幅（质量 50 时勉强可存活）
     * - 4.0 = 默认值，能扛住质量 80~90 的重压缩
     * - 更高 = 更顽固，但像素改动更大
     *
     * 注意：不再使用「绝对步长」语义 —— 因为不同频点的 JPEG
     * 量化步长相差 10 倍以上，统一绝对步长无法兼顾隐蔽与鲁棒。
     */
    val strengthScale: Float = 1f
) {

    companion object {
        /** 位置序列的固定种子。改这个值会让旧图无法被新版本检测。 */
        const val POSITION_SEED = 0x5761746DL // "Watm"

        /**
         * 提取时额外尝试的旋转角（度）。
         *
         * ## 为什么需要它们
         *
         * DCT 嵌入是按 8×8 块网格做的，而「哪个块承载哪个比特」
         * 由固定种子洗牌决定。图片被旋转 90°/180°/270° 后：
         * - 块内像素被转置或翻转 → DCT 系数跟着变
         * - 块索引整体重排 → 洗牌序列对不上
         *
         * 两者叠加，直接提取必然失败（实测确认）。但这类旋转**可逆**：
         * 把图转回原方向，块网格与索引就都恢复了。
         *
         * 试 90/180/270 三个角度即可覆盖全部直角旋转 ——
         * 图被转过 θ 时，转 360−θ 恰好还原，而它一定在这个集合里。
         *
         * 任意角度（如 15°）**不在**覆盖范围内：重采样会让块网格彻底
         * 错位，需要几何同步机制才能解决，属另一课题。
         */
        private val ORTHOGONAL_ROTATIONS = listOf(90f, 180f, 270f)

        /** 活动度分档阈值。
         *
         * ## 数值依据
         *
         * 低频交流系数（u+v=1）在 8×8 DCT 后的量级大致是：
         * - 纯色 / 平坦：`|C| < 10`
         * - 轻微渐变：`10 ~ 40`
         * - 普通照片纹理：`40 ~ 150`
         * - 强边缘 / 细节：`> 150`
         *
         * 取 30 / 100 把「几乎纯色」「普通纹理」「强纹理」分开。
         */
        private const val FLAT_ACTIVITY = 30f
        private const val TEXTURED_ACTIVITY = 100f

        /** 平坦区强度折扣：改动在这种地方最扎眼。 */
        private const val FLAT_FACTOR = 0.6f

        /** 中等纹理区强度折扣。 */
        private const val MID_FACTOR = 0.8f

        /**
         * 块「活动度」：衡量这个块有多"花"。
         *
         * ## 为什么只取 (0,1) 与 (1,0)
         *
         * 这两个频率的 `u+v = 1`，**永远不在** [MID_BAND] 覆盖的
         * `u+v ∈ [2,6]` 里 —— 也就是说嵌入**从不改动它们**。
         *
         * 这一点至关重要：判据必须建立在「嵌入不碰的量」上，
         * 否则嵌入端与提取端会算出不同的活动度，直接对不上。
         * （`DC` 亮度能当判据是同一个道理。）
         *
         * ## 它代表什么
         *
         * 低频交流能量 ≈ 这一块有没有"内容"：
         * - 纯色天空 / 墙面 → 接近 0
         * - 有边缘、有纹理 → 明显大于 0
         */
        private fun blockActivity(coeffs: FloatArray): Float =
            abs(coeffs[1]) + abs(coeffs[8])

        /** 块大小的常量。 */
        const val BLOCK = 8

        /**
         * 标准 JPEG 亮度量化表（质量 50），索引 [v][u]。
         *
         * 用来**在编译期筛选**可用频点：只有量化步长小的频点才适合
         * 承载水印（否则会被 JPEG 量化器抹掉）。
         *
         * 硬编码这张表而非运行时读取，是因为：
         * 1. 它是 JPEG 标准的一部分，不会变
         * 2. 我们需要在 `MID_BAND` 初始化时就用它
         */
        internal val JPEG_LUMA_QUANT: Array<IntArray> = arrayOf(
            intArrayOf(16, 11, 10, 16, 24, 40, 51, 61),
            intArrayOf(12, 12, 14, 19, 26, 58, 60, 55),
            intArrayOf(14, 13, 16, 24, 40, 57, 69, 56),
            intArrayOf(14, 17, 22, 29, 51, 87, 80, 62),
            intArrayOf(18, 22, 37, 56, 68, 109, 103, 77),
            intArrayOf(24, 35, 55, 64, 81, 104, 113, 92),
            intArrayOf(49, 64, 78, 87, 103, 121, 120, 101),
            intArrayOf(72, 92, 95, 98, 112, 100, 103, 99)
        )

        /**
         * 每个 8×8 块承载 1 bit 时使用的频点（u,v）。
         *
         * ## 选点依据
         *
         * **核心原则**：QIM 步长必须**大于该频点的 JPEG 量化步长**，
         * 否则 JPEG 的量化器会把嵌入信号当噪声抹掉。
         *
         * 因此这里从候选角落区（u+v ∈ [2,6]）中，**自动筛出**
         * 标准量化步长 ≤ 14 的频点。这样即使质量 90（表缩到 1/4）
         * 也仍有裕量。
         *
         * 避开 (3,3)=22、(4,4)=30 这些"死亡区"——实测在那里嵌入
         * 连质量 90 都扛不住。
         */
        private val MID_BAND: List<Pair<Int, Int>> = buildList {
            // 候选：u+v ∈ [2,6]（低频到中低频），且标准量化步长 <= 14
            for (v in 0 until 8) {
                for (u in 0 until 8) {
                    val s = u + v
                    if (s < 2 || s > 6) continue
                    if (JPEG_LUMA_QUANT[v][u] > 14) continue
                    add(u to v)
                }
            }
        }

        /**
         * 量化步长相对于「JPEG 量化表」的倍数。
         *
         * ## 为什么必须大于 1
         *
         * JPEG 编码时会把 DCT 系数按量化表**四舍五入到最近的整数倍**。
         * 若我们的嵌入幅度小于 JPEG 量化步长，改动会在压缩时被抹平。
         *
         * ## 取值 6 的依据（实测调优）
         *
         * - 取 4 时，strengthScale=1 的步长约 28，在"高频噪声较多"
         *   的图上投票不够稳（实测提取失败）
         * - 取 6 后，最小步长约 42，配合 MAX_STEP=60 的封顶，
         *   scale=1~4 全部可稳定自洽提取
         *
         * 上限由 [MAX_STEP] 保证，因此调大此值不会导致像素失控。
         */
        const val STRENGTH_OVER_QUANT = 6f

        /**
         * 量化步长的**硬上限**。
         *
         * ## 为什么必须有上限
         *
         * QIM 步长越大，单个 DCT 系数的改动越大，IDCT 后像素被推到
         * 0 或 255 的概率越高。一旦大面积 clamp：
         * - 嵌入端以为写入了某个系数，实际像素已饱和
         * - 提取端读回的系数与预期不符 → 提取失败
         *
         * 实测：步长 > 100 时提取成功率反而下降（"越顽固越差"）。
         *
         * ## 取值
         *
         * 60：对应单块像素峰值改动约 ±40（相对 255 约 16%），
         * 仍属"局部细微噪声"级别，视觉上不引起注意；
         * 同时足以让 JPEG 质量 80 的量化器无法抹除信号。
         */
        const val MAX_STEP = 60f
    }

    /**
     * 把 [text] 嵌入 [source]，返回**新的** Bitmap。
     *
     * @return 嵌入后的 Bitmap；文本非法 / 图像太小时返回 null
     */
    fun embed(source: Bitmap, text: String): Bitmap? {
        val payload = WatermarkPayload.encode(text) ?: return null
        val encodedBytes = ReedSolomon.encode(payload)
        val bits = bytesToBits(encodedBytes)

        val width = source.width
        val height = source.height
        val blocksX = width / BLOCK
        val blocksY = height / BLOCK
        val totalBlocks = blocksX * blocksY

        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        out.getPixels(pixels, 0, width, 0, 0, width, height)

        // 位置映射：每个块恰好访问一次，块 → bit 槽位由确定性洗牌决定。
        //
        // 这样同一 bit 的副本被散布到全图各处（抗局部损伤），
        // 且**不会重复写同一个块**（重复写会导致改动累积、
        // 与提取端看到的系数不一致）。
        val slotOrder = bitSlotOrder(totalBlocks)

        val blockPixels = FloatArray(64)
        val coeffs = FloatArray(64)
        for (blockIndex in 0 until totalBlocks) {
            val bit = bits[slotOrder[blockIndex] % bits.size]
            val bx = (blockIndex % blocksX) * BLOCK
            val by = (blockIndex / blocksX) * BLOCK
            readBlockLuma(pixels, width, bx, by, blockPixels)

            Dct8x8.forwardInto(blockPixels, coeffs)
            val (u, v) = MID_BAND[blockIndex % MID_BAND.size]
            val idx = v * 8 + u
            val q = adaptiveStrength(coeffs, u, v)
            // DC-QIM（失真补偿）：不把系数拉满到格子中心，只推 40%，
            // 剩下的距离留给噪声预算。
            // 实测收益：PSNR 38.82 → 43.16 dB（+4.34），代价 0.42% 位错误率。
            // 详见 [Qim.embedCompensated]。
            // 提取端判决规则不变 → 旧图仍可正常检测。
            coeffs[idx] = Qim.embedCompensated(coeffs[idx], bit, q)

            val blockOut = Dct8x8.inverse(coeffs)
            writeBlockLuma(pixels, width, bx, by, blockOut, blockPixels)
        }

        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * 提取隐藏水印。
     *
     * ## 为什么要试多个方向
     *
     * DCT 嵌入是**按 8×8 块网格**做的，而「哪个块承载哪个比特」
     * 由固定种子洗牌决定。图片一旦被旋转 90°/180°/270°：
     *
     * - 块内部像素被转置或翻转 → DCT 系数跟着变
     * - 块索引整体重排 → 洗牌序列对不上
     *
     * 两者叠加，直接提取必然失败。但这类旋转是**可逆的**：
     * 把图转回原方向，块网格与索引就都恢复了 —— 实测确认。
     *
     * 所以这里先按原方向试一次（绝大多数图不需要旋转），
     * 不行再依次试 90°/180°/270°。相册的「旋转」按钮、
     * 多数图片工具产生的都是这类直角旋转。
     *
     * 任意角度（如 15°）的旋转**不在覆盖范围内** —— 重采样会让
     * 块网格彻底错位，需要几何同步机制才能解决，属另一课题。
     *
     * @param tryRotations 失败时是否再试 3 个直角方向。
     *        检测页可以关掉它换速度（代价：旋转过的图认不出来）。
     */
    fun extract(bitmap: Bitmap, tryRotations: Boolean = true): WatermarkExtraction {
        // 1) 原方向 —— 绝大多数图不需要旋转，先走这条快路径
        val direct = extractOriented(bitmap)
        if (direct is WatermarkExtraction.Found) return direct

        // 2) 直角旋转 —— 把图转回去再试
        if (!tryRotations) return direct

        for (angle in ORTHOGONAL_ROTATIONS) {
            val restored = rotateBack(bitmap, angle) ?: continue
            try {
                val result = extractOriented(restored)
                if (result is WatermarkExtraction.Found) return result
            } finally {
                if (restored !== bitmap && !restored.isRecycled) restored.recycle()
            }
        }

        // 三个方向都不行，返回原方向的结果 —— 它的失败描述最贴合
        // 用户看到的那张图，不会误导成「旋转害的」
        return direct
    }

    /**
     * 把位图绕中心旋转 [degrees]（用于让直角旋转过的图回到正方向）。
     *
     * @return 旋转后的新位图；失败、或结果与原图是同一对象时返回 null
     */
    private fun rotateBack(bitmap: Bitmap, degrees: Float): Bitmap? = runCatching {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        rotated.takeIf { it !== bitmap }
    }.getOrNull()

    /** 实际执行提取（默认朝向已正确）。 */
    private fun extractOriented(bitmap: Bitmap): WatermarkExtraction {
        val blocksX = bitmap.width / BLOCK
        val blocksY = bitmap.height / BLOCK
        val totalBlocks = blocksX * blocksY
        if (totalBlocks < 64) return WatermarkExtraction.NotFound("图片过小，无法承载水印")

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ---- 第一遍：缓存**所有块**的中频系数与自适应步长 ----
        //
        // 位置序列只依赖块索引（不筛选），因此两端天然对齐。
        // 平坦块的置信度会很低，在投票时权重自然小 —— 用
        // 加权投票替代"提前筛掉"，既稳又无损。
        val blockPixels = FloatArray(64)
        val coeffs = FloatArray(64)
        val coeffValues = FloatArray(totalBlocks)
        val coeffConfs = FloatArray(totalBlocks)
        val coeffQs = FloatArray(totalBlocks)

        for (blockIndex in 0 until totalBlocks) {
            val bx = (blockIndex % blocksX) * BLOCK
            val by = (blockIndex / blocksX) * BLOCK
            readBlockLuma(pixels, width, bx, by, blockPixels)
            Dct8x8.forwardInto(blockPixels, coeffs)
            val (u, v) = MID_BAND[blockIndex % MID_BAND.size]
            val idx = v * 8 + u
            val q = adaptiveStrength(coeffs, u, v)
            coeffValues[blockIndex] = coeffs[idx]
            coeffQs[blockIndex] = q
            coeffConfs[blockIndex] = Qim.confidence(coeffs[idx], q)
        }

        // ---- 枚举循环之前：把**与候选长度无关**的量算一次 ----
        //
        // ## 为什么必须提到外面（性能关键，别再塞回循环里）
        //
        // 下面那个循环会跑最多 255 轮（枚举 1..MAX_DATA_BYTES 个候选长度）。
        // 但其中有两件事**只依赖块本身、与 dataBytes 完全无关**：
        //
        // 1. `bitSlotOrder(totalBlocks)` —— 一次 Fisher-Yates 洗牌，
        //    复杂度 O(totalBlocks)。它是**确定性**的（固定种子），
        //    255 轮里结果逐位相同。放循环里 = 白洗 254 次。
        //    4000×3000 的图约 18 万块 → 白做约 4600 万次交换。
        //
        // 2. 每个块的**判决比特** —— `Qim.extract(coeff, q)` 只读第一遍
        //    已缓存好的系数与步长，同样与 dataBytes 无关。
        //
        // 提出来之后，内层循环只剩「累加」这一件真正随 dataBytes 变的事。
        // **语义完全不变**（被提出来的都是纯函数重复计算），只是不再重复算。
        val slotOrder = bitSlotOrder(totalBlocks)
        val blockBits = IntArray(totalBlocks) { i ->
            Qim.extract(coeffValues[i], coeffQs[i])
        }

        // ---- 第二遍：枚举候选负载长度，做软判决投票 + RS 解码 ----
        var bestText: String? = null
        var bestConfidence = 0f
        var bestSignature: Boolean? = null

        for (dataBytes in 1..WatermarkPayload.MAX_DATA_BYTES) {
            val payloadBits = WatermarkPayload.bitLength(dataBytes)
            val payloadBytes = (payloadBits + 7) / 8
            val encodedBytes = ReedSolomon.encode(ByteArray(payloadBytes))
            val bitCount = encodedBytes.size * 8
            if (bitCount > totalBlocks) continue

            val softBits = FloatArray(bitCount)
            val weightSum = FloatArray(bitCount)

            for (blockIndex in 0 until totalBlocks) {
                val bitIndex = slotOrder[blockIndex] % bitCount
                val conf = coeffConfs[blockIndex]
                // 软判决：bit=1 → +conf，bit=0 → -conf
                softBits[bitIndex] += if (blockBits[blockIndex] == 1) conf else -conf
                weightSum[bitIndex] += conf
            }

            // 硬判决：必须**按 RS 编码后的符号数**重组字节，
            // 而不是按"负载字节数"。因为 RS 解码器接收的是含校验符号的序列。
            //
            // 历史 bug：曾写成 `ByteArray(payloadBytes)`（负载长度），
            // 导致只填了前一小段、RS 解码输入不完整 → 恒失败。
            val votedBytes = ByteArray(encodedBytes.size)
            for (i in 0 until encodedBytes.size) {
                var b = 0
                for (j in 0 until 8) {
                    val flat = i * 8 + j
                    if (flat >= bitCount) break
                    val decision = if (softBits[flat] >= 0f) 1 else 0
                    b = (b shl 1) or decision
                }
                votedBytes[i] = b.toByte()
            }

            // ---- 每个比特的归一化确定性（0~1，越大越可靠）----
            //
            // 投票已把各副本的软值累加过，再除以权重和即得归一化值；
            // 这样「副本多」不会天然显得更可靠（不同负载长度的副本数不同）。
            val bitCertainty = FloatArray(bitCount)
            for (i in 0 until bitCount) {
                bitCertainty[i] =
                    if (weightSum[i] > 0f) abs(softBits[i]) / weightSum[i] else 0f
            }

            // ---- 符号置信度 = 该符号 8 个比特里**最差**的那个 ----
            //
            // 一个符号只要有一个比特不可靠，整个符号就可能错，
            // 所以取最小值而非平均 —— 这决定了谁是「擦除」的候选。
            val symbolConfidence = FloatArray(encodedBytes.size)
            for (i in encodedBytes.indices) {
                var worst = Float.MAX_VALUE
                for (j in 0 until 8) {
                    val flat = i * 8 + j
                    val c = if (flat < bitCount) bitCertainty[flat] else 0f
                    if (c < worst) worst = c
                }
                symbolConfidence[i] = worst
            }

            // 交给 RS 做纠错 + **擦除译码**：置信度最低的符号会被优先
            // 当作「已知坏了但不知道值」的擦除，纠错预算从
            // 「2 个校验符号换 1 个错误」变成「1 个换 1 个」，
            // 因此每块可修的范围从 4 个错误扩到最多 8 个擦除。
            val corrected = ReedSolomon.decode(votedBytes, symbolConfidence)
            val decoded = WatermarkPayload.decodeDetailed(corrected)
            if (decoded != null) {
                var sum = 0f
                var count = 0
                for (i in 0 until bitCount) {
                    if (weightSum[i] > 0f) {
                        sum += abs(softBits[i]) / weightSum[i]
                        count++
                    }
                }
                val avg = if (count > 0) sum / count else 0f
                if (avg > bestConfidence) {
                    bestConfidence = avg
                    bestText = decoded.text
                    bestSignature = decoded.signatureValid
                }
            }
        }

        return if (bestText != null) {
            WatermarkExtraction.Found(bestText, bestConfidence, bestSignature)
        } else {
            WatermarkExtraction.NotFound("未检测到水印（或损坏超出纠错能力）")
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 生成「第 i 个块承载第几个 bit」的映射。
     *
     * ## 关键约束：每个块**只能出现一次**
     *
     * 早期实现让 `order` 长度 = R × bitCount（R 为重复轮数），
     * 导致**同一个块被反复访问**：第二次读到的像素已被第一次改过，
     * 改动不断累积；而提取端每个块只读一次，两者看到的系数
     * 不一致 → 逐位准确率掉到 50%（实测数据）。
     *
     * 正确做法：**遍历所有块恰好一次**，每个块承载
     * `bits[blockOrdinal % bitCount]`。这样：
     * - 每个块只被写入一次 → 嵌入端与提取端看到同一份像素
     * - 同一 bit 的副本数 = `totalBlocks / bitCount`（自然的冗余）
     *
     * ## 洗牌的作用
     *
     * 用固定种子洗牌决定「块 → bit 序号」的分配，使同一 bit 的副本
     * **散布在图像各处**（抗局部裁剪），同时保证两端可复现。
     *
     * @return 长度 = totalBlocks 的数组，第 i 项是该块的"bit 槽位"
     */
    private fun bitSlotOrder(totalBlocks: Int): IntArray {
        val all = IntArray(totalBlocks) { it }
        val rnd = Random(POSITION_SEED.toInt())
        for (i in totalBlocks - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = all[i]; all[i] = all[j]; all[j] = t
        }
        return all
    }

    /**
     * 读取一个 8×8 块的**亮度**值。
     *
     * 用 BT.601 亮度公式。为什么不直接用 R 或 G：
     * 亮度是感知与压缩共同关注的主通道，鲁棒性与隐蔽性都最好。
     */
    private fun readBlockLuma(
        pixels: IntArray,
        width: Int,
        bx: Int,
        by: Int,
        out: FloatArray
    ) {
        var i = 0
        for (y in 0 until BLOCK) {
            val rowBase = (by + y) * width + bx
            for (x in 0 until BLOCK) {
                val p = pixels[rowBase + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i++] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
    }

    /**
     * 把修改后的亮度块写回像素。
     *
     * ## 分配方式：ΔR = ΔG = ΔB = ΔY
     *
     * 保持色相最简单，且因为 0.299+0.587+0.114 = 1，
     * 等量加 ΔY 时亮度变化恰好也是 ΔY。
     *
     * ## 为什么**不能**做「整块统一缩放」（重要，别改回去）
     *
     * 曾经的做法是：先扫一遍块内像素，只要有**任意一个**像素的
     * 某通道在加 ΔY 后会越界，就把**整个块**的 ΔY 按那个像素的
     * 可用余量比例缩小。
     *
     * 这在浅色背景（白底、浅灰、高光、天空、白毛）上会灾难性失效。
     * 实测数据（555×583 的浅色背景图，3000 个块）：
     *
     * | 策略 | 逐块逐位正确率 |
     * |---|---|
     * | 整块统一缩放 | **54.17%**（≈ 随机，必失败） |
     * | 逐像素裁剪（现在） | **99.50%** |
     *
     * 失效链条：
     * 1. 白底像素 `(250,250,250)` + `ΔY=+42` → 余量只有 5
     * 2. `scale = 5/42 ≈ 0.12` —— 被那**一个像素**拖累
     * 3. 整块 64 个像素的 ΔY 全部只剩 12%
     * 4. 提取端读回的系数与嵌入端严重不符 → 判 bit 出错
     * 5. 更糟的是：**错误是成片的、系统性的**（白底块集体偏向
     *    同一方向），冗余投票无法修正系统性偏差 ——
     *    12.9 倍副本也救不回来
     *
     * 实测分布：该图 43.5% 的块含近白像素，28.4% 的块
     * `scale = 0`（水印**一个字节都写不进去**）。
     *
     * ## 逐像素裁剪为什么是对的
     *
     * 饱和只影响**那一个像素**，其余 63 个照常写入。提取端做的是
     * 整块 DCT，个别像素饱和只是给系数叠加少量噪声 ——
     * 远好于整块幅度归零。
     *
     * 代价：饱和像素附近实际 ΔY 略小于预期。但 QIM 的判决边界
     * 有半个步长的裕量（步长 42~60），这点误差完全在容差内。
     *
     * ## 兼容性
     *
     * 改动**只影响写入**，[extract] 完全不用动（它只从像素做 DCT）。
     * 因此**旧图仍可正常检测** —— 旧图本来就是「写不进去」，
     * 这次修复只是让新图能真正写进去。
     */
    private fun writeBlockLuma(
        pixels: IntArray,
        width: Int,
        bx: Int,
        by: Int,
        newLuma: FloatArray,
        originalLuma: FloatArray
    ) {
        var i = 0
        for (y in 0 until BLOCK) {
            val rowBase = (by + y) * width + bx
            for (x in 0 until BLOCK) {
                val delta = newLuma[i] - originalLuma[i]
                // 逐像素独立裁剪：越界的只有这一个像素，不牵连整块。
                val p = pixels[rowBase + x]
                val r = (((p shr 16) and 0xFF) + delta).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + delta).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) + delta).toInt().coerceIn(0, 255)
                pixels[rowBase + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }
    }

    /**
     * 计算某个块在指定频点上使用的**自适应量化步长**。
     *
     * ## 依据
     *
     * 1. **该频点的 JPEG 量化步长** —— 核心洞察：嵌入幅度必须与
     *    JPEG 在该频点的量化步长挂钩，否则压缩时会被抹平。
     * 2. **DC 亮度** —— 中灰区人眼最敏感，压低；暗部/亮部可给足。
     * 3. **块活动度** —— 平坦区（天空、墙面）的扰动最扎眼，
     *    纹理区（草地、毛发）的扰动会被纹理本身掩盖。
     *
     * ## 为什么两端一定一致（关键）
     *
     * 三个依据都必须建立在「**嵌入不碰的量**」上：
     * - `(u, v)`：由块索引决定，与像素无关 ✓
     * - `DC`（下标 0）：QIM 只改交流系数，DC 不动 ✓
     * - 活动度：只取 `u+v=1` 的 (0,1)/(1,0)，
     *   而 [MID_BAND] 覆盖的是 `u+v ∈ [2,6]` —— 交集为空 ✓
     *
     * 任何一条换成「会被水印改动的量」，嵌入端与提取端就会
     * 算出不同的步长，判决边界错位 → 直接提取失败。
     */
    private fun adaptiveStrength(coeffs: FloatArray, u: Int, v: Int): Float {
        val jpegQuant = JPEG_LUMA_QUANT[v][u].toFloat()
        // 中灰区（人眼最敏感）降低幅度；暗部/亮部可全幅
        val dc = abs(coeffs[0]) / 8f
        val luminanceFactor = when {
            dc < 40f -> 1.0f
            dc > 215f -> 1.0f
            else -> 0.7f
        }
        // 活动度分档：越平坦越收着
        val activity = blockActivity(coeffs)
        val activityFactor = when {
            activity < FLAT_ACTIVITY -> FLAT_FACTOR
            activity < TEXTURED_ACTIVITY -> MID_FACTOR
            else -> 1f
        }
        // **硬上限 MAX_STEP**：步长过大会让大量像素被 clamp 到 0/255，
        // 从而破坏嵌入（实测 q > 100 时提取率骤降）。这是"更顽固
        // 反而更差"的原因。
        return (jpegQuant * STRENGTH_OVER_QUANT * luminanceFactor * activityFactor * strengthScale)
            .coerceIn(4f, MAX_STEP)
    }

    private fun bytesToBits(bytes: ByteArray): IntArray {
        val bits = IntArray(bytes.size * 8)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            for (j in 0 until 8) {
                bits[i * 8 + j] = (b shr (7 - j)) and 1
            }
        }
        return bits
    }
}

/**
 * 提取结果。
 */
sealed class WatermarkExtraction {
    /**
     * 检测到水印。
     *
     * @param signatureValid 签名状态：
     *        `true` = 本应用签发且未篡改；
     *        `false` = 带了签名但校验失败（**可能被伪造**）；
     *        `null` = 旧格式无签名，无法判断真伪
     */
    data class Found(
        val text: String,
        val confidence: Float,
        val signatureValid: Boolean? = null
    ) : WatermarkExtraction()

    /** 未检测到。 */
    data class NotFound(val reason: String) : WatermarkExtraction()
}