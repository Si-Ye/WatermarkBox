package com.watermarkbox.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watermarkbox.R

/**
 * 面板分组标题。
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp)
    )
}

/**
 * 带标签 + 数值显示的滑块行。
 *
 * @param valueText 右侧数值文本（由调用方格式化）
 * @param hint 非空时在标签右侧显示 ⓘ，点击弹出说明（见 [InfoDot]）
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    hint: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (hint != null) {
                    Spacer(Modifier.width(6.dp))
                    InfoDot(title = label, text = hint)
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

/**
 * 分段选择控件（Segmented Control），用于水印类型 / 布局模式等少量互斥选项。
 *
 * @param hint 非空时在上方显示一个带 ⓘ 的标签行，点击弹出说明（见 [InfoDot]）
 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    hintTitle: String? = null,
    hint: String? = null
) {
    if (hint != null && hintTitle != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hintTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            InfoDot(title = hintTitle, text = hint)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 13.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 预设调色板 + 当前选中态。点击色块回调颜色。
 */
@Composable
fun ColorPalette(
    colors: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { color ->
            val isSelected = color.toArgbSafe() == selected.toArgbSafe()
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.common_selected),
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 九宫格位置选择器（仅 SINGLE 模式使用）。
 * index 0..8 对应 左上→右下。
 */
@Composable
fun GravityGrid(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「圈里一个 i」的说明图标：点一下弹出完整解释。
 *
 * ## 为什么要有它
 *
 * 选项解释动辄两三行。直接铺在界面上有两个问题：
 * - 把真正要操作的东西挤下去，滚动变长
 * - 信息量太大，用户反而会整段跳过 —— 那这些字就等于白写
 *
 * 收进一个图标里：想了解的人点开看，不想看的人不受打扰。
 *
 * @param title 弹窗标题（通常就是选项名）
 * @param text  完整说明
 */
@Composable
fun InfoDot(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = stringResource(R.string.common_whats_this),
        tint = MaterialTheme.colorScheme.outline,
        modifier = modifier
            .size(17.dp)
            .clip(CircleShape)
            .clickable { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
    }
}

/**
 * 简单的开关行（左侧标题 + 右侧 Switch）。
 *
 * @param hint 非空时，在标题右侧显示一个 ⓘ 图标，点击弹出这段说明。
 *             解释性文案走这里，不要直接铺在界面上（见 [InfoDot]）。
 *
 * 注意 [hint] 放在 [onCheckedChange] **之前**：Kotlin 的「尾随 lambda」
 * 只会绑定到最后一个参数，若把 hint 放最后，现有写法
 * `ToggleRow(label, checked) { ... }` 全会编译不过。
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            // 标题占满剩余宽度 —— 否则长标题会把 Switch 挤到屏幕外
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hint != null) {
                Spacer(Modifier.width(6.dp))
                InfoDot(title = label, text = hint)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 兼容不同 Compose 版本的 Color → ARGB 提取。 */
private fun Color.toArgbSafe(): Long = this.value.toLong()

/** 面板项之间的垂直间隔。 */
@Composable
fun ItemSpacer() {
    Spacer(Modifier.height(12.dp))
}

/** 面板项之间的水平间隔。 */
@Composable
fun HItemSpacer() {
    Spacer(Modifier.width(12.dp))
}