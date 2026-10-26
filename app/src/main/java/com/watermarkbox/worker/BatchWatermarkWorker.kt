package com.watermarkbox.worker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.watermarkbox.data.model.BatchPositionResolver
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.PositionScope
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.data.repository.BatchFailureItem
import com.watermarkbox.data.repository.BatchRunResult
import com.watermarkbox.data.repository.BatchRunStore
import com.watermarkbox.data.repository.ExportRepository
import com.watermarkbox.invisible.InvisibleWatermarkEngine
import com.watermarkbox.util.BitmapBudget
import com.watermarkbox.util.BitmapLoadOutcome
import com.watermarkbox.util.BitmapUtils
import com.watermarkbox.util.FileNameResolver
import com.watermarkbox.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * 批量水印处理 Worker。
 *
 * 设计要点：
 * - 使用 [CoroutineWorker]，天然支持协程与取消
 * - 每张图片**独立处理**，单张失败不影响后续（累计成功/失败计数）
 * - 通过 setProgress() 上报进度，UI 侧用 WorkInfo 观察
 * - 使用 BitmapFactory inSampleSize 控制内存（复用 BitmapUtils）
 * - 输入/输出通过 Data 传递，Bitmap 不跨进程传递
 *
 * 输入 Data：
 * - uris            (Array<String>) 源图 Uri 列表
 * - indices         (IntArray) 每张图对应的**序号**（可选；缺省按 1..n）
 * - watermarkType   (String) TEXT / IMAGE
 * - textStyleJson   (String) 文字水印配置 JSON
 * - imageStyleJson  (String) 图片水印配置 JSON
 * - logoUri         (String?) logo Uri
 * - exportSettingsJson (String) 导出设置 JSON
 *
 * 输出 Data：
 * - successCount, failCount 与四个失败原因计数
 *
 * 逐张的成功/失败**清单不放在输出 Data 里**（10KB 上限），
 * 而是由 [BatchRunStore] 按 `id`（= WorkRequest id）落盘，UI 侧再读。
 */
class BatchWatermarkWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val renderer = WatermarkRenderer()
    private val exportRepository = ExportRepository(context)
    private val runStore by lazy { BatchRunStore(context) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uris = inputData.getStringArray(KEY_URIS)?.toList().orEmpty()
        if (uris.isEmpty()) return@withContext Result.failure()

        // 序号入参：重跑失败项时由调用方带过来，保证「重跑的结果还是原来那个号」。
        // 缺省（老调用 / 全新一批）按位置 1..n。
        val indices = inputData.getIntArray(KEY_INDICES)?.toList()

        val watermarkType = runCatching {
            WatermarkType.valueOf(inputData.getString(KEY_TYPE) ?: "TEXT")
        }.getOrDefault(WatermarkType.TEXT)

        val textStyle = JsonCodec.decodeTextStyle(inputData.getString(KEY_TEXT_STYLE))
        val imageStyle = JsonCodec.decodeImageStyle(inputData.getString(KEY_IMAGE_STYLE))

        // 每张图的位置覆盖（批量页「每张独立」作用域）。
        // 统一模式下这两张表是空的 —— 于是下面 forEachIndexed 里查不到覆盖，
        // 自然退回共享配置的位置，不需要额外分支。
        val positionScope = JsonCodec.decodePositionScope(
            inputData.getString(KEY_POSITION_SCOPE)
        )
        val textPositions = if (positionScope == PositionScope.PER_IMAGE) {
            JsonCodec.decodePositions(inputData.getString(KEY_TEXT_POSITIONS))
        } else {
            emptyMap()
        }
        val imagePositions = if (positionScope == PositionScope.PER_IMAGE) {
            JsonCodec.decodePositions(inputData.getString(KEY_IMAGE_POSITIONS))
        } else {
            emptyMap()
        }

        // 位置作用域的可观测性：出问题时能一眼看出是「作用域没传进来」
        // 还是「传进来了但 key 对不上」—— 这两种在结果页上长得一模一样。
        // 只打一行、不含图片数据，正常日志量可忽略。
        Log.i(
            TAG,
            "batch export: scope=$positionScope textPositions=${textPositions.size} " +
                "imagePositions=${imagePositions.size} uris=${uris.size} " +
                "keys=${textPositions.keys.map { it.takeLast(16) }}"
        )

        val exportSettings = JsonCodec.decodeExportSettings(
            inputData.getString(KEY_EXPORT_SETTINGS)
        )
        val logoUri = inputData.getString(KEY_LOGO_URI)?.let { it.toUri() }
        val logoBitmap = logoUri?.let { loadLogo(it) }

        // 隐藏水印：内容在入队前已解析好（避免 Worker 内再算一次设备/时间，
        // 那会导致同一批次里每张图的嵌入内容不一致，无法互相印证）
        val invisibleContent = inputData.getString(KEY_INVISIBLE_CONTENT)
        val invisibleStrength = inputData.getInt(KEY_INVISIBLE_STRENGTH, 0)
        // 两条通道各自独立。默认值刻意与“升级前”的行为一致：
        // EXIF 从前总是写；DCT 从前是 strength > 0 才嵌。
        val invisibleExif = inputData.getBoolean(KEY_INVISIBLE_EXIF, true)
        val invisibleDct = inputData.getBoolean(KEY_INVISIBLE_DCT, invisibleStrength > 0)

        var success = 0
        // 成功清单：结果页的「分享」要用；失败清单：结果页的「重跑失败项」要用。
        // 两者都落盘（见类注释），Data 里只留计数。
        val savedUris = mutableListOf<String>()
        val failed = mutableListOf<BatchFailureItem>()
        // 失败按原因归类：结果页要说清「为什么失败」，不只是一个数字
        val reasons = mutableMapOf<BatchFailureReason, Int>()

        uris.forEachIndexed { position, uriString ->
            // 支持取消：每张开始前检查
            if (isStopped) return@withContext Result.failure()

            // 序号：调用方给了就沿用（重跑失败项时必须与前一批一致），否则按位置 1..n
            val seq = indices?.getOrNull(position) ?: (position + 1)


            val outcome = processOne(
                uriString = uriString,
                index = seq,
                type = watermarkType,
                // 「每张独立」作用域下，这张图若有自己的位置就套上；
                // 没有（或处于统一模式）则沿用共享配置 —— 见 BatchPositionResolver。
                textStyle = BatchPositionResolver.applyText(
                    textStyle,
                    textPositions[uriString]
                ),
                imageStyle = BatchPositionResolver.applyImage(
                    imageStyle,
                    imagePositions[uriString]
                ),
                logoBitmap = logoBitmap,
                exportSettings = exportSettings,
                invisibleContent = invisibleContent,
                invisibleStrength = invisibleStrength,
                invisibleExif = invisibleExif,
                invisibleDct = invisibleDct
            )
            if (outcome.failure == null) {
                success++
                outcome.savedUri?.let { savedUris.add(it) }
            } else {
                failed.add(
                    BatchFailureItem(
                        uri = uriString,
                        index = seq,
                        reason = outcome.failure.name
                    )
                )
                reasons[outcome.failure] = (reasons[outcome.failure] ?: 0) + 1
            }

            setProgress(
                workDataOf(
                    KEY_PROGRESS_DONE to position + 1,
                    KEY_PROGRESS_TOTAL to uris.size
                )
            )
        }

        logoBitmap?.takeIf { !it.isRecycled }?.recycle()

        // 清单落盘（runId 用 WorkRequest 的 id，UI 侧同一个 id 来取）
        runStore.write(
            runId = id.toString(),
            result = BatchRunResult(success = savedUris, failed = failed)
        )
        runStore.prune()

        Result.success(
            workDataOf(
                KEY_SUCCESS_COUNT to success,
                KEY_FAIL_COUNT to failed.size,
                // 失败原因分类（结果页展示用）
                KEY_FAIL_TOO_LARGE to (reasons[BatchFailureReason.SOURCE_TOO_LARGE] ?: 0),
                KEY_FAIL_UNREADABLE to (reasons[BatchFailureReason.SOURCE_UNREADABLE] ?: 0),
                KEY_FAIL_SAVE to (reasons[BatchFailureReason.SAVE_FAILED] ?: 0),
                KEY_FAIL_UNKNOWN to (reasons[BatchFailureReason.UNKNOWN] ?: 0)
            )
        )
    }

    /**
     * 处理单张图片：加载全分辨率 → 渲染 → 保存。
     *
     * @return `null` 表示成功；非 null 是**失败原因**（见 [BatchFailureReason]）。
     *   把原因带出来而不是只报 true/false，结果页才能告诉用户下一步该做什么。
     */
    private suspend fun processOne(
        uriString: String,
        index: Int,
        type: WatermarkType,
        textStyle: com.watermarkbox.data.model.TextWatermarkStyle,
        imageStyle: com.watermarkbox.data.model.ImageWatermarkStyle,
        logoBitmap: Bitmap?,
        exportSettings: ExportSettings,
        invisibleContent: String?,
        invisibleStrength: Int,
        invisibleExif: Boolean,
        invisibleDct: Boolean
    ): ProcessOutcome {
        var source: Bitmap? = null
        var rendered: Bitmap? = null
        var stamped: Bitmap? = null
        return try {
            val uri = uriString.toUri()
            // 全分辨率加载 + 内存预检：放不下就把这一张记为失败，
            // 而不是让后续的 source.copy() 把整个批量任务带走
            val outcome = BitmapUtils.loadFullResolution(
                context = applicationContext,
                uri = uri,
                // 按**本次实际**需要几份位图来算预算。
                //
                // 链路峰值：原图 + 渲染结果 + （开了 DCT 才有）频域副本。
                // 早期固定传 3，导致「只写 EXIF、不开 DCT」的用户
                // 也被按 3 份扣预算 —— 白白砍掉 1/3，大图直接判"太大"。
                copies = BitmapBudget.exportCopies(withDct = invisibleDct)
            )
            when (outcome) {
                is BitmapLoadOutcome.Ok -> source = outcome.bitmap
                // 「图太大」与「读不出来」要分开：用户该做的事完全不同
                is BitmapLoadOutcome.TooLarge ->
                    return ProcessOutcome(failure = BatchFailureReason.SOURCE_TOO_LARGE)
                is BitmapLoadOutcome.Failed ->
                    return ProcessOutcome(failure = BatchFailureReason.SOURCE_UNREADABLE)
            }

            // 取源文件名用于 {filename} 与命名
            val displayName = queryDisplayName(uri)

            rendered = when (type) {
                WatermarkType.TEXT -> renderer.render(
                    source = source,
                    config = textStyle,
                    sourceFileName = displayName
                )
                WatermarkType.IMAGE -> {
                    if (logoBitmap == null || logoBitmap.isRecycled) {
                        return ProcessOutcome(failure = BatchFailureReason.UNKNOWN)
                    }
                    renderer.renderImage(
                        source = source,
                        logo = logoBitmap,
                        config = imageStyle
                    )
                }
            }

            // 隐藏水印：两条通道**共用同一个已校验的内容**（code-review-2026-10 的 M5）。
            //
            // 原来两处守卫条件**不对称**：
            //   · DCT 分支判 `!invisibleContent.isNullOrBlank() && strength > 0`
            //   · EXIF 分支只判 `invisibleExif`（开关）
            // 于是 `content == null/blank 但 exif 开着` 时，saveBitmap 会收到 null。
            // 虽然它内部应当能兜住，但两处守卫不一致本身就是易错点 ——
            // 统一成下面这个已校验的值，两条通道用同一个。
            val content = invisibleContent?.takeIf { it.isNotBlank() }

            // 隐藏水印频域嵌入（仅当该通道开启）
            var toWrite = rendered
            if (invisibleDct && content != null && invisibleStrength > 0) {
                stamped = InvisibleWatermarkEngine(invisibleStrength.toFloat())
                    .embed(rendered, content)
                if (stamped != null) toWrite = stamped
            }

            val fileName = FileNameResolver.build(
                template = exportSettings.fileNameTemplate,
                sourceName = displayName,
                format = exportSettings.format,
                index = index
            )

            // ⚠️ 注意类型：saveBitmap 返回 ExportResult?，
            // 这里要的是「是否成功」这个判断本身，**不能**把 `!= null`
            // 的结果再赋回给同名变量 —— 那样 saved 变成 Boolean，
            // 下面 `saved != null` 恒为 true，失败会被静默吞掉。
            val savedResult = exportRepository.saveBitmap(
                bitmap = toWrite,
                displayName = fileName,
                format = exportSettings.format,
                jpegQuality = exportSettings.jpegQuality,
                albumPath = exportSettings.customOutputDir,
                sourceUri = uri,
                preserveExif = exportSettings.preserveExif,
                // EXIF 通道独立开关：关掉时传 null，等价于「不写元数据水印」。
                // ⚠️ 用上面那个**已校验**的 `content`（与 DCT 通道同源），
                // 而不是原始字符串 —— 见函数开头关于 M5 的说明。
                invisibleContent = content.takeIf { invisibleExif }
            )
            if (savedResult != null) {
                // 成功：把落盘 uri 带出去（结果页「分享」要用）
                ProcessOutcome(savedUri = savedResult.uri.toString())
            } else {
                // 日志：批量导出失败的原因需要可诊断。
                // 之前这里静默返回 SAVE_FAILED，结果页只显示「保存失败 N 张」，
                // 排查时根本不知道是哪一步出的问题。
                Log.w(
                    TAG,
                    "saveBitmap 返回 null: file=$fileName uri=$uri " +
                        "bitmap=${toWrite.width}x${toWrite.height} " +
                        "recycled=${toWrite.isRecycled} " +
                        "format=${exportSettings.format} " +
                        "album=${exportSettings.customOutputDir}"
                )
                ProcessOutcome(failure = BatchFailureReason.SAVE_FAILED)
            }
        } catch (oom: OutOfMemoryError) {
            // OOM 是 `Error` 而不是 `Exception`，但下面的 `catch (t: Throwable)` 本来也会
            // 把它吞掉 —— 问题不在「没兜住」，而在**兜成了 UNKNOWN**：
            // 用户看到「失败 N 张 / 原因未知」，其实答案是「图太大，内存不够」。
            // 单独前置一个分支，给出用户能照着做的原因（换小图 / 分批）。
            Log.e(TAG, "processOne 内存不足: uri=$uriString", oom)
            ProcessOutcome(failure = BatchFailureReason.SOURCE_TOO_LARGE)
        } catch (t: Throwable) {
            // 同样：把异常打出来，否则「失败 N 张 / 原因未知」无从下手
            Log.e(TAG, "processOne 抛异常: uri=$uriString", t)
            ProcessOutcome(failure = BatchFailureReason.UNKNOWN)
        } finally {
            stamped?.takeIf { it != rendered && !it.isRecycled }?.recycle()
            rendered?.takeIf { it != source && !it.isRecycled }?.recycle()
            source?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /**
     * 单张处理结果：成功则带落盘 uri，失败则带原因。
     *
     * 原来 processOne 只返回 `BatchFailureReason?`，于是「成功保存到了哪个 uri」
     * 这个信息在跳出函数时就丢了 —— 结果页因此无法提供分享。
     */
    private data class ProcessOutcome(
        val savedUri: String? = null,
        val failure: BatchFailureReason? = null
    )

    private fun loadLogo(uri: Uri): Bitmap? =
        BitmapUtils.loadBitmap(applicationContext, uri, maxEdge = 512)

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    companion object {
        const val KEY_URIS = "uris"

        /**
         * 每张图对应的**序号**（`IntArray`，与 [KEY_URIS] 等长）。
         *
         * 缺省时按位置取 `1..n`；只有「只重跑失败项」才需要显式传，
         * 目的是让重跑出来的文件名沿用上一批的号，不与已成功的那批撞名。
         */
        const val KEY_INDICES = "indices"
        const val KEY_TYPE = "watermarkType"
        const val KEY_TEXT_STYLE = "textStyleJson"
        const val KEY_IMAGE_STYLE = "imageStyleJson"
        const val KEY_LOGO_URI = "logoUri"
        const val KEY_EXPORT_SETTINGS = "exportSettingsJson"

        /**
         * 位置作用域（`UNIFIED` / `PER_IMAGE`）。
         *
         * 缺省按 `UNIFIED` 处理：老任务（升级前入队的）没有这个键，
         * 而当时的行为就是"位置存在共享配置里、所有图一致"。
         */
        const val KEY_POSITION_SCOPE = "positionScope"

        /** 「每张独立」作用域下的文字水印位置表（JSON，见 JsonCodec.encodePositions）。 */
        const val KEY_TEXT_POSITIONS = "textPositionsJson"

        /** 「每张独立」作用域下的图片水印位置表（JSON）。 */
        const val KEY_IMAGE_POSITIONS = "imagePositionsJson"
        const val KEY_INVISIBLE_CONTENT = "invisibleContent"
        const val KEY_INVISIBLE_STRENGTH = "invisibleStrength"

        /**
         * 是否写 EXIF 通道。
         *
         * 缺省 `true`：老任务里没有这个键，而当时的行为就是「内容非空必写 EXIF」，
         * 用 true 兜底才能保持升级前后的行为一致。
         */
        const val KEY_INVISIBLE_EXIF = "invisibleExif"

        /**
         * 是否写 DCT 通道。
         *
         * 缺省值由 [KEY_INVISIBLE_STRENGTH] 推断（老任务的约定是 strength > 0 才嵌），
         * 同样是为了兼容升级前入队的任务。
         */
        const val KEY_INVISIBLE_DCT = "invisibleDct"

        const val KEY_PROGRESS_DONE = "progressDone"
        const val KEY_PROGRESS_TOTAL = "progressTotal"

        const val KEY_SUCCESS_COUNT = "successCount"
        const val KEY_FAIL_COUNT = "failCount"
        // 逐张清单不放 Data：100 张图的 uri 清单就接近 Data 的 10KB 上限，
        // 超限会让整个任务被判失败。清单一律走 BatchRunStore 落盘。
        // 失败原因分类计数（与 BatchFailureReason 一一对应）
        const val KEY_FAIL_TOO_LARGE = "failTooLarge"
        const val KEY_FAIL_UNREADABLE = "failUnreadable"
        const val KEY_FAIL_SAVE = "failSave"
        const val KEY_FAIL_UNKNOWN = "failUnknown"

        const val WORK_NAME = "batch_watermark_work"

        /** 日志 TAG。批量失败必须可诊断 —— 见 [BatchWatermarkWorker.processOne]。 */
        const val TAG = "BatchWorker"
    }
}