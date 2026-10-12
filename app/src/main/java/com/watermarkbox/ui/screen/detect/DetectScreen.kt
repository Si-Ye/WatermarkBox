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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.watermarkbox.invisible.DetectResult
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
                title = { Text("检测隐藏水印", fontWeight = FontWeight.SemiBold) },
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
                    text = "选择一张图片，检测其中是否含有由本应用写入的隐藏水印。\n\n" +
                        "检测分两级：先读 EXIF 元数据（若图片未被压缩过，此级即可命中）；" +
                        "若 EXIF 无结果，再尝试从像素的频域中提取（可抵抗 JPEG 重压缩）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.Item))
            }

            // ---- 图片预览 ----
            if (state.imageUri != null) {
                AsyncImage(
                    model = state.imageUri,
                    contentDescription = "待检测图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(Spacing.Item))
            }

            // ---- 操作按钮 ----
            Button(
                onClick = {
                    if (state.imageUri != null) viewModel.reset()
                    onPickImage()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (state.imageUri == null) Icons.Filled.AddPhotoAlternate
                    else Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(if (state.imageUri == null) "选择图片" else "换一张检测")
            }

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
                            text = "正在检测…",
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

/** 检测结果卡片。 */
@Composable
private fun ResultCard(result: DetectResult) {
    when (result) {
        is DetectResult.Found -> ResultCardImpl(
            icon = Icons.Filled.CheckCircle,
            iconTint = Color(0xFF2E7D32),
            title = "检测到隐藏水印",
            lines = buildList {
                add("内容" to result.content)
                add("来源通道" to result.channel.displayName)
                add("置信度" to "${(result.confidence * 100).roundToInt()}%")
            }
        )
        is DetectResult.NoWatermark -> ResultCardImpl(
            icon = Icons.Filled.CloudOff,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = "未检测到隐藏水印",
            lines = listOf("说明" to result.reason)
        )
        is DetectResult.Error -> ResultCardImpl(
            icon = Icons.Filled.Cancel,
            iconTint = MaterialTheme.colorScheme.error,
            title = "检测失败",
            lines = listOf("原因" to result.message)
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