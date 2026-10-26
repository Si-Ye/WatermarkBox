package com.watermarkbox.data.repository

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次批量导出的**逐张结果清单**（成功 / 失败各一份）。
 *
 * @param success 成功保存后的 uri
 * @param failed  失败项的 uri + **它在这次导出里的序号** + 失败原因
 */
@Serializable
data class BatchRunResult(
    val success: List<String> = emptyList(),
    val failed: List<BatchFailureItem> = emptyList()
) {
    val isEmpty: Boolean get() = success.isEmpty() && failed.isEmpty()
}

/**
 * 失败项。
 *
 * `index` 是**导出时用的那个序号**（不是列表下标）——
 * 「只重跑失败项」时必须沿用它，否则重跑出来的文件名会和第一批重号。
 */
@Serializable
data class BatchFailureItem(
    val uri: String,
    val index: Int,
    val reason: String
)

/**
 * 批量导出结果的落盘仓库。
 *
 * ## 为什么不用 WorkManager 的 outputData
 *
 * `Data` 的上限是 **10KB**。100 张图的 uri 清单约 8KB，
 * 再放失败清单和原因必然超限 —— 超限的表现是 `IllegalStateException`，
 * 整个任务被判失败，用户看到的却是「导出完成但结果丢了」。
 *
 * 所以：**Data 里只留计数，清单落盘**，UI 按 `runId`（= WorkRequest id）取。
 *
 * ## 为什么放 filesDir 而不是 cacheDir
 *
 * 结果页要在进程被杀 / 屏幕旋转后仍能读到（分享按钮依赖成功清单）。
 * cacheDir 随时可能被系统清理。
 */
class BatchRunStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 写入一次结果；同名 runId 覆盖。 */
    fun write(runId: String, result: BatchRunResult) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val target = fileFor(runId)
            // 先写临时文件再改名：写到一半被中断时不会留下半截 JSON
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(BatchRunResult.serializer(), result))
            if (target.exists() && !target.delete()) {
                // 删不掉就退化成直接覆盖，别把结果丢掉
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            } else {
                tmp.renameTo(target)
            }
        }
    }

    /** 读取一次结果；不存在或损坏都返回 null（不抛）。 */
    fun read(runId: String): BatchRunResult? = runCatching {
        val file = fileFor(runId)
        if (!file.exists()) return null
        json.decodeFromString(BatchRunResult.serializer(), file.readText())
    }.getOrNull()

    /**
     * 只保留最近 [keep] 份。
     *
     * 不做后台定时清理：清单很小（每次一批），启动时顺手清一次已经足够，
     * 再多就是过度设计。
     */
    fun prune(keep: Int = KEEP_RUNS) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(keep).forEach { it.delete() }
        }
    }

    /** 清掉全部结果（调试与「清空记录」时用）。 */
    fun clear() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun fileFor(runId: String): File =
        File(dir, runId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + SUFFIX)

    companion object {
        private const val DIR_NAME = "batch-runs"
        private const val SUFFIX = ".json"
        private const val KEEP_RUNS = 3
    }
}
