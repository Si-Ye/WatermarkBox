package com.watermarkbox.ui.screen.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watermarkbox.R

/**
 * 导出进行中的**进度卡**。
 *
 * ## 它解决什么问题
 *
 * 大图导出要依次走「解码原图 → 渲染水印 → 嵌隐藏水印 → 写文件」，
 * 全程可能好几秒。此前的反馈只有按钮文字从「导出」变成「导出中」——
 * 用户看到的是画面不动，感受就是**卡死**。
 *
 * ## 为什么是「阶段」而不是「百分比」
 *
 * 解码与写文件都没有可用的进度回调（`BitmapFactory` / `ContentResolver`
 * 都不提供）。硬凑一个百分比只会是假的，进度条走到 80% 卡住反而更让人焦虑。
 *
 * 所以这里给两样东西：
 * - **诚实的阶段文案** —— 用户知道系统在做事、在做哪件事
 * - **不确定进度条** —— 表示「正在进行」，不承诺何时结束
 *
 * ## 动画
 *
 * - 阶段文案切换用 [AnimatedContent] 轻微上下滑动：比直接跳变更容易
 *   让人感觉到「它在往前走」
 * - 整张卡用 [animateContentSize]：文案长短在不同语言下不一样，
 *   换行变化时卡片高度是补间过去的，不会「弹一下」
 *
 * 时长都控制在 150~200ms：动效只负责说明状态，不该让人等它演完。
 */
@Composable
fun ExportProgressCard(stage: ExportStage?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(animationSpec = tween(180))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.editor_export_progress_title),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(180)) { it / 2 } + fadeIn(tween(180)))
                        .togetherWith(
                            (slideOutVertically(animationSpec = tween(180)) { -it / 2 } + fadeOut(tween(120)))
                        )
                },
                label = "export-stage"
            ) { current ->
                Text(
                    text = stringResource(exportStageLabel(current)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.82f),
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.editor_export_large_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 阶段 → 文案。
 *
 * `null` 只会出现在淡出那一瞬间（`isExporting` 已置 false、
 * 卡片还在做退场动画），按第一步显示即可，无需特判。
 */
private fun exportStageLabel(stage: ExportStage?): Int = when (stage) {
    ExportStage.RENDERING -> R.string.editor_export_stage_render
    ExportStage.EMBEDDING_INVISIBLE -> R.string.editor_export_stage_invisible
    ExportStage.SAVING -> R.string.editor_export_stage_save
    ExportStage.LOADING_SOURCE, null -> R.string.editor_export_stage_source
}