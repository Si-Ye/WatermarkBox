package com.watermarkbox.ui.screen.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预设管理对话框。
 *
 * 三块内容：
 * 1. 保存当前配置为新预设（输入名称）
 * 2. 预设列表（点击应用 / 点击垃圾桶删除）
 * 3. 空状态提示
 *
 * ## 重名处理
 *
 * 点「保存」时若名称已存在，会先弹出**三选一**确认框：
 * - **替换**：覆盖旧预设（旧配置丢失）
 * - **保留两者**：旧的不动，本次另存为「名称 (2)」
 * - **取消**：什么都不做
 *
 * @param onSaveCurrent 保存回调。参数为 (名称, 是否覆盖)
 */
@Composable
fun PresetDialog(
    presets: List<WatermarkPreset>,
    onSaveCurrent: (String, Boolean) -> Unit,
    onApply: (WatermarkPreset) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<WatermarkPreset?>(null) }
    // 待用户确认的重名；非 null 时展示重名确认框
    var pendingConflict by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_dialog_title), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 保存当前配置 ----
                SectionLabel(stringResource(R.string.preset_save_section))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.preset_name_label)) },
                        placeholder = { Text(stringResource(R.string.preset_name_placeholder)) }
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val trimmed = newName.trim()
                            if (trimmed.isEmpty()) return@TextButton
                            // 重名 → 先问用户；否则直接保存
                            if (presets.any { it.name == trimmed }) {
                                pendingConflict = trimmed
                            } else {
                                onSaveCurrent(trimmed, false)
                                newName = ""
                            }
                        }
                    ) {
                        Text(stringResource(R.string.preset_save))
                    }
                }
                // 输入的名称已存在时，就地在输入框下方提示（避免用户点了才知道）
                val typed = newName.trim()
                if (typed.isNotEmpty() && presets.any { it.name == typed }) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.preset_duplicate_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(Spacing.Item))

                // ---- 预设列表 ----
                SectionLabel(stringResource(R.string.preset_saved_section, presets.size))
                if (presets.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.preset_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presets, key = { it.id }) { preset ->
                            PresetRow(
                                preset = preset,
                                onApply = { onApply(preset) },
                                onDeleteRequest = { pendingDelete = preset }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_close)) }
        }
    )

    // ---- 重名确认（三选一）----
    pendingConflict?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text(stringResource(R.string.preset_conflict_title)) },
            text = {
                Text(stringResource(R.string.preset_conflict_message, name))
            },
            // 主操作放 confirmButton（右侧）
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCurrent(name, false)
                        pendingConflict = null
                        newName = ""
                    }
                ) { Text(stringResource(R.string.preset_keep_both)) }
            },
            // 次操作与"替换"都放在 dismiss 侧；"取消"最左
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pendingConflict = null }) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(2.dp))
                    TextButton(
                        onClick = {
                            onSaveCurrent(name, true)
                            pendingConflict = null
                            newName = ""
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.preset_replace),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }

    // ---- 删除确认 ----
    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.preset_delete_title)) },
            text = { Text(stringResource(R.string.preset_delete_message, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(preset.id)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.preset_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * 单条预设行。
 */
@Composable
private fun PresetRow(
    preset: WatermarkPreset,
    onApply: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onApply() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSummary(preset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onDeleteRequest) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.preset_delete_action),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 生成预设的一行摘要，便于用户辨识。
 *
 * 标为 @Composable 是因为要调用 [stringResource] 取当前语言的文案
 * （摘要含「文字：」「图片：宽度」这类标签，需跟随界面语言）。
 */
@Composable
private fun buildSummary(preset: WatermarkPreset): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(preset.createdAt))
    return when (preset.type) {
        WatermarkType.TEXT -> {
            val text = preset.textStyle.text.replace("\n", " ").take(14)
            stringResource(R.string.preset_summary_text, text, time)
        }
        WatermarkType.IMAGE ->
            stringResource(R.string.preset_summary_image, (preset.imageStyle.widthPercent * 100).toInt(), time)
    }
}