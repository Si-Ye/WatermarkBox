package com.watermarkbox.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.BatchLimits
import com.watermarkbox.ui.onboarding.OnboardingTarget
import com.watermarkbox.ui.onboarding.OnboardingTargetRegistry
import com.watermarkbox.ui.onboarding.targetIf
import com.watermarkbox.ui.theme.BrandAmber
import com.watermarkbox.ui.theme.BrandNavy
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.ui.theme.WatermarkBoxTheme

/**
 * 启动页（页面 1 - 图片选择）。
 *
 * 提供两个大入口：
 * - 选择图片 → 进入单张编辑
 * - 批量处理 → 进入批量选择（阶段三接入，当前为占位）
 */
@Composable
fun HomeScreen(
    onPickSingleImage: () -> Unit,
    onPickBatchImages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetect: () -> Unit,
    onOpenPresets: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 引导目标的注册中心。
     *
     * 传 null 时**不挂任何上报 Modifier** —— 这样 `@Preview` 用默认值
     * 就能渲染，不必造一个 registry（预览里那些回调也永远不会触发）。
     */
    onboardingRegistry: OnboardingTargetRegistry? = null
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // 右上角：预设 + 设置（两个小图标按钮并排）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenPresets,
                    modifier = Modifier.targetIf(OnboardingTarget.ENTRY_PRESETS, onboardingRegistry)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmarks,
                        contentDescription = stringResource(R.string.a11y_presets),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.targetIf(OnboardingTarget.ENTRY_SETTINGS, onboardingRegistry)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.a11y_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.PageHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // ---- 品牌头部 ----
            AppLogo()
            // Logo 从 96dp 收到 84dp：三个入口卡片才是这一页的主角，
            // 品牌头只是「报个到」，不该占掉五分之一屏。
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.home_brand_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // ============ 两个弹性间隔 ============
            //
            // 用 weight 而不是固定高度：卡片数量/字号变化时，
            // 间距会自适应地把内容推向中间。
            //
            // ⚠️ 但 weight 会把「剩余空间」全部吃掉 —— 前提是**其它子项的高度是确定的**。
            // 上面 AppLogo / 文字高度固定，卡片现在也有 min+max 兜住，
            // 所以这里算出来的剩余空间是稳定的，不会出现「某个卡片膨胀」的连锁反应。
            Spacer(Modifier.weight(0.6f))

            // ---- 两个主入口 ----
            EntryCard(
                title = stringResource(R.string.home_entry_single_title),
                subtitle = stringResource(R.string.home_entry_single_subtitle),
                icon = Icons.Filled.Image,
                accent = BrandNavy,
                onClick = onPickSingleImage,
                modifier = Modifier.targetIf(
                    OnboardingTarget.ENTRY_PICK_IMAGE, onboardingRegistry
                )
            )
            Spacer(Modifier.height(Spacing.Item))
            EntryCard(
                title = stringResource(R.string.home_entry_batch_title),
                subtitle = stringResource(
                    R.string.home_entry_batch_subtitle,
                    BatchLimits.MAX_BATCH_SIZE
                ),
                icon = Icons.Filled.Collections,
                accent = BrandAmber,
                onClick = onPickBatchImages,
                modifier = Modifier.targetIf(OnboardingTarget.ENTRY_BATCH, onboardingRegistry)
            )
            Spacer(Modifier.height(Spacing.Item))
            EntryCard(
                title = stringResource(R.string.home_entry_detect_title),
                subtitle = stringResource(R.string.home_entry_detect_subtitle),
                icon = Icons.Filled.VerifiedUser,
                accent = BrandNavy,
                onClick = onOpenDetect,
                modifier = Modifier.targetIf(OnboardingTarget.ENTRY_DETECT, onboardingRegistry)
            )

            Spacer(Modifier.weight(0.8f))

            Text(
                text = stringResource(R.string.home_footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 品牌 Logo —— 与应用图标完全一致的矢量图。
 *
 * 使用 `R.drawable.ic_app_logo`（由 branding/ic_launcher.svg 转换而来），
 * 保证「启动器图标」与「App 内品牌标识」视觉统一。
 *
 * 说明：
 * - 图标**自带**深蓝背景与圆角（见矢量图内的路径），这里不再 clip ——
 *   再裁一次只会把已经圆好的边角又切掉一圈
 * - 用矢量图而非位图，任意尺寸都清晰
 */
@Composable
private fun AppLogo() {
    Image(
        painter = painterResource(R.drawable.ic_app_logo),
        contentDescription = stringResource(R.string.a11y_app_icon),
        contentScale = ContentScale.Crop,
        // 84dp：与「关于页头部」一致。首页的主角是下面三张入口卡片，
        // Logo 只是品牌点缀 —— 96dp 会显得头重脚轻。
        modifier = Modifier.size(84.dp)
    )
}

/**
 * 首页入口卡片：大图标 + 标题 + 副标题，整卡可点。
 */
@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            // 用 heightIn 而不是 height：系统字号调大后卡片能长高，
            // 否则标题 + 副标题会被裁掉（无障碍：不只是读屏，字号也算）
            //
            // ⚠️ min 必须贴近**实际内容高度**，不能拍脑袋给大数。
            //
            // 内容构成：图标 48dp 与「标题 + 4dp + 副标题」两行文字取较大者，
            // 再各加 12dp 上下 padding：
            //     48 + 24 = 72dp      ← 图标 + padding
            //     24 + 4 + 20 + 24 = 72dp  ← 两行文字 + padding
            // 取 **76dp**：比内容略高一点，让三种字形组合看起来一致。
            //
            // 之前给 104dp 时，每张卡片白多出约 28dp —— 三张就多 84dp，
            // 直接把第三个入口挤出屏幕。min 只负责「对齐观感」，
            // 不该承担「撑大」的职责。
            //
            // max 给 2 倍：大字号（无障碍设置里可到 200%）时文字换行也不会被裁。
            .heightIn(min = 76.dp, max = 152.dp)
    ) {
        Row(
            modifier = Modifier
                // ⚠️ 这里必须是 fillMaxHeight 而不是 fillMaxSize / fillMaxWidth：
                //
                // - fillMaxSize  → 撑满宽高。Card 高度不受限时会把高度顶爆
                //                  （曾导致首页只剩一个巨卡的 bug）
                // - fillMaxWidth → 只有内容高。而 Card 被 heightIn(min = 104.dp)
                //                  撑到了 104dp，Row 却只有约 88dp 且贴顶，
                //                  于是文字/图标**整体偏上**，下面留一条空白
                // - fillMaxHeight → 跟着 Card 撑满高度，宽度仍由内容决定 ✓
                //
                // 配合 verticalAlignment = CenterVertically，内容就在
                // 卡片的垂直中线上 —— 这才是「文字居中」的正确解。
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Compose 版背景修饰符封装（避免重复 import）。 */
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    WatermarkBoxTheme {
        HomeScreen(
            onPickSingleImage = {},
            onPickBatchImages = {},
            onOpenSettings = {},
            onOpenDetect = {},
            onOpenPresets = {}
        )
    }
}