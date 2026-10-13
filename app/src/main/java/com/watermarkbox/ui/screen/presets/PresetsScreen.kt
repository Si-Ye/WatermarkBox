package com.watermarkbox.ui.screen.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.ui.component.InfoDot
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.PresetFileCodec
import com.watermarkbox.util.resolve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页「水印预设」页。
 *
 * ## 定位
 *
 * 与编辑页里的「预设对话框」分工：
 * - **编辑页**：把当前配置**存为**预设 / 快速切换
 * - **本页**（首页入口）：**管理** —— 浏览、重命名、删除
 *
 * ## 为什么这里不能「应用」预设
 *
 * 应用预设需要一张图片（预设描述的是"水印长什么样"，
 * 不是"处理哪张图"）。而首页没有已选图片，
 * 在这里发起选图会把流程做得绕（选图 → 跳编辑页 → 应用）。
 *
 * 因此本页只做管理。要用某个预设，请：
 * 选图片 → 进编辑页 → 点顶部「书签」→ 选择预设。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel,
    onBack: () -> Unit,
    onEditPreset: (WatermarkPreset) -> Unit = {},
    onExportAll: () -> Unit = {},
    onExportOne: (WatermarkPreset) -> Unit = {},
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作反馈
    // 先在 Composable 上下文里解析好文本，再交给 LaunchedEffect
    val messageText = state.message?.resolve()
    LaunchedEffect(state.message) {
        messageText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.presets_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(2.dp))
                        // 页面简介收进标题旁的 ⓘ —— 它解释的是「这个页面能干什么」，
                        // 挂在标题边上比塞在列表上方更贴切，也省一行高度
                        InfoDot(
                            title = stringResource(R.string.presets_title),
                            text = stringResource(R.string.presets_tip)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // 「更多」菜单：导入 / 导出全部
                    //
                    // 为什么放在溢出菜单而不是两个并列按钮：
                    // 这两个操作都不常用（备份/迁移时才用），
                    // 占着标题栏会挤压标题空间，也让日常操作（编辑/删除）显得不突出。
                    var menuOpen by remember { mutableStateOf(false) }

                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = !state.transferBusy
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.presets_more)
                            )
                        }

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.presets_import)) },
                                onClick = {
                                    menuOpen = false
                                    onImport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.presets_export_all)) },
                                onClick = {
                                    menuOpen = false
                                    onExportAll()
                                },
                                // 没有预设时导出无意义，直接禁用比点了再提示更好
                                enabled = state.presets.isNotEmpty()
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.PageHorizontal)
        ) {
            Spacer(Modifier.height(Spacing.Item))

            when {
                // 尚未加载完成：不显示"空"文案，避免误导
                !state.loaded -> {
                    Text(
                        text = stringResource(R.string.presets_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.presets.isEmpty() -> {
                    EmptyHint()
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(state.presets, key = { it.id }) { preset ->
                            // 增删/排序时让卡片自己补间滑入滑出，而不是「啪」地出现或消失。
                            // animateItem 只作用于本项的摆放（layer + 位置），不触发兄弟项重排动画。
                            Box(modifier = Modifier.animateItem()) {
                                PresetCard(
                                    preset = preset,
                                    onEdit = { onEditPreset(preset) },
                                    onExport = { onExportOne(preset) },
                                    onDelete = { viewModel.requestDelete(preset) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    // ---- 删除确认 ----
    state.pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(stringResource(R.string.preset_delete_title)) },
            text = { Text(stringResource(R.string.preset_delete_message, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDelete() }
                ) {
                    Text(stringResource(R.string.preset_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // ---- 导入结果 ----
    state.importOutcome?.let { outcome ->
        when (outcome) {
            is PresetImportOutcome.Success -> AlertDialog(
                onDismissRequest = { viewModel.dismissImportOutcome() },
                title = { Text(stringResource(R.string.presets_import_done_title)) },
                text = {
                    Column {
                        Text(
                            text = if (outcome.renamed > 0) {
                                pluralStringResource(
                                    R.plurals.presets_import_done_message_renamed,
                                    outcome.imported,
                                    outcome.imported,
                                    outcome.renamed
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.presets_import_done_message,
                                    outcome.imported,
                                    outcome.imported
                                )
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.presets_import_safe_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 只有真的导入了图片预设才提这句 ——
                        // 否则每次导入都弹一句无关的警告，用户很快就学会无视它，
                        // 那这条提示在需要时也就失效了
                        if (outcome.imagePresets > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.presets_import_logo_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissImportOutcome() }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )

            is PresetImportOutcome.Failure -> AlertDialog(
                onDismissRequest = { viewModel.dismissImportOutcome() },
                title = { Text(stringResource(R.string.presets_import_failed_title)) },
                text = {
                    Column {
                        Text(importFailureText(outcome.reason))
                        // 技术细节只在调试包显示：普通用户看到
                        // "SerializationException: Unexpected JSON token"
                        // 只会更困惑，而这句话对开发者排查很有用
                        if (BuildConfig.DEBUG && !outcome.detail.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = outcome.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissImportOutcome() }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }
    }
}

/**
 * 把导入失败原因映射为用户可读的文案。
 *
 * 每种原因都对应一句**能指导下一步行动**的话，而不是笼统的"导入失败"：
 * 「文件是空的」→ 重新导出一次；「不是水印盒的文件」→ 选错文件了；
 * 「版本过新」→ 升级应用。
 */
@Composable
private fun importFailureText(reason: PresetFileCodec.FailureReason): String = when (reason) {
    PresetFileCodec.FailureReason.EMPTY -> stringResource(R.string.presets_import_fail_empty)
    PresetFileCodec.FailureReason.TOO_LARGE -> stringResource(R.string.presets_import_fail_too_large)
    PresetFileCodec.FailureReason.MALFORMED -> stringResource(R.string.presets_import_fail_malformed)
    PresetFileCodec.FailureReason.NOT_OURS -> stringResource(R.string.presets_import_fail_not_ours)
    PresetFileCodec.FailureReason.VERSION_TOO_NEW -> stringResource(R.string.presets_import_fail_version)
    PresetFileCodec.FailureReason.NO_PRESETS -> stringResource(R.string.presets_import_fail_no_presets)
}

@Composable
private fun EmptyHint() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.presets_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.presets_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单个预设卡片：展示信息 + 右侧 导出 / 重命名 / 删除。
 *
 * 卡片**不可整体点击** —— 首页没有图片，无法"应用"预设。
 * 若做成可点击但没有反馈，用户会以为坏了。
 *
 * 三个图标都用触摸区 40dp（而非默认 48dp）以节省横向空间 ——
 * 360dp 宽的屏幕上还要留给名称和类型徽章。
 */
@Composable
private fun PresetCard(
    preset: WatermarkPreset,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 名称 + 摘要 + 类型徽章
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    TypeBadge(preset.type)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSummary(preset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 操作：编辑 / 导出 / 删除
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.presets_edit_action),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.presets_export_one),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
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

/** 类型小徽章：文字 / 图片。 */
@Composable
private fun TypeBadge(type: WatermarkType) {
    val (label, color) = when (type) {
        WatermarkType.TEXT -> stringResource(R.string.presets_type_text) to MaterialTheme.colorScheme.primary
        WatermarkType.IMAGE -> stringResource(R.string.presets_type_image) to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 生成预设摘要，便于用户辨识。
 *
 * 标为 @Composable 是因为要调用 [stringResource] 取当前语言的文案。
 */
@Composable
private fun buildSummary(preset: WatermarkPreset): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(preset.createdAt))
    return when (preset.type) {
        WatermarkType.TEXT -> {
            val text = preset.textStyle.text.replace("\n", " ").take(16)
            stringResource(R.string.preset_summary_text, text, time)
        }
        WatermarkType.IMAGE ->
            stringResource(R.string.presets_logo_width, (preset.imageStyle.widthPercent * 100).toInt(), time)
    }
}