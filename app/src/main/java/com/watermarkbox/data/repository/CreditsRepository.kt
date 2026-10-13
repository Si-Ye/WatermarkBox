package com.watermarkbox.data.repository

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 制作人资料的**在线档案**：头像地址 + 昵称。
 *
 * ## 为什么运行时取，而不是打包进 APK
 *
 * 头像是「会变的东西」：换了 GitHub 头像、改了昵称，打包方式得等下一个
 * 版本才更新；运行时取回来就自动跟上了。
 *
 * ## 网络使用的边界（说清楚，不含糊）
 *
 * - 只请求 **GitHub 公开接口**，不带任何用户数据、不做埋点、不留标识
 * - 头像走 `github.com/<user>.png`（静态地址，不消耗 API 额度），
 *   由 Coil 下载并**磁盘缓存**，遵循 GitHub 的缓存头
 * - 昵称走 `api.github.com/users/<user>`，**6 小时**才重新取一次
 * - 任何一步失败（离线 / 限流 / 接口变更）都返回 `null`，
 *   界面回退到内置的头像与昵称 —— 显示永远不会开天窗
 *
 * ## 为什么不用 HTTP 客户端库
 *
 * 只需要读三个字段。`HttpURLConnection` + `kotlinx.serialization` 足够，
 * 不值得为它引入 OkHttp（本项目其它网络代码也是这个做法）。
 */
class CreditsRepository(private val githubUser: String) {

    /** GitHub 用户接口里我们真正用到的字段。 */
    @Serializable
    data class GitHubProfile(
        /** 用户自己填的显示名；没填时为 null。 */
        val name: String? = null,
        /** 登录名（一定存在），显示名缺失时用它兜底。 */
        val login: String? = null,
        /** 头像地址（接口给的原始值；平时用 [avatarUrl] 那个静态地址）。 */
        @SerialName("avatar_url") val avatarUrl: String? = null
    ) {
        /** 最合适的显示名：显示名 → 登录名 → null。 */
        val displayName: String? get() = name?.takeIf { it.isNotBlank() }
            ?: login?.takeIf { it.isNotBlank() }
    }

    /**
     * 头像地址（静态，不消耗 API 额度）。
     *
     * `size=256` 够 56dp 的头像在高分屏上清晰，又不会拖回一张大图。
     */
    val avatarUrl: String = "https://github.com/$githubUser.png?size=256"

    private val json = Json { ignoreUnknownKeys = true }

    private var cached: GitHubProfile? = null
    private var cachedAtMs = 0L

    /**
     * 取用户档案；失败返回 null（调用方回退到内置资料）。
     *
     * @param force 忽略缓存强制刷新（留给「关于页下拉刷新」之类的将来需求）
     */
    suspend fun fetchProfile(force: Boolean = false): GitHubProfile? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force) {
                cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return@withContext it }
            }
            val fetched = runCatching { requestProfile() }.getOrNull()
            if (fetched != null) {
                cached = fetched
                cachedAtMs = now
            }
            fetched
        }

    private fun requestProfile(): GitHubProfile? {
        val connection = URL("https://api.github.com/users/$githubUser")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // GitHub 要求带 User-Agent，否则直接 403
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<GitHubProfile>(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** 缓存时长：6 小时。改头像/昵称后最多半天内跟上，够用又不打扰接口。 */
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        const val TIMEOUT_MS = 8_000

        /** 带上应用名 + 仓库地址，方便对方在日志里认出调用方。 */
        const val USER_AGENT = "WatermarkBox (https://github.com/Si-Ye/WatermarkBox)"
    }
}