package com.watermarkbox.ui.screen.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.ui.theme.Spacing

/** 引导的一页。 */
private data class OnboardingPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int
)

/**
 * 新手引导。
 *
 * ## 设计取舍
 *
 * - **4 页封顶**：再多用户会直接跳过，等于没写
 * - **可以跳过**：不强迫看完，右上角一直有「跳过」
 * - **不自动播放**：引导是「读」不是「看」，节奏交给用户
 * - **最后一页指向教程页**：引导只负责「知道有这么个功能」，
 *   讲细节交给「设置 → 其他 → 使用教程」。两边重叠会导致
 *   改了一处忘了另一处。
 */
@Composable
fun OnboardingDialog(onFinish: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardingPage(R.string.onboarding_p1_title, R.string.onboarding_p1_body),
            OnboardingPage(R.string.onboarding_p2_title, R.string.onboarding_p2_body),
            OnboardingPage(R.string.onboarding_p3_title, R.string.onboarding_p3_body),
            OnboardingPage(R.string.onboarding_p4_title, R.string.onboarding_p4_body)
        )
    }

    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex

    AlertDialog(
        // 点外部不关：引导只有「跳过 / 下一步」两条出路，
        // 误触关闭会让用户以为没看过就等于看过了
        onDismissRequest = { },
        title = {
            Text(
                text = stringResource(page.titleRes),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(page.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(Spacing.Item))

                PageIndicator(total = pages.size, current = index)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isLast) onFinish() else index++
                }
            ) {
                Text(
                    stringResource(
                        if (isLast) R.string.onboarding_start else R.string.onboarding_next
                    )
                )
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    TextButton(onClick = { index-- }) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                }
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        }
    )
}

/**
 * 页码指示：一排小圆点，当前页高亮。
 *
 * 用它而不是「1 / 4」这类文字，是因为圆点能一眼看出**还剩几页** ——
 * 文字要读一下才知道。
 */
@Composable
private fun PageIndicator(total: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            Dot(isCurrent = i == current)
            if (i != total - 1) Spacer(Modifier.size(6.dp))
        }
    }
}

/**
 * 一个小圆点。
 *
 * 刻意**不叫 Box** —— 那会和 Compose 的 `Box` 同名遮蔽，
 * 以后在这个文件里想用真正的 `Box` 时会一头雾水。
 */
@Composable
private fun Dot(isCurrent: Boolean) {
    Spacer(
        modifier = Modifier
            .size(if (isCurrent) 8.dp else 6.dp)
            .clip(CircleShape)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
    )
}