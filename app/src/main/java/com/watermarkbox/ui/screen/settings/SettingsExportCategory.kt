package com.watermarkbox.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.watermarkbox.R
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.repository.ExportRepository
import com.watermarkbox.ui.component.FileNameTemplateSection
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

@Suppress("unused")
private const val TMP_LINT_PROBE = "硬编码的中文字符串用于测试"


/** 「导出」分类：决定文件怎么存。 */
@Composable
internal fun ExportCategory(
    settings: ExportSettings,
    viewModel: SettingsViewModel
) {
    SectionLabel(stringResource(R.string.settings_export_format_title))
    SegmentedControl(
        options = listOf(
            ExportFormat.PNG to stringResource(R.string.settings_export_png),
            ExportFormat.JPEG to stringResource(R.string.settings_export_jpeg)
        ),
        selected = settings.format,
        onSelect = viewModel::updateFormat
    )

    if (settings.format == ExportFormat.JPEG) {
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = stringResource(R.string.settings_export_jpeg_quality),
            value = settings.jpegQuality.toFloat(),
            onValueChange = { viewModel.updateJpegQuality(it.roundToInt()) },
            valueRange = 60f..100f,
            valueText = "${settings.jpegQuality}"
        )
    }

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel(stringResource(R.string.settings_naming_title))
    // 模板 + 序号开关 + 位数 + 起始号 + 实时预览，与「导出设置」弹窗共用同一份实现
    FileNameTemplateSection(
        settings = settings,
        onChange = viewModel::updateExportSettings
    )

    Spacer(Modifier.height(Spacing.Item))
    var outputDir by remember(settings.customOutputDir) {
        mutableStateOf(settings.customOutputDir.orEmpty())
    }
    OutlinedTextField(
        value = outputDir,
        onValueChange = {
            outputDir = it
            viewModel.updateCustomOutputDir(it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.settings_output_dir)) },
        supportingText = {
            // 目录名直接取自仓库常量，避免「改动一处、文案忘了同步」的脱节
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
        checked = settings.preserveExif,
        onCheckedChange = { viewModel.togglePreserveExif() }
    )
}