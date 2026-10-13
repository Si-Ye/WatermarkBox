package com.watermarkbox.ui.screen.settings

import android.net.Uri
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
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/** 「水印」分类：决定新图片的默认水印长什么样。 */
@Composable
internal fun WatermarkCategory(
    settings: ExportSettings,
    viewModel: SettingsViewModel,
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit
) {
    SectionLabel(stringResource(R.string.settings_default_text_title))
    var defaultText by remember(settings.defaultWatermarkText) {
        mutableStateOf(settings.defaultWatermarkText)
    }
    OutlinedTextField(
        value = defaultText,
        onValueChange = {
            defaultText = it
            viewModel.updateDefaultWatermarkText(it)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.settings_default_text_title)) },
        supportingText = {
            Text(
                text = stringResource(R.string.settings_default_text_desc),
                style = MaterialTheme.typography.labelSmall
            )
        }
    )

    Spacer(Modifier.height(Spacing.Item))

    SectionLabel(stringResource(R.string.settings_default_image_title))
    DefaultLogoPicker(
        logoUri = settings.defaultLogoUri,
        onPick = { onPickLogo { uri -> viewModel.updateDefaultLogoUri(uri.toString()) } },
        onClear = { viewModel.updateDefaultLogoUri(null) }
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel(stringResource(R.string.settings_default_opacity_title))
    LabeledSlider(
        label = stringResource(R.string.settings_default_opacity_desc),
        value = settings.defaultAlpha,
        onValueChange = viewModel::updateDefaultAlpha,
        valueRange = 0.01f..1f,
        valueText = "${(settings.defaultAlpha * 100).roundToInt()}%"
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    // ---- 隐藏水印默认值 ----
    SectionLabel(stringResource(R.string.settings_invisible_title))
    ToggleRow(
        label = stringResource(R.string.settings_invisible_switch),
        checked = settings.invisibleDctByDefault,
        onCheckedChange = { viewModel.toggleInvisibleDctByDefault() },
        hint = stringResource(R.string.settings_invisible_desc)
    )
}