package com.watermarkbox.ui.screen.editor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.InvisibleSource
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 隐藏水印配置区。
 *
 * 隐藏水印与「可见水印」互不影响：它不会改变画面外观，
 * 而是把内容编码后同时写进 EXIF 元数据与像素频域，
 * 用于日后溯源。详见 [com.watermarkbox.invisible.InvisibleWatermarkEngine]。
 */
@Composable
internal fun InvisibleWatermarkSection(
    state: WatermarkPanelState,
    viewModel: WatermarkPanelHost
) {
    val config = state.invisibleConfig

    SectionLabel(stringResource(R.string.editor_invisible_title))

    ToggleRow(
        label = stringResource(R.string.editor_invisible_enable),
        checked = config.enabled,
        hint = stringResource(R.string.editor_invisible_disabled_hint),
        onCheckedChange = { viewModel.toggleInvisibleWatermark() }
    )

    if (!config.enabled) return

    Spacer(Modifier.height(Spacing.Item))

    // ---- 内容来源 ----
    SegmentedControl(
        options = listOf(
            InvisibleSource.CUSTOM_TEXT to stringResource(R.string.editor_invisible_source_custom),
            InvisibleSource.DEVICE_AND_TIME to stringResource(R.string.editor_invisible_source_device)
        ),
        selected = config.source,
        onSelect = viewModel::updateInvisibleSource,
        hintTitle = stringResource(R.string.editor_invisible_source),
        // 说明只在「自动生成」模式下有意义 —— 自定义模式下点了也白点
        hint = if (config.source == InvisibleSource.DEVICE_AND_TIME) {
            stringResource(R.string.editor_invisible_auto_hint)
        } else null
    )

    // ---- 自定义文字输入 ----
    //
    // 用 AnimatedVisibility 做展开/收起，而不是直接 if/else 增删节点。
    // 原因：这段内容会**随「内容来源」切换出现或消失**，
    // 突兀地整块闪现/消失很跳。给它 160ms 的高度+透明度过渡，
    // 视线能跟上「这里多了一块 / 少了一块」。
    AnimatedVisibility(
        visible = config.source == InvisibleSource.CUSTOM_TEXT,
        enter = expandVertically(tween(160)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(100))
    ) {
        // ⚠️ 必须用 Column 包住：`AnimatedVisibility` 不提供
        // Column/Row 作用域，多个直接子节点会**重叠**在同一位置
        // （描边颜色区就踩过这个坑，见 WatermarkPanelContent）。
        // 这里虽然只有一个可见子节点，也统一包上以防后续加内容踩坑。
        Column {
            Spacer(Modifier.height(Spacing.Item))
            OutlinedTextField(
                value = config.content,
                onValueChange = viewModel::updateInvisibleContent,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.editor_invisible_content)) },
                placeholder = { Text(stringResource(R.string.editor_invisible_content_placeholder)) },
                supportingText = {
                    Text(
                        stringResource(R.string.editor_invisible_content_limit),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }

    // ---- 强度 ----
    Spacer(Modifier.height(Spacing.Item))
    LabeledSlider(
        label = stringResource(R.string.editor_invisible_strength),
        value = config.strength.toFloat(),
        onValueChange = { viewModel.updateInvisibleStrength(it.roundToInt()) },
        // 只保留 1~2 两档：3、4 档在数学上会被 MAX_STEP 封顶吃掉，
        // 与 1、2 档**完全等价**（详见 WatermarkConfig.strength 的说明）。
        valueRange = 1f..2f,
        steps = 1,
        valueText = stringResource(R.string.editor_invisible_strength_value, config.strength),
        hint = stringResource(R.string.editor_invisible_strength_hint)
    )

    // ---- 两条写入通道（各自独立开关）----
    Spacer(Modifier.height(Spacing.Item))
    InvisibleChannels(config = config, viewModel = viewModel)
}

/**
 * 隐藏水印的两条**写入通道**。
 *
 * ## 为什么把两条都摆出来
 *
 * 两条通道的取舍是正交互补的，只露一条容易让人误以为
 * 「开了隐藏水印就万事大吉」：
 *
 * | 通道 | 画质 | 抗压缩 | 暴露程度 |
 * |---|---|---|---|
 * | EXIF 元数据 | 完全无损 | ❌ 一压就没 | 明文，工具一看就见 |
 * | DCT 频域 | ±3/255 | ✅ 能扛 JPEG | 藏在像素里，不易察觉 |
 *
 * 并列展示后，用户能一眼看清「这条给你什么、要你付什么」，
 * 不必靠猜，也不用翻文档。
 */
@Composable
private fun InvisibleChannels(
    config: com.watermarkbox.data.model.InvisibleWatermarkConfig,
    viewModel: WatermarkPanelHost
) {
    Text(
        text = stringResource(R.string.editor_invisible_channels),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(Modifier.height(Spacing.Item))

    // ---- 通道 1：EXIF（零代价，但脆弱）----
    ChannelToggle(
        label = stringResource(R.string.editor_invisible_exif),
        checked = config.exifEnabled,
        onCheckedChange = { viewModel.toggleInvisibleExif() },
        pro = stringResource(R.string.editor_invisible_exif_pro),
        con = stringResource(R.string.editor_invisible_exif_con),
        hasQualityCost = false
    )

    Spacer(Modifier.height(Spacing.Item))

    // ---- 通道 2：DCT（有代价，但顽固）----
    ChannelToggle(
        label = stringResource(R.string.editor_invisible_dct),
        checked = config.dctEnabled,
        onCheckedChange = { viewModel.toggleInvisibleDct() },
        pro = stringResource(R.string.editor_invisible_dct_pro),
        con = stringResource(R.string.editor_invisible_dct_con),
        hasQualityCost = true
    )

    // ---- 两条都关：这不是「更隐蔽」，而是「没写」----
    //
    // 用 AnimatedVisibility 让警示卡平滑出现/消失：
    // 它出现在用户**刚关掉最后一条通道**的瞬间，
    // 正是最需要被注意到的时刻 —— 直接闪现容易看漏。
    AnimatedVisibility(
        visible = !config.exifEnabled && !config.dctEnabled,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
    ) {
        Column {
            Spacer(Modifier.height(Spacing.Item))
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.editor_invisible_no_channel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * 单条通道的开关 + 取舍说明卡。
 *
 * 卡片**无论开关都显示同样两条信息**（能得到什么 / 要付什么代价），
 * 只用颜色区分当前是否启用 —— 这样用户不必「先打开再后悔」。
 *
 * @param hasQualityCost 开启后是否有画质代价，决定警示配色与符号
 */
@Composable
private fun ChannelToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    pro: String,
    con: String,
    hasQualityCost: Boolean
) {
    ToggleRow(
        label = label,
        checked = checked,
        onCheckedChange = { onCheckedChange() }
    )

    Spacer(Modifier.height(6.dp))

    val (container, content) = when {
        // 未启用：中性灰，不抢视觉
        !checked -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onSurfaceVariant
        // 启用且有画质代价：橙黄警示
        hasQualityCost -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onTertiaryContainer
        // 启用且零代价：平和的绿系
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "✓ $pro",
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${if (hasQualityCost) "⚠" else "✗"} $con",
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
        }
    }
}
