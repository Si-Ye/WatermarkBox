package com.watermarkbox.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Bitmap 加载/降采样工具。
 *
 * 关键点：
 * 1. 使用 inJustDecodeBounds 先探测原图尺寸，再按目标最大边计算 inSampleSize，
 *    避免一次性把大图解码进内存导致 OOM。
 * 2. 读取 EXIF 方向并做旋正，保证水印方向与用户看到的一致。
 */
object BitmapUtils {

    /** 预览用缩略图的最大边长（px），平衡清晰度与流畅性。 */
    const val PREVIEW_MAX_EDGE = 1024

    /**
     * 从 Uri 加载 Bitmap。
     *
     * @param context 上下文
     * @param uri 图片 Uri
     * @param maxEdge 目标最大边；<=0 表示不降采样（导出用原图）
     */
    fun loadBitmap(
        context: Context,
        uri: Uri,
        maxEdge: Int = PREVIEW_MAX_EDGE
    ): Bitmap? {
        return try {
            // ---- 第一步：只解码边界，拿到原始宽高 ----
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val (rawWidth, rawHeight) = boundsOptions.outWidth to boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // ---- 第二步：按目标最大边计算 inSampleSize ----
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(rawWidth, rawHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            // ---- 第三步：按 EXIF 旋正 ----
            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                readExifOrientation(input)
            } ?: ExifInterface.ORIENTATION_NORMAL

            applyExifOrientation(decoded, orientation)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 计算 inSampleSize：始终取 2 的幂，且保证降采样后最大边 >= 目标最大边。
     *
     * 例：原图 4000x3000，目标 1024 → inSampleSize = 4（1000x750，接近且不小于 1024/2）
     */
    fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        var sampleSize = 1
        var w = rawWidth
        var h = rawHeight
        val target = maxEdge

        // 双倍递减，直到再减一倍就会小于目标最大边
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 读取 InputStream 中的 EXIF 方向。 */
    private fun readExifOrientation(input: InputStream): Int {
        return try {
            val exif = ExifInterface(input)
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** 根据 EXIF 方向对 Bitmap 做矩阵变换，返回旋正后的新 Bitmap。 */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        } catch (t: Throwable) {
            bitmap
        }
    }

    /**
     * 生成用于预览显示的软引用安全副本。
     * 若目标已是需要的尺寸则直接返回；否则等比缩放到 maxEdge。
     */
    fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxEdge) return source
        val scale = maxEdge.toFloat() / maxSide
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}