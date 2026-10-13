package com.watermarkbox.watermark

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

/**
 * 预览渲染的**双缓冲池**。
 *
 * ## 它解决什么
 *
 * 预览每帧渲染都要一张输出位图。默认实现是 `source.copy()` ——
 * 每帧新分配一张 1024 边的 ARGB_8888 位图（约 3 MB），
 * 拖动时 60 帧/秒就是 ~180 MB/s 的原生分配，足以把 GC 拖出来。
 *
 * 这里维护固定的两块位图轮流复用：分配只在「换了一张尺寸不同的图」时发生。
 *
 * ## 为什么两块就够，以及为什么必须避开「正在显示的那张」
 *
 * 渲染发生在后台线程，而 UI 可能正拿着上一帧的结果在画。
 * 若这一帧写进的就是 UI 正在画的那块，用户会看到撕裂的画面。
 *
 * 所以分配时**跳过最近发布出去的那块**：
 * 正在写 A 的时候，UI 手里的永远是 B。
 *
 * 两块轮换已经足够 —— 渲染是串行的（见 `PreviewRenderEngine` 的
 * `collectLatest`），不存在同时写两块的情况。
 *
 * ## 为什么不在这里 recycle 掉「正在显示」的那块
 *
 * 同 `ImageRepository` 的取舍：正在被绘制的位图一旦 recycle 就会崩
 * （`Canvas: trying to use a recycled bitmap`）。
 * 尺寸变化时只回收**非发布中**的槽位。
 */
class PreviewBuffers(private val slotCount: Int = 2) {

    init {
        require(slotCount >= 2) {
            "至少需要两块缓冲，否则无法避开正在显示的那张（收到 $slotCount）"
        }
    }

    private val slots = arrayOfNulls<Bitmap>(slotCount)

    /** 下一次优先尝试的槽位下标。 */
    private var cursor = 0

    /** 最近一次交给 UI 显示的位图。 */
    private var published: Bitmap? = null

    /**
     * 取一块可以安全写入的位图。
     *
     * 尺寸不符或已被回收时会重新分配（换图片时才会发生）。
     */
    fun acquire(width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "尺寸必须为正数：${width}x$height" }

        val publishedIndex = slots.indexOfFirst { it === published }
        cursor = nextSlotIndex(cursor, publishedIndex, slotCount)

        var buffer = slots[cursor]
        if (buffer == null || buffer.isRecycled ||
            buffer.width != width || buffer.height != height
        ) {
            buffer?.takeIf { !it.isRecycled }?.recycle()
            buffer = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            slots[cursor] = buffer
        }
        cursor = (cursor + 1) % slotCount
        return buffer
    }

    /** 渲染结果发布给 UI 之后调用，记下「哪块正在显示」。 */
    fun onPublished(bitmap: Bitmap?) {
        published = bitmap
    }

    /** 释放全部缓冲（离开页面时调用）。 */
    fun release() {
        slots.forEach { slot -> slot?.takeIf { !it.isRecycled }?.recycle() }
        slots.fill(null)
        published = null
        cursor = 0
    }
}

/**
 * 选出下一个可写的槽位下标。
 *
 * 规则只有一条：**绝不选正在显示的那块**（选了就会撕裂）；
 * 其余情况下沿用当前游标，让两块轮流用。
 *
 * 抽成纯函数是为了可测 —— 单元测试跑在 JVM 上，构造不了 `Bitmap`。
 */
internal fun nextSlotIndex(cursor: Int, publishedIndex: Int, slotCount: Int): Int {
    if (cursor < 0 || cursor >= slotCount) return 0
    return if (cursor == publishedIndex) (cursor + 1) % slotCount else cursor
}