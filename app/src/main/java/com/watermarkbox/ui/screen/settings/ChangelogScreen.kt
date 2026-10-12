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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.BuildConfig
import com.watermarkbox.data.model.ChangeKind
import com.watermarkbox.data.model.ReleaseHistory
import com.watermarkbox.data.model.ReleaseNote
import com.watermarkbox.data.model.ReleaseType
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
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("更新说明", fontWeight = FontWeight.SemiBold) },
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
                SummaryCard()
            }

            // ---- 各版本 ----
            items(ReleaseHistory.releases, key = { it.version }) { note ->
                ReleaseCard(
                    note = note,
                    isCurrent = note.version == BuildConfig.VERSION_NAME
                )
            }
        }
    }
}

/** 顶部概览：当前版本 + 总版本数 + 总变更数。 */
@Composable
private fun SummaryCard() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "当前版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 ${ReleaseHistory.releases.size} 个版本 · " +
                    "${ReleaseHistory.totalChanges} 项改动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/** 单个版本的卡片。 */
@Composable
private fun ReleaseCard(note: ReleaseNote, isCurrent: Boolean) {
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
                    text = note.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                VersionBadge(note.type)
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    CurrentBadge()
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = note.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 主题 ----
            Spacer(Modifier.height(6.dp))
            Text(
                text = note.title,
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

            note.changes.forEach { change ->
                ChangeRow(change)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 单条变更：彩色圆点符号 + 文字。 */
@Composable
private fun ChangeRow(change: ReleaseNote.Change) {
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
            text = type.label,
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
            text = "当前",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}