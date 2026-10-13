package com.watermarkbox.worker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.WatermarkType
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
 * - watermarkType   (String) TEXT / IMAGE
 * - textStyleJson   (String) 文字水印配置 JSON
 * - imageStyleJson  (String) 图片水印配置 JSON
 * - logoUri         (String?) logo Uri
 * - exportSettingsJson (String) 导出设置 JSON
 *
 * 输出 Data：
 * - successCount, failCount, failedUris
 */
class BatchWatermarkWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val renderer = WatermarkRenderer()
    private val exportRepository = ExportRepository(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uris = inputData.getStringArray(KEY_URIS)?.toList().orEmpty()
        if (uris.isEmpty()) return@withContext Result.failure()

        val watermarkType = runCatching {
            WatermarkType.valueOf(inputData.getString(KEY_TYPE) ?: "TEXT")
        }.getOrDefault(WatermarkType.TEXT)

        val textStyle = JsonCodec.decodeTextStyle(inputData.getString(KEY_TEXT_STYLE))
        val imageStyle = JsonCodec.decodeImageStyle(inputData.getString(KEY_IMAGE_STYLE))
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
        val failed = mutableListOf<String>()

        uris.forEachIndexed { index, uriString ->
            // 支持取消：每张开始前检查
            if (isStopped) return@withContext Result.failure()

            val ok = processOne(
                uriString = uriString,
                index = index + 1,
                type = watermarkType,
                textStyle = textStyle,
                imageStyle = imageStyle,
                logoBitmap = logoBitmap,
                exportSettings = exportSettings,
                invisibleContent = invisibleContent,
                invisibleStrength = invisibleStrength,
                invisibleExif = invisibleExif,
                invisibleDct = invisibleDct
            )
            if (ok) success++ else failed.add(uriString)

            setProgress(
                workDataOf(
                    KEY_PROGRESS_DONE to index + 1,
                    KEY_PROGRESS_TOTAL to uris.size
                )
            )
        }

        logoBitmap?.takeIf { !it.isRecycled }?.recycle()

        Result.success(
            workDataOf(
                KEY_SUCCESS_COUNT to success,
                KEY_FAIL_COUNT to failed.size,
                KEY_FAILED_URIS to failed.toTypedArray()
            )
        )
    }

    /** 处理单张图片：加载全分辨率 → 渲染 → 保存。返回是否成功。 */
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
    ): Boolean {
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
                copies = BitmapBudget.EXPORT_COPIES
            )
            source = (outcome as? BitmapLoadOutcome.Ok)?.bitmap ?: return false

            // 取源文件名用于 {filename} 与命名
            val displayName = queryDisplayName(uri)

            rendered = when (type) {
                WatermarkType.TEXT -> renderer.render(
                    source = source,
                    config = textStyle,
                    sourceFileName = displayName
                )
                WatermarkType.IMAGE -> {
                    if (logoBitmap == null || logoBitmap.isRecycled) return false
                    renderer.renderImage(
                        source = source,
                        logo = logoBitmap,
                        config = imageStyle
                    )
                }
            }

            // 隐藏水印频域嵌入（仅当该通道开启）
            var toWrite = rendered
            if (invisibleDct && !invisibleContent.isNullOrBlank() && invisibleStrength > 0) {
                stamped = InvisibleWatermarkEngine(invisibleStrength.toFloat())
                    .embed(rendered, invisibleContent)
                if (stamped != null) toWrite = stamped
            }

            val fileName = FileNameResolver.build(
                template = exportSettings.fileNameTemplate,
                sourceName = displayName,
                format = exportSettings.format,
                index = index
            )

            exportRepository.saveBitmap(
                bitmap = toWrite,
                displayName = fileName,
                format = exportSettings.format,
                jpegQuality = exportSettings.jpegQuality,
                albumPath = exportSettings.customOutputDir,
                sourceUri = uri,
                preserveExif = exportSettings.preserveExif,
                // EXIF 通道独立开关：关掉时传 null，等价于「不写元数据水印」
                invisibleContent = invisibleContent.takeIf { invisibleExif }
            ) != null
        } catch (t: Throwable) {
            false
        } finally {
            stamped?.takeIf { it != rendered && !it.isRecycled }?.recycle()
            rendered?.takeIf { it != source && !it.isRecycled }?.recycle()
            source?.takeIf { !it.isRecycled }?.recycle()
        }
    }

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
        const val KEY_TYPE = "watermarkType"
        const val KEY_TEXT_STYLE = "textStyleJson"
        const val KEY_IMAGE_STYLE = "imageStyleJson"
        const val KEY_LOGO_URI = "logoUri"
        const val KEY_EXPORT_SETTINGS = "exportSettingsJson"
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
        const val KEY_FAILED_URIS = "failedUris"

        const val WORK_NAME = "batch_watermark_work"
    }
}