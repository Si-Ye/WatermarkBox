package com.watermarkbox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.util.FileNameResolver
import com.watermarkbox.ui.theme.Spacing
import java.util.Date

/**
 * 文件名模板编辑区（设置页与导出设置弹窗共用）。
 *
 * ## 为什么抽成一个组件
 *
 * 文件名模板原本在**两处**各写了一份：「设置 → 导出」和编辑/批量页的「导出设置」弹窗。
 * 序号开关、位数、实时预览如果各实现一次，两边必然会漂（一处改了、另一处忘）。
 * 这里只保留一份实现，两个调用点只传各自的 `onChange`。
 *
 * ## 为什么没有「序号开关」这个状态
 *
 * 开关状态**直接由模板里有没有 `{index...}` 反推**，不另存布尔值。
 * 存两份的话，用户手输模板改掉占位符后，开关就会显示错误的状态。
 */
@Composable
fun FileNameTemplateSection(
    settings: ExportSettings,
    onChange: (ExportSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    // 文本框用本地状态：输入中间态（刚清空还没打完）不该被上游回写打断光标
    var fileName by remember(settings.fileNameTemplate) {
        mutableStateOf(settings.fileNameTemplate)
    }
    val hasIndex = FileNameResolver.hasIndex(fileName)
    val digits = FileNameResolver.indexDigits(fileName) ?: FileNameResolver.DEFAULT_INDEX_DIGITS

    fun applyTemplate(next: String) {
        fileName = next
        onChange(settings.copy(fileNameTemplate = next))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fileName,
            onValueChange = { applyTemplate(it) },
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
        ToggleRow(
            label = stringResource(R.string.settings_index_toggle),
            checked = hasIndex,
            hint = stringResource(R.string.settings_index_toggle_hint),
            onCheckedChange = { on ->
                applyTemplate(
                    if (on) {
                        FileNameResolver.appendIndex(fileName, digits)
                    } else {
                        FileNameResolver.removeIndex(fileName)
                    }
                )
            }
        )

        if (hasIndex) {
            Spacer(Modifier.height(Spacing.Small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ---- 位数：只改占位符里的数字，不动位置与前后分隔符 ----
                Text(
                    text = stringResource(R.string.settings_index_digits),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (digits > 1) {
                            applyTemplate(FileNameResolver.replaceIndexDigits(fileName, digits - 1))
                        }
                    },
                    enabled = digits > 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = null)
                }
                Text(
                    text = "$digits",
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(
                    onClick = {
                        if (digits < 6) {
                            applyTemplate(FileNameResolver.replaceIndexDigits(fileName, digits + 1))
                        }
                    },
                    enabled = digits < 6,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }

            // ---- 起始号 ----
            var startText by remember(settings.indexStart) {
                mutableStateOf(settings.indexStart.toString())
            }
            OutlinedTextField(
                value = startText,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() }.take(4)
                    startText = filtered
                    filtered.toIntOrNull()?.let { value ->
                        if (value in 1..9999) {
                            onChange(settings.copy(indexStart = value))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.settings_index_start)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.settings_index_start_hint),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }

        Spacer(Modifier.height(Spacing.Item))
        FileNamePreview(settings = settings, template = fileName, startIndex = settings.indexStart)
    }
}

/**
 * 实时预览：单张与批量各一行。
 *
 * 时间戳在这里**冻结**（`remember` 到模板/格式变化为止）——
 * 否则含 `{date}` / `{time}` 的模板会每秒跳一次，看起来像故障。
 */
@Composable
private fun FileNamePreview(
    settings: ExportSettings,
    template: String,
    startIndex: Int
) {
    val frozenNow = remember(template, settings.format, startIndex) { Date() }
    val sampleSource = "IMG_2041.jpg"

    val batchName = FileNameResolver.build(
        template = template,
        sourceName = sampleSource,
        format = settings.format,
        index = startIndex,
        now = frozenNow
    )
    val singleName = FileNameResolver.build(
        template = template,
        sourceName = sampleSource,
        format = settings.format,
        index = null,
        now = frozenNow
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            PreviewLine(stringResource(R.string.settings_index_preview_batch), batchName)
            Spacer(Modifier.height(4.dp))
            PreviewLine(stringResource(R.string.settings_index_preview_single), singleName)
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
