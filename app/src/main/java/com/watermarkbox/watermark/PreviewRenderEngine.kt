package com.watermarkbox.watermark

import android.graphics.Bitmap
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    val sourceFileName: String?,
    /**
     * **原图**宽高（不是 [source] 的宽高）。
     *
     * 参与 `data class` 的 equals —— 换图（原图尺寸变了）时必须
     * 触发新一轮渲染，否则 scale 还是旧的。
     *
     * 详见 [PreviewRenderer.render] 的 `sourceBaseWidth` 说明。
     */
    val baseWidth: Int = 0,
    val baseHeight: Int = 0
)

/**
 * 「本次渲染是否已被取消」的查询口。
 *
 * ## 为什么必须显式传这个，而不是靠协程自动取消
 *
 * 渲染链路（[PreviewRenderer] → [WatermarkRenderer]）是**完全同步**的：
 * 没有 `delay`、没有线程切换、没有 `yield`。Kotlin 协程的取消
 * **只在挂起点生效** —— 对一个同步阻塞函数，`collectLatest`
 * 发出的取消只能「排个队」，要等函数自己跑完才抛出来。
 *
 * 后果（老大实测的频闪根因）：
 *
 * ```
 * frame1  开始渲染 A（约 20ms，全同步）
 * frame2  collectLatest 取消 A → 但 A 正阻塞着，取消被挂起
 * frame3  新请求 → 又起一个协程渲染 B
 *         ⚠️ 此刻 A 还在跑，两个渲染【并发写同一批缓冲】
 * frame4  第三个渲染 C 又开始…
 * ```
 *
 * 多个渲染并发写同一组复用位图 → UI 看到的画面在
 * 「A 的半成品 / B 的半成品 / C 的半成品」之间跳 → **频闪**。
 * 松手后不再有新请求，渲染重新串行化，画面就恢复了 ——
 * 这正是「拖动时闪、停下就不闪」的原因。
 *
 * 所以要把「已取消」这个信息**传进同步渲染代码**，
 * 让它在耗时的循环里主动检查并提前退出。
 *
 * 预览默认传 `{ false }`（不取消）；导出/批量等一次性任务也传它，
 * 保持行为一致。
 */
fun interface RenderCancelCheck {
    fun isCancelled(): Boolean

    companion object {
        /** 永不取消。用于导出、批量等不需要中途放弃的场景。 */
        val Never = RenderCancelCheck { false }
    }
}

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
    /**
     * 真正的渲染动作。
     *
     * ## 第二个参数为什么是「取消检查」
     *
     * 渲染链路是**完全同步**的（见 [RenderCancelCheck] 的说明），
     * 协程取消无法中断它。所以把 `isActive` 以函数形式传进去，
     * 让渲染代码在耗时的循环里主动检查、提前退出 ——
     * 这是避免「多个渲染并发写同一批缓冲 → 频闪」的唯一办法。
     */
    private val render: suspend (T, RenderCancelCheck) -> R?,
    /**
     * 渲染**成功完成且未被取消**后调用，参数是刚发布的结果。
     *
     * ## 为什么需要这个回调，而不是让 render 自己调
     *
     * 早期写法是让 `render` 内部在画完之后直接调缓冲池的 `onPublished`。
     * 问题在于：`render` 被取消时（拖滑块每帧都取消），
     * **onPublished 已经执行了** —— 缓冲池以为这块已经"发布给 UI"，
     * 于是下轮 acquire 时优先避开它、改用另一块；
     * 可实际上 UI 那边 **StateFlow 根本没更新**（结果被取消丢弃了）。
     *
     * 结果：缓冲池的「谁在显示」认知与 UI 实际显示**脱节**，
     * 保护窗口护错了对象 → 后台写进 UI 正在画的那块 → 撕裂/频闪。
     *
     * 放到引擎里、只在**未被取消**时调用，两者就始终一致。
     */
    private val onRendered: ((R?) -> Unit)? = null
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

                // ---- 用一个「哨兵」把「失败」和「真的没内容」分开 ----
                //
                // 早期写法是：
                //     val rendered = try { render(request) } catch (t: Throwable) { null }
                //     _result.value = rendered
                //
                // 问题：**null 同时表示两件完全不同的事** ——
                //   (a) 渲染过程抛异常（Bitmap 正被回收、参数非法…）
                //   (b) 渲染成功，但确实没有内容（source 为 null）
                //
                // 对 (a)，把画面置空是错的：用户看到的是「我的图不见了」，
                // 而实际上图还在，只是这一帧没算出来。
                //
                // **拖动滑块时这个 bug 被放大**：参数每帧都变 →
                // collectLatest 每帧取消上一次渲染 → 取消/失败次数暴增 →
                // 结果被反复置成 null → 画面持续闪烁。
                val failed = Any()
                // 先在**挂起上下文**里取一次「是否已取消」的查询口。
                //
                // ⚠️ 不能在 render 的 lambda 里直接调 currentCoroutineContext()
                // ——它是挂起函数，而 lambda 本身不是挂起函数（类型签名是
                // (T, RenderCancelCheck) -> R?，不是 suspend）。所以这里
                // 提前把 coroutineContext 捕获成普通对象，
                // 用它的 isActive 做查询，等价且编译得过。
                val context = currentCoroutineContext()
                val rendered: Any? = try {
                    withContext(dispatcher) {
                        // 把「是否已取消」传给同步渲染代码 —— 它在耗时的
                        // 平铺循环里会主动检查，被取消就立刻退出，
                        // 不再往缓冲里画（见 RenderCancelCheck 的说明）。
                        render(request) { !context.isActive }
                    }
                } catch (cancellation: CancellationException) {
                    // 取消是我们**主动**发的（有新请求了）：这一次的结果
                    // 本就该丢弃，直接向上抛，什么都不写回。
                    //
                    // ⚠️ 注意：**不能**在这里回调 onRendered ——
                    // 结果没发布给 UI，缓冲池就不该认为它「正在显示」，
                    // 否则保护窗口会护错对象（见 onRendered 的文档）。
                    throw cancellation
                } catch (error: Throwable) {
                    // 渲染失败：标记为 failed，下面不覆盖画面
                    failed
                }

                when {
                    // 失败 → **不动 _result**，保留上一帧，界面不会闪
                    rendered === failed -> Unit
                    // 成功 → 如实写回（含「确实没有内容」的 null）
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        val result = rendered as R?
                        // 先让缓冲池更新「谁在显示」，再更新 StateFlow ——
                        // 顺序很重要：UI 一旦收到新结果并开始画它，
                        // 缓冲池必须已经知道那块受保护
                        onRendered?.invoke(result)
                        _result.value = result
                    }
                }
                _rendering.value = false
            }
        }
    }

    /** 提交一次渲染请求（非阻塞）。 */
    fun submit(request: T) {
        requests.value = request
    }
}