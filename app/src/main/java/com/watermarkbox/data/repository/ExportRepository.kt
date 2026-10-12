package com.watermarkbox.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.util.ExifCopier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 导出结果。
 *
 * @param uri        MediaStore 中的 Uri（可用于分享/预览）
 * @param displayName 最终文件名
 */
data class ExportResult(
    val uri: Uri,
    val displayName: String
)

/**
 * 图片导出仓库：把 Bitmap 写入 MediaStore 的 Pictures/水印盒 目录。
 *
 * 关键点（Android 10+ 分区存储）：
 * - 通过 MediaStore.Images 插入条目，由系统分配 Uri，**无需任何存储权限**
 * - RELATIVE_PATH 指定相册内相对路径（Pictures/水印盒）
 * - IS_PENDING 标记：写入期间对其它 App 不可见，写完置 0 完成发布
 *
 * Android 9 及以下（minSdk 26 覆盖到）需要 WRITE_EXTERNAL_STORAGE，
 * 但本 App 目标设备普遍为 10+，且已在 Manifest 中以 maxSdkVersion 限制申请。
 */
class ExportRepository(private val context: Context) {

    companion object {
        /** 默认输出目录（相册相对路径）。 */
        const val DEFAULT_ALBUM = "Pictures/水印盒"
    }

    /**
     * 把 Bitmap 保存为图片到相册。
     *
     * @param bitmap       要保存的位图
     * @param displayName  文件名（含扩展名）
     * @param format       导出格式（决定压缩方式与 MIME）
     * @param jpegQuality  JPEG 质量（仅 JPEG 有效）
     * @param albumPath    相册相对路径；null 用默认
     * @param sourceUri    源图 Uri；用于 EXIF 拷贝（null 则跳过）
     * @param preserveExif 是否把源图 EXIF 复制到导出文件
     * @param invisibleContent 隐藏水印内容（null 表示不写）；会写进 EXIF UserComment
     * @return 导出结果；失败返回 null
     */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        format: ExportFormat,
        jpegQuality: Int = 95,
        albumPath: String? = null,
        sourceUri: Uri? = null,
        preserveExif: Boolean = false,
        invisibleContent: String? = null
    ): ExportResult? = withContext(Dispatchers.IO) {
        val relativePath = (albumPath ?: DEFAULT_ALBUM).trimEnd('/')

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = context.contentResolver.insert(collection, values) ?: return@withContext null

            context.contentResolver.openOutputStream(uri)?.use { output ->
                writeToStream(bitmap, output, format, jpegQuality)
            } ?: return@withContext null

            // ---- 像素写完后，才处理元数据（顺序很重要）----
            //
            // 必须先关掉输出流再让 ExifInterface 以 rw 打开同一个 Uri，
            // 否则两边的文件描述符会互相踩踏。
            applyMetadata(uri, sourceUri, preserveExif, invisibleContent)

            // 发布：清除 pending 标记
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
            }

            ExportResult(uri = uri, displayName = displayName)
        } catch (t: Throwable) {
            // 失败时清理已插入的空条目，避免相册出现损坏文件
            uri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
            }
            null
        }
    }

    /**
     * 写入元数据：EXIF 拷贝 + 隐藏水印。
     *
     * **顺序**：先拷贝源图的 EXIF，再写隐藏水印的 UserComment。
     * 反过来的话，源图若有 UserComment 会覆盖掉我们的水印标签。
     */
    private fun applyMetadata(
        targetUri: Uri,
        sourceUri: Uri?,
        preserveExif: Boolean,
        invisibleContent: String?
    ) {
        if (preserveExif && sourceUri != null) {
            ExifCopier.copyExif(context, sourceUri, targetUri)
        }
        if (!invisibleContent.isNullOrBlank()) {
            ExifCopier.writeUserComment(context, targetUri, invisibleContent)
        }
    }

    /** 按格式写入输出流。 */
    private fun writeToStream(
        bitmap: Bitmap,
        output: OutputStream,
        format: ExportFormat,
        jpegQuality: Int
    ) {
        when (format) {
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            ExportFormat.JPEG -> bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                jpegQuality.coerceIn(1, 100),
                output
            )
        }
        output.flush()
    }

    /** 检查外部存储是否可写（Android 9 及以下使用）。 */
    fun isExternalStorageWritable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
}