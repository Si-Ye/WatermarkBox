package com.watermarkbox.watermark

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PreviewRenderEngine] 的单元测试。
 *
 * ## 怎么在 JVM 单测里驱动协程
 *
 * 用 `Dispatchers.Unconfined` 建 scope 与渲染调度器：
 * 提交会**同步**走完整条链路，断言不必等、也不会 flaky。
 *
 * 所以这里用 `String` 代替 `Bitmap` —— 引擎的逻辑（合并最新请求、
 * 发布结果、渲染标志位）跟渲染出什么无关。
 */
class PreviewRenderEngineTest {

    /** 把 `submit` 当参数用的假引擎：渲染结果就是参数加个前缀。 */
    private fun engineWith(
        scopeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        renderDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        render: suspend (String) -> String
    ) = PreviewRenderEngine<String, String>(
        scope = CoroutineScope(scopeDispatcher),
        dispatcher = renderDispatcher,
        render = render
    )

    private fun engineWith(render: suspend (String) -> String) =
        engineWith(Dispatchers.Unconfined, Dispatchers.Unconfined, render)

    @Test
    fun `提交后发布渲染结果`() {
        val engine = engineWith { "bmp:$it" }
        engine.submit("hello")
        assertEquals("bmp:hello", engine.result.value)
        assertFalse(engine.rendering.value)
    }

    /** 参数没变就不该重复渲染（StateFlow 去重）。 */
    @Test
    fun `相同请求不重复渲染`() {
        val calls = AtomicInteger()
        val engine = engineWith { calls.incrementAndGet(); "bmp:$it" }
        engine.submit("same")
        engine.submit("same")
        engine.submit("same")
        assertEquals(1, calls.get())
    }

    @Test
    fun `参数变化会重新渲染`() {
        val calls = AtomicInteger()
        val engine = engineWith { calls.incrementAndGet(); "bmp:$it" }
        engine.submit("a")
        engine.submit("b")
        engine.submit("c")
        assertEquals(3, calls.get())
        assertEquals("bmp:c", engine.result.value)
    }

    /**
     * 渲染必须**离开调用线程** —— 这正是本类存在的理由。
     *
     * 用真实的 `Dispatchers.Default`（工作线程池）验证：
     * 渲染线程不应是提交所在的测试线程。
     */
    @Test
    fun `渲染不在调用线程上执行`() {
        val callerThread = Thread.currentThread()
        var renderThread: Thread? = null
        val engine = engineWith(
            scopeDispatcher = Dispatchers.Default,
            renderDispatcher = Dispatchers.Default,
            render = { renderThread = Thread.currentThread(); "ok" }
        )
        engine.submit("x")

        // 线程池是别的线程，等它写回结果（给足余量，避免慢机器上 flaky）
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.result.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("ok", engine.result.value)
        assertNotEquals(callerThread, renderThread)
    }

    /** 渲染抛异常时不应把整个引擎搞死（后续请求仍能工作）。 */
    @Test
    fun `渲染异常后仍可继续使用`() {
        var fail = true
        val engine = engineWith {
            if (fail) error("boom") else "recovered"
        }
        runCatching { engine.submit("first") }
        fail = false
        engine.submit("second")
        assertEquals("recovered", engine.result.value)
    }

    /** 结果一开始是空的 —— UI 靠它决定显示占位还是加载圈。 */
    @Test
    fun `初始状态没有结果`() {
        val engine = engineWith { "x" }
        assertEquals(null, engine.result.value)
        assertFalse(engine.rendering.value)
    }

    @Test
    fun `渲染过程中 rendering 标志会被置位再复位`() {
        val seen = mutableListOf<Boolean>()
        lateinit var engine: PreviewRenderEngine<String, String>
        // Dispatchers.Unconfined 下链路是同步的，所以在渲染函数里读到的就是「渲染中」
        engine = engineWith {
            seen.add(engine.rendering.value)
            "done"
        }
        engine.submit("a")
        assertTrue("渲染中应看到 rendering=true", seen.first())
        assertFalse(engine.rendering.value)
    }
}