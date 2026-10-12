package com.watermarkbox.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.watermark.PreviewRenderer
import com.watermarkbox.watermark.WatermarkRenderer

/**
 * 派生「预览渲染结果」的公共 Composable 帮助函数。
 *
 * ## 为什么必须用 rememberUpdatedState
 *
 * `derivedStateOf` **只追踪 [androidx.compose.runtime.State] 读取**。
 * 函数参数（textConfig / watermarkType / logoBitmap 等）是普通值，
 * 若在 derivedStateOf 内部直接引用，会被闭包捕获并永久停留在首次值
 * —— 结果就是「拖动滑块预览完全不动」这个 Bug。
 *
 * 正确做法：先把每个参数用 [rememberUpdatedState] 包成 State，
 * 再在 derivedStateOf 内部读 `.value`，这样任何参数变化都会让派生值失效并重算。
 *
 * 本函数被 [WatermarkPreview] 与自动化测试共同调用，
 * 保证「测试覆盖的代码路径」= 「线上运行的代码路径」。
 */
@Composable
fun rememberRenderedPreview(
    bitmap: Bitmap?,
    watermarkType: WatermarkType,
    textConfig: TextWatermarkStyle,
    imageConfig: ImageWatermarkStyle,
    logoBitmap: Bitmap?,
    sourceFileName: String?,
    renderer: WatermarkRenderer = remember { WatermarkRenderer() }
): Bitmap? {
    val bitmapState = rememberUpdatedState(bitmap)
    val typeState = rememberUpdatedState(watermarkType)
    val textConfigState = rememberUpdatedState(textConfig)
    val imageConfigState = rememberUpdatedState(imageConfig)
    val logoState = rememberUpdatedState(logoBitmap)
    val fileNameState = rememberUpdatedState(sourceFileName)

    val rendered by remember(renderer) {
        derivedStateOf {
            PreviewRenderer.render(
                renderer = renderer,
                bitmap = bitmapState.value,
                watermarkType = typeState.value,
                textConfig = textConfigState.value,
                imageConfig = imageConfigState.value,
                logoBitmap = logoState.value,
                sourceFileName = fileNameState.value
            )
        }
    }
    return rendered
}

/**
 * 图片预览区。
 *
 * ## 尺寸语义（关键修复）
 *
 * 预览图必须**随容器高度真实变化**，因此：
 * - 外层 `Box` 用 `fillMaxSize()`，接受父容器（[ShrinkablePreviewArea]）给的受限高度
 * - `Image` 用 `ContentScale.Fit` + `fillMaxSize()`
 *
 * ⚠️ 历史上的坑：如果 Image 的父容器**高度不受限**（或宽度成为限制条件），
 * `Fit` 会按宽度撑满，导致「拖面板时图片尺寸不变」。
 * 因此本组件**必须**被放进一个高度受限的父容器（如 ShrinkablePreviewArea）。
 *
 * @param bitmap 图片源
 * @param watermarkType 水印类型
 * @param textConfig 文字水印配置
 * @param imageConfig 图片水印配置
 * @param logoBitmap logo 位图
 * @param sourceFileName 源文件名（占位符用）
 * @param isLoading 是否加载中
 */
@Composable
fun WatermarkPreview(
    bitmap: Bitmap?,
    watermarkType: WatermarkType,
    textConfig: TextWatermarkStyle,
    imageConfig: ImageWatermarkStyle,
    logoBitmap: Bitmap?,
    sourceFileName: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 是否允许**单指拖动**平移图片。
     *
     * ## 为什么需要这个开关
     *
     * 批量页把预览放进了 `HorizontalPager`：单指横滑要用来**切换图片**。
     * 而本组件内部的 `detectTransformGestures` 会优先消费单指手势，
     * 导致滑动手势到不了 pager —— 表现就是"滑动完全没反应"。
     *
     * 关掉后：
     * - 单指横滑 → 交给 pager 切换图片
     * - **双指缩放仍然可用**（`zoom` 继续生效，缩放后的平移也仍然允许，
     *   因为那时用户显然是在操作图片本身）
     *
     * 编辑页保持默认 `true`（没有 pager，拖动平移是必要能力）。
     */
    panEnabled: Boolean = true
) {
    // ---- 缩放/平移状态 ----
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // ---- 核心：派生「预览渲染结果」（逻辑集中在公共帮助函数中） ----
    val renderedBitmap = rememberRenderedPreview(
        bitmap = bitmap,
        watermarkType = watermarkType,
        textConfig = textConfig,
        imageConfig = imageConfig,
        logoBitmap = logoBitmap,
        sourceFileName = sourceFileName
    )

    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }

            renderedBitmap == null -> {
                Text(
                    text = stringResource(R.string.preview_no_image),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            else -> {
                // ⚠️ 关键：这里不能套 Column 或任何会「包裹内容」的容器。
                // 必须让 Image 直接 fillMaxSize()，这样 ContentScale.Fit
                // 才会同时受限于【父容器的高度】—— 从而在父容器变矮时真实缩小。
                //
                // 历史 Bug：原先套了 Column（默认 wrapContentHeight），
                // Image 的高度约束被解开，Fit 只按宽度撑满 →
                // 拖面板时图片尺寸完全不变。
                Image(
                    bitmap = renderedBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                        // ⚠️ 关键：panEnabled=false 时**完全不挂**这个手势处理器。
                        //
                        // 不能只在回调里判断后不处理 —— `detectTransformGestures`
                        // 一旦挂上就会在 `awaitEachGesture` 里消费掉触摸序列，
                        // 外层 HorizontalPager 依然收不到手势，横滑还是没反应。
                        // 必须从根上不注册。
                        .then(
                            if (panEnabled) {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 8f)
                                        if (scale <= 1.01f) {
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }

        // ---- 右下角提示：图片水印未选择 logo 时 ----
        if (watermarkType == WatermarkType.IMAGE && logoBitmap == null && !isLoading) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.preview_choose_logo),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}