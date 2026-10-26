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
     * ## 容量是**算出来的**，不再是写死的数
     *
     * 预览图最大边 [BitmapUtils.PREVIEW_MAX_EDGE]（4096），一张 4:3 的约 **48 MB** ——
     * 3 张就是 144 MB，任何机型都吃不消。
     *
     * 所以容量交给 [BitmapBudget.previewCacheCapacity]：按进程堆预算算能放几张
     * （1～3）。largeHeap 机型通常 2 张（当前页 + 相邻一张），小堆机型自动退到 1 张。
     *
     * 早期这里写死「1024 边 → 4 MB → 3 张 ≈ 12 MB」，那是**降采样时代**的数：
     * 底图换成原分辨率后实际占用是它的 12 倍，而容量却还按旧数字拍的板
     * （见 pending-fixes C2）。
     *
     * ## 为什么淘汰时**不** recycle
     *
     * 被淘汰的那张可能**正被某一帧绘制**（UI 还持有引用）。
     * recycle 掉它就会炸「Canvas: trying to use a recycled bitmap」。
     * 这里只丢引用交给 GC —— 宁可晚一点释放，也不要崩。
     */
    private val previewCache = LruCacheMap<Uri, Bitmap>(
        capacity = BitmapBudget.previewCacheCapacity(BitmapUtils.PREVIEW_MAX_EDGE)
    )

    /**
     * 取已缓存的预览图（**不触发解码**）。
     *
     * 供 UI 在切换前先看看「有没有现成的」：命中就直接显示，
     * 不命中再走异步加载 —— 这样滑动相邻页不会闪加载圈。
     */
    fun peekPreview(uri: Uri): Bitmap? = previewCache.get(uri)?.takeIf { !it.isRecycled }

    /**
     * 加载预览用的降采样 Bitmap。
     *
     * ## 参数为什么变多了（渐进式加载）
     *
     * 预览现在是「**先低清占位 → 再高清替换**」（见 `EditorViewModel.loadImage`）：
     * 大图解码 + 上传 GPU 纹理要几百毫秒，先给用户看一眼低清版，
     * 体感上「立刻就有图」，之后再无缝换成高清。
     *
     * 因此这个方法要能：
     * - 按**指定分辨率**解码（低清占位 / 高清最终）
     * - 决定**是否写入 LRU 缓存**
     *
     * @param maxEdge 最大边长；默认 [BitmapUtils.PREVIEW_MAX_EDGE]（高清）
     * @param cache 是否写入 LRU 缓存。
     *   **低清占位图必须传 `false`** —— 它只是"先看一眼"的临时物，
     *   缓存它会把**高清版挤出去**（容量只有 [BitmapBudget.previewCacheCapacity] 张），
     *   下一帧又得重新解码，反而更慢。
     */
    suspend fun loadPreviewBitmap(
        uri: Uri,
        maxEdge: Int = BitmapUtils.PREVIEW_MAX_EDGE,
        cache: Boolean = true
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 只有「要缓存的」（高清）才走缓存命中；低清每次现解 ——
        // 但它每张图只解一次，代价可忽略
        if (cache) peekPreview(uri)?.let { return@withContext it }
        val bitmap = BitmapUtils.loadBitmap(context, uri, maxEdge)
        if (bitmap != null && cache) previewCache.put(uri, bitmap)
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

}
