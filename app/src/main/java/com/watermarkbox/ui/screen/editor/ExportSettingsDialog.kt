package com.watermarkbox.ui.screen.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.repository.ExportRepository
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 导出设置对话框（**纯受控**）。
 *
 * ## 设计要点：它自己不碰全局设置
 *
 * 所有改动只写进内部的 `draft`，点确认时才把 `draft` 交给调用方 ——
 * 由调用方决定这份设置是**持久化为全局默认值**，还是**只用于本次导出**。
 *
 * 于是同一个对话框能服务两种语义：
 *
 * | 调用方 | 确认时做什么 |
 * |---|---|
 * | 编辑页右上角 / 批量页 | 写入全局（下次打开还是这套）|
 * | 编辑页右下角 | 用这份设置导出，**不动全局** |
 *
 * 早期版本是「改一下就回写全局」—— 那时只有一种用法所以没暴露问题；
 * 一旦要支持「本次覆盖」就必须改成受控的，否则用户「临时改一下」
 * 会静默改掉他所有的导出默认值。
 *
 * @param title       对话框标题（两种语义用不同标题区分）
 * @param confirmText 确认按钮文案（全局模式是「保存」，本次模式是「开始导出」）
 * @param scopeHint   作用域说明（显示在表单顶部的小卡片里）。
 *                    传 null/空串则不显示。
 *                    这是为了消除"两个入口长得一样"造成的困惑 ——
 *                    详见下方实现处的注释。
 * @param persistentScope 作用域是否**持久**（写全局默认值）。
 *                    只影响说明卡片的配色与图标，不改变行为。
 */
@Composable
fun ExportSettingsDialog(
    settings: com.watermarkbox.data.model.ExportSettings,
    onConfirm: (com.watermarkbox.data.model.ExportSettings) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.editor_export_title),
    confirmText: String = stringResource(R.string.common_save),
    scopeHint: String? = null,
    persistentScope: Boolean = true
) {
    // 完整草稿：所有改动只动它，绝不外泄
    var draft by remember(settings) { mutableStateOf(settings) }

    // 两个文本框另用独立的本地状态。
    //
    // 原因：`draft` 是 remember(settings) 的，外部 settings 一变就会重置；
    // 而输入过程中「刚清空还没打完」这种中间态不该被打断光标。
    var fileName by remember(settings.fileNameTemplate) {
        mutableStateOf(settings.fileNameTemplate)
    }
    var outputDir by remember(settings.customOutputDir) {
        mutableStateOf(settings.customOutputDir.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---- 作用域说明 ----
                //
                // ## 为什么必须有这一段
                //
                // 同一个组件服务两种语义（写全局 / 只本次），
                // 而两边的默认标题都叫「导出设置」—— 用户根本分不清
                // 点「保存」会改掉什么。老大实测的反馈正是：
                // 「批量右上角的导出设置按钮功能没有改成全局导出设置」。
                //
                // 功能其实是对的，问题出在**没有任何提示告诉用户
                // 这一份设置的作用范围**。所以这里在表单最上方
                // 用一句话把作用域讲清楚，并用配色区分：
                // - 全局（持久）：主色容器，语气平和
                // - 本次（临时）：次要容器，明确"仅本次"
                if (!scopeHint.isNullOrBlank()) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (persistentScope) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (persistentScope) {
                                    Icons.Filled.Save
                                } else {
                                    Icons.Filled.Info
                                },
                                contentDescription = null,
                                tint = if (persistentScope) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = scopeHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (persistentScope) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.Item))
                }

                SectionLabel(stringResource(R.string.editor_export_format))
                SegmentedControl(
                    options = listOf(
                        ExportFormat.PNG to stringResource(R.string.editor_export_png),
                        ExportFormat.JPEG to stringResource(R.string.editor_export_jpeg)
                    ),
                    selected = draft.format,
                    onSelect = { draft = draft.copy(format = it) }
                )

                if (draft.format == ExportFormat.JPEG) {
                    Spacer(Modifier.height(Spacing.Item))
                    LabeledSlider(
                        label = stringResource(R.string.settings_export_jpeg_quality),
                        value = draft.jpegQuality.toFloat(),
                        onValueChange = {
                            draft = draft.copy(jpegQuality = it.roundToInt())
                        },
                        valueRange = 60f..100f,
                        valueText = "${draft.jpegQuality}"
                    )
                }

                Spacer(Modifier.height(Spacing.Item))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(Spacing.Item))

                SectionLabel(stringResource(R.string.editor_export_naming))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        draft = draft.copy(fileNameTemplate = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_filename_template)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.settings_filename_placeholders),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )

                Spacer(Modifier.height(Spacing.Item))
                OutlinedTextField(
                    value = outputDir,
                    onValueChange = {
                        outputDir = it
                        draft = draft.copy(customOutputDir = it.ifBlank { null })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_output_dir)) },
                    supportingText = {
                        // 目录名取自仓库常量，避免文案与实际路径脱节
                        Text(
                            text = stringResource(
                                R.string.settings_output_dir_default,
                                ExportRepository.DEFAULT_ALBUM
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )

                Spacer(Modifier.height(Spacing.Item))
                ToggleRow(
                    label = stringResource(R.string.settings_keep_exif),
                    checked = draft.preserveExif,
                    onCheckedChange = { draft = draft.copy(preserveExif = it) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_export_cancel))
            }
        }
    )
}