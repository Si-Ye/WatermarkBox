package com.watermarkbox.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 应用内下载与安装新版本 APK（实验性功能）。
 *
 * ## 为什么这是「实验性」
 *
 * 应用内安装需要 `REQUEST_INSTALL_PACKAGES` 权限，这会给应用带来：
 * - 首次使用时的系统授权弹窗
 * - 部分应用商店（如 F-Droid）因此拒绝收录
 *
 * 所以默认**关闭**，只有用户在设置里主动开启后才走这条路。
 * 关闭状态下本类完全不会被调用，应用与「无此权限」时体验一致。
 *
 * ## 权限模型
 *
 * 这个权限**不是运行时权限**（没有 requestPermissions 一说），
 * 而是「特殊权限」：必须引导用户到系统设置页手动开启。
 *
 * ## 为什么下载到 filesDir 而不是 cacheDir（踩坑记录）
 *
 * 最初下载到 `cacheDir/updates/`，用 `<cache-path>` 暴露。
 * 实测在部分设备上，**系统安装器（PackageInstaller，独立进程）
 * 打开该 URI 时报 `ENOENT (No such file or directory)`**：
 *
 * - `cacheDir` 的目录权限位更特殊，且系统有权在低存储时主动清理
 * - 某些 ROM 对「从 cache 暴露给其它进程」有额外限制
 *
 * 改用 `filesDir/updates/`（应用持久数据目录）后问题消失。
 * 代价是这些文件不会被系统自动回收，所以安装流程结束后
 * 我们**必须**主动 [cleanup]。
 *
 * ## 为什么不能用 file://
 *
 * Android 7.0+ 传 `file://` 给系统安装器会抛
 * `FileUriExposedException`，必须走 FileProvider 的 Content URI。
 */
object ApkInstaller {

    /** 下载目录（应用私有持久数据目录）。 */
    private fun updateDir(context: Context): File =
        File(context.filesDir, "updates").apply { if (!exists()) mkdirs() }

    /**
     * 当前是否已获得「安装未知应用」权限。
     *
     * Android 8.0+ 用 `canRequestPackageInstalls()`；
     * 低版本（本项目 minSdk=26，恰好 8.0 起）无需检查，直接 true。
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳转到「安装未知应用」授权设置页。
     *
     * 注意：不同厂商 ROM 的页面路径不同，但 ACTION_MANAGE_UNKNOWN_APP_SOURCES
     * 是标准入口，绝大多数设备能直达。加 FLAG_ACTIVITY_NEW_TASK 是因为
     * 可能从非 Activity 上下文调用。
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 个别 ROM 不支持带 package 的入口，退回打开总列表
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    /**
     * 下载 APK 到私有目录。
     *
     * @param url        直链（GitHub 的 browser_download_url 会 302 到 CDN，
     *                   已由 [HttpURLConnection] 默认跟随）
     * @param onProgress 进度回调（0..100）；无法确定总长度时回调 null
     * @return 下载完成的本地文件
     * @throws IOException 网络或写文件失败
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Int?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "WatermarkBox-Android")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = conn.contentLengthLong.takeIf { it > 0 }

            // 先清掉旧文件，避免新旧混淆
            cleanup(context)
            val target = File(updateDir(context), "update.apk")

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total != null) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        } else {
                            onProgress(null)
                        }
                    }
                    output.flush()
                }
            }

            // 校验：文件必须真实存在且非空，否则视为下载失败。
            // 这一步是为防止「连接中断但没抛异常」导致留下空文件，
            // 进而在安装时表现为难以定位的 ENOENT。
            if (!target.exists() || target.length() == 0L) {
                throw IOException("downloaded file is empty")
            }

            onProgress(100)
            target
        } catch (e: SocketTimeoutException) {
            throw IOException("timeout", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 用系统安装器安装已下载的 APK。
     *
     * @return true 表示成功唤起安装界面；false 表示文件缺失或无权限
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() == 0L) return false
        if (!canInstall(context)) return false

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 临时授予读取权限：系统安装器需要靠它打开 Content URI
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 清理已下载的更新包。
     *
     * ⚠️ **不要在唤起安装器后立即调用** —— 系统安装器是独立进程，
     * 可能尚未读完文件，删掉会导致它报 ENOENT。
     * 由应用回到前台后（或在下次下载前）再清理。
     */
    fun cleanup(context: Context) {
        runCatching { updateDir(context).listFiles()?.forEach { it.delete() } }
    }
}