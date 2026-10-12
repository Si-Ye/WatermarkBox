package com.watermarkbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.watermarkbox.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片数据仓库（Repository 层）。
 *
 * 职责：
 * - 从 Uri 加载图片（含降采样、EXIF 旋正）
 * - 提供参数变化时的重新加载能力
 *
 * 说明：所有耗时操作均在 IO 线程执行，UI 层只拿结果。
 */
class ImageRepository(private val context: Context) {

    /** 缓存的预览缩略图（阶段二会用到，先留位）。 */
    private var cachedPreview: Bitmap? = null
    private var cachedPreviewUri: Uri? = null

    /**
     * 加载预览用的降采样 Bitmap（最大边 1024）。
     * 会复用同一 Uri 的缓存，避免重复解码。
     */
    suspend fun loadPreviewBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        if (cachedPreviewUri == uri && cachedPreview != null && !cachedPreview!!.isRecycled) {
            return@withContext cachedPreview
        }
        recycleCache()
        val bitmap = BitmapUtils.loadBitmap(context, uri, BitmapUtils.PREVIEW_MAX_EDGE)
        cachedPreview = bitmap
        cachedPreviewUri = uri
        bitmap
    }

    /**
     * 加载全分辨率原图（用于导出）。
     * maxEdge <= 0 表示不降采样。
     */
    suspend fun loadFullResolutionBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        BitmapUtils.loadBitmap(context, uri, maxEdge = 0)
    }

    /**
     * 加载 logo 图片（阶段二）。
     * 与预览图不同，logo 通常较小，按给定 maxEdge 降采样即可。
     */
    suspend fun loadBitmapForLogo(uri: Uri, maxEdge: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
            BitmapUtils.loadBitmap(context, uri, maxEdge)
        }

    /**
     * 获取 Uri 对应的显示文件名（用于 {filename} 占位符与导出命名）。
     * 优先查 ContentResolver 的 DISPLAY_NAME。
     */
    suspend fun queryDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** 释放预览缓存。在离开编辑页时调用，避免内存泄漏。 */
    fun recycleCache() {
        cachedPreview?.takeIf { !it.isRecycled }?.recycle()
        cachedPreview = null
        cachedPreviewUri = null
    }
}