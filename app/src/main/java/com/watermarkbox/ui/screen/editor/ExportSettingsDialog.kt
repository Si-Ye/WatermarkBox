package com.watermarkbox.ui.screen.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 导出设置对话框。
 *
 * 展示当前导出配置，并允许临时调整（会持久化到 DataStore）。
 */
@Composable
fun ExportSettingsDialog(
    settings: com.watermarkbox.data.model.ExportSettings,
    onSettingsChange: (com.watermarkbox.data.model.ExportSettings) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var fileName by remember(settings.fileNameTemplate) {
        mutableStateOf(settings.fileNameTemplate)
    }
    var outputDir by remember(settings.customOutputDir) {
        mutableStateOf(settings.customOutputDir.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("导出设置", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionLabel("输出格式")
                SegmentedControl(
                    options = listOf(
                        ExportFormat.PNG to "PNG",
                        ExportFormat.JPEG to "JPEG"
                    ),
                    selected = settings.format,
                    onSelect = { onSettingsChange(settings.copy(format = it)) }
                )

                if (settings.format == ExportFormat.JPEG) {
                    Spacer(Modifier.height(Spacing.Item))
                    LabeledSlider(
                        label = "JPEG 质量",
                        value = settings.jpegQuality.toFloat(),
                        onValueChange = {
                            onSettingsChange(settings.copy(jpegQuality = it.roundToInt()))
                        },
                        valueRange = 60f..100f,
                        valueText = "${settings.jpegQuality}"
                    )
                }

                Spacer(Modifier.height(Spacing.Item))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(Spacing.Item))

                SectionLabel("文件命名")
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        onSettingsChange(settings.copy(fileNameTemplate = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("文件名模板") },
                    supportingText = {
                        Text(
                            "占位符：{原文件名} {date} {time} {timestamp} {index}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )

                Spacer(Modifier.height(Spacing.Item))
                OutlinedTextField(
                    value = outputDir,
                    onValueChange = {
                        outputDir = it
                        onSettingsChange(
                            settings.copy(customOutputDir = it.ifBlank { null })
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("输出目录（留空用默认）") },
                    supportingText = {
                        Text(
                            "默认：Pictures/水印盒",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )

                Spacer(Modifier.height(Spacing.Item))
                ToggleRow(
                    label = "保留原图 EXIF 信息",
                    checked = settings.preserveExif,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(preserveExif = it))
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("开始导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}