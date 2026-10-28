package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BitmapBudget] 的单元测试。
 *
 * 全部用「显式注入堆上限」的方式测 —— 不依赖跑测试那台机器的内存，
 * 断言才是确定的。
 *
 * ## 判错方向的后果（不对称）
 *
 * - 判「放得下」而其实放不下 → **崩溃**
 * - 判「放不下」而其实可以 → 用户少导一张图（但会明确告知"图太大"）
 *
 * 所以宁可保守。但**过度保守也是 bug**：早期按 `0.5` 系数 + 固定 3 份，
 * 256MB 堆下上限只有 42.7MB（约 1120 万像素），
 * 导致随手拍的照片就"导出失败" —— 这正是老大实测到的现象。
 */
class BitmapBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `字节数按像素与份数计算`() {
        // 1000x500 = 50 万像素 × 4 字节 = 2 MB
        assertEquals(2_000_000L, BitmapBudget.bytesOf(1000, 500))
        assertEquals(6_000_000L, BitmapBudget.bytesOf(1000, 500, copies = 3))
    }

    /**
     * 预算是堆上限的 60%。
     *
     * 历史：早期是 50%。下调到 60% 是因为
     * API 26+ 位图内存走 NativeAllocationRegistry、不计入 Java 堆，
     * "留一半给 Java 堆"的前提不成立。
     */
    @Test
    fun `预算是堆上限的六成`() {
        assertEquals((256 * mb * 0.6).toLong(), BitmapBudget.budgetBytes(256 * mb))
        assertEquals((512 * mb * 0.6).toLong(), BitmapBudget.budgetBytes(512 * mb))
    }

    // ------------------------------------------------------------------
    // exportCopies：按实际通道数取份数
    // ------------------------------------------------------------------

    @Test
    fun `开 DCT 时导出需要三份`() {
        assertEquals(3, BitmapBudget.exportCopies(withDct = true))
    }

    /** 只写 EXIF 时没有频域副本，只需 2 份 —— 别多扣 1/3 预算。 */
    @Test
    fun `不开 DCT 时导出只需两份`() {
        assertEquals(2, BitmapBudget.exportCopies(withDct = false))
    }

    // ------------------------------------------------------------------
    // 回归：这次修复的核心场景
    // ------------------------------------------------------------------

    /**
     * **回归防护**：1200 万像素（手机随手拍）在 256MB 堆下必须能导出。
     *
     * 4032×3024 = 1219 万像素，一份 46.5 MB。
     * - 不开 DCT：2 份 = 93 MB ≤ 256×0.6 = 153.6 MB ✅
     * - 开 DCT：  3 份 = 139.5 MB ≤ 153.6 MB ✅
     *
     * 而旧实现（0.5 系数 + 固定 3 份）预算只有 128 MB，
     * 3 份 139.5 MB > 128 MB → **判为太大而失败**。
     */
    @Test
    fun `一千二百万像素在256MB堆下应能导出`() {
        val w = 4032
        val h = 3024
        val heap = 256 * mb

        assertTrue(
            "不开 DCT 时必须放得下（旧实现这里会失败）",
            BitmapBudget.fitsFullResolution(
                w, h, BitmapBudget.exportCopies(withDct = false), heap
            )
        )
        assertTrue(
            "开 DCT 时也必须放得下",
            BitmapBudget.fitsFullResolution(
                w, h, BitmapBudget.exportCopies(withDct = true), heap
            )
        )
    }

    /** 512MB 堆（largeHeap 生效）下，2000 万像素也该能过。 */
    @Test
    fun `两千万像素在largeHeap下能导出`() {
        // 5472×3648 = 1996 万像素，一份 76.4 MB；3 份 229 MB ≤ 307 MB ✅
        assertTrue(
            BitmapBudget.fitsFullResolution(
                5472, 3648, BitmapBudget.exportCopies(withDct = true), 512 * mb
            )
        )
    }

    /**
     * 加 `largeHeap` 后上限翻倍：同一张图可能从"放不下"变"放得下"。
     *
     * 用 6000×4500（2700 万像素，一份 103 MB）验证 —— 注意这里**不开 DCT**
     * （2 份），否则 3 份 = 309 MB 在 512MB 堆下也差一点点（预算 307 MB）。
     * 正好说明"按实际份数算"的价值：开关 DCT 会改变可行性判断。
     */
    @Test
    fun `largeHeap 让原本超限的图变可行`() {
        val copies = BitmapBudget.exportCopies(withDct = false)   // 2 份 = 206 MB

        assertFalse(
            "256MB 堆预算 153.6MB < 需要的 206MB → 应判放不下",
            BitmapBudget.fitsFullResolution(6000, 4500, copies, 256 * mb)
        )
        assertTrue(
            "512MB 堆预算 307.2MB ≥ 206MB → 应判放得下",
            BitmapBudget.fitsFullResolution(6000, 4500, copies, 512 * mb)
        )
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    /** 5000 万像素（8160×6120）三份约 598 MB → 512 MB 堆下必然放不下。 */
    @Test
    fun `五千万像素放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(
                width = 8160, height = 6120,
                copies = BitmapBudget.exportCopies(withDct = true),
                maxHeapBytes = 512 * mb
            )
        )
    }

    /**
     * 同一张图，**份数不同结论可以不同**。
     *
     * ⚠️ 这里用**显式的 2 / 3 份**，而不是引用 `DETECT_COPIES` 之类的常量 ——
     * 本测试要钉的是「份数会影响结论」这条**性质**，
     * 与具体某条链路取几份无关。早期引用常量，导致常量一改测试就假失败
     * （那是「把现状写成断言」的又一例）。
     */
    @Test
    fun `份数不同结论可以不同`() {
        val w = 7000
        val h = 5000          // 3500 万像素：一份 133.5 MB
        val heap = 512 * mb   // 预算 307 MB
        // 2 份 = 267 MB ≤ 307 MB → 放得下
        assertTrue(BitmapBudget.fitsFullResolution(w, h, copies = 2, maxHeapBytes = heap))
        // 3 份 = 400 MB > 307 MB → 放不下
        assertFalse(BitmapBudget.fitsFullResolution(w, h, copies = 3, maxHeapBytes = heap))
    }

    /** 尺寸非法（探测失败）时不能判成「放得下」。 */
    @Test
    fun `非法尺寸判为放不下`() {
        assertFalse(BitmapBudget.fitsFullResolution(0, 100, maxHeapBytes = 4L * 1024 * mb))
        assertFalse(BitmapBudget.fitsFullResolution(100, -1, maxHeapBytes = 4L * 1024 * mb))
    }

    /** 一亿像素即便堆给到 2 GB 也不该通过（3 份 = 1.2 GB）。 */
    @Test
    fun `一亿像素即使大堆也放不下`() {
        assertFalse(
            BitmapBudget.fitsFullResolution(
                12000, 9000, BitmapBudget.exportCopies(withDct = true), 2048 * mb
            )
        )
    }

    @Test
    fun `MB 换算向上取整`() {
        assertEquals(0, BitmapBudget.megabytes(0))
        assertEquals(1, BitmapBudget.megabytes(1))
        assertEquals(1, BitmapBudget.megabytes(mb))
        assertEquals(2, BitmapBudget.megabytes(mb + 1))
        assertEquals(200, BitmapBudget.megabytes(200 * mb))
    }

    /** 默认参数必须真的能用（别在构造时炸，例如 maxMemory 返回 0）。 */
    @Test
    fun `默认参数可直接调用`() {
        assertTrue(BitmapBudget.budgetBytes() > 0)
        // 1x1 的小图在任何真实环境都放得下
        assertTrue(BitmapBudget.fitsFullResolution(1, 1))
    }

    // ------------------------------------------------------------------
    // previewCacheCapacity：预览缓存张数由预算算出（不再是写死的 2）
    // ------------------------------------------------------------------

    /**
     * largeHeap 机型（512MB 堆）维持「当前页 + 相邻一张」= 2 张。
     *
     * 单张按 4096×3072 ≈ 48 MB 估，份额 0.35 → 107 MB → 2 张。
     * 这是**行为不变**的那一档：过去写死 2，现在算出来还是 2 —— 区别在于
     * 它现在是「算得出来的 2」，堆变小会自动退，而不是所有机型一律 2。
     */
    @Test
    fun `largeHeap 下预览缓存仍留两张`() {
        assertEquals(2, BitmapBudget.previewCacheCapacity(4096, 512 * mb))
    }

    /**
     * 256MB 堆自动退到 1 张。
     *
     * 过去这里同样是写死的 2 —— 约 96 MB，占该机堆预算（153 MB）的 63%，
     * 却没被任何预算逻辑算过账（见 pending-fixes C2）。
     */
    @Test
    fun `小堆机型预览缓存退到一张`() {
        assertEquals(1, BitmapBudget.previewCacheCapacity(4096, 256 * mb))
    }

    /** 上限夹到 [BitmapBudget.PREVIEW_CACHE_MAX_IMAGES]：堆再大也不多缓存。 */
    @Test
    fun `超大堆也不超过张数上限`() {
        assertEquals(
            BitmapBudget.PREVIEW_CACHE_MAX_IMAGES,
            BitmapBudget.previewCacheCapacity(4096, 8L * 1024 * mb)
        )
    }

    /**
     * 下限是 1：堆再小也不能算出 0。
     *
     * 算出 0 或负数会让 `LruCacheMap` 的 `require(capacity > 0)` 直接抛异常，
     * 而 0 张意味着每次切换图片都要重新解码。
     */
    @Test
    fun `堆极小时至少留一张`() {
        assertEquals(1, BitmapBudget.previewCacheCapacity(4096, 64 * mb))
        assertEquals(1, BitmapBudget.previewCacheCapacity(4096, 1))
    }

    /** 边长非法（探测失败 / 传错）时兜底 1，不抛异常。 */
    @Test
    fun `非法边长兜底一张`() {
        assertEquals(1, BitmapBudget.previewCacheCapacity(0, 512 * mb))
        assertEquals(1, BitmapBudget.previewCacheCapacity(-100, 512 * mb))
    }

    /** 默认参数可直接调用（真实设备上 maxMemory 不会返回 0）。 */
    @Test
    fun `预览缓存容量的默认参数可直接调用`() {
        assertTrue(BitmapBudget.previewCacheCapacity(BitmapUtils.PREVIEW_MAX_EDGE) >= 1)
    }

    // ------------------------------------------------------------------
    // fitLongEdge：导出尺寸档位的尺寸换算
    // ------------------------------------------------------------------

    @Test
    fun `长边不超上限时保持原尺寸`() {
        assertEquals(1000 to 800, BitmapBudget.fitLongEdge(1000, 800, 2048))
        // 正好等于上限也不算「超」
        assertEquals(2048 to 1536, BitmapBudget.fitLongEdge(2048, 1536, 2048))
    }

    /** `maxEdge <= 0` 是「原图」档的语义，必须原样返回。 */
    @Test
    fun `上限为零表示不缩放`() {
        assertEquals(12000 to 9000, BitmapBudget.fitLongEdge(12000, 9000, 0))
        assertEquals(12000 to 9000, BitmapBudget.fitLongEdge(12000, 9000, -1))
    }

    @Test
    fun `横图按长边等比缩小`() {
        // 4032×3024 → 2048×1536（4:3 保持）
        assertEquals(2048 to 1536, BitmapBudget.fitLongEdge(4032, 3024, 2048))
    }

    /** 竖图必须缩**高**，而不是照搬横图的逻辑。 */
    @Test
    fun `竖图按高边等比缩小`() {
        assertEquals(1536 to 2048, BitmapBudget.fitLongEdge(3024, 4032, 2048))
    }

    /**
     * 极端长条图：短边会被缩到 1 像素级，但**绝不能出现 0**。
     *
     * 0 会让 `Bitmap.createScaledBitmap` 直接抛 IllegalArgumentException，
     * 而那张图其实完全能导出（只是很窄）。
     */
    @Test
    fun `极端长条图的短边至少为一`() {
        val (w, h) = BitmapBudget.fitLongEdge(10000, 10, 2048)
        assertEquals(2048, w)
        assertTrue("短边不能缩成 0（会抛异常）", h >= 1)
        assertEquals(2, h)

        val (w2, h2) = BitmapBudget.fitLongEdge(10, 20000, 1080)
        assertEquals(1080, h2)
        assertTrue(w2 >= 1)
    }

    /** 尺寸非法（探测失败）时原样返回，不抛异常。 */
    @Test
    fun `非法尺寸不做换算`() {
        assertEquals(0 to 100, BitmapBudget.fitLongEdge(0, 100, 2048))
        assertEquals(100 to -1, BitmapBudget.fitLongEdge(100, -1, 2048))
    }

    // ------------------------------------------------------------------
    // scaledExportPeakBytes：选档位后的峰值口径
    // ------------------------------------------------------------------

    /**
     * 缩放**后**占优：解码图小、目标图也不大，但 copies 份目标图更多。
     *
     * 1500×1125 = 6.75 MB，目标 1080×810 = 3.5 MB，3 份 = 10.5 MB
     * vs 缩放瞬间 6.75 + 3.5 = 10.25 MB → 取 10.5 MB。
     */
    @Test
    fun `缩放后的份数可能占优`() {
        val peak = BitmapBudget.scaledExportPeakBytes(1500, 1125, 1080, 810, copies = 3)
        assertEquals(BitmapBudget.bytesOf(1080, 810, 3), peak)
    }

    /**
     * 缩放**瞬间**占优：`inSampleSize` 只能取 2 的幂，
     * 解码结果可能比目标大一倍 —— 那一刻两张位图同时存在。
     *
     * 4096×3072 = 50.3 MB + 目标 2048×1536 = 12.6 MB = 62.9 MB
     * vs 2 份目标图 = 25.2 MB → 取 62.9 MB。
     *
     * 这条正是「只按 copies 算会漏掉缩放峰值」的防护。
     */
    @Test
    fun `缩放瞬间的峰值可能占优`() {
        val peak = BitmapBudget.scaledExportPeakBytes(4096, 3072, 2048, 1536, copies = 2)
        val duringScale = BitmapBudget.bytesOf(4096, 3072, 1) +
            BitmapBudget.bytesOf(2048, 1536, 1)
        assertEquals(duringScale, peak)
        assertTrue(
            "必须大于只按 copies 份目标图算的结果",
            peak > BitmapBudget.bytesOf(2048, 1536, 2)
        )
    }

    @Test
    fun `解码尺寸非法时峰值为零`() {
        assertEquals(0L, BitmapBudget.scaledExportPeakBytes(0, 100, 100, 100, copies = 2))
    }

    // ------------------------------------------------------------------
    // 回归：这就是「导出尺寸档位」要解决的核心场景
    // ------------------------------------------------------------------

    /**
     * **同一张一亿像素的图**：原图档必然失败，2048 档必须成功。
     *
     * 这是整个功能的立身之本 —— 若这条测试挂了，说明「档位」白做：
     * 用户选了小尺寸还是导不出来。
     *
     * 12000×9000，256MB 堆（预算 153.6 MB）：
     * - 原图档：3 份 = 1.2 GB → 失败（现状，用户无路可走）
     * - 2048 档：`inSampleSize` 取 4 → 解码 3000×2250（27 MB），
     *   目标 2048×1536（12.6 MB），峰值 = 27 + 12.6 = 39.6 MB → 成功
     */
    @Test
    fun `一亿像素图在原图档失败而2048档成功`() {
        val rawW = 12000
        val rawH = 9000
        val heap = 256 * mb
        val copies = BitmapBudget.exportCopies(withDct = true)

        // 原图档：与升级前的口径完全一致
        assertFalse(
            "原图档本就放不下（这是现状，不是回归）",
            BitmapBudget.fitsFullResolution(rawW, rawH, copies, heap)
        )

        // 2048 档：解码时就已经少解像素
        val sample = BitmapUtils.calculateInSampleSize(rawW, rawH, 2048)
        assertEquals("2 的幂且让长边落在 [2048, 4096)", 4, sample)
        val decodeW = rawW / sample
        val decodeH = rawH / sample
        val (targetW, targetH) = BitmapBudget.fitLongEdge(decodeW, decodeH, 2048)

        val peak = BitmapBudget.scaledExportPeakBytes(decodeW, decodeH, targetW, targetH, copies)
        val budget = BitmapBudget.budgetBytes(heap)
        assertTrue(
            "2048 档必须放得下：峰值 ${BitmapBudget.megabytes(peak)}MB " +
                "应 ≤ 预算 ${BitmapBudget.megabytes(budget)}MB",
            peak <= budget
        )
    }

    /**
     * 档位越小、峰值越小（单调性）。
     *
     * 这条是「选小一档就能导出」这个承诺的数学前提：
     * 若峰值不随档位下降，「换小尺寸」就救不了任何图。
     */
    @Test
    fun `档位越小峰值越小`() {
        val copies = BitmapBudget.exportCopies(withDct = true)
        fun peakFor(maxEdge: Int): Long {
            val sample = BitmapUtils.calculateInSampleSize(12000, 9000, maxEdge)
            val dw = 12000 / sample
            val dh = 9000 / sample
            val (tw, th) = BitmapBudget.fitLongEdge(dw, dh, maxEdge)
            return BitmapBudget.scaledExportPeakBytes(dw, dh, tw, th, copies)
        }

        val p4096 = peakFor(4096)
        val p2048 = peakFor(2048)
        val p1080 = peakFor(1080)

        assertTrue("4096 档（${BitmapBudget.megabytes(p4096)}MB）应大于 2048 档", p4096 > p2048)
        assertTrue("2048 档（${BitmapBudget.megabytes(p2048)}MB）应大于 1080 档", p2048 > p1080)
        assertTrue("1080 档在小堆下也应可行", p1080 <= BitmapBudget.budgetBytes(64 * mb))
    }
}