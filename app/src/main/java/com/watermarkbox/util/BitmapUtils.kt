package com.watermarkbox.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import androidx.core.graphics.scale

/**
 * 全分辨率加载的结果。
 *
 * ## 为什么要区分「放不下」与「加载失败」
 *
 * 两者给用户的动作完全不同：
 * - **放不下** → 这张图太大，本机内存办不到（换设备，或先把图缩小）
 * - **失败** → 文件损坏 / 读不到（换文件）
 *
 * 早先两者都被压成 `null`，UI 只能笼统地说「加载失败」，
 * 用户无从判断该换什么。预算口径见 [BitmapBudget]。
 */
sealed interface BitmapLoadOutcome {

    /** 加载成功。 */
    data class Ok(val bitmap: Bitmap) : BitmapLoadOutcome

    /** 按全分辨率的峰值超出内存预算 —— 调用方应**告知**用户，而不是重试。 */
    data class TooLarge(
        val width: Int,
        val height: Int,
        /** 按估算份数算出的峰值字节数。 */
        val requiredBytes: Long,
        /** 允许的预算字节数。 */
        val budgetBytes: Long
    ) : BitmapLoadOutcome

    /** 文件读不到 / 解码失败 / 其它异常。 */
    data class Failed(val cause: Throwable?) : BitmapLoadOutcome
}

/**
 * Bitmap 加载/降采样工具。
 *
 * 关键点：
 * 1. 使用 inJustDecodeBounds 先探测原图尺寸，再按目标最大边计算 inSampleSize，
 *    避免一次性把大图解码进内存导致 OOM。
 * 2. 读取 EXIF 方向并做旋正，保证水印方向与用户看到的一致。
 */
object BitmapUtils {

/**
     * 预览用缩略图的最大边长（px），平衡清晰度与流畅性。
     *
     * ## 为什么是 720 而不是 1024（实测调优）
     *
     * 预览的渲染路径每帧要做一次「底图填充」：把源图写进输出的复用位图。
     * 这个操作的成本**与像素数成正比**（软件 Canvas 下的逐像素拷贝）。
     *
     * 实测（本机内存吃紧、Swap 使用率高）：
     *
     * ```
     * 768×1024  = 3.1 MB  →  outputFor 平均 33.8 ms   ← 慢到跟不上手指
     * ```
     *
     * 降到 720 后：
     *
     * ```
     * 540×720   = 1.5 MB  →  预期 ~16 ms            ← 进入 60fps 可接受范围
     * ```
     *
     * ## 720 够不够清晰
     *
     * 预览区由 `ShrinkablePreviewArea` 约束，在 1080×2400 的手机上
     * 实际显示宽度约 1080px、高度约 1390px（panelFraction=0.42）。
     * 而图片是 `ContentScale.Fit` 缩放的 —— 720 边对绝大多数横图/竖图
     * 都能撑满预览区而不明显发虚。
     *
     * ## 为什么不更低（如 540）
     *
     * 预览里要看清**水印文字本身**（用户就是在调字号/角度）。
     * 540 边上小字号水印会糊到看不清是否漏字。
     * 720 是「看得清水印」与「渲染够快」的折中点。
     *
     * ## 与导出的关系
     *
     * ⚠️ **只影响预览**。导出走 `loadFullResolutionBitmap`（原图分辨率），
     * 这里是完全独立的路径，降这个值不会让导出变糊。
     */
    const val PREVIEW_MAX_EDGE = 720

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

            val oriented = applyExifOrientation(decoded, orientation)
            // ---- 第四步：精确缩放到目标尺寸（兜底）----
            scaleDownToMaxEdge(oriented, maxEdge)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 把位图**精确**缩放到最长边不超过 [maxEdge]。
     *
     * ## 为什么 `inSampleSize` 不够，还需要这一步
     *
     * `inSampleSize` 只能是 2 的幂（1/2/4/8…），所以降采样后
     * 最长边落在 `[target, 2*target)` —— **可能显著超过目标**：
     *
     * ```
     * 原图 1304×600，target=1024
     *   calculateInSampleSize → 1（因为 maxOf(652, 300) < 1024）
     *   → 输出 1304×600，比目标大 27%
     * ```
     *
     * 在内存宽裕时这只是"稍微多花一点"；但在**内存吃紧的设备上**
     * （实测本机 15GB 已用满、Swap 用了 12GB），
     * 这多出来的部分会让位图更容易被换出到 Swap，
     * 之后每次访问都要从磁盘读回 —— 实测 `outputFor` 要 **21ms**，
     * 整个渲染 31ms，滑块根本追不上手指。
     *
     * 所以补一次精确缩放：用 `createScaledBitmap` 把最长边压到 [maxEdge]。
     * 代价是一次额外的位图分配 + 缩放（只在「降不够」时发生），
     * 换来的是**稳定的内存占用**。
     *
     * @return 缩放后的位图；已在目标内则原样返回（不复制）
     */
    private fun scaleDownToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap

        val ratio = maxEdge.toFloat() / longest
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        // 缩放产生新实例时，旧的要回收；若返回同一实例（理论不会），别误回收
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    /**
     * 只**探测尺寸**，不解码像素。
     *
     * ## 用途
     *
     * 预览需要知道「原图多大」才能把水印按正确比例渲染
     * （见 `WatermarkPreview.sourceWidth` 的说明）。
     * 但为了拿尺寸去解码整张原图显然荒谬 —— 用 `inJustDecodeBounds`
     * 只读文件头，开销可以忽略（几 KB 的读取）。
     *
     * @return (width, height)；探测失败返回 null
     */
    fun queryImageSize(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val w = options.outWidth
            val h = options.outHeight
            if (w > 0 && h > 0) w to h else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * **全分辨率**加载（导出 / 批量 / 频域检测用），带内存预检。
     *
     * ## 与 [loadBitmap] 的区别
     *
     * [loadBitmap] 用 `null` 表达一切失败 —— 对预览够用了。
     * 但全分辨率路径必须先回答一个问题：**这张图在本机内存里跑得完吗？**
     * 因为真正的崩溃点在后面：渲染阶段的 `source.copy()` 没有任何兜底。
     *
     * 所以这里**先探尺寸 → 再算预算 → 最后才解码**，
     * 放不下就返回 [BitmapLoadOutcome.TooLarge]，一步都不多走。
     *
     * @param copies 峰值同时存在几份同位图（见 [BitmapBudget] 的说明）
     * @param maxHeapBytes 进程堆上限；测试可注入
     */
    fun loadFullResolution(
        context: Context,
        uri: Uri,
        copies: Int = BitmapBudget.EXPORT_COPIES,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): BitmapLoadOutcome {
        // ---- 第一步：只读头部拿尺寸（几乎不占内存）----
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (t: Throwable) {
            return BitmapLoadOutcome.Failed(t)
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return BitmapLoadOutcome.Failed(null)

        // ---- 第二步：动手前先算 ----
        val required = BitmapBudget.bytesOf(width, height, copies)
        val budget = BitmapBudget.budgetBytes(maxHeapBytes)
        if (required > budget) {
            return BitmapLoadOutcome.TooLarge(width, height, required, budget)
        }

        // ---- 第三步：解码（已预检，OOM 概率很低；仍单独兜一层）----
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return BitmapLoadOutcome.Failed(null)

            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                readExifOrientation(input)
            } ?: ExifInterface.ORIENTATION_NORMAL

            BitmapLoadOutcome.Ok(applyExifOrientation(decoded, orientation))
        } catch (oom: OutOfMemoryError) {
            // 估算漏了（比如旋正副本叠加、别处同时占着内存）。
            // 当作「放不下」上报 —— 总比让它在渲染阶段把进程带走好。
            BitmapLoadOutcome.TooLarge(width, height, required, budget)
        } catch (t: Throwable) {
            BitmapLoadOutcome.Failed(t)
        }
    }

    /**
     * 计算 inSampleSize：始终取 2 的幂。
     *
     * ## 判定条件用 `||` 而不是 `&&`（这是一个真实修过的 bug）
     *
     * 降采样的目的是「让**最长边**落到目标附近」。
     * 早期实现写的是：
     *
     * ```kotlin
     * while (w / 2 >= target && h / 2 >= target)   // ❌ 错
     * ```
     *
     * 用 `&&` 要求**两条边都还能折半**才降，于是**非正方形图降不够**：
     *
     * ```
     * 2048×1536, target=1024
     *   第1轮检查：2048/2=1024 >= 1024 ✓ ，但 1536/2=768 >= 1024 ✗
     *   → 循环一次都不执行 → sampleSize=1 → 完全没降采样
     *   （实测批量页就是这样拿到 2048×1536 的源图，每帧 12.6 MB，
     *     渲染要 600ms 才出一帧，表现为「预览不实时」）
     *
     * 4000×3000, target=1024
     *   第1轮：2000>=1024 ✓ 且 1500>=1024 ✓ → ss=2
     *   第2轮：1000>=1024 ✗ → 停
     *   → 输出 2000×1500，仍是目标的近 2 倍
     * ```
     *
     * 改成 `||`：只要**任一边**还大于目标，就继续折半。
     * 结果是降采样后最长边落在 `[target, 2*target)` —— 符合预期。
     *
     * ## 为什么坚持 2 的幂
     *
     * `inSampleSize` 取 2 的幂时，解码器可以用整数移位完成降采样，
     * 质量与速度都优于非 2 幂（后者部分解码器会退化成先解全图再缩放）。
     *
     * @return inSampleSize，保证 >= 1
     */
    fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        var sampleSize = 1
        var w = rawWidth
        var h = rawHeight
        val target = maxEdge

        // 只要**最长边**折半后仍不小于目标，就继续降
        while (maxOf(w / 2, h / 2) >= target) {
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
        return source.scale(w, h)
    }
}