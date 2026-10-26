package com.watermarkbox.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * 系统分享。
 *
 * ## 为什么单独抽出来
 *
 * 批量导出完成后用户最想要的下一步往往是「发出去」，而在此之前
 * 应用只能让用户自己去相册里翻。分享走系统选择器，不申请任何额外权限
 * （uris 都是 MediaStore 自己建的，带 `FLAG_GRANT_READ_URI_PERMISSION` 即可）。
 */
object ShareUtils {

    /**
     * 分享一组图片。
     *
     * @return 是否成功唤起选择器；没有匹配的应用时为 `false`（调用方可以提示）
     */
    fun shareImages(
        context: Context,
        uris: List<String>,
        title: String?
    ): Boolean {
        if (uris.isEmpty()) return false
        val parsed = uris.mapNotNull { runCatching { it.toUri() }.getOrNull() }
        if (parsed.isEmpty()) return false

        val intent = if (parsed.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, parsed.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            // 从 Activity 起的 context 不需要 NEW_TASK；万一拿到的是
            // application context（如 ViewModel 层调用），补一个标志免崩。
            val launcher = Intent.createChooser(intent, title)
            if (context !is android.app.Activity) {
                launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launcher)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // 少数 ROM 上给第三方应用授权会抛 SecurityException
            false
        }
    }
}
