package com.watermarkbox.watermark

import android.graphics.Bitmap
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一次预览渲染请求 —— 渲染所需参数的**快照**。
 *
 * 用 `data class` 是为了让 `StateFlow` 自动去重：
 * 参数没变（`equals` 相等）就不会重复渲染，这在
 * 「拖动面板导致重组、但水印参数没变」时能省下整轮渲染。
 */
data class PreviewRequest(
    val source: Bitmap,
    val watermarkType: WatermarkType,
    val textConfig: TextWatermarkStyle,
    val imageConfig: ImageWatermarkStyle,
    val logoBitmap: Bitmap?,
    val sourceFileName: String?
)

/**
 * 预览渲染引擎：**把渲染搬出主线程**，并且只保留最新一次请求。
 *
 * ## 为什么需要它
 *
 * 原先预览是这么算出来的：
 *
 * ```kotlin
 * val rendered by remember { derivedStateOf { PreviewRenderer.render(...) } }
 * ```
 *
 * `derivedStateOf` 的值在**组合阶段被读取时同步计算** ——
 * 也就是跑在主线程、卡在帧里。拖滑块、拖水印时每帧都要算一遍，
 * 单帧成本虽小（实测 1~3 ms），但它和布局、绘制挤在同一个 16 ms 预算里，
 * 图片一大、效果一复杂就可能把帧顶破。
 *
 * 放到后台线程后：**UI 线程永远不做渲染**，画面最多晚一两帧跟上，
 * 但绝不会因为「算得慢」而掉帧。
 *
 * ## 为什么只保留最新请求（conflation）
 *
 * 拖动时参数每帧都在变，如果每次都排队渲染，后台会堆一串
 * **已经过时**的活。用 `collectLatest` + `StateFlow`：
 * - `StateFlow` 本身会合并：来不及消费的中间值直接跳过
 * - `collectLatest` 会取消上一次渲染：只认最后一次
 *
 * 于是渲染次数上限 = 「渲染耗时允许的次数」，与输入频率解耦。
 *
 * ## 为什么提供 [rendering]
 *
 * 首次渲染时结果还没出来。UI 需要知道「是在渲染」而不是
 * 「没有图片」，否则会先闪一下「请选择图片」占位。
 *
 * ## 为什么是泛型的
 *
 * 单元测试跑在 JVM 上，构造不了 `android.graphics.Bitmap`。
 * 泛型化之后，测试可以用 `String` 驱动整套逻辑（合并请求、发布结果、
 * 渲染标志位），而生产侧照样传 `PreviewRequest` 与 `Bitmap`。
 *
 * @param render 真正的渲染动作。抽成参数既是为了可测，
 *        也是为了让本类不依赖 [PreviewRenderer] 的具体写法定型。
 */
class PreviewRenderEngine<T : Any, R : Any>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val render: suspend (T) -> R?
) {

    private val requests = MutableStateFlow<T?>(null)

    private val _result = MutableStateFlow<R?>(null)

    /** 最新一次渲染结果；新请求在途时**保留上一个**，避免画面闪空。 */
    val result: StateFlow<R?> = _result.asStateFlow()

    private val _rendering = MutableStateFlow(false)

    /** 是否有渲染正在进行。 */
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    init {
        scope.launch {
            requests.filterNotNull().collectLatest { request ->
                _rendering.value = true
                // 被 collectLatest 取消时，这里会抛 CancellationException，
                // 于是**不会**写回结果 —— 过期的画面不该覆盖新的
                val rendered = withContext(dispatcher) {
                    try {
                        render(request)
                    } catch (cancellation: CancellationException) {
                        // 取消必须照常向上传播（见上）
                        throw cancellation
                    } catch (error: Throwable) {
                        // ⚠️ 单次渲染失败**绝不能**把这条流水线带走：
                        // 协程一死，之后所有 submit 都会石沉大海 ——
                        // 表现就是「预览从此再也不更新」，用户只能重启应用。
                        // 当作「这次没有结果」，下一次请求照常处理。
                        null
                    }
                }
                _result.value = rendered
                _rendering.value = false
            }
        }
    }

    /** 提交一次渲染请求（非阻塞）。 */
    fun submit(request: T) {
        requests.value = request
    }
}