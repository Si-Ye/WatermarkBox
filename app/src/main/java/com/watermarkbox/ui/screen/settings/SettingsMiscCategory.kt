package com.watermarkbox.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.AppLanguage

/** 「其他」分类：预设统计 + 更新 + 更新说明 + 关于入口。 */
@Composable
internal fun MiscCategory(
    presetCount: Int,
    autoCheckUpdate: Boolean,
    inAppDownload: Boolean,
    onToggleAutoCheck: (Boolean) -> Unit,
    onToggleInApp: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    SectionLabel(stringResource(R.string.settings_presets_title))
    Text(
        text = stringResource(R.string.settings_presets_desc, presetCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel(stringResource(R.string.settings_app_info_title))

    // ---- 更新分组 ----
    // 放在「应用信息」标题下、语言之前：更新也是应用级偏好，且用户找它通常直奔设置页底部
    ToggleRow(
        label = stringResource(R.string.update_auto_check_title),
        checked = autoCheckUpdate,
        onCheckedChange = onToggleAutoCheck
    )
    Text(
        text = stringResource(R.string.update_auto_check_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))

    ToggleRow(
        label = stringResource(R.string.update_inapp_title),
        checked = inAppDownload,
        onCheckedChange = onToggleInApp
    )
    Text(
        text = stringResource(R.string.update_inapp_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))

    // 手动检查更新入口
    NavRow(
        title = stringResource(R.string.update_check_title),
        subtitle = stringResource(R.string.update_check_desc),
        trailing = {
            Text(
                text = stringResource(R.string.update_check_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        onClick = onCheckUpdate
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    // 语言入口：放在最前，因为它是「应用级偏好」里最常改的一项
    var showLanguageDialog by remember { mutableStateOf(false) }

    /**
     * 当前语言。
     *
     * ## 为什么用 mutableStateOf + 手动更新，而不是直接读
     *
     * 有个反直觉的场景：**从「中文」切到「跟随系统」，而系统语言恰好也是中文** ——
     * 此时 `setApplicationLocales` 发现「生效语言没变」，**不会重建 Activity**，
     * 于是本页不会重组，界面上的语言项就一直显示旧值「中文」，
     * 用户会以为没切成功。
     *
     * 所以这里把它做成可变状态，在用户做出选择后**立即同步一次**，
     * 不依赖 Activity 重建来刷新。
     */
    var currentLanguage by remember { mutableStateOf(AppLanguage.current()) }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            current = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = { option ->
                showLanguageDialog = false
                // 先落状态再切语言：
                // 若切换触发了 Activity 重建，状态会在重建后的新组合里重新初始化；
                // 若没触发（生效语言未变），这行保证界面立刻反映用户的选择。
                currentLanguage = option
                AppLanguage.apply(option)
            }
        )
    }

    NavRow(
        title = stringResource(R.string.settings_language_title),
        subtitle = stringResource(R.string.settings_language_desc),
        trailing = {
            Text(
                text = when (currentLanguage) {
                    AppLanguage.Option.SYSTEM ->
                        stringResource(R.string.settings_language_follow_system)
                    AppLanguage.Option.CHINESE ->
                        stringResource(R.string.settings_language_current_zh)
                    AppLanguage.Option.ENGLISH ->
                        stringResource(R.string.settings_language_current_en)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        onClick = { showLanguageDialog = true }
    )

    Spacer(Modifier.height(8.dp))

    // 更新说明入口
    NavRow(
        title = stringResource(R.string.settings_changelog_title),
        subtitle = stringResource(R.string.settings_changelog_desc),
        trailing = {
            Text(
                text = BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        onClick = onOpenChangelog
    )

    Spacer(Modifier.height(8.dp))

    // 关于入口
    NavRow(
        title = stringResource(R.string.settings_about_title),
        subtitle = stringResource(
            R.string.settings_about_desc,
            BuildConfig.VERSION_NAME
        ),
        onClick = onOpenAbout
    )
}