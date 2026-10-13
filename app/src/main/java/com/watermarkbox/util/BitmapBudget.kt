package com.watermarkbox.util

/**
 * 位图内存预算：判断「这张图按全分辨率走完能不能放得下」。
 *
 * ## 为什么需要它
 *
 * 预览会降采样到 1024 边（约 3 MB），所以预览永远不会炸。
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

    /** 检测链路：原图 + 若干一维数组，按 2 份位图估算。 */
    const val DETECT_COPIES = 2

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

    /** [copies] 份 width×height 的 ARGB_8888 位图共需多少字节。 */
    fun bytesOf(width: Int, height: Int, copies: Int = 1): Long =
        width.toLong() * height.toLong() * BYTES_PER_PIXEL * copies

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