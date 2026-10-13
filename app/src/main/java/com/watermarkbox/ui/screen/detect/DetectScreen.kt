package com.watermarkbox.ui.screen.detect

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.watermarkbox.R
import com.watermarkbox.invisible.DetectOptions
import com.watermarkbox.invisible.DetectResult
import com.watermarkbox.invisible.WatermarkDetector
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 隐藏水印检测页。
 *
 * 让用户选一张图，检测其中是否含「水印盒」写入的隐藏水印。
 * 检测走两级：先读 EXIF（像素未被动过时最可信），
 * 再尝试频域提取（能扛压缩）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectScreen(
    viewModel: DetectViewModel,
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detect_title), fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = Spacing.PageHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(Spacing.Item))

            // ---- 说明 ----
            if (state.imageUri == null) {
                Text(
                    text = stringResource(R.string.detect_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.Item))
            }

            // ---- 图片预览 ----
            if (state.imageUri != null) {
                AsyncImage(
                    model = state.imageUri,
                    contentDescription = stringResource(R.string.detect_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(Spacing.Item))
            }

            // ---- 操作按钮 ----
            //
            // 两种状态，动作集合不同：
            //
            // ① 还没有图：只有「选择图片」一个动作。
            //
            // ② 已有图：拆成两个并列动作，因为它们**服务于不同目的**：
            //    - 「重新检测」（主按钮，实心）：不换图，用**当前设置**再跑一遍。
            //      典型场景：改完下面的检测设置（强度/方向），想在同一张图上
            //      验证新设置 —— 不必回相册重选一遍。
            //      这是改设置后的**下一个自然动作**，所以给它更高的视觉权重。
            //    - 「换一张检测」（次按钮，描边）：回相册选新图。
            //      它不是改设置后的常用路径，所以降级为次要动作，
            //      也避免用户误以为只能换图。
            //
            // 之前只有一个按钮，文案写「换一张检测」但图标是放大镜 ——
            // 语义与行为不一致，且**完全没有原地重检的入口**
            // （尽管 ViewModel 早有 recheck()）。
            if (state.imageUri == null) {
                Button(
                    onClick = onPickImage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.detect_pick))
                }
            } else {
                Button(
                    onClick = viewModel::recheck,
                    // 检测中禁用：否则连点会叠加多次检测任务，
                    // 而且并发跑同一个 detector 没有意义
                    enabled = !state.isDetecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.detect_rerun))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.reset()
                        onPickImage()
                    },
                    enabled = !state.isDetecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.detect_recheck))
                }
            }

            Spacer(Modifier.height(Spacing.Item))

            // ---- 检测设置 ----
            DetectOptionsCard(
                options = state.options,
                onChange = viewModel::updateOptions
            )

            Spacer(Modifier.height(Spacing.Item))

            // ---- 检测中 ----
            if (state.isDetecting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.Item),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.detect_running),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 检测结果 ----
            state.result?.let { result ->
                ResultCard(result)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 检测设置卡片。
 *
 * 把「多花时间换更高检出率」的决定权交给用户：
 * 默认全开（**正确性优先** —— 宁可慢，也别给出可能是误判的「没有水印」），
 * 想快就逐项关掉，代价自己承担。
 *
 * DCT 关闭时，它下面的两个加速项会一并隐藏 —— 没有 DCT 检测，
 * 「强度」和「方向」都没有意义。
 */
@Composable
private fun DetectOptionsCard(
    options: DetectOptions,
    onChange: (DetectOptions) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detect_options_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(12.dp))

            // ---- 通道 1：EXIF ----
            ToggleRow(
                label = stringResource(R.string.detect_option_exif),
                checked = options.checkExif,
                onCheckedChange = { onChange(options.copy(checkExif = !options.checkExif)) },
                hint = stringResource(R.string.detect_option_exif_hint)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // ---- 通道 2：DCT ----
            ToggleRow(
                label = stringResource(R.string.detect_option_dct),
                checked = options.checkDct,
                onCheckedChange = { onChange(options.copy(checkDct = !options.checkDct)) },
                hint = stringResource(R.string.detect_option_dct_hint)
            )

            if (options.checkDct) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                ToggleRow(
                    label = stringResource(R.string.detect_option_strength),
                    checked = options.autoStrength,
                    onCheckedChange = {
                        onChange(options.copy(autoStrength = !options.autoStrength))
                    },
                    hint = stringResource(R.string.detect_option_strength_hint)
                )

                Spacer(Modifier.height(10.dp))

                ToggleRow(
                    label = stringResource(R.string.detect_option_rotation),
                    checked = options.autoRotation,
                    onCheckedChange = {
                        onChange(options.copy(autoRotation = !options.autoRotation))
                    },
                    hint = stringResource(R.string.detect_option_rotation_hint)
                )

                Spacer(Modifier.height(10.dp))

                ToggleRow(
                    label = stringResource(R.string.detect_option_verify_sig),
                    checked = options.verifySignatureViaDct,
                    onCheckedChange = {
                        onChange(
                            options.copy(verifySignatureViaDct = !options.verifySignatureViaDct)
                        )
                    },
                    hint = stringResource(R.string.detect_option_verify_sig_hint)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.detect_options_apply_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 检测结果卡片。 */
@Composable
private fun ResultCard(result: DetectResult) {
    when (result) {
        is DetectResult.Found -> {
            // 签名无效 = 可能是伪造的，视觉上要和「确认是本应用签发」区分开，
            // 否则用户会把两者当成同一件事
            val suspicious = result.signatureValid == false
            ResultCardImpl(
                icon = if (suspicious) Icons.Filled.WarningAmber
                else Icons.Filled.CheckCircle,
                iconTint = if (suspicious) Color(0xFFE65100) else Color(0xFF2E7D32),
                title = stringResource(
                    if (suspicious) R.string.detect_found_suspicious_title
                    else R.string.detect_found_title
                ),
                lines = buildList {
                    add(stringResource(R.string.detect_field_content) to result.content)
                    add(
                        stringResource(R.string.detect_field_channel) to
                            stringResource(result.channel.labelRes)
                    )
                    add(
                        stringResource(R.string.detect_field_signature) to
                            signatureLabel(result.signatureValid)
                    )
                    add(
                        stringResource(R.string.detect_field_confidence) to
                            "${(result.confidence * 100).roundToInt()}%"
                    )
                }
            )
        }
        is DetectResult.NoWatermark -> ResultCardImpl(
            icon = Icons.Filled.CloudOff,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.detect_not_found_title),
            lines = buildList {
                add(
                    stringResource(R.string.detect_field_reason) to
                        localizedReason(result.reason)
                )
                // 「可能旧版本导出」这行只在**真的可能是旧版本**时才加。
                //
                // 为什么这么判：
                //
                // 引擎返回的 reason 是一组固定字符串（见 localizedReason）。
                // 其中「图片过小」「跳过检测通道」这两类是**确定的技术原因** ——
                // 图和版本无关，加了只会误导用户以为换新版导出就能解决。
                //
                // 只有「未检测到水印」这一类是**含糊的**：可能是图被平台
                // 重压缩过、被裁剪过，也可能是本应用在修复「浅色背景写不进去」
                // 之前导出的（那时水印确实没嵌进像素里）。这种情况下，
                // 提示「可能旧版本导出」才是有用且不误导的。
                //
                // 这里引用 [WatermarkDetector.REASON_NOT_FOUND] 而非再写一遍
                // 字面量 —— 两处字面量一旦改文案就会**静默失配**（提示不再
                // 显示，且无编译错误）。引用常量把失配变成编译错误。
                if (result.reason == WatermarkDetector.REASON_NOT_FOUND) {
                    add(
                        stringResource(R.string.detect_field_hint) to
                            stringResource(R.string.invisible_error_old_version_hint)
                    )
                }
            }
        )
        is DetectResult.Error -> ResultCardImpl(
            icon = Icons.Filled.Cancel,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.detect_failed_title),
            lines = listOf(stringResource(R.string.detect_field_error) to result.message)
        )
    }
}

@Composable
private fun ResultCardImpl(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    lines: List<Pair<String, String>>
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}
/** 把签名状态翻译成人话。 */
@Composable
private fun signatureLabel(state: Boolean?): String = when (state) {
    true -> stringResource(R.string.detect_signature_valid)
    false -> stringResource(R.string.detect_signature_invalid)
    // null 有两种来路：EXIF 通道（明文，本来就没签名）、
    // 或旧版本导出的图（那时还没有签名机制）。对用户来说都是「验不了」。
    null -> stringResource(R.string.detect_signature_unknown)
}

/**
 * 把检测器返回的「未找到」原因映射为本地化文案。
 *
 * ## 为什么不在引擎里直接返回资源 ID
 *
 * [com.watermarkbox.invisible.InvisibleWatermarkEngine] 是**纯逻辑层** ——
 * 它同时跑在单元测试和后台 Worker 里，不该依赖 Android 资源。
 * 让引擎继续说中文原因、由界面层做一次映射，是既保住可测性、
 * 又能本地化的折中。
 *
 * 认不出的原因原样返回：将来引擎新增原因时，界面不会空白。
 */
@Composable
private fun localizedReason(reason: String): String = when (reason) {
    "图片过小，无法承载水印" -> stringResource(R.string.invisible_error_too_small)
    "未检测到水印（或损坏超出纠错能力）" -> stringResource(R.string.invisible_error_not_found)
    "已按设置跳过频域检测，EXIF 中也没有" -> stringResource(R.string.detect_error_skipped_dct)
    "已按设置跳过全部检测通道" -> stringResource(R.string.detect_error_skipped_all)
    else -> reason
}
