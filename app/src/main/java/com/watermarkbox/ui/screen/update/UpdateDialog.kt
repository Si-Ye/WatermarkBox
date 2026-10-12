package com.watermarkbox.ui.screen.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.UpdateInfo
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.ApkVerifier

/**
 * 「检查更新」的各类对话框。
 *
 * ## 为什么全部集中在这个文件
 *
 * 更新流程有 5 种状态（检查中 / 已最新 / 有新版本 / 下载中 / 下载完成），
 * 它们**共享同一套视觉规范**（标题、间距、按钮样式）。
 * 拆成多个文件会让「改个间距要翻 5 处」，集中反而更好维护。
 *
 * ## 为什么用 AlertDialog 而非独立页面
 *
 * 更新是「打断式」的次要信息，用户处理完就该消失。
 * 独立页面会污染导航栈、增加返回逻辑复杂度；对话框天然是临时的。
 */

/**
 * 发现新版本。
 *
 * @param inAppAvailable 是否允许应用内下载（受设置开关 + 权限影响）
 *                          false 时只显示「浏览器下载」
 */
@Composable
fun UpdateAvailableDialog(
    currentVersion: String,
    info: UpdateInfo,
    inAppAvailable: Boolean,
    onDismiss: () -> Unit,
    onDownloadInApp: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.update_available_title, info.version),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_available_current, currentVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.update_available_new, info.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.Item))

                // 更新说明：可能很长，限制最大高度并允许滚动，
                // 否则超长 release notes 会把对话框撑出屏幕、按钮都点不到。
                val notes = info.notes?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.update_no_notes)
                // GitHub 的 release body 是 Markdown 原文，必须渲染而非直接显示
                // （否则用户会看到一堆 ## 和 | 符号）。
                MarkdownText(
                    markdown = notes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            // 有应用内下载能力时，它是主操作（用户开了开关，说明偏好这种方式）
            if (inAppAvailable) {
                TextButton(onClick = onDownloadInApp) {
                    Text(stringResource(R.string.update_btn_download_inapp))
                }
            } else {
                TextButton(onClick = onOpenBrowser) {
                    Text(stringResource(R.string.update_btn_download_browser))
                }
            }
        },
        dismissButton = {
            // 两个按钮：开了应用内下载时，「浏览器下载」降级为次选项
            if (inAppAvailable) {
                TextButton(onClick = onOpenBrowser) {
                    Text(stringResource(R.string.update_btn_download_browser))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_btn_later))
                }
            }
        }
    )
}

/** 已是最新版本。 */
@Composable
fun UpdateUpToDateDialog(
    currentVersion: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_up_to_date)) },
        text = { Text(stringResource(R.string.update_up_to_date_desc, currentVersion)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_btn_cancel))
            }
        }
    )
}

/**
 * 检查更新失败。
 *
 * @param message 已经本地化好的错误文案（由调用方按 ErrorKind 映射）
 */
@Composable
fun UpdateFailedDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_check_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_btn_cancel))
            }
        }
    )
}

/** 正在检查（轻量提示，不阻塞操作太久）。 */
@Composable
fun UpdateCheckingDialog() {
    AlertDialog(
        // 检查中不允许点外部关闭 —— 避免请求还在飞、对话框没了，
        // 结果回来后用户一脸茫然
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_checking)) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {}
    )
}

/**
 * 应用内下载进行中。
 *
 * @param percent 0..100；null 表示总大小未知（服务器未返回 Content-Length）
 */
@Composable
fun UpdateDownloadingDialog(percent: Int?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_inapp_title)) },
        text = {
            Column {
                Text(
                    if (percent != null) {
                        stringResource(R.string.update_downloading, percent)
                    } else {
                        stringResource(R.string.update_downloading_unknown)
                    }
                )
                Spacer(Modifier.height(Spacing.Item))
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // 不确定进度：用循环动画，让用户知道还在动
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * 下载完成，正在校验 APK（SHA-256 / 包名 / 签名）。
 *
 * 用 indeterminate 进度条：校验是「一次哈希计算」，没有细粒度进度，
 * 显示假的百分比反而不诚实。
 */
@Composable
fun UpdateVerifyingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_verifying_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_verifying_desc))
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {}
    )
}

/**
 * 校验失败对话框。
 *
 * 到这里文件**已经被删除**，用户只能重试或去 GitHub 手动下载。
 * 文案按 [reason] 分类：普通用户需要知道「是网络问题还是严重问题」。
 *
 * @param detail 技术细节（失败原因的内部描述）；debug 包会附在末尾便于排查，
 *               release 包不展示，避免吓到普通用户。
 */
@Composable
fun UpdateVerifyFailedDialog(
    reason: ApkVerifier.FailureReason,
    detail: String?,
    showDetail: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val messageRes = when (reason) {
        ApkVerifier.FailureReason.EMPTY_FILE,
        ApkVerifier.FailureReason.SIZE_MISMATCH,
        ApkVerifier.FailureReason.HASH_MISMATCH ->
            R.string.update_verify_failed_corrupt

        ApkVerifier.FailureReason.UNPARSEABLE ->
            R.string.update_verify_failed_unparseable

        ApkVerifier.FailureReason.PACKAGE_MISMATCH,
        ApkVerifier.FailureReason.SIGNATURE_MISMATCH ->
            R.string.update_verify_failed_security
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_verify_failed_title)) },
        text = {
            Column {
                Text(stringResource(messageRes))
                if (showDetail && !detail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.update_verify_failed_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_verify_failed_close))
            }
        }
    )
}

/**
 * 下载完成，确认安装。
 *
 * 「下载完成」不自动安装 —— 系统安装界面会切走应用，
 * 给用户一次确认机会更稳妥（也符合「用户主导」的原则）。
 */
@Composable
fun UpdateReadyToInstallDialog(
    onInstall: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.update_install_confirm_title)) },
        text = { Text(stringResource(R.string.update_install_confirm_desc)) },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.update_install_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_btn_later))
            }
        }
    )
}

/**
 * 应用内下载需要「安装未知应用」权限时的引导。
 */
@Composable
fun UpdatePermissionDialog(
    onGoSettings: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.update_inapp_permission_title)) },
        text = {
            Text(
                text = stringResource(R.string.update_inapp_permission_desc),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onGoSettings) {
                Text(stringResource(R.string.update_inapp_permission_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_btn_cancel))
            }
        }
    )
}