package com.watermarkbox.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.util.AppLanguage

/**
 * 设置页里的「可点击跳转行」。
 *
 * 抽出来是因为「更新说明」和「关于」长得必须一样 ——
 * 它们在同一个分组里，样式不一致会显得很随意。
 */
@Composable
internal fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            trailing()
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「默认水印图」选择行。
 *
 * 只表明「设了没有」，**不显示缩略图** —— 设置页保持纯配置列表，
 * 预览交给编辑页的完整画布。若将来需要缩略图，在这里加 Image 即可。
 */
@Composable
internal fun DefaultLogoPicker(
    logoUri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 标题不在此处重复：外层的 SectionLabel 已经显示了
                // 「默认水印图」，这里只放状态说明。
                Text(
                    text = if (logoUri == null) {
                        stringResource(R.string.settings_default_image_unset)
                    } else {
                        stringResource(R.string.settings_default_image_set)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (logoUri == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            if (logoUri != null) {
                TextButton(onClick = onClear) {
                    Text(
                        text = stringResource(R.string.common_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Button(
                onClick = onPick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(
                        if (logoUri == null) R.string.settings_pick_image
                        else R.string.settings_change_image
                    )
                )
            }
        }
    }
}

/**
 * 应用语言选择对话框。
 *
 * ## 为什么语言名不用 stringResource
 *
 * 每项显示的是[语言自称]（中文 / English），**不随界面语言翻译**。
 * 若跟随界面翻译，中文界面显示「中文 / 英语」、英文界面显示「Chinese / English」，
 * 对只懂一种语言的用户反而更难找到自己那一项。
 *
 * ## 为什么用 RadioButton 而非列表点击
 *
 * 这是「多选一且需明确当前值」的场景，单选圆点是系统里最无歧义的表达；
 * 用普通列表行会让用户不确定「到底选中了没」。
 */
@Composable
internal fun LanguagePickerDialog(
    current: AppLanguage.Option,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage.Option) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_language_dialog_title),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                AppLanguage.Option.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == current,
                            onClick = { onSelect(option) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when (option) {
                                AppLanguage.Option.SYSTEM ->
                                    stringResource(R.string.settings_language_follow_system)
                                else -> option.displayName
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}