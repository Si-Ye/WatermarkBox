package com.watermarkbox.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.withRotation
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
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
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
     * @param cancel    取消检查点。平铺循环里会周期性查询，被取消则
     *                  立即停止后续绘制并抛出 [CancellationException]。
     *
     *                  **为什么必须显式传**：本方法及其下游是**完全同步**的
     *                  （无 `delay`、无线程切换），Kotlin 协程的取消
     *                  **只在挂起点生效** —— 同步函数里的取消只能排队，
     *                  要等函数自己跑完才抛出来。
     *
     *                  后果（老大实测的预览频闪根因）：拖滑块时
     *                  `collectLatest` 每帧都取消上一次，但每次渲染都
     *                  阻塞着跑完 → 多个渲染**并发**写同一批复用位图
     *                  → UI 看到的画面在各自半成品之间跳 → 频闪。
     *
     *                  所以把「是否已取消」传进来，让耗时的平铺循环
     *                  主动退出。
     *
     * @return 渲染后的新 Bitmap
     */
    fun render(
        source: Bitmap,
        config: TextWatermarkStyle,
        sourceFileName: String? = null,
        baseWidth: Int = source.width,
        baseHeight: Int = source.height,
        cancel: RenderCancelCheck = RenderCancelCheck.Never
    ): Bitmap {
        val ctx = RenderContext(
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            targetWidth = source.width,
            targetHeight = source.height
        )
        return when (config.layoutMode) {
            LayoutMode.SINGLE -> renderSingle(source, config, sourceFileName, ctx)
            LayoutMode.TILED -> renderTiled(source, config, sourceFileName, ctx, cancel)
            LayoutMode.DIAGONAL -> renderDiagonal(source, config, sourceFileName, ctx, cancel)
        }
    }

    /**
     * 预览渲染时可选的**复用目标**（默认 `null` = 每次新分配）。
     *
     * ## 为什么需要它
     *
     * 预览每帧都要渲染一次，而每帧 `source.copy()` 一张 1024 边的
     * ARGB_8888 位图约 3 MB。拖动时 60 帧/秒 ≈ **180 MB/s 的原生分配** ——
     * 这类大块原生分配会通过 NativeAllocationRegistry 触发 GC，
     * 表现就是「拖起来偶尔一顿」。
     *
     * 复用后：内存不再每帧申请归还，只做一次位块传送。
     *
     * ## ⚠️ 设置者的义务
     *
     * 传进来的这张**绝不能是当前正在显示的那张**：写它的时候 UI 可能
     * 正在画它，用户会看到「画到一半」的撕裂画面。
     * 预览侧用 [PreviewBuffers] 的双缓冲来保证这一点。
     *
     * 其他调用方（导出、批量、预设页）不要设置它 —— 那些路径需要
     * 「返回的位图归调用方所有」，复用会破坏这个约定。
     */
    var reuseTarget: Bitmap? = null

    /**
     * 取得本次渲染要画的位图：能复用就复用，否则新分配一张。
     *
     * 复用分支做的事等价于 `source.copy(...)`：
     * 先擦透明，再把原图垫上去。
     */
    private fun outputFor(source: Bitmap): Bitmap {
        val reuse = reuseTarget
        val result = if (reuse == null || reuse.isRecycled || !reuse.isMutable ||
            reuse.width != source.width || reuse.height != source.height ||
            reuse.config != Bitmap.Config.ARGB_8888
        ) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            // 把源图整块盖到复用位图上。
            //
            // ## 为什么用 Canvas.drawBitmap（而不是 copyPixelsFromBuffer）
            //
            // 曾经试过 `copyPixelsToBuffer` / `copyPixelsFromBuffer` 走裸内存拷贝，
            // 想省掉绘制管线开销。结果**出现水印重影**：
            //
            // `copyPixelsToBuffer` 写出的是**含行对齐填充**的整块数据
            // （长度 = `allocationByteCount`，可能 > `width * height * 4`）；
            // 而 `copyPixelsFromBuffer` 是按 `width * height * 4` 从头读的。
            // 两者对不齐时，像素会**错位写入** —— 表现为旧水印没被完全覆盖。
            //
            // 这类 bug 很隐蔽（多数设备上 rowStride 恰好等于 width*4 时不复现），
            // 而且收益有限：降采样到 720 边后，drawBitmap 只要约 9ms。
            // **画面正确性优先于这点性能**，所以回到语义最直白的写法。
            //
            // ⚠️ 必须先擦除，再画源图。
            //
            // 只靠 `drawBitmap` 覆盖看起来"应该够"（源图不透明、铺满全图），
            // 但实测出现过**水印重影**：某些机型/某些 alpha 配置下，
            // 源图边缘或半透明像素不会完全覆盖旧内容，上一帧的水印就残留下来。
            //
            // 这次擦除的成本已经很小：720 边的位图约 0.9MB，
            // `eraseColor` 走的是原生填充，实测耗时在 1ms 量级 ——
            // 相比"画面干净"这个正确性要求，完全值得。
            reuse.eraseColor(0)
            Canvas(reuse).drawBitmap(source, 0f, 0f, null)
            reuse
        }
        return result
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
        val output = outputFor(source)
        val canvas = Canvas(output)

        val paint = buildPaint(config, ctx)
        val text = resolveText(config, sourceFileName)

        // 多行文本测量：取最长行宽作为锚定宽度
        val lines = text.split("\n")
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }
        val blockWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val blockHeight = lineHeight * lines.size

        val margin = config.margin * ctx.scale

        // ---- 位置计算 ----
        //
        // 两种来源：
        //   1. 九宫格方位（TOP_LEFT ... BOTTOM_RIGHT）：按边距对齐
        //   2. CUSTOM：用归一化坐标 (0~1) 算，由「在预览图上拖动水印」产生
        //
        // 归一化坐标存的是**水印块中心**的位置，所以算左上角时要减掉半宽半高。
        val blockLeft: Float
        val topY: Float
        if (config.gravity == GravityPosition.CUSTOM) {
            val cx = config.customX.coerceIn(0f, 1f) * output.width
            val cy = config.customY.coerceIn(0f, 1f) * output.height
            blockLeft = cx - blockWidth / 2f
            topY = cy - blockHeight / 2f
        } else {
            blockLeft = when (config.gravity) {
                GravityPosition.TOP_LEFT, GravityPosition.CENTER_LEFT, GravityPosition.BOTTOM_LEFT ->
                    margin
                GravityPosition.TOP_CENTER, GravityPosition.CENTER, GravityPosition.BOTTOM_CENTER ->
                    (output.width - blockWidth) / 2f
                else -> output.width - blockWidth - margin
            }
            topY = when (config.gravity) {
                GravityPosition.TOP_LEFT, GravityPosition.TOP_CENTER, GravityPosition.TOP_RIGHT ->
                    margin
                GravityPosition.CENTER_LEFT, GravityPosition.CENTER, GravityPosition.CENTER_RIGHT ->
                    (output.height - blockHeight) / 2f
                else -> output.height - blockHeight - margin
            }
        }

        val baseLineX = blockLeft
        // 基线位置：先到首行顶部，再加 -ascent 得到首行基线
        val baseLineY = topY - paint.fontMetrics.ascent

        // 整体旋转：**绕水印自身中心**，而不是绕画布中心。
        //
        // ## 为什么（踩坑记录）
        //
        // 之前写的是 `canvas.rotate(deg, output.width/2, output.height/2)`
        // ——绕**画布中心**转。水印在画面正中时看着没问题，
        // 但一旦把位置选到四角（比如右下角），旋转就变成了「绕画布中心公转」，
        // 水印会被甩到画布外面去。用户看到的现象就是「选外圈位置时旋转不对」。
        //
        // 正确做法是绕**水印块自己的中心**旋转，这样无论水印放在哪，
        // 都是「原地打转」，位置不会被旋转带跑偏。
        val blockCenterX = baseLineX + blockWidth / 2f
        val blockCenterY = topY + blockHeight / 2f
        // 用 KTX 的 withRotation 代替手写 save/rotate/restore：
        // 它内部就是 save → rotate → block → restore，而且**异常时也会 restore**
        // —— 手写版本一旦中间抛异常，save 就永远回不来了。
        var y = baseLineY
        canvas.withRotation(config.rotationDegrees, blockCenterX, blockCenterY) {
            for (line in lines) {
                drawTextLine(canvas, line, baseLineX, y, paint, config, ctx.scale)
                y += lineHeight
            }
        }

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
        ),
        cancel: RenderCancelCheck = RenderCancelCheck.Never
    ): Bitmap {
        val output = outputFor(source)
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

        // ---- 旋转：逐字旋转（与「对角线」的整体旋转相反）----
        //
        // ## 两个模式的旋转职责（用户明确要求）
        //
        // - **平铺**：网格轴对齐、**每个水印自己按滑块角度倾斜**。
        //   文字之间互相平行，但排列是水平整齐的网格。
        // - **对角线**：**整个坐标系转 45°**，水印呈斜向条带铺满。
        //
        // 这两个模式的旋转逻辑此前是反的（平铺做整体旋转、对角线做逐字旋转），
        // 现在对调回来。
        //
        // ## 为什么不是「整体 canvas.rotate()」
        //
        // 整体旋转会让网格一起转，那样效果就和对角线重了。
        // 平铺要保持「整齐的方格排列」，所以只让**单个水印**倾斜 ——
        // 绕它自己的绘制原点做一次 rotate。
        //
        // 当角度为 0 时不做任何旋转，避免多余的 save/restore 开销。
        val rotateEach = config.rotationDegrees

        // 嵌套循环：行 → 列，逐个绘制
        //
        // ⚠️ **取消检查点**（本函数是整条渲染链路里唯一耗时的地方）：
        //
        // 每画完**一行**检查一次「是否已被取消」。粒度选「行」而非「格」：
        // - 一格的开销在微秒级，每格检查反而让检查本身成为负担
        // - 一行通常几十格，即便大图上也能在几十微秒内响应取消
        //
        // 为什么必须检查：本函数**完全同步**（无挂起点），协程的
        // `collectLatest` 取消无法中断它。不检查的后果是拖滑块时
        // 多个渲染**并发**写同一批缓冲 → 画面在各自半成品间跳 → 频闪。
        // 详见 [RenderCancelCheck] 的完整说明。
        for (row in 0..rows) {
            if (cancel.isCancelled()) {
                // 抛出 CancellationException 让上层协程按取消处理：
                // 不写结果、不覆盖画面。
                throw CancellationException("预览渲染已被新请求取代")
            }
            for (col in 0..cols) {
                // 奇数行做半格错位，形成砖块式平铺，视觉更均匀
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = col * cellWidth + offsetX
                val y = row * cellHeight + lineHeight

                if (rotateEach != 0f) {
                    // KTX 包装：不再手写 save/restore（异常路径也不会漏 restore）
                    canvas.withRotation(rotateEach, x, y) {
                        drawTextLine(canvas, text, x - textWidth / 2f, y, paint, config, ctx.scale)
                    }
                } else {
                    drawTextLine(canvas, text, x - textWidth / 2f, y, paint, config, ctx.scale)
                }
            }
        }

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
        ),
        cancel: RenderCancelCheck = RenderCancelCheck.Never
    ): Bitmap {
        val output = outputFor(source)
        val canvas = Canvas(output)

        val paint = buildPaint(config, ctx)
        val text = resolveText(config, sourceFileName).replace("\n", " ")
        val textWidth = paint.measureText(text)
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent }

        val count = config.tileCount.coerceIn(1, 20)

        // 对角线 = 整体旋转 45°（再叠加用户自定义角度）
        val angle = -45f + config.rotationDegrees

        // ---- 网格尺寸 ----
        //
        // 用**图片对角线长度**做密度基准：对角线上放 count 个水印。
        // 这样横图竖图密度观感一致（都按「最长方向」衡量）。
        val diagonal = kotlin.math.sqrt(
            (output.width * output.width + output.height * output.height).toDouble()
        ).toFloat()
        val step = diagonal / count
        val cellWidth = step
        val cellHeight = max(step / 1.6f, lineHeight * 1.4f)

        // ---- 铺开方式：整体旋转坐标系 + 按「旋转后包围盒」铺满 ----
        //
        // ## 与「平铺」的区别（本次重构）
        //
        // - 平铺：不做整体旋转（只按用户角度微调），文字水平，网格轴对齐。
        // - 对角线：**整个坐标系转 45°**，水印呈斜向条带排列。
        //
        // 之前的实现是「逐格旋转」——每个文字自己斜 45°，但网格还是轴对齐的。
        // 那样看起来不像「斜着铺满」，只是「一堆斜着的字排在方格里」。
        //
        // ## 为什么必须按「旋转后包围盒」铺（踩坑记录）
        //
        // 整体 canvas.rotate(45°) 之后，**画布自身也被转了**：
        //   在旋转后的坐标系里，画布是一个**斜矩形**，它的包围盒比原画布大得多
        //   （3000×1000 转 45° 后包围盒约 2828×2828）。
        //
        // 如果还按「原画布尺寸」铺网格，旋转后四个角就会空出来 ——
        // 这正是用户看到的「对角线模式下角上没有水印」。
        //
        // 所以这里按**旋转后包围盒**的尺寸铺网格，再整体旋转坐标系。
        // 网格本身仍然轴对齐（在旋转坐标系里），但因为整体转了，视觉上就是斜纹。
        val bounds = rotatedBounds(output.width.toFloat(), output.height.toFloat(), angle)
        val originX = output.width / 2f
        val originY = output.height / 2f

        // 在旋转坐标系里，画布包围盒是 [originX + left, originX + left + width] × ...
        // 从包围盒左上角再外扩一格开始铺，保证四角都被覆盖。
        val startX = originX + bounds.left - cellWidth
        val startY = originY + bounds.top - cellHeight
        val cols = ceil((bounds.width + cellWidth * 2) / cellWidth).toInt() + 1
        val rows = ceil((bounds.height + cellHeight * 2) / cellHeight).toInt() + 1

        canvas.withRotation(angle, originX, originY) {
            // ⚠️ 取消检查点，见 renderTiled 里同一处的说明。
            // 抛出的异常会经由 withRotation 内部的 finally 正确 restore 画布状态。
            for (row in 0..rows) {
                if (cancel.isCancelled()) {
                    throw CancellationException("预览渲染已被新请求取代")
                }
                for (col in 0..cols) {
                    val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                    val x = startX + col * cellWidth + offsetX
                    val y = startY + row * cellHeight
                    drawTextLine(canvas, text, x - textWidth / 2f, y, paint, config, ctx.scale)
                }
            }
        }

        return output
    }

    /** 旋转后矩形（相对中心）的包围盒，left/top 为负值表示在原矩形左/上方向。 */
    private data class RotatedBounds(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    /**
     * 计算「以矩形中心为原点，旋转 [angleDeg] 度后」的包围盒。
     *
     * 返回的 left/top 是**相对中心**的偏移（可能是负数）。
     *
     * 原理：把矩形四角绕原点旋转，取 min/max。
     * 对 ±θ 而言结果对称，这里不假设对称，老老实实算四个角，
     * 以便支持任意角度（用户可自定义 rotationDegrees）。
     *
     * 注：对角线水印已改用「原图坐标系铺网格 + 逐格旋转」的方案，
     * 不再需要它；保留是因为做旋转相关的边界计算时它仍然好用。
     */
    @Suppress("unused")
    private fun rotatedBounds(w: Float, h: Float, angleDeg: Float): RotatedBounds {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val hw = w / 2f
        val hh = h / 2f
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val corners = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        for (i in 0 until 4) {
            val dx = corners[i * 2]
            val dy = corners[i * 2 + 1]
            xs[i] = dx * cosA - dy * sinA
            ys[i] = dx * sinA + dy * cosA
        }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        return RotatedBounds(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * 判断「旋转坐标系下的一个格子」是否与图片区域相交。
     *
     * ## 演进过程（踩坑记录，共三版）
     *
     * **第一版**：只判断格子**左上角**是否落在图片矩形内。
     * 问题：格子有宽高，左上角稍一出界整格就被跳过，而它右半 / 下半
     * 明明还盖在图上 → 边缘断档。宽图尤其明显。
     *
     * **第二版**：判断格子**四角 + 中心**共 5 点，任一落在图片内即绘制。
     * 问题：45° 旋转下，格子的四角在「原图坐标系」里会**伸出很远**
     * （一个 632×395 的格子旋转 45° 后，对角夸度约 745）。
     * 这些外伸的角大量落在图片外，但同时又因为 pad 容差而勉强通过 ——
     * 判定结果变得不稳定，实测水印只覆盖画布宽度的 68%。
     *
     * **第三版（当前）**：换个角度 —— 不去判断格子，而是把**图片的四个角
     * 反向旋转**到「旋转坐标系」中，得到一个**菱形**。然后判断格子中心
     * 是否落在这个菱形内（外扩半格容差）。
     *
     * 这样逻辑直观、结果稳定：只要格心在画布覆盖范围内就画。
     * 菱形外的一律跳过，不会有「外伸四角」带来的误判。
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

        // 把「旋转坐标系里的格心」反向旋转，映射回「原图坐标系」
        val rad = Math.toRadians(-angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = (x + cellW / 2f) - cx
        val dy = (y + cellH / 2f) - cy
        val px = cx + (dx * cosA - dy * sinA)
        val py = cy + (dx * sinA + dy * cosA)

        // 格心落在图片内（外扩半个格子，保证边缘不断档）即绘制。
        // 半格容差用 min(cellW, cellH)/2 的保守值，避免外扩过多画出屏幕外。
        val padX = cellW / 2f
        val padY = cellH / 2f
        return px >= -padX && px <= w + padX && py >= -padY && py <= h + padY
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
        val output = outputFor(source)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val margin = config.margin * ctx.scale

        // 位置：九宫格方位 或 自定义归一化坐标（见 TextWatermarkStyle 的说明）
        val left: Float
        val top: Float
        if (config.gravity == GravityPosition.CUSTOM) {
            val cx = config.customX.coerceIn(0f, 1f) * output.width
            val cy = config.customY.coerceIn(0f, 1f) * output.height
            left = cx - w / 2f
            top = cy - h / 2f
        } else {
            left = when (config.gravity) {
                GravityPosition.TOP_LEFT, GravityPosition.CENTER_LEFT, GravityPosition.BOTTOM_LEFT ->
                    margin
                GravityPosition.TOP_CENTER, GravityPosition.CENTER, GravityPosition.BOTTOM_CENTER ->
                    (output.width - w) / 2f
                else -> output.width - w - margin
            }
            top = when (config.gravity) {
                GravityPosition.TOP_LEFT, GravityPosition.TOP_CENTER, GravityPosition.TOP_RIGHT ->
                    margin
                GravityPosition.CENTER_LEFT, GravityPosition.CENTER, GravityPosition.CENTER_RIGHT ->
                    (output.height - h) / 2f
                else -> output.height - h - margin
            }
        }

        val dst = Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())

        // 绕 logo 自身中心旋转（不是画布中心）。
        // 绕画布中心转会把边角的 logo 甩出画面，详见 renderSingle 的踩坑记录。
        canvas.withRotation(config.rotationDegrees, left + w / 2f, top + h / 2f) {
            canvas.drawBitmap(logo, null, dst, paint)
        }
        return output
    }

    private fun renderImageTiled(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Bitmap {
        val output = outputFor(source)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val count = config.tileCount.coerceIn(1, 20)
        val cellWidth = output.width.toFloat() / count
        val cellHeight = max(cellWidth / 1.6f, h * 1.4f)

        val cols = ceil(output.width / cellWidth).toInt() + 1
        val rows = ceil(output.height / cellHeight).toInt() + 1

        // 旋转：逐格旋转（与「对角线」的整体旋转相反，与文字平铺保持一致）
        val rotateEach = config.rotationDegrees

        for (row in 0..rows) {
            for (col in 0..cols) {
                val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                val x = col * cellWidth + offsetX
                val y = row * cellHeight
                if (rotateEach != 0f) {
                    // KTX 包装：不再手写 save/restore（异常路径也不会漏 restore）
                    canvas.withRotation(rotateEach, x + w / 2f, y + h / 2f) {
                        canvas.drawBitmap(
                            logo, null,
                            Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()),
                            paint
                        )
                    }
                } else {
                    canvas.drawBitmap(
                        logo, null,
                        Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()),
                        paint
                    )
                }
            }
        }
        return output
    }

    private fun renderImageDiagonal(
        source: Bitmap,
        logo: Bitmap,
        config: ImageWatermarkStyle,
        ctx: RenderContext
    ): Bitmap {
        val output = outputFor(source)
        val canvas = Canvas(output)
        val paint = buildLogoPaint(config)

        val (w, h) = computeLogoSize(logo, config, ctx)
        val count = config.tileCount.coerceIn(1, 20)

        val angle = -45f + config.rotationDegrees

        // 网格尺寸：对角线等分（与文字对角线一致）
        val diagonal = kotlin.math.sqrt(
            (output.width * output.width + output.height * output.height).toDouble()
        ).toFloat()
        val step = diagonal / count
        val cellWidth = step
        val cellHeight = max(step / 1.6f, h * 1.4f)

        // 铺开方式：与文字对角线一致 —— 整体旋转坐标系，
        // 并按「旋转后包围盒」铺满网格。原因见 renderDiagonal 的踩坑记录。
        val bounds = rotatedBounds(output.width.toFloat(), output.height.toFloat(), angle)
        val originX = output.width / 2f
        val originY = output.height / 2f

        val startX = originX + bounds.left - cellWidth
        val startY = originY + bounds.top - cellHeight
        val cols = ceil((bounds.width + cellWidth * 2) / cellWidth).toInt() + 1
        val rows = ceil((bounds.height + cellHeight * 2) / cellHeight).toInt() + 1

        canvas.withRotation(angle, originX, originY) {
            for (row in 0..rows) {
                for (col in 0..cols) {
                    val offsetX = if (row % 2 == 1) cellWidth / 2f else 0f
                    val x = startX + col * cellWidth + offsetX
                    val y = startY + row * cellHeight
                    canvas.drawBitmap(
                        logo, null,
                        Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()),
                        paint
                    )
                }
            }
        }
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