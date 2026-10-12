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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        title = { Text("水印预设", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 保存当前配置 ----
                SectionLabel("保存当前配置")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("预设名称") },
                        placeholder = { Text("例如：我的博客水印") }
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
                        Text("保存")
                    }
                }
                // 输入的名称已存在时，就地在输入框下方提示（避免用户点了才知道）
                val typed = newName.trim()
                if (typed.isNotEmpty() && presets.any { it.name == typed }) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠ 已存在同名预设，保存时会询问如何处理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(Spacing.Item))

                // ---- 预设列表 ----
                SectionLabel("已保存的预设（${presets.size}）")
                if (presets.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "还没有预设。调好水印后在上方输入名称保存吧。",
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
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    // ---- 重名确认（三选一）----
    pendingConflict?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text("名称已存在") },
            text = {
                Text(
                    "已有一个名为「$name」的预设，要如何处理？\n\n" +
                        "• 替换：用当前配置覆盖它（原预设的配置会丢失）\n" +
                        "• 保留两者：原预设不动，本次另存为「$name (2)」"
                )
            },
            // 主操作放 confirmButton（右侧）
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCurrent(name, false)
                        pendingConflict = null
                        newName = ""
                    }
                ) { Text("保留两者") }
            },
            // 次操作与"替换"都放在 dismiss 侧；"取消"最左
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pendingConflict = null }) { Text("取消") }
                    Spacer(Modifier.width(2.dp))
                    TextButton(
                        onClick = {
                            onSaveCurrent(name, true)
                            pendingConflict = null
                            newName = ""
                        }
                    ) {
                        Text(
                            text = "替换",
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
            title = { Text("删除预设") },
            text = { Text("确定删除「${preset.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(preset.id)
                        pendingDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 生成预设的一行摘要，便于用户辨识。 */
private fun buildSummary(preset: WatermarkPreset): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(preset.createdAt))
    return when (preset.type) {
        WatermarkType.TEXT -> {
            val text = preset.textStyle.text.replace("\n", " ").take(14)
            "文字：「$text」 · ${time}"
        }
        WatermarkType.IMAGE ->
            "图片：宽度 ${(preset.imageStyle.widthPercent * 100).toInt()}% · ${time}"
    }
}