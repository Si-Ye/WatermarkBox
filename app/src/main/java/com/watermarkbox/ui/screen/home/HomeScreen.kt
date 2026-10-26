package com.watermarkbox.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
 * 首页布局常量。
 *
 * ⚠️ 这里**只放与屏宽/屏高无关的边界值**。具体的 logo 尺寸、间距、卡片高度
 * 都由 [homeMetrics] 按实际可用空间算出来 —— 不要在 Composable 里再写死像素，
 * 那正是「小屏第三张卡片被压扁」的成因。
 */
internal object HomeDims {
    /**
     * 单张入口卡片的最小高度。
     *
     * 88dp 是**可用性下限**（Material 触控目标下限是 48dp，这里是两倍左右）：
     * 无论屏幕多矮，卡片都不会比这个更扁，保证拇指轻松点中。
     */
    val CardMin = 88.dp

    /**
     * 单张入口卡片的最大高度。
     *
     * 上限的作用是「不让卡片为了填满屏幕而怪异地拉长」。
     * 超过 132dp 后，多出来的高度对可读性没有帮助，只会让页面像被拉伸。
     */
    val CardMax = 132.dp

    /** 平板/横屏时三卡并排，卡片改用「竖版」排版，常规高度。 */
    val CardWide = 176.dp

    /** 三卡并排时的最小高度（横屏手机只剩这么点纵向空间时的下限）。 */
    val CardWideMin = 140.dp

    /**
     * 三卡并排的触发宽度。
     *
     * 600dp 是 Material 3 的 compact/medium 分界；竖屏平板（≥600dp）与
     * 手机横屏（短边 411dp、长边 730~914dp）都会落到并排分支。
     */
    val WideBreakpoint = 600.dp

    /**
     * 内容最大宽度。
     *
     * 平板横屏（1280dp 宽）若让三张卡片铺满整行，每张会被拉成一条超长的横条。
     * 限到 900dp 后保持卡片比例，两侧留白。
     */
    val MaxContentWidth = 900.dp

    /** 品牌标题块（headlineSmall 单行 + 余量）。 */
    val TitleBlock = 40.dp

    /** 副标题块（6dp 间距 + 单行）。 */
    val TaglineBlock = 26.dp

    /** 页脚一行文字的高度（labelSmall）。 */
    val FooterText = 16.dp

    /** 低于这个高度（典型：手机横屏 411dp）走「紧凑」尺寸。 */
    val ShortHeight = 620.dp

    /** 低于这个高度（手机横屏）走「极紧凑」尺寸：藏页脚、缩 Logo。 */
    val VeryShortHeight = 480.dp
}

/**
 * 首页一次布局所需的全部尺寸，由 [homeMetrics] 从可用空间推导。
 *
 * @property wide 三卡是否并排（平板 / 手机横屏）
 * @property logoSize 品牌 Logo 边长
 * @property gapAfterTopBar 顶栏（设置/预设图标）下方留白
 * @property gapAfterLogo Logo 与标题之间的间距
 * @property showTagline 是否显示副标题（极矮屏直接省掉，优先保住入口卡片）
 * @property showFooter 是否显示页脚（只给「极矮屏 + 并排」让位）
 * @property gapBeforeCards 品牌头部与卡片区之间的留白
 * @property footerGap 卡片区与页脚之间的留白
 * @property bottomSpacer 页脚下方的收尾留白
 * @property cardHeight 三张入口卡片统一的高度
 */
internal data class HomeMetrics(
    val wide: Boolean,
    val logoSize: Dp,
    val gapAfterTopBar: Dp,
    val gapAfterLogo: Dp,
    val showTagline: Boolean,
    val showFooter: Boolean,
    val gapBeforeCards: Dp,
    val footerGap: Dp,
    val bottomSpacer: Dp,
    val cardHeight: Dp
)

/**
 * 按可用空间推导首页尺寸。
 *
 * ## 为什么是「算」而不是「用 weight 分配」
 *
 * 历史 bug：三张卡片用 `heightIn(104..132)`、上下用 `weight` 间隔。
 * Compose 的 `Column` 会用**剩余空间**作为子项的高度上限去测量非 weight 子项，
 * 于是小屏上第三张卡片拿到的上限不足 104dp，`heightIn` 的下限被父约束压掉，
 * 卡片被压成一条扁条 —— 又扁又难点。
 *
 * 现在改成：先扣掉所有固定内容（顶栏留白、Logo、标题、副标题、页脚、各处间距），
 * 剩下的高度**三等分**给卡片（并排时直接吃掉剩余），并夹在各自的下限..上限之间。
 * 卡片因此永远有确定高度，不再受父约束挤压；真的放不下时由外层滚动兜底。
 *
 * ⚠️ [reserved] 里的每一项都必须与 Composable 里实际写出的间距**逐项对应** ——
 * 少算 20dp，第三张卡就会超出屏幕 20dp。改下面的间距时，务必同步改这里。
 *
 * @param width 可用宽度（dp）
 * @param height 可用高度（dp，已扣掉状态栏与顶栏）
 */
internal fun homeMetrics(width: Dp, height: Dp): HomeMetrics {
    val wide = width >= HomeDims.WideBreakpoint
    val short = height < HomeDims.ShortHeight
    val veryShort = height < HomeDims.VeryShortHeight

    val logoSize = when {
        veryShort -> 48.dp
        short -> 64.dp
        height < 780.dp -> 80.dp
        else -> 96.dp
    }

    val gapAfterTopBar = when {
        veryShort -> 4.dp
        short -> 8.dp
        else -> 32.dp
    }
    val gapAfterLogo = when {
        veryShort -> 8.dp
        short -> 12.dp
        else -> 20.dp
    }

    // 高度极小时（如横屏 411dp）先牺牲副标题与页脚，把空间让给三个入口 ——
    // 入口是页面存在的理由，页脚只是装饰。
    val showTagline = height >= 470.dp
    val showFooter = height >= 420.dp

    val gapBeforeCards = when {
        veryShort -> 12.dp
        short -> 20.dp
        else -> 24.dp
    }
    val footerGap = when {
        veryShort -> 12.dp
        short -> 20.dp
        else -> 24.dp
    }
    val bottomSpacer = when {
        veryShort -> 8.dp
        short -> 12.dp
        else -> 24.dp
    }

    val headerBlock = gapAfterTopBar + logoSize + gapAfterLogo + HomeDims.TitleBlock +
        (if (showTagline) HomeDims.TaglineBlock else 0.dp)

    val footerBlock = if (showFooter) footerGap + HomeDims.FooterText else 0.dp

    // 卡片之间的两道间隙 + 头部到卡片、卡片到页脚各一道，共 4 处 Spacing.Item 级别的间距。
    val reserved = headerBlock + gapBeforeCards + Spacing.Item * 2 + footerBlock + bottomSpacer

    val cardHeight = if (wide) {
        (height - reserved).coerceIn(HomeDims.CardWideMin, HomeDims.CardWide)
    } else {
        ((height - reserved) / 3).coerceIn(HomeDims.CardMin, HomeDims.CardMax)
    }

    return HomeMetrics(
        wide = wide,
        logoSize = logoSize,
        gapAfterTopBar = gapAfterTopBar,
        gapAfterLogo = gapAfterLogo,
        showTagline = showTagline,
        showFooter = showFooter,
        gapBeforeCards = gapBeforeCards,
        footerGap = footerGap,
        bottomSpacer = bottomSpacer,
        cardHeight = cardHeight
    )
}

/**
 * 启动页（页面 1 - 图片选择）。
 *
 * 提供三个大入口：
 * - 选择图片 → 进入单张编辑
 * - 批量处理 → 进入批量选择
 * - 检测水印 → 进入检测
 *
 * ## 适配策略（手机 / 平板 / 横屏）
 *
 * | 条件 | 行为 |
 * |---|---|
 * | 宽度 ≥ 600dp | 三卡**并排**，卡片改竖版（图标在上），内容限宽 900dp 居中 |
 * | 高度 < 620dp | Logo 缩到 64dp、上留白缩到 8dp（横屏/矮屏） |
 * | 高度 < 470dp | 隐藏副标题 |
 * | 其它 | 三卡竖排，高度 = 剩余空间三等分，夹在 88~132dp |
 *
 * 内容放得下时占满整屏并垂直居中；放不下（极端小屏）时整页可滚动，
 * **不会出现被压扁的卡片**。
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
            //
            // 状态栏高度交给 statusBarsPadding()（应用是 edge-to-edge 的），
            // 不再写死 40dp —— 平板的刘海/挖孔/手势条区域高度与手机并不相同。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.Small, vertical = Spacing.Tiny),
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val availableHeight = maxHeight
            val metrics = homeMetrics(width = maxWidth, height = availableHeight)

            // 外层负责滚动兜底，内层负责「放得下就铺满并垂直居中」。
            // 注意内层的 heightIn(min = maxHeight)：内容比屏幕矮时它把容器撑满，
            // Arrangement.Center 才能把内容居中；内容比屏幕高时高度由内容决定，滚动生效。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = HomeDims.MaxContentWidth)
                        .fillMaxWidth()
                        .heightIn(min = availableHeight)
                        .padding(horizontal = Spacing.PageHorizontal),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.height(metrics.gapAfterTopBar))

                    // ---- 品牌头部 ----
                    AppLogo(size = metrics.logoSize)

                    Spacer(Modifier.height(metrics.gapAfterLogo))

                    Text(
                        text = stringResource(R.string.home_brand_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (metrics.showTagline) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.home_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(metrics.gapBeforeCards))

                    // ---- 三个主入口 ----
                    if (metrics.wide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Item)
                        ) {
                            EntryCardStacked(
                                title = stringResource(R.string.home_entry_single_title),
                                subtitle = stringResource(R.string.home_entry_single_subtitle),
                                icon = Icons.Filled.Image,
                                accent = BrandNavy,
                                onClick = onPickSingleImage,
                                height = metrics.cardHeight,
                                modifier = Modifier
                                    .weight(1f)
                                    .targetIf(OnboardingTarget.ENTRY_PICK_IMAGE, onboardingRegistry)
                            )
                            EntryCardStacked(
                                title = stringResource(R.string.home_entry_batch_title),
                                subtitle = stringResource(
                                    R.string.home_entry_batch_subtitle,
                                    BatchLimits.MAX_BATCH_SIZE
                                ),
                                icon = Icons.Filled.Collections,
                                accent = BrandAmber,
                                onClick = onPickBatchImages,
                                height = metrics.cardHeight,
                                modifier = Modifier
                                    .weight(1f)
                                    .targetIf(OnboardingTarget.ENTRY_BATCH, onboardingRegistry)
                            )
                            EntryCardStacked(
                                title = stringResource(R.string.home_entry_detect_title),
                                subtitle = stringResource(R.string.home_entry_detect_subtitle),
                                icon = Icons.Filled.VerifiedUser,
                                accent = BrandNavy,
                                onClick = onOpenDetect,
                                height = metrics.cardHeight,
                                modifier = Modifier
                                    .weight(1f)
                                    .targetIf(OnboardingTarget.ENTRY_DETECT, onboardingRegistry)
                            )
                        }
                    } else {
                        EntryCard(
                            title = stringResource(R.string.home_entry_single_title),
                            subtitle = stringResource(R.string.home_entry_single_subtitle),
                            icon = Icons.Filled.Image,
                            accent = BrandNavy,
                            onClick = onPickSingleImage,
                            height = metrics.cardHeight,
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
                            height = metrics.cardHeight,
                            modifier = Modifier.targetIf(
                                OnboardingTarget.ENTRY_BATCH, onboardingRegistry
                            )
                        )
                        Spacer(Modifier.height(Spacing.Item))
                        EntryCard(
                            title = stringResource(R.string.home_entry_detect_title),
                            subtitle = stringResource(R.string.home_entry_detect_subtitle),
                            icon = Icons.Filled.VerifiedUser,
                            accent = BrandNavy,
                            onClick = onOpenDetect,
                            height = metrics.cardHeight,
                            modifier = Modifier.targetIf(
                                OnboardingTarget.ENTRY_DETECT, onboardingRegistry
                            )
                        )
                    }

                    if (metrics.showFooter) {
                        Spacer(Modifier.height(metrics.footerGap))
                        Text(
                            text = stringResource(R.string.home_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(metrics.bottomSpacer))
                }
            }
        }
    }
}

/**
 * 品牌 Logo —— 与应用图标完全一致的矢量图。
 *
 * 使用 `R.drawable.ic_app_logo`（由 branding/ic_launcher.svg 转换而来），
 * 保证「启动器图标」与「App 内品牌标识」视觉统一。
 *
 * 尺寸由调用方传入：小屏要缩，否则会把入口卡片挤扁。
 */
@Composable
private fun AppLogo(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4)),
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
 * 首页入口卡片（横版）：左图标 + 右文字，整卡可点。
 *
 * 高度由调用方给定（见 [homeMetrics]），**不再用 heightIn 让父约束去捏** ——
 * 那正是卡片被压扁的原因。
 */
@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    height: Dp,
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
            .height(height)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconBadge(icon = icon, accent = accent, contentDescription = title)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 首页入口卡片（竖版）：图标在上、文字居中，用于平板/横屏的三卡并排。
 *
 * 并排时每张卡片宽度只有 240dp 左右，横版图文会挤成一列；
 * 竖版在同样宽度下文字有整行可用，可读性明显更好。
 */
@Composable
private fun EntryCardStacked(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    height: Dp,
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
            .height(height)
    ) {
        // 并排时卡片高度可能被压到 140~160dp（手机横屏）。此时用常规的
        // 56dp 图标 + 双行副标题会超出卡片、把副标题裁掉半行 —— 所以按高度分档。
        val tight = height < 176.dp
        val badgeSize = if (tight) 44.dp else 56.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 12.dp,
                    vertical = if (tight) 10.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IconBadge(
                icon = icon,
                accent = accent,
                contentDescription = title,
                badgeSize = badgeSize
            )
            Spacer(Modifier.height(if (tight) 8.dp else 12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (tight) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 卡片上的圆角图标底 —— 横版与竖版共用，避免两处各调一次色。 */
@Composable
private fun IconBadge(
    icon: ImageVector,
    accent: Color,
    contentDescription: String,
    // 底板边长；图标本身按 0.54 比例缩放（56dp→30dp、44dp→24dp）。
    badgeSize: Dp = 56.dp
) {
    Box(
        modifier = Modifier
            .size(badgeSize)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(badgeSize / 3)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(badgeSize * 0.54f)
        )
    }
}

/** 小屏手机：360×640dp（5 英寸 1080p @ 480dpi 一类的紧凑机）。 */
@Preview(name = "小屏手机 360x640", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeScreenSmallPreview() {
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

/** 主流手机：411×914dp。 */
@Preview(name = "主流手机 411x914", showBackground = true, widthDp = 411, heightDp = 914)
@Composable
private fun HomeScreenRegularPreview() {
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

/** 平板竖屏：800×1280dp（三卡并排）。 */
@Preview(name = "平板竖屏 800x1280", showBackground = true, widthDp = 800, heightDp = 1280)
@Composable
private fun HomeScreenTabletPreview() {
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

/** 手机横屏：914×411dp（三卡并排 + 紧凑头部）。 */
@Preview(name = "手机横屏 914x411", showBackground = true, widthDp = 914, heightDp = 411)
@Composable
private fun HomeScreenLandscapePreview() {
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
