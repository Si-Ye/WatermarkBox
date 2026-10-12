package com.watermarkbox.invisible

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.watermarkbox.util.BitmapUtils
import com.watermarkbox.util.ExifCopier
import com.watermarkbox.util.ExifTextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * @param uri        图片 Uri
     * @param engine     频域引擎（强度必须与嵌入时一致才能正确提取）
     */
    suspend fun detect(
        uri: Uri,
        engine: InvisibleWatermarkEngine = InvisibleWatermarkEngine()
    ): DetectResult = withContext(Dispatchers.IO) {
        // ---- 第一级：EXIF ----
        // readUserComment 已内部完成 Base64 解码（见 ExifTextCodec），
        // 因此这里拿到的是**可读原文**，中文完整。
        val exifComment = ExifCopier.readUserComment(context, uri)
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

        // ---- 第二级：频域 ----
        val bitmap = BitmapUtils.loadBitmap(context, uri, maxEdge = 0)
            ?: return@withContext DetectResult.Error("图片无法解码")

        val extraction = try {
            engine.extract(bitmap)
        } finally {
            bitmap.recycle()
        }

        when (extraction) {
            is WatermarkExtraction.Found -> DetectResult.Found(
                content = extraction.text,
                channel = DetectChannel.FREQUENCY,
                confidence = extraction.confidence,
                frequencyText = extraction.text
            )
            is WatermarkExtraction.NotFound -> {
                // 有 EXIF 但不是我们的 → 明确报告「非本 App 水印」，
                // 避免用户误以为没水印就代表图片没被动过
                if (exifComment != null) {
                    DetectResult.NoWatermark("图含 EXIF 注释但不属于本应用水印")
                } else {
                    DetectResult.NoWatermark(extraction.reason)
                }
            }
        }
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
 */
enum class DetectChannel(val displayName: String) {
    /** EXIF UserComment —— 像素未被改动（若未经过压缩）。 */
    EXIF("EXIF 元数据"),

    /** DCT 频域 —— 能扛压缩。 */
    FREQUENCY("频域（DCT）")
}