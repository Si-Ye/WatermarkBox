package com.watermarkbox.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内「更新说明」的数据源。
 *
 * ## 为什么把说明搬到远端
 *
 * 更新说明原本**硬编码**在 APK 里（`ReleaseHistory` + 一堆字符串资源）。
 * 这带来一个流程隐患：**发版时容易忘记同步它**。
 * 本仓库真实发生过 —— 2.1.0 发布时漏了，用户在新版里看不到本版说明了什么。
 *
 * 搬到远端后：
 *
 * - 说明的更新**不需要改代码、重新发版**（发个 JSON 就行）
 * - 发版流程里只需动一个文件（`docs/changelog.json`）
 * - 配合 CI 的一致性校验脚本，漏写会**直接构建失败**
 *
 * ## 三级数据源（优先级从高到低）
 *
 * | 来源 | 何时用 | 特点 |
 * |---|---|---|
 * | 网络 | 能联网 | 最新 |
 * | 本地缓存 | 网络失败但有缓存 | 滞后但可用 |
 * | 内置兜底 | 都没有 | 随 APK 发布，最旧但一定在 |
 *
 * 兜底那份由 UI 层提供（`ReleaseHistory`），本类只负责前两级。
 *
 * ## 缓存为什么存**原文**而不是解析结果
 *
 * 解析结果里的文案是**按当时语言选好的**。若缓存解析结果，
 * 用户切换语言后会看到"半中半英"或整段旧语言的内容。
 *
 * 存原文则每次按当前语言重新解析 —— 切换语言立即正确。
 *
 * ## 为什么用文件而不是 DataStore
 *
 * 缓存内容是几十 KB 的 JSON 文本。DataStore 面向小而频繁的配置项，
 * 放这个量级的东西并不合适；一个普通文件更直接。
 */
class ChangelogRepository(private val context: Context) {

    companion object {
        /**
         * 远端文档地址。
         *
         * 用 `raw.githubusercontent.com` 而不是 GitHub API：
         * - 无需认证、速率限制宽松（API 未认证只有 60 次/小时）
         * - 响应就是纯文本，省一层 JSON 包装
         *
         * ⚠️ 代价：raw 走 CDN，**推送后最长约 5 分钟**才会对外可见。
         * 发布流程文档里因此要求在发版后做一次"生效确认"。
         */
        private const val RAW_URL =
            "https://raw.githubusercontent.com/Si-Ye/WatermarkBox/main/docs/changelog.json"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /** 响应体大小上限：正常文档约几十 KB，超过这个数说明拿到了别的东西。 */
        private const val MAX_BYTES = 512 * 1024

        private const val CACHE_FILE_NAME = "changelog_cache.json"
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE_NAME)

    /**
     * 从网络拉取最新**原文**并写入缓存。
     *
     * ## 为什么返回原文而不是解析结果
     *
     * 解析结果里的文案是**按调用时语言选好的**。若把解析结果当作
     * 长驻状态，用户切换语言（应用支持中英切换）后就会看到旧语言的正文。
     *
     * 返回原文则由上层在**每次需要时按当前语言重新解析** ——
     * 切换语言立即正确，不需要重新联网。`ChangelogJson.parse` 是纯函数，
     * 重复解析的成本可以忽略。
     *
     * @return 文档原文；网络失败或内容不可用时返回 `null`
     *         （调用方据此保留已有内容）
     */
    suspend fun refreshRaw(): String? = withContext(Dispatchers.IO) {
        runCatching { fetchRaw() }.getOrNull()
    }

    /**
     * 读本地缓存的**原文**。
     *
     * @return 缓存原文；无缓存或读失败返回 `null`
     */
    suspend fun cachedRaw(): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.isFile) null else cacheFile.readText()
        }.getOrNull()
    }

    /**
     * 写入缓存。
     *
     * **只在解析成功后才调用** —— 否则一次网络抖动抓到半截内容，
     * 会把好缓存也毁掉。由调用方（ViewModel）在解析通过后触发。
     */
    suspend fun writeCache(raw: String) = withContext(Dispatchers.IO) {
        runCatching { cacheFile.writeText(raw) }
        Unit
    }

    /** 发 GET 请求取文档原文。非 2xx 或超限抛 [IllegalStateException]。 */
    private fun fetchRaw(): String {
        val conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // raw 不强制要求 UA，但带上便于从服务端日志区分来源
            setRequestProperty("User-Agent", "WatermarkBox-Android")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }

            val length = conn.contentLengthLong
            check(length <= MAX_BYTES) { "响应过大：$length 字节" }

            return conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    total += n
                    // contentLength 有时是 -1（分块传输），所以边读边限更保险
                    check(total <= MAX_BYTES) { "响应过大" }
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }
}