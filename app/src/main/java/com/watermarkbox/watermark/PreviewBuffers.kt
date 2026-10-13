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
 * ## 为什么两块就够了
 *
 * 渲染是**串行**的（`PreviewRenderEngine` 用 `collectLatest`），
 * 同一时刻只有一次渲染在写。所以「正在写的那块」与
 * 「最近发布的那块」互斥，两块足够轮换。
 *
 * ## ⚠️ 为什么必须保护「最近发布」而不只是「最新发布那一块」
 *
 * 早期实现只记**一个** `published` 引用，acquire 时避开它。
 * 这在快速连发（拖滑块）时会漏 —— 场景：
 *
 * ```
 * t0  渲染用 A → 发布 A，UI 开始画 A
 * t1  acquire 避开 A → 用 B → 发布 B（此时 published 改成 B）
 * t2  acquire 避开 B → **又选中 A** → 往 A 上画
 * ```
 *
 * 问题在 t2：A 刚刚才发布出去，**UI 很可能还在画它**
 * （渲染在后台线程，UI 落后一两帧是常态）。
 * 往一块正在被绘制的位图上写，画面就会**撕裂 / 频闪** ——
 * 这正是老大实测到的「调字号/角度/密度/透明度时预览频闪」。
 *
 * 修法：不再只记一块，而是记录**最近发布的若干块**（保护窗口），
 * acquire 时避开窗口内的全部。窗口大小取 `slotCount - 1`：
 * 「最近发布过的都别碰，只能写那块最没人看的」。
 *
 * 这样在「2 块缓冲」的配置下等效于严格轮换 A→B→A→B，
 * 只要 UI 落后不超过一帧就不会画到一半被覆写。
 *
 * ## 为什么不在这里 recycle 掉「正在显示」的那块
 *
 * 同 `ImageRepository` 的取舍：正在被绘制的位图一旦 recycle 就会崩
 * （`Canvas: trying to use a recycled bitmap`）。
 * 尺寸变化时只回收**不在保护窗口内**的槽位。
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

    /**
     * **最近发布过的**位图（最近的排在最前）。
     *
     * 长度不超过 [slotCount] - 1 —— 留出至少一块可写，
     * 否则 acquire 会找不到可用槽位。
     */
    private val recentlyPublished = ArrayDeque<Bitmap>()

    /**
     * 取一块可以安全写入的位图。
     *
     * 尺寸不符或已被回收时会重新分配（换图片时才会发生）。
     */
    fun acquire(width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "尺寸必须为正数：${width}x$height" }

        val protectedIndexes = recentlyPublished
            .mapNotNull { pub -> slots.indexOfFirst { it === pub }.takeIf { it >= 0 } }
            .toSet()

        val chosen = pickSlot(cursor, protectedIndexes, slotCount)
        cursor = (chosen + 1) % slotCount

        var buffer = slots[chosen]
        if (buffer == null || buffer.isRecycled ||
            buffer.width != width || buffer.height != height
        ) {
            buffer?.takeIf { !it.isRecycled }?.recycle()
            buffer = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            slots[chosen] = buffer
        }
        return buffer
    }

    /** 渲染结果发布给 UI 之后调用，记下「哪块正在（或刚）显示」。 */
    fun onPublished(bitmap: Bitmap?) {
        if (bitmap == null) {
            // 发布空结果（源图没了）不需要保护任何块
            recentlyPublished.clear()
            return
        }
        recentlyPublished.remove(bitmap)
        recentlyPublished.addFirst(bitmap)
        // 窗口最多留 slotCount - 1 块，保证总有一块可写
        while (recentlyPublished.size > slotCount - 1) {
            recentlyPublished.removeLast()
        }
    }

    /** 释放全部缓冲（离开页面时调用）。 */
    fun release() {
        slots.forEach { slot -> slot?.takeIf { !it.isRecycled }?.recycle() }
        slots.fill(null)
        recentlyPublished.clear()
        cursor = 0
    }
}

/**
 * 选出下一个可写的槽位下标。
 *
 * 规则：**绝不选正在（或刚）发布的那块**（选了会撕裂）；
 * 优先沿用当前游标，被占用就顺延。
 *
 * 抽成纯函数是为了可测 —— 单元测试跑在 JVM 上，构造不了 `Bitmap`。
 *
 * @param cursor 当前游标
 * @param protectedIndexes 受保护（不可写）的槽位
 * @param slotCount 槽位总数
 * @return 可选槽位下标；若全部受保护则退回 `cursor`（调用方保证
 *         窗口最多留一块，正常不会走到这里）
 */
internal fun pickSlot(cursor: Int, protectedIndexes: Set<Int>, slotCount: Int): Int {
    if (cursor < 0 || cursor >= slotCount) return 0
    if (cursor !in protectedIndexes) return cursor
    // 从 cursor 起顺延，找第一个不受保护的
    for (offset in 1 until slotCount) {
        val candidate = (cursor + offset) % slotCount
        if (candidate !in protectedIndexes) return candidate
    }
    // 理论上不可达（窗口最多 slotCount - 1 个），兜底返回游标
    return cursor
}