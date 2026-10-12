package com.watermarkbox.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.ui.theme.Spacing

/**
 * 参数面板的拖拽把手。
 *
 * 点击可收起/展开，长按拖动可调整面板高度（拖动由
 * [DraggableBottomPanel] 处理，本组件只负责视觉与点击）。
 *
 * ## 为什么抽成公共组件
 *
 * 编辑页与批量页都有"底部可拖拽参数面板"，把手必须长得一模一样 ——
 * 否则两个页面切换时会有明显的割裂感。抽出来后两处共用，
 * 改一次两边同步。
 *
 * @param expanded   面板当前是否展开（决定箭头方向）
 * @param overlaying 面板是否已进入"覆盖预览"阶段（决定提示文案颜色）
 * @param onToggle   点击回调（收起/展开）
 * @param title      标题文案，默认「水印参数」
 */
@Composable
fun PanelDragHandle(
    expanded: Boolean,
    overlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes titleRes: Int = R.string.panel_title
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = Spacing.PageHorizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (overlaying) R.string.panel_hint_overlay
                    else R.string.panel_hint_drag
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (overlaying) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore
                else Icons.Filled.ExpandLess,
                contentDescription = stringResource(
                    if (expanded) R.string.panel_collapse
                    else R.string.panel_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}