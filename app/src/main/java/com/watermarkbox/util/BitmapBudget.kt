package com.watermarkbox.util

/**
 * 位图内存预算：判断「这张图按全分辨率走完能不能放得下」。
 *
 * ## 为什么需要它
 *
 * ## 预览也在账上
 *
 * 预览会降采样到 [BitmapUtils.PREVIEW_MAX_EDGE]（4096 边），单张 4:3 约 48 MB。
 * 它的**缓存张数**由 [previewCacheCapacity] 按预算算出（1～3 张），不再写死 ——
 * 写死张数等于让缓存占用不受这里任何约束（见 pending-fixes C2）。
 * 但**导出 / 批量 / 频域检测**都必须用原尺寸：
 *
 * | 图 | ARGB_8888 一张 |
 * |---|---|
 * | 1200 万像素 | 约 48 MB |
 * | 5000 万像素 | 约 200 MB |
 * | 1 亿像素 | 约 400 MB |
 *
 * 而现在的失败点很尴尬：
 *
 * - 解码阶段的 OOM 被 `BitmapUtils` 兜住了（返回 null），只报「加载失败」
 * - **渲染阶段的 `source.copy()` 没有任何兜底** —— 会在那里崩
 * - 频域检测还要再开 `FloatArray(w*h)` 与若干副本，峰值更高
 *
 * 所以正确做法不是「崩了再兜」，而是**动手前先算**：
 * 放不下就别开始，直接给用户一句能看懂的话。
 *
 * ## 预算怎么定
 *
 * `Runtime.maxMemory()` 是**进程堆上限**（不是物理内存）。
 * 取它的一半作为位图预算 —— 剩下一半要留给解码器内部缓冲、
 * 渲染器临时对象、绘制缓存等。
 *
 * ## 为什么要乘「份数」
 *
 * 导出链路峰值同时存在：**原图 + 渲染结果 + 频域副本**，
 * 最多 3 份同位图。只按 1 份算会低估两倍以上。
 *
 * 本文件是纯逻辑，不碰 Android API，可直接单测。
 */
object BitmapBudget {

    /** ARGB_8888：每像素 4 字节。 */
    const val BYTES_PER_PIXEL = 4

    /**
     * 导出链路的**峰值份数**：原图 + 渲染结果 + 频域副本。
     *
     * 注意这是**最坏情况**（开了 DCT 隐藏水印时）。
     * 只写 EXIF、不开 DCT 时实际只需 2 份 —— 用 [exportCopies] 按需取。
     */
    const val EXPORT_COPIES = 3

    /**
     * 检测链路的**峰值份数**。
     *
     * ## 峰值出现在「旋转兜底」时
     *
     * ```
     * 原图位图 + 旋转副本位图 + 提取用的 IntArray(w*h)
     * ```
     *
     * ## 为什么是 3 而不是 2（真实缺陷）
     *
     * `IntArray(w * height)` 是**每像素 4 字节**，等价于**一份位图**的量，
     * 但它分配在 **Java 堆**上，而 `Bitmap` 走 native 堆 ——
     * 两者是**两套账**，都必须计入预算。
     *
     * 早期取 2，只算了位图、漏了 IntArray：一张 1200 万像素的图
     * 在 `getPixels` 处还要再要 48 MB Java 堆，可能直接 OOM。
     *
     * 取 3 是**保守**的（宁可判「图太大」给出明确提示，
     * 也不要让进程在检测中途被系统杀掉）。
     */
    const val DETECT_COPIES = 3

    /**
     * 位图允许占用的堆比例。
     *
     * ## 为什么是 0.6 而不是 0.5（调优记录）
     *
     * 这个系数原本取 0.5（"剩一半留给别的"）。但实测发现它**过于保守**：
     *
     * ```
     * 设备：heapgrowthlimit = 256MB
     * 预算 = 256 × 0.5 = 128MB
     * 每张图 = 128 ÷ 3 = 42.7MB → 约 1120 万像素
     * ```
     *
     * 也就是**随手拍的照片就被判「太大」**。
     *
     * 而 API 26+ 起 `Bitmap` 的像素内存由 `NativeAllocationRegistry`
     * 管理，**不计入 Java 堆**，`Runtime.maxMemory()` 反映的只是
     * Java 堆上限 —— 位图与 Java 对象是两套账。
     * 所以"留一半给 Java 堆"这个前提本身不成立，可以放宽。
     *
     * 另外配合 Manifest 里的 `android:largeHeap="true"`，
     * 上限会从 256MB 提到 512MB，进一步缓解。
     *
     * ## 为什么不能更高
     *
     * 导出链路除位图外还要开频域副本（`FloatArray`）、
     * 编码器缓冲等；留 40% 余量给它们，避免"预检通过但真跑起来 OOM"。
     */
    private const val HEAP_FRACTION = 0.6

    /**
     * 位图预算（字节）。
     *
     * @param maxHeapBytes 进程堆上限；默认取运行时值，测试可注入
     */
    fun budgetBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        (maxHeapBytes * HEAP_FRACTION).toLong()

    /**
     * 本次导出**实际**需要的份数。
     *
     * 链路里同时存在的位图：
     * - 原图（`loadFullResolution` 解出来的）
     * - 渲染结果（`renderer.render` 里 `source.copy()` 出的）
     * - 频域副本（**只在开了 DCT 通道时有**）
     *
     * @param withDct 是否启用 DCT 隐藏水印嵌入
     */
    fun exportCopies(withDct: Boolean): Int = if (withDct) EXPORT_COPIES else 2

    /**
     * 预览缓存最多占预算的比例。
     *
     * ## 为什么是 0.35，而不是跟导出链路平均分
     *
     * 预览缓存是**唯一按「张」计量**的位图缓存，缓存的又是用户**正在看**的图：
     * 少一张的代价是「滑回上一页要重新解码 200～400ms」—— 用户直接能感觉到。
     * 而导出链路的预检本来就会在装不下时给出明确提示，代价是可控的。
     *
     * 取值校核（单张按 4096×3072、ARGB_8888 估 ≈ 48 MB）：
     *
     * | 堆上限 | 预算(60%) | 缓存份额(35%) | 可放张数 |
     * |---|---|---|---|
     * | 512 MB（largeHeap） | 307 MB | 107 MB | 2 |
     * | 256 MB | 153 MB | 53 MB | 1 |
     *
     * 也就是：**largeHeap 机型维持「当前页 + 相邻一张」**（与过去写死 2 的体感一致），
     * 小堆机型自动退到 1 张 —— 而不是过去那样两种机型都写死 2 张。
     */
    private const val PREVIEW_CACHE_SHARE = 0.35

    /** 预览缓存张数上限：再多就明显挤占导出预算，边际收益也低。 */
    const val PREVIEW_CACHE_MAX_IMAGES = 3

    /**
     * 预览缓存该留几张（1～[PREVIEW_CACHE_MAX_IMAGES]）。
     *
     * 单张尺寸按 **4:3** 估（`height = maxEdge * 3 / 4`）：实际照片极少是正方形，
     * 按正方形算会高估 1/3，把容量硬压到 1。
     *
     * 这是**估算**而非精确字节计量 —— 精确计量要给 `LruCacheMap` 加 `sizeOf`，
     * 影响面比这个缺陷本身大，暂不做（见 pending-fixes C2 的说明）。
     *
     * @param maxEdge 预览图的最大边长（[BitmapUtils.PREVIEW_MAX_EDGE]）
     * @param maxHeapBytes 进程堆上限；测试可注入
     */
    fun previewCacheCapacity(
        maxEdge: Int,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): Int {
        if (maxEdge <= 0) return 1
        val perImage = bytesOf(maxEdge, maxEdge * 3 / 4, 1)
        val share = (budgetBytes(maxHeapBytes) * PREVIEW_CACHE_SHARE).toLong()
        return (share / perImage).toInt().coerceIn(1, PREVIEW_CACHE_MAX_IMAGES)
    }

    /** [copies] 份 width×height 的 ARGB_8888 位图共需多少字节。 */
    fun bytesOf(width: Int, height: Int, copies: Int = 1): Long =
        width.toLong() * height.toLong() * BYTES_PER_PIXEL * copies

    /**
     * 把 [width]×[height] 等比缩到「长边不超过 [maxEdge]」后的尺寸。
     *
     * @param maxEdge 长边上限；<= 0 表示不缩放，原样返回
     * @return (宽, 高)，两边都至少为 1
     *
     * ## 为什么要单独抽出来
     *
     * 预检要算「缩完有多大」，缩放又要真的缩到那个尺寸 ——
     * 两处必须用**同一个取整规则**，否则预检说放得下、实际却多出几个像素。
     * 这里不碰任何 Android API，可以直接单测。
     */
    fun fitLongEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        if (maxEdge <= 0) return width to height
        val maxSide = maxOf(width, height)
        if (maxSide <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / maxSide
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * 选了导出尺寸档位时，链路的**峰值**字节数。
     *
     * ## 为什么不能直接套 [bytesOf] × copies
     *
     * 降采样后的链路有两个先后不同的峰值，取大的那个才是真的峰值：
     *
     * ```
     * ① 缩放瞬间：解码出的位图 + 缩放结果      （2 份，尺寸不同）
     * ② 缩放完成后：原图(=缩放结果) + 渲染结果 + 频域副本 （copies 份，同尺寸）
     * ```
     *
     * 只按 ② 算会**漏掉 ①**：`inSampleSize` 只能取 2 的幂，
     * 解码结果落在 `[maxEdge, 2×maxEdge)`，缩放那一刻它可能比目标大一倍。
     * 而只按 ① 算又会漏掉 DCT 那份副本。
     *
     * @param decodeWidth  解码器实际输出的宽（已含 inSampleSize 降采样）
     * @param targetWidth  最终要用的宽（长边 = 档位上限）
     */
    fun scaledExportPeakBytes(
        decodeWidth: Int,
        decodeHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        copies: Int
    ): Long {
        if (decodeWidth <= 0 || decodeHeight <= 0) return 0
        val duringScale = bytesOf(decodeWidth, decodeHeight, 1) +
            bytesOf(targetWidth, targetHeight, 1)
        val afterScale = bytesOf(targetWidth, targetHeight, copies)
        return maxOf(duringScale, afterScale)
    }

    /**
     * 这份图能不能按全分辨率处理完。
     *
     * 尺寸非法（探测失败）直接判 false —— 调用方会走「加载失败」分支。
     */
    fun fitsFullResolution(
        width: Int,
        height: Int,
        copies: Int = EXPORT_COPIES,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        return bytesOf(width, height, copies) <= budgetBytes(maxHeapBytes)
    }

    /** 字节数换算成 MB（向上取整），用于提示文案。 */
    fun megabytes(bytes: Long): Long {
        if (bytes <= 0) return 0
        val mb = 1024L * 1024L
        return (bytes + mb - 1) / mb
    }
}