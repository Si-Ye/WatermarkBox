package com.watermarkbox.ui.screen.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.BuildConfig
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 设置页的分类。
 *
 * 按「这个设置影响什么」划分，而不是按实现顺序堆成一条长列表：
 *
 * - [WATERMARK]：决定**水印长什么样**（默认文字 / 默认图 / 透明度）
 * - [EXPORT]：决定**文件怎么存**（格式 / 质量 / 命名 / 目录 / EXIF）
 * - [MISC]：预设统计与关于入口
 *
 * 顺序即 Tab 顺序，默认停在第一项。
 */
private enum class SettingsCategory(val label: String) {
    WATERMARK("水印"),
    EXPORT("导出"),
    MISC("其他")
}

/**
 * 设置页（模块 3）。
 *
 * 布局：顶部横向分类菜单（[SettingsCategory]）→ 该分类下的设置项。
 * 切换分类只换内容区，顶栏与返回按钮保持不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.exportSettings

    var category by remember { mutableStateOf(SettingsCategory.WATERMARK) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---- 分类菜单：固定在顶部，不随内容滚动 ----
            SettingsCategoryTabs(selected = category, onSelect = { category = it })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // ---- 内容区 ----
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.PageHorizontal)
            ) {
                Spacer(Modifier.height(Spacing.Item))

                when (category) {
                    SettingsCategory.WATERMARK -> WatermarkCategory(
                        settings = settings,
                        viewModel = viewModel,
                        onPickLogo = onPickLogo
                    )
                    SettingsCategory.EXPORT -> ExportCategory(
                        settings = settings,
                        viewModel = viewModel
                    )
                    SettingsCategory.MISC -> MiscCategory(
                        presetCount = state.presetCount,
                        onOpenAbout = onOpenAbout,
                        onOpenChangelog = onOpenChangelog
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 顶部横向分类菜单。
 *
 * 手写胶囊按钮而非 Material 的 `TabRow`：TabRow 的下划线指示器
 * 和这套偏圆角的视觉不搭，胶囊高亮更接近首页入口卡片的风格。
 */
@Composable
private fun SettingsCategoryTabs(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.PageHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsCategory.entries.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                    .clickable { onSelect(item) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** 「水印」分类：决定新图片的默认水印长什么样。 */
@Composable
private fun WatermarkCategory(
    settings: ExportSettings,
    viewModel: SettingsViewModel,
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit
) {
    SectionLabel("默认水印文字")
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
        label = { Text("默认水印文字") },
        supportingText = {
            Text(
                "选图进入编辑页时，自动填入这个文字",
                style = MaterialTheme.typography.labelSmall
            )
        }
    )

    Spacer(Modifier.height(Spacing.Item))

    SectionLabel("默认水印图")
    DefaultLogoPicker(
        logoUri = settings.defaultLogoUri,
        onPick = { onPickLogo { uri -> viewModel.updateDefaultLogoUri(uri.toString()) } },
        onClear = { viewModel.updateDefaultLogoUri(null) }
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel("默认水印透明度")
    LabeledSlider(
        label = "新图片的初始透明度",
        value = settings.defaultAlpha,
        onValueChange = viewModel::updateDefaultAlpha,
        valueRange = 0.01f..1f,
        valueText = "${(settings.defaultAlpha * 100).roundToInt()}%"
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    // ---- 隐藏水印默认值 ----
    SectionLabel("隐藏水印")
    ToggleRow(
        label = "默认启用 DCT 频域嵌入",
        checked = settings.invisibleDctByDefault,
        onCheckedChange = { viewModel.toggleInvisibleDctByDefault() }
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "开启后，在编辑页打开「隐藏水印」时会自动勾上 DCT。\n" +
            "DCT 能抵抗 JPEG 重压缩，但会轻微影响画质 —— " +
            "这只是初始值，编辑页里仍可单独关闭。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 「导出」分类：决定文件怎么存。 */
@Composable
private fun ExportCategory(
    settings: ExportSettings,
    viewModel: SettingsViewModel
) {
    SectionLabel("导出格式")
    SegmentedControl(
        options = listOf(
            ExportFormat.PNG to "PNG（无损）",
            ExportFormat.JPEG to "JPEG"
        ),
        selected = settings.format,
        onSelect = viewModel::updateFormat
    )

    if (settings.format == ExportFormat.JPEG) {
        Spacer(Modifier.height(Spacing.Item))
        LabeledSlider(
            label = "JPEG 质量",
            value = settings.jpegQuality.toFloat(),
            onValueChange = { viewModel.updateJpegQuality(it.roundToInt()) },
            valueRange = 60f..100f,
            valueText = "${settings.jpegQuality}"
        )
    }

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel("文件命名与输出")
    var fileName by remember(settings.fileNameTemplate) {
        mutableStateOf(settings.fileNameTemplate)
    }
    OutlinedTextField(
        value = fileName,
        onValueChange = {
            fileName = it
            viewModel.updateFileNameTemplate(it)
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
        onCheckedChange = { viewModel.togglePreserveExif() }
    )
}

/** 「其他」分类：预设统计 + 更新说明 + 关于入口。 */
@Composable
private fun MiscCategory(
    presetCount: Int,
    onOpenAbout: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    SectionLabel("水印预设")
    Text(
        text = "已保存 $presetCount 个预设。\n" +
            "在首页右上角「书签」图标中管理。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(Spacing.Item))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(Spacing.Item))

    SectionLabel("应用信息")

    // 更新说明入口
    NavRow(
        title = "更新说明",
        subtitle = "查看各版本的变更记录",
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
        title = "关于水印盒",
        subtitle = "版本 ${BuildConfig.VERSION_NAME} · 制作人",
        onClick = onOpenAbout
    )
}

/**
 * 设置页里的「可点击跳转行」。
 *
 * 抽出来是因为「更新说明」和「关于」长得必须一样 ——
 * 它们在同一个分组里，样式不一致会显得很随意。
 */
@Composable
private fun NavRow(
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
private fun DefaultLogoPicker(
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
                Text(
                    text = "默认水印图",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (logoUri == null) {
                        "未设置（切换到图片水印时需手动选图）"
                    } else {
                        "已设置 · 编辑页将自动载入"
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
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(4.dp))
            }
            Button(
                onClick = onPick,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(if (logoUri == null) "选择" else "更换")
            }
        }
    }
}