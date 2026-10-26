package com.watermarkbox.util

import com.watermarkbox.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 检查更新：从 GitHub Releases API 拉取最新版本并比较。
 *
 * ## 为什么不用 Retrofit / OkHttp
 *
 * 整个应用**只发一个 GET 请求**（拉 latest release 的 JSON）。
 * 为此引入 OkHttp（+方法数、+体积）不划算 ——
 * `HttpURLConnection` 是 JDK 自带，零依赖，完全够用。
 *
 * JSON 解析复用项目已有的 `kotlinx.serialization`（已在依赖里），
 * 但这里**手写读取字段**而非定义 @Serializable 数据类，原因见下。
 *
 * ## 为什么不定义 @Serializable 数据类
 *
 * GitHub 的响应体字段极多（assets 数组里每个元素还有十几个字段），
 * 定义完整数据类既冗长又脆弱（GitHub 加字段不影响我们，但我们还得维护）。
 * 这里只取需要的几个字段，用 `JsonObject` 手动导航，
 * 既容错（字段缺失不崩），又不会因上游结构微调而编译报错。
 *
 * ## 线程
 *
 * 所有网络 I/O 都切到 [Dispatchers.IO]，调用方无需关心线程。
 * 取消（协程 cancel）时 `HttpURLConnection` 会抛异常，被上层捕获。
 */
object UpdateChecker {

    /**
     * GitHub API：最新 release。
     *
     * ⚠️ 未认证的 API 每小时限 60 次（按 IP）。
     * 我们只在用户主动点「检查」或每次启动最多一次时调用，
     * 远低于限额，无需 token。
     */
    private const val API_LATEST =
        "https://api.github.com/repos/Si-Ye/WatermarkBox/releases/latest"

    /** 兜底地址：API 失败时跳这里，用户仍能手动找版本。 */
    const val RELEASES_PAGE =
        "https://github.com/Si-Ye/WatermarkBox/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 检查更新的结果。
     *
     * 用密封类而非「返回 UpdateInfo?」：
     * 「已最新」和「失败」是**不同语义**，用 null 混淆了两者，
     * UI 就无法区分「无需更新」与「没查到」。
     */
    sealed interface Result {
        /** 有新版本可用。 */
        data class Available(val info: UpdateInfo) : Result

        /** 已是最新版本（[latestVersion] 仅供提示，可能等于当前版本）。 */
        data class UpToDate(val latestVersion: String) : Result

        /** 检查失败。[cause] 用于日志，UI 按 [kind] 显示本地化文案。 */
        data class Failed(val kind: ErrorKind, val cause: Throwable?) : Result
    }

    /** 失败分类 —— 决定 UI 显示哪条本地化提示。 */
    enum class ErrorKind {
        /** 连不上网 / DNS 失败 / 连接被拒。 */
        NETWORK,

        /** 连接或读取超时（国内访问 GitHub 最常见）。 */
        TIMEOUT,

        /** HTTP 非 200（如 404 仓库不存在、403 限流）。 */
        HTTP,

        /** 响应体解析失败。 */
        PARSE,

        /** 其它未知错误。 */
        UNKNOWN
    }

    /**
     * 把下载 / 安装链路上的异常归到 [ErrorKind]。
     *
     * ## 为什么需要它
     *
     * [ApkInstaller.download] 会把超时包成 `IOException("timeout", e)`、
     * 把非 2xx 包成 `IOException("HTTP 503")`。若上层只按**顶层类型**判断，
     * 所有异常都会落到「网络失败」，于是用户照着提示换网络、重试，结果依旧失败 ——
     * 提示与病灶不符比没有提示更浪费时间。
     *
     * 所以这里沿 cause 链找真正的异常类型，再回退到消息前缀。
     *
     * @return 不会抛异常，且对任意 [Throwable]（含 null cause）都有确定结果。
     */
    fun classifyFailure(t: Throwable): ErrorKind {
        var cur: Throwable? = t
        // 深度上限：异常链理论上可能自引用，不能让日志/分类把线程拖死
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is SocketTimeoutException -> return ErrorKind.TIMEOUT
                is UnknownHostException, is SocketException -> return ErrorKind.NETWORK
                else -> Unit
            }
            cur = cur.cause
            depth++
        }
        // HTTP 状态码在下载实现里以消息形式携带（`IOException("HTTP 503")`）
        if (t.message?.startsWith("HTTP ") == true) return ErrorKind.HTTP
        return ErrorKind.UNKNOWN
    }

    /**
     * 查询最新版本。
     *
     * @param currentVersion 当前安装的版本名（如 `1.4.1`），用于比较
     */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val raw = try {
            fetchLatestJson()
        } catch (e: SocketTimeoutException) {
            return@withContext Result.Failed(ErrorKind.TIMEOUT, e)
        } catch (e: IOException) {
            return@withContext Result.Failed(ErrorKind.NETWORK, e)
        } catch (e: Exception) {
            return@withContext Result.Failed(ErrorKind.UNKNOWN, e)
        }

        val info = try {
            parseRelease(raw)
        } catch (e: Exception) {
            return@withContext Result.Failed(ErrorKind.PARSE, e)
        } ?: return@withContext Result.Failed(ErrorKind.PARSE, null)

        if (isNewer(info.version, currentVersion)) {
            Result.Available(info)
        } else {
            Result.UpToDate(info.version)
        }
    }

    /** 发 GET 请求，返回响应体字符串。非 2xx 抛 [IOException]。 */
    private fun fetchLatestJson(): String {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub API 要求带 Accept 头，且带 UA 是良好实践
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WatermarkBox-Android")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析 GitHub release JSON。
     *
     * 只取需要的字段；任何字段缺失都返回 null 由调用方转成 PARSE 错误，
     * 而不是在这里抛异常（保持「解析函数不抛」的约定，便于测试）。
     */
    private fun parseRelease(raw: String): UpdateInfo? {
        val obj: JsonObject = json.parseToJsonElement(raw).jsonObject

        val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        if (tagName.isBlank()) return null

        val version = normalizeVersion(tagName)
        val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNullSafe()
            ?: "$RELEASES_PAGE"

        val title = obj["name"]?.jsonPrimitive?.contentOrNullSafe()
        val notes = obj["body"]?.jsonPrimitive?.contentOrNullSafe()
        val apk = findApkAsset(obj)

        return UpdateInfo(
            version = version,
            tagName = tagName,
            title = title?.takeIf { it.isNotBlank() },
            notes = notes?.takeIf { it.isNotBlank() },
            htmlUrl = htmlUrl,
            apkUrl = apk?.url,
            apkSha256 = apk?.sha256,
            apkSize = apk?.size ?: 0L
        )
    }

    /** 从附件里挑出的 APK 信息。 */
    private data class ApkAsset(
        val url: String,
        /** 小写十六进制 SHA-256；GitHub 未提供时为 null */
        val sha256: String?,
        val size: Long
    )

    /**
     * 从 assets 数组里挑出要下载的 APK（含 SHA-256 与大小）。
     *
     * ## 挑选策略（按优先级）
     *
     * 1. 排除文件名含 `debug` 的 —— 调试包体积大、含日志，不适合普通用户
     * 2. 优先选文件名含 `release` 的 —— 这是 CI 产出的正式包
     * 3. 其余情况退回第一个 apk 附件
     *
     * ## 为什么要排除 debug 而不是只匹配 release
     *
     * 早期实现只判断「含 release」，但 Release 里可能同时存在
     * `app-release.apk`（Gradle 默认名）与 `WatermarkBox-1.5.0-release.apk`
     * 两种命名，前者虽能用却不是我们想推荐给用户的那个。
     * 先排除 debug 再挑 release，命中更稳。
     *
     * 目前 CI 已统一产出 `WatermarkBox-<version>-release.apk`，
     * 这里的多重判断是为了兼容历史上传的旧附件。
     *
     * ## digest 字段
     *
     * GitHub API 会为上传后的附件计算摘要，格式 `sha256:<hex>`。
     * 本函数把它剥成纯小写 hex 存进 [ApkAsset.sha256]。
     * 若该字段缺失（旧附件），返回 null —— 下载后跳过哈希校验。
     */
    private fun findApkAsset(obj: JsonObject): ApkAsset? {
        val assets = obj["assets"] as? JsonArray ?: return null

        val apks = assets.mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNullSafe()
                ?: return@mapNotNull null
            val size = asset["size"]?.jsonPrimitive?.longOrNullSafe() ?: 0L
            name to ApkAsset(url = url, sha256 = parseDigest(asset), size = size)
        }
        if (apks.isEmpty()) return null

        // 1. 先排除 debug 包
        val nonDebug = apks.filterNot { it.first.contains("debug", ignoreCase = true) }
        val pool = nonDebug.ifEmpty { apks }

        // 2. 在剩余里优先挑 release
        return (pool.firstOrNull { it.first.contains("release", ignoreCase = true) }
            ?: pool.first()).second
    }

    /**
     * 解析附件的 `digest` 字段。
     *
     * 格式：`sha256:<64位小写hex>`。返回剥掉前缀的**小写** hex；
     * 若不是 sha256（例如未来出现 sha512）或字段缺失，返回 null。
     */
    private fun parseDigest(asset: JsonObject): String? {
        val raw = asset["digest"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val prefix = "sha256:"
        if (!raw.startsWith(prefix, ignoreCase = true)) return null
        val hex = raw.substring(prefix.length).trim().lowercase()
        // 与 MessageDigest 输出对齐：64 位十六进制才算有效
        return hex.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    /**
     * 归一化版本 tag：剥离 `v` / `V` 前缀、`release-` 前缀与首尾空白。
     *
     * `v1.5.0` → `1.5.0`；`release-1.5.0` → `1.5.0`；`1.5.0` → `1.5.0`
     */
    fun normalizeVersion(tag: String): String {
        var s = tag.trim()
        s = s.removePrefix("v").removePrefix("V")
        s = s.removePrefix("release-").removePrefix("Release-")
        return s.trim()
    }

    /**
     * 语义化版本比较：[candidate] 是否比 [current] 新。
     *
     * ## 规则
     *
     * 按 `.` 分段逐段比数字（缺失段视为 0）：
     * - `1.5.0` vs `1.4.1` → 新
     * - `1.4.1` vs `1.4.1` → 否
     * - `1.4` vs `1.4.0`  → 否（等价）
     * - `2.0` vs `1.9.9`  → 新
     *
     * ## 容错
     *
     * 段内非数字部分（如 `1.5.0-beta`）取前导数字；
     * 完全无法解析成数字时视为 0。宁可判「不更新」也不误报新版本。
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersionParts(candidate)
        val b = parseVersionParts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 把 `1.5.0` 拆成 `[1, 5, 0]`，非数字段取前导数字。 */
    private fun parseVersionParts(v: String): List<Int> =
        v.trim()
            .split('.', '-', '_')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /**
     * 安全读取 JsonPrimitive 内容。
     *
     * JSON 里 `null` 字面量在 kotlinx.serialization 里是 `JsonNull`，
     * 直接 `jsonPrimitive` 会抛异常 —— 这里统一吞掉，返回 null。
     */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    /**
     * 安全读取 JsonPrimitive 的 long 值。
     *
     * 非数字内容（字符串、JsonNull、小数）都返回 null，
     * 由调用方决定默认值（附件 size 缺失时用 0 表示未知）。
     */
    private fun JsonPrimitive.longOrNullSafe(): Long? =
        if (this is kotlinx.serialization.json.JsonNull) null else longOrNull
}
