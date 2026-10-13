package com.watermarkbox.ui.screen.settings

/**
 * 关于页 —— **制作人区块**。
 *
 * 从 `AboutScreen.kt` 拆出（原本三块内容挤在一个 749 行的文件里）。
 * 这一块的独立价值在于：它的数据来自 `AppCredits` + 在线档案，
 * 改动频率与「许可 / 构建信息」完全不同。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.watermarkbox.R
import com.watermarkbox.data.model.AppCredits
import com.watermarkbox.data.model.Credit


/**
 * 单个制作人卡片：头像 + 名字 + 角色 + QQ。
 *
 * 头像资源 id 由 [AppCredits] 直接持有（`R.drawable.siye_avatar`）；
 * 取不到时退回一个占位图标 —— 即使资源没打包进来也不会崩，只是少张图。
 *
 * 早先这里用的是「按名字反射查表」（`AppCredits.LEAD_AVATAR_NAME`）。
 * 那种做法让 lint 看不见资源被引用，清理未使用资源时被误删过 ——
 * 详见 [Avatar] 的注释。
 */
@Composable
internal fun CreditCard(
    credit: Credit,
    displayName: String,
    avatarUrl: String?,
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
            Avatar(credit, avatarUrl)

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
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (credit.isLead) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                if (credit.role.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = localizedCreditRole(credit.role),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                credit.qq?.let { qq ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_qq_format, qq),
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
                        contentDescription = stringResource(R.string.about_copy_qq),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}


/**
 * 把 [Credit.role] 里的中文角色名映射到本地化文案。
 *
 * `AppCredits` 是纯数据层（可单测），它的 role 是**中文原文**。
 * 这里做一次映射，而不是让数据层依赖 Android 资源 ——
 * 保持数据层与平台无关，是当初把它拆出来的初衷。
 *
 * 认不出的角色原样返回：将来新增角色时，界面不会空白。
 */
@Composable
internal fun localizedCreditRole(role: String): String {
    return when (role) {
        "主创" -> stringResource(R.string.credit_role_lead)
        "开发协助" -> stringResource(R.string.credit_role_assist)
        else -> role
    }
}


/**
 * 圆形头像。
 *
 * ## 为什么改成直接读 [Credit.avatarRes]（曾经按名字反射）
 *
 * 早先用 `resources.getIdentifier("siye_avatar", …)` 按名称查表 ——
 * 代价很实在：lint 看不见这条引用，清理「未使用资源」时把头像文件删了，
 * 头像悄悄变成占位图标；而且反射查表在构建期无法校验。
 *
 * 现在资源 id 由 [AppCredits] 直接持有：lint 看得见、删不掉，
 * 写错编译期就报。取不到 id 时仍退回 `Person` 占位图标，保证不崩。
 *
 * ## 在线头像
 *
 * 传入 [avatarUrl]（来自 GitHub）时优先显示在线图，由 Coil 下载并磁盘缓存；
 * 加载中与失败都显示内置的那张 —— 换了头像不必发版就能跟上，离线也不开天窗。
 */
@Composable
internal fun Avatar(credit: Credit, avatarUrl: String? = null) {
    val resId = credit.avatarRes
    // 内置图同时充当「占位」与「兜底」：在线图还没到就先显示它，失败也回退到它
    val fallback = resId?.let { painterResource(it) }

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
        when {
            // 在线头像：Coil 负责下载与磁盘缓存（遵循 GitHub 的缓存头），
            // 加载中走 placeholder、失败走 error —— 两者都是内置那张图
            avatarUrl != null -> AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.a11y_avatar, credit.name),
                contentScale = ContentScale.Crop,
                placeholder = fallback,
                error = fallback,
                fallback = fallback,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )

            fallback != null -> androidx.compose.foundation.Image(
                painter = fallback,
                contentDescription = stringResource(R.string.a11y_avatar, credit.name),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )

            else -> Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
