package com.watermarkbox.invisible

import com.watermarkbox.R
import androidx.annotation.StringRes
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
    val autoRotation: Boolean = true
) {
    companion object {
        val Default = DetectOptions()

        /** 不指定强度时尝试的那个值（与导出默认值一致）。 */
        const val DEFAULT_STRENGTH = 1f

        /**
         * 自动模式下依次尝试的强度。
         *
         * 顺序不是 1→4，而是**从导出默认值开始**（导出面板的 strength
         * 默认是 2）：命中率最高的排最前，自动模式能少跑几轮。
         */
        val STRENGTH_CANDIDATES = listOf(2f, 1f, 3f, 4f)
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
        if (options.checkExif) {
            exifComment = ExifCopier.readUserComment(context, uri)
            val exifText = exifComment
                ?.takeIf { it.startsWith(ExifTextCodec.TAG_PREFIX) }

            if (exifText != null) {
                return@withContext DetectResult.Found(
                    content = exifText,
                    channel = DetectChannel.EXIF,
                    confidence = 1f,
                    frequencyText = null
                )
            }
        }

        if (!options.checkDct) {
            return@withContext DetectResult.NoWatermark(
                if (options.checkExif) REASON_SKIPPED_DCT else REASON_SKIPPED_ALL
            )
        }

        // ---- 第二级：频域 ----
        val bitmap = BitmapUtils.loadBitmap(context, uri, maxEdge = 0)
            ?: return@withContext DetectResult.Error("图片无法解码")

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
                    return@withContext DetectResult.Found(
                        content = extraction.text,
                        channel = DetectChannel.FREQUENCY,
                        confidence = extraction.confidence,
                        frequencyText = extraction.text
                    )
                }
            }
        } finally {
            bitmap.recycle()
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
        val frequencyText: String?
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