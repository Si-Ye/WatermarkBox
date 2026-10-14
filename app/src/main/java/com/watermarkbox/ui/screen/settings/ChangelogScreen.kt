package com.watermarkbox.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.data.model.ChangeKind
import com.watermarkbox.data.model.ChangelogEntry
import com.watermarkbox.data.model.ReleaseHistory
import com.watermarkbox.data.model.ReleaseNote
import com.watermarkbox.data.model.ReleaseType
import com.watermarkbox.data.model.totalChanges
import com.watermarkbox.ui.theme.Spacing

/**
 * 更新说明页。
 *
 * 按版本倒序列出全部变更，最新的在最上面。
 *
 * ## 信息层级
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 1.4.0   [大版本]   2026-10-12 │  ← 版本 + 徽章 + 日期
 * │ 预设可独立编辑 · 批量页大改      │  ← 主题一句话
 * ├──────────────────────────────┤
 * │ ＋ 首页右上角新增「水印预设」入口  │  ← 按性质带符号
 * │ ✓ 修复保存同名预设时静默覆盖…    │
 * │ ↑ 设置页顶部新增分类菜单…        │
 * └──────────────────────────────┘
 * ```
 *
 * ## 当前版本会被标出来
 *
 * 用户打开这页最想知道的往往是「我现在是哪个版本」，
 * 所以给当前版本单独加一个「当前」标记，不用去别处对照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangelogViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 当前界面语言：决定从远端文档里取中文还是英文那一段。
    // 与 TutorialScreen 用同一套判断（"en" 之外一律中文），避免两处规则不一致。
    val lang = LocalConfiguration.current.locales[0].language
    // 语言变化时重新解析（内部不会重复联网，见 ChangelogViewModel.load）
    LaunchedEffect(lang) { viewModel.load(lang) }

    // ---- 三级数据源的最后一级 ----
    //
    // 远端与本地缓存都拿不到时，回退到随 APK 发布的内置说明。
    // 它一定存在（不用联网），只是可能滞后于最新版本。
    //
    // 这个回退放在 UI 层而不是 ViewModel：内置数据用的是 `@StringRes`，
    // 必须在 Composable 上下文里经 `stringResource` 解析。
    val entries = state.entries.ifEmpty {
        ReleaseHistory.releases.map { it.toEntry() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.PageHorizontal,
                end = Spacing.PageHorizontal,
                top = Spacing.Item,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Item)
        ) {
            // ---- 顶部概览 ----
            item {
                SummaryCard(entries)
            }

            // ---- 各版本 ----
            items(entries, key = { it.version }) { entry ->
                ReleaseCard(
                    entry = entry,
                    isCurrent = entry.version == BuildConfig.VERSION_NAME
                )
            }
        }
    }
}

/** 顶部概览：当前版本 + 总版本数 + 总变更数。 */
@Composable
private fun SummaryCard(entries: List<ChangelogEntry>) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.changelog_current_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.changelog_summary,
                    entries.size,
                    entries.totalChanges
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/** 单个版本的卡片。 */
@Composable
private fun ReleaseCard(entry: ChangelogEntry, isCurrent: Boolean) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // ---- 标题行：版本号 + 徽章 + 日期 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                VersionBadge(entry.type)
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    CurrentBadge()
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 主题 ----
            //
            // 文案已是**解析好的字符串**（远端 JSON 按语言选过一份，
            // 内置数据则在 toEntry() 里用 stringResource 解析过）——
            // 这里不再需要 stringResource。
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ---- 变更条目 ----
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(10.dp))

            entry.changes.forEach { change ->
                ChangeRow(change)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 单条变更：彩色圆点符号 + 文字。 */
@Composable
private fun ChangeRow(change: ChangelogEntry.Item) {
    val color = when (change.kind) {
        ChangeKind.ADDED -> MaterialTheme.colorScheme.primary
        ChangeKind.FIXED -> MaterialTheme.colorScheme.tertiary
        ChangeKind.IMPROVED -> MaterialTheme.colorScheme.secondary
        ChangeKind.CHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        // 符号放在圆形底色里，比纯文字更醒目
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = change.kind.symbol,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = change.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 内置更新记录 → UI 模型。
 *
 * ## 为什么需要它
 *
 * 内置数据（[ReleaseHistory]）用的是 `@StringRes` 资源 ID，
 * 而远端 JSON 给的已经是**解析好的字符串**。两者要渲染成同一种东西，
 * 就必须在这里把资源 ID 解掉 —— 这需要 Composable 上下文，
 * 所以本函数也只能是 `@Composable`。
 *
 * ## 它是**兜底路径**
 *
 * 正常情况（能联网或有过缓存）根本不会走到这里。
 * 只有在「刚装好、从未联网成功过」时才用内置数据 ——
 * 那份数据随 APK 发布，一定存在，只是可能滞后于最新版本。
 */
@Composable
private fun ReleaseNote.toEntry(): ChangelogEntry = ChangelogEntry(
    version = version,
    date = date,
    type = type,
    title = stringResource(titleRes),
    changes = changes.map { change ->
        ChangelogEntry.Item(
            kind = change.kind,
            text = stringResource(change.textRes)
        )
    }
)

/** 「大版本 / 小版本」徽章。 */
@Composable
private fun VersionBadge(type: ReleaseType) {
    val (bg, fg) = when (type) {
        ReleaseType.MAJOR -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to
            MaterialTheme.colorScheme.primary
        ReleaseType.MINOR -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) to
            MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(type.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 「当前」徽章：描边样式，与实心的版本徽章区分开。 */
@Composable
private fun CurrentBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.common_current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}