package com.watermarkbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.watermarkbox.util.BitmapBudget
import com.watermarkbox.util.BitmapLoadOutcome
import com.watermarkbox.util.BitmapUtils
import com.watermarkbox.util.LruCacheMap
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

    /**
     * 预览缩略图缓存：**按 Uri 保留最近用到的几张**。
     *
     * ## 为什么从「只缓存一张」改成 LRU
     *
     * 批量页要左右滑图片。只留一张时，每次滑动都得重新解码，
     * 而且旧图被立刻 recycle，UI 只能先闪一个加载圈 ——
     * 来回滑几下就是「亮一下、卡一下」。
     *
     * 留 3 张后，滑到相邻页是**瞬时**的（切过去之前就已经解码好了）。
     *
     * ## 为什么容量是 3
     *
     * 每张预览图最大边 1024 → 约 4 MB（ARGB_8888）。
     * 3 张 ≈ 12 MB，在批量页这个场景里可以接受；
     * 再多就会跟「上限 100 张」的批量规模一起把内存吃紧。
     *
     * ## 为什么淘汰时**不** recycle
     *
     * 被淘汰的那张可能**正被某一帧绘制**（UI 还持有引用）。
     * recycle 掉它就会炸「Canvas: trying to use a recycled bitmap」。
     * 这里只丢引用交给 GC —— 宁可晚一点释放，也不要崩。
     */
    private val previewCache = LruCacheMap<Uri, Bitmap>(capacity = PREVIEW_CACHE_CAPACITY)

    /**
     * 取已缓存的预览图（**不触发解码**）。
     *
     * 供 UI 在切换前先看看「有没有现成的」：命中就直接显示，
     * 不命中再走异步加载 —— 这样滑动相邻页不会闪加载圈。
     */
    fun peekPreview(uri: Uri): Bitmap? = previewCache.get(uri)?.takeIf { !it.isRecycled }

    /**
     * 加载预览用的降采样 Bitmap（最大边 1024）。
     * 命中缓存直接返回，避免重复解码。
     */
    suspend fun loadPreviewBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        peekPreview(uri)?.let { return@withContext it }
        val bitmap = BitmapUtils.loadBitmap(context, uri, BitmapUtils.PREVIEW_MAX_EDGE)
        if (bitmap != null) previewCache.put(uri, bitmap)
        bitmap
    }

    /**
     * 加载**全分辨率**原图（导出用）。
     *
     * 带内存预检：放不下时返回 [BitmapLoadOutcome.TooLarge]，
     * 由 UI 明确告诉用户「图太大」，而不是等渲染阶段的 `source.copy()` 崩掉。
     *
     * @param withDct 本次导出是否会走 DCT 嵌入。
     *                会 → 峰值 3 份（原图 + 渲染结果 + 频域副本）；
     *                不会 → 只需 2 份。
     *
     *                **为什么必须区分**：早期固定按 3 份扣预算，
     *                导致"只写 EXIF"的用户也被多扣 1/3，
     *                大图直接判 TooLarge（实测 256MB 堆下上限仅
     *                约 1120 万像素，随手拍的照片就超了）。
     */
    suspend fun loadFullResolutionBitmap(
        uri: Uri,
        withDct: Boolean = true
    ): BitmapLoadOutcome =
        withContext(Dispatchers.IO) {
            BitmapUtils.loadFullResolution(
                context = context,
                uri = uri,
                copies = BitmapBudget.exportCopies(withDct = withDct)
            )
        }

    /**
     * 只**探测原图尺寸**（不解码像素）。
     *
     * 预览需要它来把水印按「相对原图的比例」渲染 ——
     * 否则预览与导出的水印大小会差出图片缩放倍数
     * （见 `WatermarkPreview.sourceWidth`）。
     *
     * @return (width, height)；失败返回 null
     */
    suspend fun queryImageSize(uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        BitmapUtils.queryImageSize(context, uri)
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

    /**
     * 丢掉所有缓存引用，但**不 recycle**。
     *
     * 用于「换了一批图片」这类场景：旧图的解码结果没用了，
     * 但它可能还在某一帧里被画着 —— 当场 recycle 会崩。
     */
    fun dropPreviewCache() = previewCache.clear()

    /** 彻底释放预览缓存（含 recycle）。只在页面销毁时调用，避免内存泄漏。 */
    fun recycleCache() {
        previewCache.values().forEach { bitmap ->
            bitmap.takeIf { !it.isRecycled }?.recycle()
        }
        previewCache.clear()
    }

    private companion object {
        /**
         * 预览缓存容量。
         *
         * ## 为什么从 3 降到 2
         *
         * 预览底图现在是**原图分辨率**（见 [BitmapUtils.PREVIEW_MAX_EDGE]）：
         * 一张 1200 万像素的图约 **48 MB** —— 3 张就是 144 MB，太重。
         *
         * 降到 2（当前页 + 相邻一张）后约 96 MB，仍能保证
         * 「滑到隔壁看一眼」是瞬时的（批量页最常用的动作）。
         */
        const val PREVIEW_CACHE_CAPACITY = 2
    }
}