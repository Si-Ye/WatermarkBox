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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.data.model.AppCredits
import com.watermarkbox.data.model.AppLicense
import com.watermarkbox.data.model.Credit
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.theme.Spacing

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var copiedQq by remember { mutableStateOf<String?>(null) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    // 复制 QQ 后给个反馈
    LaunchedEffect(copiedQq) {
        val qq = copiedQq ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("已复制 QQ：$qq")
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
                title = { Text("关于", fontWeight = FontWeight.SemiBold) },
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
            AppIdentityHeader()

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 制作人 ----
            SectionLabel("制作人")
            AppCredits.entries.forEach { credit ->
                CreditCard(
                    credit = credit,
                    onCopyQq = { qq ->
                        copyToClipboard(context, label = "QQ", text = qq)
                        copiedQq = qq
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 本应用许可 ----
            SectionLabel("本应用许可")
            LicenseBanner(
                onOpenRepo = {
                    if (openUrl(context, AppLicense.REPOSITORY_URL)) {
                        copiedLabel = "已打开仓库页面"
                    } else {
                        copyToClipboard(context, label = "仓库地址", text = AppLicense.REPOSITORY_URL)
                        copiedLabel = "无法打开浏览器，已复制地址"
                    }
                },
                onOpenLicense = {
                    if (!openUrl(context, AppLicense.LICENSE_URL)) {
                        copyToClipboard(context, label = "许可证地址", text = AppLicense.LICENSE_URL)
                        copiedLabel = "无法打开浏览器，已复制地址"
                    }
                }
            )

            Spacer(Modifier.height(Spacing.Item))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(Spacing.Item))

            // ---- 第三方开源组件 ----
            SectionLabel("第三方组件")
            Text(
                text = "本应用基于以下开源项目构建：",
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
            SectionLabel("版权")
            Text(
                text = "© 2026 ${AppCredits.lead.name} 保留所有权利。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "本项目采用 ${AppLicense.LICENSE_SPDX} 许可证开源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 顶部应用标识：名称 + 版本 + 一句话简介。
 */
@Composable
private fun AppIdentityHeader() {
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
                contentDescription = "水印盒图标",
                modifier = Modifier.size(72.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "水印盒",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "WatermarkBox",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "轻松添加图片水印 · 支持隐藏水印溯源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 单个制作人卡片：头像 + 名字 + 角色 + QQ。
 *
 * 头像用资源**名称**解析（`AppCredits.LEAD_AVATAR_NAME`），解析不到时
 * 退回一个占位图标 —— 这样即使资源没打包进来也不会崩，
 * 只是少张图。相比把 `R.drawable.xxx` 写死在 model 层，
 * 这种方式让 `AppCredits` 保持为纯数据、可单测。
 */
@Composable
private fun CreditCard(
    credit: Credit,
    onCopyQq: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (credit.isLead) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 头像 ----
            Avatar(credit)

            Spacer(Modifier.width(12.dp))

            // ---- 名字 / 角色 / QQ ----
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (credit.isLead) {
                        Text(
                            text = "★",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = credit.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (credit.isLead) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                if (credit.role.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = credit.role,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                credit.qq?.let { qq ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "QQ：$qq",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ---- 复制 QQ ----
            credit.qq?.let { qq ->
                IconButton(onClick = { onCopyQq(qq) }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制 QQ",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 圆形头像。
 *
 * 资源按名称解析：`siye_avatar` → `R.drawable.siye_avatar`。
 * 解析不到就用 `Person` 占位图标。
 */
@Composable
private fun Avatar(credit: Credit) {
    val context = LocalContext.current
    val resId = if (credit.isLead) {
        remember(context) {
            context.resources.getIdentifier(
                AppCredits.LEAD_AVATAR_NAME,
                "drawable",
                context.packageName
            ).takeIf { it != 0 }
        }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (credit.isLead) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        if (resId != null) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = resId),
                contentDescription = "${credit.name} 的头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 本应用许可卡片。
 *
 * 展示许可证名称、一句话说明、源码仓库地址。
 * 仓库地址与许可证全文均可点击 —— 能打开浏览器就跳转，
 * 打不开（无浏览器 / 无网络）则退化为复制到剪贴板，保证信息拿得到。
 */
@Composable
private fun LicenseBanner(
    onOpenRepo: () -> Unit,
    onOpenLicense: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 许可证名称
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = AppLicense.LICENSE_NAME,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = AppLicense.LICENSE_SUMMARY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            // 源码仓库
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenRepo)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "源码仓库",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = AppLicense.REPOSITORY_DISPLAY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "打开仓库",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 许可证全文
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenLicense)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Gavel,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "许可证全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "apache.org/licenses/LICENSE-2.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "打开许可证",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 开源许可条目列表。 */
@Composable
private fun LicenseList(items: List<Pair<String, String>>) {
    Column {
        items.forEach { (name, license) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(16.dp)
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 复制文本到剪贴板，并给系统一个"已复制"的提示。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 尝试用系统浏览器打开链接。
 *
 * @return true 表示成功交给某个 Activity 处理；false 表示设备上没有可用的浏览器，
 *         调用方应退化为「复制链接」。
 */
private fun openUrl(context: Context, url: String): Boolean {
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
