package com.watermarkbox.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.data.model.AppCredits
import com.watermarkbox.data.repository.CreditsRepository
import com.watermarkbox.data.model.AppLicense
import com.watermarkbox.data.model.Credit
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.theme.Spacing
import androidx.core.net.toUri

/**
 * 关于页。
 *
 * 从设置页的「关于」入口进入，集中展示应用身份信息：
 * 版本、制作人（含主创头像与 QQ）、开源许可。
 *
 * ## 为什么独立成页
 *
 * 原先这些内容内联在设置页底部，把设置页撑得很长，
 * 且「关于」是低频只读信息，和上面的可调参数混在一起没有好处。
 * 独立后：设置页只放**会改变行为的设置**，关于页只放**身份信息**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var copiedQq by remember { mutableStateOf<String?>(null) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    // ---- 主创资料：运行时从 GitHub 取（头像 + 昵称）----
    //
    // 为什么不在打包时写死：换了头像、改了昵称，写死就得等下一个版本才更新。
    // 失败一律回退到内置资源 —— 离线 / 限流 / 接口变更都不会开天窗。
    val leadCredit = AppCredits.lead
    val creditsRepository = remember(leadCredit.githubUser) {
        leadCredit.githubUser?.let { CreditsRepository(it) }
    }
    var onlineLeadName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(creditsRepository) {
        onlineLeadName = creditsRepository?.fetchProfile()?.displayName
    }

    /** 主创显示名：在线取到的优先，取不到用内置的。 */
    val leadDisplayName = onlineLeadName ?: leadCredit.name

    // 预先取好文案：LaunchedEffect 的挂起块里不能调用 @Composable 函数
    val qqCopiedMessage = stringResource(R.string.about_qq_copied, copiedQq.orEmpty())

    // 复制 QQ 后给个反馈
    LaunchedEffect(copiedQq) {
        val qq = copiedQq ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(qqCopiedMessage)
        copiedQq = null
    }

    // 其它复制/跳转的通用提示
    LaunchedEffect(copiedLabel) {
        val msg = copiedLabel ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        copiedLabel = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), fontWeight = FontWeight.SemiBold) },
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.PageHorizontal)
        ) {
            Spacer(Modifier.height(Spacing.Item))

            // ---- 应用标识 ----
            AppIdentityHeader(onCheckUpdate = onCheckUpdate)

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // 预先取好许可相关的文案：点击回调里不是 @Composable 上下文
            val repoOpenedMsg = stringResource(R.string.about_repo_opened)
            val browserFailedMsg = stringResource(R.string.about_browser_failed)
            val repoLabel = stringResource(R.string.about_license_repo)
            val licenseLabel = stringResource(R.string.about_license_url)

            // ---- 制作人 ----
            SectionLabel(stringResource(R.string.about_section_credits))
            AppCredits.entries.forEach { credit ->
                CreditCard(
                    credit = credit,
                    // 主创的昵称与头像来自在线档案；取不到就是内置的那份
                    displayName = if (credit.isLead) leadDisplayName else credit.name,
                    avatarUrl = if (credit.isLead) creditsRepository?.avatarUrl else null,
                    onCopyQq = { qq ->
                        copyTextToClipboard(context, label = "QQ", text = qq)
                        copiedQq = qq
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 本应用许可 ----
            SectionLabel(stringResource(R.string.about_license_section))
            LicenseBanner(
                onOpenRepo = {
                    if (openUrlExternally(context, AppLicense.REPOSITORY_URL)) {
                        copiedLabel = repoOpenedMsg
                    } else {
                        copyTextToClipboard(context, label = repoLabel, text = AppLicense.REPOSITORY_URL)
                        copiedLabel = browserFailedMsg
                    }
                },
                onOpenLicense = {
                    if (!openUrlExternally(context, AppLicense.LICENSE_URL)) {
                        copyTextToClipboard(context, label = licenseLabel, text = AppLicense.LICENSE_URL)
                        copiedLabel = browserFailedMsg
                    }
                }
            )

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 第三方开源组件 ----
            SectionLabel(stringResource(R.string.about_third_party))
            Text(
                text = stringResource(R.string.about_third_party_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LicenseList(
                listOf(
                    "Jetpack Compose" to "Apache-2.0",
                    "AndroidX" to "Apache-2.0",
                    "Kotlin & Coroutines" to "Apache-2.0",
                    "kotlinx.serialization" to "Apache-2.0",
                    "Coil" to "Apache-2.0"
                )
            )

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 版权 ----
            SectionLabel(stringResource(R.string.about_copyright_title))
            Text(
                text = stringResource(R.string.about_copyright, leadDisplayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.about_license_notice,
                    AppLicense.LICENSE_SPDX
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 构建信息（页脚）----
            BuildInfoFooter()

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 页脚：**构建类型 + 实际安装包名**。
 *
 * ## 为什么值得显示
 *
 * 同一个应用常有多个可安装产物：debug（便于抓日志）与 release（正式）。
 * 两者图标一样、界面几乎一样 —— 反馈问题时第一句往往是
 * 「你装的是哪个包」。与其来回问，不如直接写在关于页最底部。
 *
 * 顺带显示 **实际安装包名**（`BuildConfig.APPLICATION_ID`）：
 * 它比版本号更能定位到具体产物（不同后缀 = 不同包，可以并存安装）。
 *
 * ## 为什么放在整页最底部
 *
 * 这是「需要时才查」的信息，不该挤在显眼处抢注意力；
 * 放在版权之后，翻到底就能看到。
 */
@Composable
private fun BuildInfoFooter() {
    val isDebug = BuildConfig.DEBUG

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDebug) {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(
                    if (isDebug) R.string.about_build_debug else R.string.about_build_release
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (isDebug) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = BuildConfig.APPLICATION_ID,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 顶部应用标识：名称 + 版本 + 一句话简介。
 */
@Composable
private fun AppIdentityHeader(onCheckUpdate: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 应用图标（与启动器图标同源，见 branding/ic_launcher.svg）
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = stringResource(R.string.a11y_app_logo),
                modifier = Modifier.size(72.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        // 第二行固定显示英文名：中文界面下它是「英文副标题」，
        // 英文界面下与主标题相同，去掉会造成布局高度跳动。
        if (stringResource(R.string.about_app_name) != stringResource(R.string.about_app_english)) {
            Text(
                text = stringResource(R.string.about_app_english),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        // 版本徽章即「检查更新」入口：点它直接查新版本。
        // 关于页是用户查看版本号的地方，顺手能查更新最符合直觉
        // （与设置页的「检查更新」构成双入口）。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .clickable(onClick = onCheckUpdate)
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
