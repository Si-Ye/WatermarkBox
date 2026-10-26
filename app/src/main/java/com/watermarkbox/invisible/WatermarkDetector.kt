package com.watermarkbox.invisible

import com.watermarkbox.R
import androidx.annotation.StringRes
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.watermarkbox.util.BitmapBudget
import com.watermarkbox.util.BitmapLoadOutcome
import com.watermarkbox.util.BitmapUtils
import com.watermarkbox.util.ExifCopier
import com.watermarkbox.util.ExifTextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 检测选项 —— 在「更快」与「更全」之间交给用户决定。
 *
 * ## 为什么需要它
 *
 * 默认的全量检测要付出好几倍代价：
 *
 * | 维度 | 为什么要多试 | 倍数 |
 * |---|---|---|
 * | **通道** | DCT 要逐像素扫描，比读 EXIF 慢几个数量级 | — |
 * | **强度** | 导出时用了几倍强度，检测端并不知道，只能 1~4 全试 | ×4 |
 * | **方向** | 图片可能被旋转过，只能 4 个方向都试 | ×4 |
 *
 * 三者叠加，最坏情况比「只读 EXIF」慢两个数量级。
 *
 * 而多数时候用户是清楚的 —— 图片没被旋转、或只想快速确认一下。
 * 这时逐项关掉能快很多，代价是自己承担「可能漏检」的风险。
 *
 * ## 默认值
 *
 * 全部开启：**正确性优先**。宁可慢一点，也不要给出
 * 「没有水印」这种可能导致误判的结论。
 */
data class DetectOptions(
    /** 是否读 EXIF 元数据通道 */
    val checkExif: Boolean = true,

    /** 是否做 DCT 频域检测（最慢的一项） */
    val checkDct: Boolean = true,

    /**
     * 是否自动尝试全部嵌入强度。
     *
     * 关掉后只试 [DEFAULT_STRENGTH]，快约 4 倍 ——
     * 但若导出时用的不是这个强度，就会漏检。
     */
    val autoStrength: Boolean = true,

    /**
     * 是否自动尝试 4 个旋转方向。
     *
     * 关掉后只按当前方向提取，快约 4 倍 ——
     * 但被旋转过的图就认不出来了。
     */
    val autoRotation: Boolean = true,

    /**
     * 是否用 DCT 通道验证签名。
     *
     * ## 为什么需要它
     *
     * EXIF 存的是**明文**，本身不带签名。若看到 EXIF 就直接返回，
     * 那么即使这张图**同时**带着签名有效的 DCT 水印，
     * 结果也只能是「无法验证」—— 一张完全合法的图显得可疑。
     *
     * 打开这项后，EXIF 命中时会**继续扫一遍 DCT** 把签名状态取回来，
     * 于是能显示「已验证 · 本应用签发」。
     *
     * ## 代价
     *
     * EXIF 命中的图也要跑一次全量频域扫描，明显变慢。
     * 只想快速确认「有没有水印」时，把它关掉 —— 那时签名列会显示
     * 「无法验证」，这是**如实**的：确实没去查。
     *
     * 仅在 [checkDct] 也开启时有意义。
     */
    val verifySignatureViaDct: Boolean = true
) {
    companion object {
        val Default = DetectOptions()

        /**
         * 「自动强度」关闭时尝试的那个值。
         *
         * ⚠️ 必须与**导出侧的默认强度**一致 —— 见
         * `InvisibleWatermarkConfig.strength` 的默认值（`2`）。
         *
         * 早期这里写的是 `1f`，而注释声称"与导出默认值一致"，
         * 实际**不一致**：用户关掉「自动强度」后只试 `1f`，
         * 而默认导出用的是 `2f` → **自己导出的图检不出来**。
         */
        const val DEFAULT_STRENGTH = 2f

        /**
         * 自动模式下依次尝试的强度。
         *
         * ## 只含导出侧**实际可能产生**的档位（1、2）
         *
         * 早期是 `listOf(2f, 1f, 3f, 4f)`。但 M4 之后导出已收紧到
         * `coerceIn(1, 2)`（3/4 档被 `MAX_STEP` 吃掉、已从 UI 移除）——
         * 那两档**永远不可能命中**，却让每次自动检测**白跑 50%** 的全图 DCT
         * （大图上每轮数秒）。
         *
         * ## 顺序
         *
         * 从导出默认值（`2`）开始 —— 命中率最高的排最前，
         * 自动模式能少跑一轮。
         */
        val STRENGTH_CANDIDATES = listOf(2f, 1f)
    }
}

/**
 * 隐藏水印**检测器**。
 *
 * ## 检测策略（两级）
 *
 * ```
 * 输入图片
 *    │
 *    ├─► 第一级：读 EXIF UserComment
 *    │     命中 "WMB1:" → 直接返回（像素未被动过，最可信）
 *    │
 *    └─► 第二级：DCT 频域提取
 *          能解出且 CRC 通过 → 返回
 *          两个都失败 → 判定无水印
 * ```
 *
 * 两级都可按 [DetectOptions] 单独关闭，用来换速度。
 *
 * ## 为什么要读「原分辨率」而不是缩略图
 *
 * 频域水印以 **8×8 块**为基本单位。如果先把图缩放到 1024，
 * 缩放会破坏块与块之间的精确对齐（重采样不是整数倍时尤甚），
 * 提取率会大幅下降。
 *
 * 因此检测时**不降采样**（用 `maxEdge = 0`）。代价是内存峰值高，
 * 但检测是一次性操作，且失败会返回 null 而不是崩溃。
 *
 * ## 关于「部分命中」
 *
 * 如果 EXIF 和频域都读到内容但**不一致**（比如用户先嵌 A，
 * 后来用别的工具嵌了 B），优先信 EXIF（人可读、无歧义），
 * 但在结果里标注 frequencyText 供对照。
 */
class WatermarkDetector(private val context: Context) {

    /**
     * 检测一张图片是否含隐藏水印。
     *
     * @param uri     图片 Uri
     * @param options 检测选项（通道 / 强度 / 方向），见 [DetectOptions]
     */
    suspend fun detect(
        uri: Uri,
        options: DetectOptions = DetectOptions.Default
    ): DetectResult = withContext(Dispatchers.IO) {
        // ---- 第一级：EXIF ----
        // readUserComment 已内部完成 Base64 解码（见 ExifTextCodec），
        // 因此这里拿到的是**可读原文**，中文完整。
        var exifComment: String? = null
        var exifText: String? = null
        if (options.checkExif) {
            // EXIF UserComment 与 XMP 是**两个独立的元数据容器**，
            // 有些工具只清其中一个。两个都读，命中率更高。
            // EXIF 优先：人可读、任何看图工具都能直接看到。
            exifComment = ExifCopier.readUserComment(context, uri)
                ?: ExifCopier.readXmp(context, uri)
            exifText = exifComment?.takeIf { it.startsWith(ExifTextCodec.TAG_PREFIX) }
        }

        // EXIF 命中，且不需要用 DCT 取签名状态 → 直接返回（最快路径）。
        //
        // 这里返回的 signatureValid 是 null 而不是 true：
        // EXIF 是**明文**、本身不带签名，谁都能改。标成「无法验证」
        // 才不会让上层把「EXIF 有内容」误读成「确认是本应用写的」。
        if (exifText != null && !(options.checkDct && options.verifySignatureViaDct)) {
            return@withContext DetectResult.Found(
                content = exifText,
                channel = DetectChannel.EXIF,
                confidence = 1f,
                frequencyText = null,
                signatureValid = null
            )
        }

        if (!options.checkDct) {
            return@withContext DetectResult.NoWatermark(
                if (options.checkExif) REASON_SKIPPED_DCT else REASON_SKIPPED_ALL
            )
        }

        // ---- 第二级：频域 ----
        //
        // 频域检测**必须**用原尺寸：降采样会破坏 8×8 块结构，水印就找不回来了。
        // 但全分辨率很吃内存，所以先过预检 —— 放不下就明确报错，
        // 而不是让后面 `FloatArray(w*h)` 的分配把进程带走。
        val load = BitmapUtils.loadFullResolution(
            context = context,
            uri = uri,
            copies = BitmapBudget.DETECT_COPIES
        )
        if (load !is BitmapLoadOutcome.Ok) {
            return@withContext DetectResult.Error(
                if (load is BitmapLoadOutcome.TooLarge) ERROR_IMAGE_TOO_LARGE
                else ERROR_DECODE_FAILED
            )
        }
        val bitmap = load.bitmap

        try {
            // 强度：导出时用了几倍，检测端无从得知，只能挨个试。
            // 关掉「自动强度」就只试默认值，快约 4 倍。
            val candidates = if (options.autoStrength) {
                DetectOptions.STRENGTH_CANDIDATES
            } else {
                listOf(DetectOptions.DEFAULT_STRENGTH)
            }

            for (strength in candidates) {
                val engine = InvisibleWatermarkEngine(strength)
                val extraction = engine.extract(bitmap, tryRotations = options.autoRotation)

                if (extraction is WatermarkExtraction.Found) {
                    // EXIF 优先（人可读、无歧义），但把 DCT 验到的**签名状态**
                    // 一起带上 —— 这正是本选项存在的意义：
                    // 让「EXIF + DCT 都有的合法图」显示成「已验证」，
                    // 而不是含糊的「无法验证」。
                    return@withContext if (exifText != null) {
                        DetectResult.Found(
                            content = exifText,
                            channel = DetectChannel.EXIF,
                            confidence = 1f,
                            frequencyText = extraction.text,
                            signatureValid = extraction.signatureValid
                        )
                    } else {
                        DetectResult.Found(
                            content = extraction.text,
                            channel = DetectChannel.FREQUENCY,
                            confidence = extraction.confidence,
                            frequencyText = extraction.text,
                            signatureValid = extraction.signatureValid
                        )
                    }
                }
            }
        } catch (oom: OutOfMemoryError) {
            // 频域检测要开 `FloatArray(w * height)`（每像素 4 字节，等价于一份位图），
            // 旋转兜底还会再多一张副本 —— `DETECT_COPIES` 已按 3 份预检，
            // 但预检是估算，仍可能漏（位图走 native 堆、数组走 Java 堆，是两套账）。
            //
            // 不在这里兜，OOM 会顺着协程把进程带走 —— 用户看到的就是「点检测，应用闪退」。
            return@withContext DetectResult.Error(ERROR_IMAGE_TOO_LARGE)
        } finally {
            bitmap.recycle()
        }

        // EXIF 有内容，但 DCT 没命中 → 只能给出 EXIF 的内容，
        // 签名无从验证（这张图可能只写了 EXIF，没写频域）
        if (exifText != null) {
            return@withContext DetectResult.Found(
                content = exifText,
                channel = DetectChannel.EXIF,
                confidence = 1f,
                frequencyText = null,
                signatureValid = null
            )
        }

        // 有 EXIF 但不是我们的 → 明确报告「非本 App 水印」，
        // 避免用户误以为没水印就代表图片没被动过
        if (exifComment != null) {
            DetectResult.NoWatermark(REASON_FOREIGN_EXIF)
        } else {
            DetectResult.NoWatermark(REASON_NOT_FOUND)
        }
    }

    companion object {
        /** 未找到原因（文案与引擎保持一致，界面层会做本地化映射）。 */
        const val REASON_NOT_FOUND = "未检测到水印（或损坏超出纠错能力）"
        const val REASON_FOREIGN_EXIF = "图含 EXIF 注释但不属于本应用水印"
        const val REASON_SKIPPED_DCT = "已按设置跳过频域检测，EXIF 中也没有"
        const val REASON_SKIPPED_ALL = "已按设置跳过全部检测通道"

        /** 图片过大：全分辨率的峰值超出内存预算，频域检测做不了。 */
        const val ERROR_IMAGE_TOO_LARGE = "图片过大，本机内存不足以做频域检测"

        /** 解码失败（文件损坏 / 读不到）。 */
        const val ERROR_DECODE_FAILED = "图片无法解码"
    }
}

/**
 * 检测结果。
 */
sealed class DetectResult {

    /** 检测到水印。 */
    data class Found(
        /** 完整内容（含 `WMB1:` 前缀）。 */
        val content: String,
        /** 从哪个通道读到的。 */
        val channel: DetectChannel,
        /** 置信度 0~1。EXIF 通道恒为 1。 */
        val confidence: Float,
        /** 频域通道读到的内容（若 EXIF 优先命中则可能为空）。 */
        val frequencyText: String?,
        /**
         * 签名状态。
         *
         * - `true`  = 本应用签发且未篡改
         * - `false` = 带签名但校验失败（**可能被伪造**）
         * - `null`  = 无法判断（EXIF 通道是明文，本身就没有签名；
         *   或图片来自旧版本，那时还没有签名机制）
         */
        val signatureValid: Boolean? = null
    ) : DetectResult()

    /** 未检测到水印。 */
    data class NoWatermark(val reason: String) : DetectResult()

    /** 出错（图片打不开等）。 */
    data class Error(val message: String) : DetectResult()
}

/**
 * 水印所在的通道。
 *
 * labelRes 存资源 ID：界面需跟随语言显示（中文「EXIF 元数据」/ 英文 "EXIF metadata"）。
 * 该枚举不参与序列化，改类型无兼容性影响。
 */
enum class DetectChannel(@param:StringRes val labelRes: Int) {
    /** EXIF UserComment —— 像素未被改动（若未经过压缩）。 */
    EXIF(R.string.detect_channel_exif),

    /** DCT 频域 —— 能扛压缩。 */
    FREQUENCY(R.string.detect_channel_frequency)
}