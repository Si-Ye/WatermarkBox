package com.watermarkbox.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
            Spacer(Modifier.height(64.dp))

            // ---- 品牌头部 ----
            AppLogo()

            Spacer(Modifier.height(20.dp))

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
 * - 图标自带深蓝背景，因此外层只需圆角裁切
 * - 用矢量图而非位图，任意尺寸都清晰
 */
@Composable
private fun AppLogo() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = stringResource(R.string.a11y_app_icon),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
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
            .height(104.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(30.dp)
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