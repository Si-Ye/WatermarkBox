package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.util.PlaceholderResolver
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 水印渲染引擎（核心算法类）。
 *
 * 设计原则：
 * 1. 纯函数式渲染 —— 输入 Bitmap + 配置，输出新 Bitmap，不修改入参。
 * 2. 尺寸归一化 —— 配置里的 fontSize / margin 等以「原图坐标系」为准，
 *    渲染到缩略图时会按 targetWidth / baseWidth 比例统一缩放，
 *    保证预览与导出视觉一致（见 [RenderContext]）。
 * 3. 三种渲染模式各自独立方法，便于单元测试与后续扩展。
 */
class WatermarkRenderer {

    /**
     * 自定义字体解析器（阶段四）。
     *
     * 传入后，当配置的 [FontFamilyOption.CUSTOM] 且 [TextWatermarkStyle.customFontName]
     * 非空时，会尝试用它加载字体；解析失败则回退到系统默认字体。
     *
     * 设计为可注入，避免渲染器直接依赖 Android Context，便于单测。
     */
    fun interface CustomFontResolver {
        fun resolve(fileName: String): Typeface?
    }

    /** 当前生效的自定义字体解析器。 */
    var customFontResolver: CustomFontResolver? = null

    /**
     * Paint 复用池。
     *
     * 拖滑块时每帧都会调 [buildPaint]，若每次都 `Paint()` 会持续产生
     * 垃圾对象，触发 GC 卡顿。这里复用一个实例，
     * 每次用前 `reset()` 清空状态。
     *
     * ⚠️ 线程约束：本渲染器不是线程安全的。
     * 预览在主线程渲染、批量导出在 Worker 单线程串行处理，
     * 各自持有独立的 Renderer 实例，因此不会有并发访问。
     */
    private val paintPool: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Typeface 缓存。
     *
     * `Typeface.create()` 内部要做系统字体查找，开销不小。
     * 字体种类 + 样式的组合有限（4 × 4 = 16 种），缓存收益明显。
     */
    private val typefaceCache = HashMap<TypefaceKey, Typeface>(16)

    /** logo 绘制专用 Paint 池（与文字水印的池分开，避免状态互污）。 */
    private val logoPaintPool: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 渲染上下文：承载「配置基准坐标系 → 实际输出坐标系」的缩放换算。
     *
     * baseWidth / baseHeight：配置数值所基于的尺寸（通常是原图尺寸）
     * targetWidth / targetHeight：本次实际渲染目标（缩略图或原图）
     */
    data class RenderContext(
        val baseWidth: Int,
        val baseHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int
    ) {
        /** 统一的缩放系数：目标宽 / 基准宽。 */
        val scale: Float = if (baseWidth <= 0) 1f else targetWidth.toFloat() / baseWidth
    }

    /**
     * 单入口：根据配置中的 layoutMode 自动分发到对应渲染方法。
     *
     * @param source    源 Bitmap（不会被修改）
     * @param config    水印配置
     * @param sourceFileName 原图文件名，用于 {filename} 占位符
     * @param baseWidth  配置数值所基于的宽度；默认与 source 同宽（预览场景传原图宽度）
     * @param baseHeight 配置数值所基于的高度；默认与 source 同高
     * @return 渲染后的新 Bitmap
     */
    fun render(
        source: Bitmap,
        config: TextWatermarkStyle,
        sourceFileName: String? = null,
        baseWidth: Int = source.width,
        baseHeight: Int = source.height
    ): Bitmap {
        val ctx = RenderContext(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            targetWidth = source.width,
            targetHeight = source.height
        )
        return when (config.layoutMode) {
            LayoutMode.SINGLE -> renderSingle(source, config, sourceFileName, ctx)
            LayoutMode.TILED -> renderTiled(source, config, sourceFileName, ctx)
            LayoutMode.DIAGONAL -> renderDiagonal(source, config, sourceFileName, ctx)
        }
    }

    // ---------------------------------------------------------------------
    // 模式一：单个水印（九宫格定位 + 边距）
    // ---------------------------------------------------------------------

    fun renderSingle(
        source: Bitmap,
        config: TextWatermarkStyle,
        sourceFileName: String? = null,
        ctx: RenderContext = RenderContext(
            source.width, source.height, source.width, source.height
        )
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val paint = buildPaint(config, ctx)
        val text = resolveText(config, sourceFileName)

        // 多行文本测量：取最长行宽作为锚定宽度
        val lines = text.split("\n")
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }
        val blockWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val blockHeight = lineHeight * lines.size

        val baseLineX: Float
        val baseLineY: Float
        val margin = config.margin * ctx.scale

        // ---- 水平方向 ----
        baseLineX = when (config.gravity) {
            GravityPosition.TOP_LEFT, GravityPosition.CENTER_LEFT, GravityPosition.BOTTOM_LEFT ->
                margin
            GravityPosition.TOP_CENTER, GravityPosition.CENTER, GravityPosition.BOTTOM_CENTER ->
                (output.width - blockWidth) / 2f
            GravityPosition.TOP_RIGHT, GravityPosition.CENTER_RIGHT, GravityPosition.BOTTOM_RIGHT ->
                output.width - blockWidth - margin
        }

        // ---- 垂直方向 ----
        val topY = when (config.gravity) {
            GravityPosition.TOP_LEFT, GravityPosition.TOP_CENTER, GravityPosition.TOP_RIGHT ->
                margin
            GravityPosition.CENTER_LEFT, GravityPosition.CENTER, GravityPosition.CENTER_RIGHT ->
                (output.height - blockHeight) / 2f
            GravityPosition.BOTTOM_LEFT, GravityPosition.BOTTOM_CENTER, GravityPosition.BOTTOM_RIGHT ->
                output.height - blockHeight - margin
        }
        // 基线位置：先到首行顶部，再加 -ascent 得到首行基线
        baseLineY = topY - paint.fontMetrics.ascent

        // 整体旋转（SINGLE 模式也支持旋转）
        canvas.save()
        if (config.rotationDegrees != 0f) {
            canvas.rotate(config.rotationDegrees, output.width / 2f, output.height / 2f)
        }
        var y = baseLineY
        for (line in lines) {
            drawTextLine(canvas, line, baseLineX, y, paint, config, ctx.scale)
            y += lineHeight
        }
        canvas.restore()

        return output
    }

    // ---------------------------------------------------------------------
    // 模式二：平铺（嵌套循环按间距重复绘制）
    // ---------------------------------------------------------------------

    fun renderTiled(
        source: Bitmap,
        config: TextWatermarkStyle,
        sourceFileName: String? = null,
        ctx: RenderContext = RenderContext(
            source.width, source.height, source.width, source.height
        )
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val paint = buildPaint(config, ctx)
        val text = resolveText(config, sourceFileName).replace("\n", " ")
        val textWidth = paint.measureText(text)
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }

        // ---- 平铺间距计算 ----
        // tileCount 视为「横向铺满所需的数量」的密度参考：
        // 数量越多 → 间距越小。这里用 count 在宽度上等分，得到 cellWidth，
        // 再通过纵横比（1.6）推导 cellHeight，保证视觉近似正方形网格。
        val count = config.tileCount.coerceIn(1, 20)
        val cellWidth = output.width.toFloat() / count
        val cellHeight = max(cellWidth / 1.6f, lineHeight * 1.4f)

        val cols = ceil(output.width / cellWidth).toInt() + 1
        val rows = ceil(output.height / cellHeight).toInt() + 1

        canvas.save()
        if (config.rotationDegrees != 0f) {
            canvas.rotate(config.rotationDegrees, output.width / 2f, output.height / 2f)
        }

        // 嵌套循环：行 → 列，逐个绘制
        for (row in 0..rows) {
            for (col in 0..cols) {
                // 奇数行做半格错位，形成砖块式平铺，视觉更均匀
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = col * cellWidth + offsetX
                val y = row * cellHeight + lineHeight
                drawTextLine(canvas, text, x - textWidth / 2f, y, paint, config, ctx.scale)
            }
        }
        canvas.restore()

        return output
    }

    // ---------------------------------------------------------------------
    // 模式三：对角线平铺（在平铺基础上叠加 45° 旋转）
    // ---------------------------------------------------------------------

    fun renderDiagonal(
        source: Bitmap,
        config: TextWatermarkStyle,
        sourceFileName: String? = null,
        ctx: RenderContext = RenderContext(
            source.width, source.height, source.width, source.height
        )
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val paint = buildPaint(config, ctx)
        val text = resolveText(config, sourceFileName).replace("\n", " ")
        val textWidth = paint.measureText(text)
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }

        val count = config.tileCount.coerceIn(1, 20)

        // ---- 网格尺寸 ----
        //
        // 用**对角线方向上的等分**来定网格，而不是只看宽度。
        // 若只按宽度分（旧写法 cellWidth = width/count），宽图时格子会非常宽，
        // 高度只有一点点，旋转后竖直方向覆盖不足 —— 这是「长方形图片
        // 对角水印覆盖不全」的另一个成因。
        //
        // 现在的做法：
        //   1. 先算图片对角线长度 diagonal；
        //   2. 对角方向等分 diagonal / count 得到基础格子边长；
        //   3. 再用它推出网格的列数与行数。
        // 这样无论横图竖图，旋转 45° 后的铺开密度都一致。
        val diagonal = kotlin.math.sqrt(
            (output.width * output.width + output.height * output.height).toDouble()
        ).toFloat()
        val base = diagonal / count
        val cellWidth = base
        val cellHeight = max(base / 1.6f, lineHeight * 1.4f)

        // 需要覆盖的范围：对角线长度 + 一格余量，保证旋转后四角不断档
        val extended = diagonal + max(cellWidth, cellHeight)

        val cols = ceil(extended / cellWidth).toInt() + 1
        val rows = ceil(extended / cellHeight).toInt() + 1

        // 画布中心旋转：对角线默认 45°，再叠加用户自定义角度
        val angle = -45f + config.rotationDegrees
        canvas.save()
        canvas.rotate(angle, output.width / 2f, output.height / 2f)

        // 以画布中心为原点，向四周按 extended 范围铺开
        val startX = output.width / 2f - cols * cellWidth / 2f
        val startY = output.height / 2f - rows * cellHeight / 2f

        for (row in 0..rows) {
            for (col in 0..cols) {
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = startX + col * cellWidth + offsetX
                val y = startY + row * cellHeight
                // 判定用「整个格子与图片是否相交」，文字按自身宽度参与，
                // 避免边缘格子因左上角出界而被整格跳过。
                if (isInsideRotatedArea(
                        x - textWidth / 2f, y - lineHeight, textWidth, lineHeight * 2f,
                        output.width, output.height, angle
                    )
                ) {
                    drawTextLine(canvas, text, x - textWidth / 2f, y, paint, config, ctx.scale)
                }
            }
        }
        canvas.restore()

        return output
    }

    /**
     * 判断「旋转坐标系下的一个格子矩形」是否与图片区域相交。
     *
     * ## 为什么不是判断单个点（踩坑记录）
     *
     * 起初这里只判断格子**左上角**是否落在图片内，结果对角水印在
     * 长方形图片上会出现**边缘覆盖不全**：格子有宽高（文字宽度 / logo 尺寸），
     * 左上角稍一出界，整格就被跳过 —— 可它的右半 / 下半明明还盖在图上。
     * 宽图（如 3000×1000）尤其明显，因为横向格子很宽，出界比例更高。
     *
     * 现在改为取**格子四角 + 中心**共 5 个点，任一落在（略微外扩的）图片内
     * 就认为该格子与图片相交、需要绘制。
     *
     * @param x       格子左上角 x（旋转后的坐标系）
     * @param y       格子左上角 y（旋转后的坐标系）
     * @param cellW   格子宽
     * @param cellH   格子高
     * @param w       图片宽
     * @param h       图片高
     * @param angleDeg 画布旋转角度（度）
     */
    private fun isInsideRotatedArea(
        x: Float,
        y: Float,
        cellW: Float,
        cellH: Float,
        w: Int,
        h: Int,
        angleDeg: Float
    ): Boolean {
        val cx = w / 2f
        val cy = h / 2f
        // 反向旋转，把「旋转坐标系里的点」映射回「原图坐标系」
        val rad = Math.toRadians(-angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        // 图片范围外扩半个格子的容差：
        // 只要格子有任何一部分盖在图上就画，宁可多画一格也不要断档。
        val padX = cellW / 2f
        val padY = cellH / 2f

        // 格子四角 + 中心
        val px = floatArrayOf(x, x + cellW, x, x + cellW, x + cellW / 2f)
        val py = floatArrayOf(y, y, y + cellH, y + cellH, y + cellH / 2f)

        for (i in px.indices) {
            val dx = px[i] - cx
            val dy = py[i] - cy
            val rx = cx + (dx * cosA - dy * sinA)
            val ry = cy + (dx * sinA + dy * cosA)
            if (rx >= -padX && rx <= w + padX && ry >= -padY && ry <= h + padY) {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------
    // 图片水印渲染（阶段二新增）
    // ---------------------------------------------------------------------

    /**
     * 图片水印统一入口。
     *
     * @param logo 已解码的 logo Bitmap（调用方负责解码与缩放）
     */
    fun renderImage(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        baseWidth: Int = source.width,
        baseHeight: Int = source.height
    ): Bitmap {
        val ctx = RenderContext(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            targetWidth = source.width,
            targetHeight = source.height
        )
        return when (config.layoutMode) {
            LayoutMode.SINGLE -> renderImageSingle(source, logo, config, ctx)
            LayoutMode.TILED -> renderImageTiled(source, logo, config, ctx)
            LayoutMode.DIAGONAL -> renderImageDiagonal(source, logo, config, ctx)
        }
    }

    /** 计算 logo 的目标绘制尺寸（按宽度百分比 + 可选保持宽高比）。 */
    private fun computeLogoSize(
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Pair<Float, Float> {
        val targetW = ctx.targetWidth * config.widthPercent.coerceIn(0.05f, 0.8f)
        return if (config.preserveAspectRatio && logo.width > 0) {
            val ratio = logo.height.toFloat() / logo.width.toFloat()
            targetW to targetW * ratio
        } else {
            val targetH = ctx.targetHeight * config.widthPercent.coerceIn(0.05f, 0.8f)
            targetW to targetH
        }
    }

    /**
     * 构建 logo 绘制用的 Paint（复用池 + 仅设置必要属性）。
     *
     * 注意：这里刻意**不复用** buildPaint 的 paintPool，
     * 因为图片水印与文字水印可能在同一帧内被先后调用（例如预览对比），
     * 共用同一个 Paint 会互相污染状态。
     */
    private fun buildLogoPaint(config: ImageWatermarkStyle): Paint {
        return logoPaintPool.apply {
            reset()
            isAntiAlias = true
            isFilterBitmap = true
            alpha = (config.alpha.coerceIn(MIN_ALPHA, 1f) * 255).toInt()
        }
    }

    private fun renderImageSingle(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val margin = config.margin * ctx.scale

        val left = when (config.gravity) {
            GravityPosition.TOP_LEFT, GravityPosition.CENTER_LEFT, GravityPosition.BOTTOM_LEFT ->
                margin
            GravityPosition.TOP_CENTER, GravityPosition.CENTER, GravityPosition.BOTTOM_CENTER ->
                (output.width - w) / 2f
            GravityPosition.TOP_RIGHT, GravityPosition.CENTER_RIGHT, GravityPosition.BOTTOM_RIGHT ->
                output.width - w - margin
        }
        val top = when (config.gravity) {
            GravityPosition.TOP_LEFT, GravityPosition.TOP_CENTER, GravityPosition.TOP_RIGHT ->
                margin
            GravityPosition.CENTER_LEFT, GravityPosition.CENTER, GravityPosition.CENTER_RIGHT ->
                (output.height - h) / 2f
            GravityPosition.BOTTOM_LEFT, GravityPosition.BOTTOM_CENTER, GravityPosition.BOTTOM_RIGHT ->
                output.height - h - margin
        }

        val dst = Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())

        canvas.save()
        if (config.rotationDegrees != 0f) {
            canvas.rotate(config.rotationDegrees, output.width / 2f, output.height / 2f)
        }
        canvas.drawBitmap(logo, null, dst, paint)
        canvas.restore()
        return output
    }

    private fun renderImageTiled(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val count = config.tileCount.coerceIn(1, 20)
        val cellWidth = output.width.toFloat() / count
        val cellHeight = max(cellWidth / 1.6f, h * 1.4f)

        val cols = ceil(output.width / cellWidth).toInt() + 1
        val rows = ceil(output.height / cellHeight).toInt() + 1

        canvas.save()
        if (config.rotationDegrees != 0f) {
            canvas.rotate(config.rotationDegrees, output.width / 2f, output.height / 2f)
        }
        for (row in 0..rows) {
            for (col in 0..cols) {
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = col * cellWidth + offsetX
                val y = row * cellHeight
                canvas.drawBitmap(
                    logo, null,
                    Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()),
                    paint
                )
            }
        }
        canvas.restore()
        return output
    }

    private fun renderImageDiagonal(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val count = config.tileCount.coerceIn(1, 20)

        // 网格尺寸：与文字对角线同样的思路 —— 以**对角线等分**定网格，
        // 而不是只看宽度，否则宽图会在竖直方向覆盖不足（见 renderDiagonal 注释）。
        val diagonal = kotlin.math.sqrt(
            (output.width * output.width + output.height * output.height).toDouble()
        ).toFloat()
        val base = diagonal / count
        val cellWidth = base
        val cellHeight = max(base / 1.6f, h * 1.4f)

        val extended = diagonal + max(cellWidth, cellHeight)
        val cols = ceil(extended / cellWidth).toInt() + 1
        val rows = ceil(extended / cellHeight).toInt() + 1

        val angle = -45f + config.rotationDegrees
        canvas.save()
        canvas.rotate(angle, output.width / 2f, output.height / 2f)

        val startX = output.width / 2f - cols * cellWidth / 2f
        val startY = output.height / 2f - rows * cellHeight / 2f

        for (row in 0..rows) {
            for (col in 0..cols) {
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = startX + col * cellWidth + offsetX
                val y = startY + row * cellHeight
                // 按 logo 实际尺寸判定相交，避免边缘格子被整格跳过
                if (isInsideRotatedArea(
                        x, y, w, h,
                        output.width, output.height, angle
                    )
                ) {
                    canvas.drawBitmap(
                        logo, null,
                        Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()),
                        paint
                    )
                }
            }
        }
        canvas.restore()
        return output
    }

    // ---------------------------------------------------------------------
    // 绘制辅助
    // ---------------------------------------------------------------------

    /** 绘制一行文字（含描边/阴影，先描边后填充保证边缘干净）。 */
    private fun drawTextLine(
        canvas: Canvas,
        line: String,
        x: Float,
        y: Float,
        paint: Paint,
        config: TextWatermarkStyle,
        scale: Float
    ) {
        // 关键：alpha 必须与颜色一起合成后再赋给 Paint。
        // 因为 Paint.setColor() 会整体覆盖 ARGB（包含 alpha 通道），
        // 若分开设置 color 与 alpha，后设置的会覆盖先设置的。
        val alphaByte = (config.alpha.coerceIn(MIN_ALPHA, 1f) * 255).toInt()

        // 描边层：用 STROKE 模式先画，颜色为描边色（带透明度）
        if (config.strokeEnabled && config.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = config.strokeWidth * scale
            paint.color = withAlpha(config.strokeColor.color.toArgb(), alphaByte)
            canvas.drawText(line, x, y, paint)
        }
        // 填充层：颜色为填充色（带透明度）
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(config.color.color.toArgb(), alphaByte)
        canvas.drawText(line, x, y, paint)
    }

    /** 把颜色的 RGB 通道与给定 alpha 合成为一个新的 ARGB。 */
    private fun withAlpha(argb: Int, alphaByte: Int): Int {
        return (argb and 0x00FFFFFF) or (alphaByte shl 24)
    }

    /**
     * 构建 Paint：颜色、字号、字体族、加粗斜体、alpha、阴影。
     * 所有与尺寸相关的数值都乘以 ctx.scale，保证缩略图与原图视觉一致。
     *
     * 性能优化（流畅度关键）：
     * 1. Typeface 按 (族, 样式) 缓存 —— 构造 Typeface 开销大，
     *    而拖滑块时每帧都会调用本方法。默认字体尤其常见。
     * 2. Paint 也缓存复用 —— 但注意 alpha 会在 drawTextLine 中被改写，
     *    所以这里每次只 set 必要的属性。
     */
    private fun buildPaint(config: TextWatermarkStyle, ctx: RenderContext): Paint {
        val paint = paintPool
        paint.reset()

        paint.isAntiAlias = true
        // 字号：配置值 × 缩放系数
        paint.textSize = (config.fontSize * ctx.scale).coerceAtLeast(1f)
        // 颜色（alpha 在 drawTextLine 中与颜色合成后统一设置）
        paint.color = config.color.color.toArgb()
        // 字体
        paint.typeface = resolveTypeface(config)

        // 阴影
        if (config.shadowEnabled) {
            paint.setShadowLayer(
                config.shadowRadius * ctx.scale,
                0f,
                0f,
                config.shadowColor.color.toArgb()
            )
        }

        return paint
    }

    /**
     * 解析字体，带缓存。
     *
     * 缓存 key 为 (字体种类, 是否加粗, 是否斜体, 自定义字体名)。
     * 最常用的 DEFAULT + 无样式 会在首次后直接命中，省去 Typeface.create。
     */
    private fun resolveTypeface(config: TextWatermarkStyle): Typeface {
        val style = when {
            config.bold && config.italic -> Typeface.BOLD_ITALIC
            config.bold -> Typeface.BOLD
            config.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        // 自定义字体优先
        if (config.fontFamily == FontFamilyOption.CUSTOM) {
            config.customFontName?.let { name ->
                customFontResolver?.resolve(name)?.let { return it }
            }
            // 加载失败 → 回退默认（走下面的缓存逻辑）
        }

        val key = TypefaceKey(config.fontFamily, style)
        typefaceCache[key]?.let { return it }

        val baseFamily = when (config.fontFamily) {
            FontFamilyOption.SERIF -> Typeface.SERIF
            FontFamilyOption.MONOSPACE -> Typeface.MONOSPACE
            // CURSIVE 在 Android 上映射到手写风格
            FontFamilyOption.CURSIVE -> Typeface.create("cursive", style)
            // 自定义字体加载失败 → 回退默认
            FontFamilyOption.CUSTOM -> Typeface.DEFAULT
            FontFamilyOption.DEFAULT -> Typeface.DEFAULT
        }
        val resolved = if (config.fontFamily == FontFamilyOption.CURSIVE) {
            baseFamily
        } else {
            Typeface.create(baseFamily, style)
        }
        typefaceCache[key] = resolved
        return resolved
    }

    /** Typeface 缓存键。 */
    private data class TypefaceKey(val family: FontFamilyOption, val style: Int)

    /** 解析占位符文本。 */
    private fun resolveText(config: TextWatermarkStyle, sourceFileName: String?): String {
        return PlaceholderResolver.resolve(config.text, sourceFileName)
    }

    companion object {
        /**
         * 透明度允许的最小值。
         *
         * 面板滑块下限为 1%，因此这里同步为 0.01。
         * 用常量集中管理，避免多处 clamp 不一致。
         */
        const val MIN_ALPHA = 0.01f

        /**
         * 计算带阴影的绘制边界。
         */
        fun applyStrokeColor(paint: Paint, color: Int, width: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.color = color
        }

        fun applyFillColor(paint: Paint, color: Int) {
            paint.style = Paint.Style.FILL
            paint.color = color
        }
    }
}

/**
 * 计算带阴影的绘制边界（供未来扩展裁剪/居中优化使用）。
 */
internal fun Paint.measureBounds(text: String): Rect {
    val w = ceil(measureText(text)).toInt()
    val fm = fontMetrics
    val h = ceil(fm.descent - fm.ascent).toInt()
    return Rect(0, 0, w, h)
}