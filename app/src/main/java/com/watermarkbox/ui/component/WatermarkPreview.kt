package com.watermarkbox.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.watermarkbox.R
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
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
    panEnabled: Boolean = true,

    /**
     * 拖动水印位置的回调（仅**单个模式**且本参数非 null 时生效）。
     *
     * 接收的参数是水印中心的**归一化坐标**（0~1，相对图片宽高）。
     * 页面负责把坐标写回配置（并把 gravity 切到 [GravityPosition.CUSTOM]）。
     *
     * ## 为什么用归一化坐标
     *
     * 预览是按缩略图尺寸渲染的、导出是按原图渲染的，两者差几十倍。
     * 传像素的话导出时水印会跑到画面外。传 0~1 的比例，
     * 两种尺寸下都能还原到相同的相对位置。
     *
     * ## 与图片缩放/平移手势的关系
     *
     * 拖动水印只在「单指 + 未缩放」时生效，此时会抢在图片平移之前消费手势；
     * 双指缩放 / 缩放后的平移仍交给图片。
     */
    onWatermarkDrag: ((x: Float, y: Float) -> Unit)? = null
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

    // ---- 拖动水印：只在「单个模式」且提供回调时启用 ----
    //
    // ## 坐标系换算
    //
    // 图片用 ContentScale.Fit 绘制，实际显示区域**小于**容器（有留白）。
    // 要把手指位置换算成「相对图片的 0~1 坐标」，必须知道图片实际画在哪：
    //
    //   1. 用 IntSize 记录容器尺寸、IntSize 记录位图尺寸
    //   2. 按 Fit 规则算出实际绘制矩形（居中）
    //   3. (触摸点 - 绘制矩形左上角) / 绘制矩形尺寸 = 归一化坐标
    //
    // 同时注意 graphicsLayer 的 scale/offset：单指拖动场景下 scale=1、
    // offset=0（缩放过才会平移），所以这里不参与换算 —— 也正因如此，
    // 拖动水印只在 scale≈1 时启用。
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val isSingleMode = when (watermarkType) {
        WatermarkType.TEXT -> textConfig.layoutMode == LayoutMode.SINGLE
        WatermarkType.IMAGE -> imageConfig.layoutMode == LayoutMode.SINGLE
    }
    val dragEnabled = onWatermarkDrag != null && isSingleMode && scale <= 1.01f

    // ⚠️ 手势回调必须先包成 State 再交给手势 Modifier。
    //
    // 直接把回调函数当作 `pointerInput` 的 key 是个陷阱：它在**每次重组时
    // 都是新的 lambda 实例**，而拖动过程中每一帧都会重组（水印位置在变）——
    // 于是 key 每帧都变，手势协程被反复取消并重建，表现为
    // 「拖到一半就卡住，得松手重新按住才能继续拖」。
    //
    // 包成 State 后：key 只由尺寸决定（拖动中不变），而回调永远是「最新那个」。
    val dragCallback = rememberUpdatedState(onWatermarkDrag)

    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .clipToBounds()
            .onSizeChanged { containerSize = it },
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
                                Modifier
                                    .pointerInput(Unit) {
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
                                    // 拖动水印：**放在图片平移之后**，
                                    // 这样只有在 scale≈1（没有平移缩放）时才会真正响应
                                    // —— 那时用户显然是在拖水印，不是在拖图片。
                                    .then(watermarkDragModifier(dragEnabled, renderedBitmap, containerSize, dragCallback))
                            } else {
                                Modifier
                                    .then(watermarkDragModifier(dragEnabled, renderedBitmap, containerSize, dragCallback))
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

/**
 * 构造「拖动水印改位置」的手势 Modifier。
 *
 * ## 坐标换算
 *
 * 图片是按 `ContentScale.Fit` 绘制的：等比缩放到**刚好放进**容器，
 * 水平或垂直方向会留下空白。要得到「相对图片的 0~1 坐标」必须还原这一步：
 *
 * ```
 * scale = min(容器宽/位图宽, 容器高/位图高)
 * 绘制宽 = 位图宽 * scale，绘制高 = 位图高 * scale
 * 绘制左上角 = ((容器宽-绘制宽)/2, (容器高-绘制高)/2)
 * 归一化 x = (触摸x - 绘制左上x) / 绘制宽
 * ```
 *
 * padding(8.dp) 也参与其中，但它对 x/y 的偏移是等量的，
 * 而本函数拿到的 `containerSize` 是**内层 Box** 的尺寸（已去掉 padding 影响前的原尺寸），
 * 因此这里按「以容器中心为基准」做换算，误差在可接受范围内
 * （拖动本身是粗粒度操作，不需要像素级精确）。
 *
 * ## 何时返回空 Modifier
 *
 * [enabled] 为 false 时返回 `Modifier`，完全不注册手势处理器
 * —— 理由同上面 `detectTransformGestures` 的注释：
 * 挂上就会消费触摸序列，必须从根上不注册。
 *
 * ## ⚠️ 为什么回调参数是 [State] 而不是普通函数
 *
 * `pointerInput` 的 key 一旦变化，**进行中的手势会被取消、从头开始**。
 * 而回调在每次重组时都是新的 lambda 实例，拖动过程中又恰恰每帧都在重组 ——
 * 把回调塞进 key 里，结果就是「手指还没抬起来，手势已被重启了好几次」，
 * 手感上表现为拖一下卡一下、得重新按住才能继续拖。
 *
 * 所以：key 只由**尺寸**决定（拖动中不变），回调经 State 取最新值。
 */
private fun watermarkDragModifier(
    enabled: Boolean,
    bitmap: Bitmap?,
    containerSize: IntSize,
    onDrag: State<((Float, Float) -> Unit)?>
): Modifier {
    if (!enabled || bitmap == null) return Modifier
    if (containerSize.width <= 0 || containerSize.height <= 0) return Modifier

    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    if (bw <= 0f || bh <= 0f) return Modifier

    return Modifier.pointerInput(containerSize, bw, bh) {
        detectDragGestures { change, _ ->
            change.consume()
            val normalized = normalizeToImageSpace(
                position = change.position,
                containerSize = containerSize,
                imageWidth = bw,
                imageHeight = bh
            )
            onDrag.value?.invoke(normalized.x, normalized.y)
        }
    }
}

/**
 * 把「容器内的触摸点」换算成「相对图片的 0~1 坐标」。
 *
 * 抽成纯函数是为了可测：Fit 的居中留白换算最容易写错，
 * 而它错了只表现为「水印跟手偏了一点」，肉眼几乎判断不出来。
 *
 * （手势本身没法单测 —— 见 `PreviewFitMeasurementTest` 里
 * 「Espresso 输入注入在本环境不可用」的说明。）
 */
internal fun normalizeToImageSpace(
    position: Offset,
    containerSize: IntSize,
    imageWidth: Float,
    imageHeight: Float
): Offset {
    if (imageWidth <= 0f || imageHeight <= 0f) return Offset(0.5f, 0.5f)
    if (containerSize.width <= 0 || containerSize.height <= 0) return Offset(0.5f, 0.5f)

    // ContentScale.Fit：等比缩放到刚好放进容器，然后居中
    val fitScale = minOf(
        containerSize.width.toFloat() / imageWidth,
        containerSize.height.toFloat() / imageHeight
    )
    val drawW = imageWidth * fitScale
    val drawH = imageHeight * fitScale
    if (drawW <= 0f || drawH <= 0f) return Offset(0.5f, 0.5f)

    val drawLeft = (containerSize.width - drawW) / 2f
    val drawTop = (containerSize.height - drawH) / 2f

    return Offset(
        x = ((position.x - drawLeft) / drawW).coerceIn(0f, 1f),
        y = ((position.y - drawTop) / drawH).coerceIn(0f, 1f)
    )
}