package com.watermarkbox.ui.screen.tutorial

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.ui.screen.update.MarkdownText
import com.watermarkbox.ui.theme.Spacing

/**
 * 使用教程页。
 *
 * ## 为什么内容放在 assets 而不是代码里
 *
 * 教程是**要长期改**的东西：术语调整、功能增减、用户反馈补充……
 * 塞进 Kotlin 字符串会让 diff 极难读，也会把代码文件撑得很大。
 * 放 markdown 里既能直接编辑，又能复用现有的 [MarkdownText]
 * （那个渲染器本来是为 Release 说明写的，正好通用）。
 *
 * ## 中英文怎么选
 *
 * 按 **resources 的生效语言**（`configuration.locales`）判断，
 * 而不是去读用户的偏好设置 —— 因为语言切换最终都会落到 resources 上，
 * 「跟随系统」这类情况系统已经帮我们算好了，不必自己再判一遍。
 * 多判一次反而容易和实际渲染语言不一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val language = configuration.locales[0].language

    // 只在语言变化时重新读一次文件；切换语言会重建 Activity，
    // 这里的 remember 也会随之重算，能正确换到另一份教程
    val markdown = remember(language) {
        val asset = if (language == "en") "tutorial.en.md" else "tutorial.md"
        runCatching {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tutorial_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.PageHorizontal)
        ) {
            Spacer(Modifier.height(Spacing.Item))

            if (markdown.isBlank()) {
                // 读不到文件不该是一片空白 —— 用户会以为应用坏了
                Text(
                    text = stringResource(R.string.tutorial_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                MarkdownText(markdown = markdown)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}