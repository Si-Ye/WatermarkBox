package com.watermarkbox.invariants

import com.watermarkbox.util.ExifTextCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 跨模块「单一来源」不变量测试。
 *
 * ## 为什么需要它
 *
 * 本项目**最高频**的缺陷类型是「**改了 A 没同步 B**」——
 * 近两轮审查里出现了 4 次：
 *
 * | 改了 | 没跟上 | 后果 |
 * |---|---|---|
 * | 导出档位 `coerceIn(1, 2)` | 检测端仍试 `3f` / `4f` | 每次自动检测白跑 50% 的全图 DCT |
 * | 导出默认强度 `2` | 检测端 `DEFAULT_STRENGTH = 1f` | 关掉「自动强度」后**自己导的图检不出来** |
 * | `MAX_DATA_BYTES` | UI 文案写死「255 字节」 | 用户看到的限制是旧的 |
 * | `MIN_ALPHA` | 滑块写死 `0.01f..1f` | 界面能拖到常量允许范围之外 |
 *
 * 这类缺陷**不会编译失败、也不会崩**，只表现为「莫名其妙」——
 * 正是测试最该守、而现有测试最容易漏的地方。
 *
 * 前两项已由 [com.watermarkbox.invisible.DetectOptionsStrengthTest] 与
 * [com.watermarkbox.data.model.WatermarkRangeTest] 以**行为断言**钉住。
 * 本类补上剩下的**源码 / 资源层面**：即「常量到底有没有被引用」这件事本身 ——
 * 行为断言测不出「UI 里又写死了一份」。
 *
 * ## 为什么可以直接读源码
 *
 * 单测的工作目录是模块目录（`app/`），因此 `src/main/...` 可直接访问。
 * 项目已有先例：[com.watermarkbox.data.model.ChangelogJsonTest] 会读
 * `../docs/changelog.json` 做集成校验。
 * 取不到文件时用 `assumeTrue` **跳过**而非失败 —— 这类校验是加分项，不该阻塞构建。
 */
class CrossModuleReferenceTest {

    // =================================================================
    // 基础设施
    // =================================================================

    /** 单测工作目录 = 模块目录（`app/`）。 */
    private val mainJava = File("src/main/java")

    /** 递归收集 main 源码里所有 `.kt`。 */
    private fun mainKotlinFiles(): List<File> {
        assumeTrue(
            "未找到 ${mainJava.path}，跳过源码扫描（非 Gradle 单测环境）",
            mainJava.isDirectory
        )
        val files = mainJava.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("main 源码里一个 .kt 都没扫到，扫描逻辑可能已失效", files.isNotEmpty())
        return files
    }

    /** 该行是否只是注释（注释里的示例文本不该造成误报）。 */
    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /**
     * 取一个 main 源码文件。
     *
     * ## ⚠️ 为什么这里用 `assert` 而不是 `assume`
     *
     * 目录不存在（非 Gradle 单测环境）可以**跳过**；
     * 但**具体文件不存在就是路径写错了**，必须**红**。
     *
     * 本项目踩过这个坑：初版用 `assumeTrue` 判断文件存在性，
     * 结果路径漏了 `com/watermarkbox/` 前缀 → 3 条用例被静默跳过，
     * 测试报告显示"全绿"，实际什么都没查（正是 §5 里
     * 「看起来有防线，实际没有」的模式）。
     */
    private fun file(rel: String): File {
        assumeTrue(
            "未找到 ${mainJava.path}，跳过（非 Gradle 单测环境）",
            mainJava.isDirectory
        )
        val f = File(mainJava, rel)
        assertTrue("源码文件不存在：${f.path} —— 相对路径写错了？", f.isFile)
        return f
    }

    /** 取一个资源文件（同样：目录可跳过，具体文件必须存在）。 */
    private fun res(rel: String): File {
        val root = File("src/main/res")
        assumeTrue("未找到 ${root.path}，跳过（非 Gradle 单测环境）", root.isDirectory)
        val f = File(root, rel)
        assertTrue("资源文件不存在：${f.path} —— 相对路径写错了？", f.isFile)
        return f
    }

    /**
     * 扫描所有 main 源码，返回命中行，格式 `路径:行号  内容`。
     *
     * @param regex 行级匹配
     * @param extra 额外条件（可选）
     */
    private fun hits(regex: Regex, extra: (String) -> Boolean = { true }): List<String> =
        mainKotlinFiles().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) ->
                    !isComment(line) && regex.containsMatchIn(line) && extra(line)
                }
                .map { (i, line) -> "${f.path}:${i + 1}  ${line.trim()}" }
        }

    // =================================================================
    // 一、TAG_PREFIX：唯一来源
    // =================================================================

    /** `const val TAG_PREFIX = ...` 这样的**定义**（getter 引用不算）。 */
    private val defTagPrefix = Regex("""const\s+val\s+TAG_PREFIX\s*=""")

    @Test
    fun `TAG_PREFIX 的全项目定义只有一处`() {
        val defs = hits(defTagPrefix)

        assertEquals(
            "TAG_PREFIX 只能有一处定义（重复定义会让检测端与写入端漂移）：\n" +
                defs.joinToString("\n"),
            1,
            defs.size
        )
        assertTrue(
            "唯一那处定义应在 ExifTextCodec.kt 里（实际：${defs.first()}）",
            defs.first().contains("ExifTextCodec.kt")
        )
    }

    @Test
    fun `WatermarkPayload 的前缀是对唯一来源的引用而非副本`() {
        val payload = file("com/watermarkbox/invisible/WatermarkPayload.kt")

        val text = payload.readText()
        assertTrue(
            "WatermarkPayload.TAG_PREFIX 必须引用 ExifTextCodec.TAG_PREFIX，" +
                "不要在这里再写一份 \"WMB1:\"（两处副本会漂移）",
            text.contains("ExifTextCodec.TAG_PREFIX")
        )
        assertFalse(
            "WatermarkPayload.kt 里出现了 \"WMB1:\" 字面量 —— 那是第二份副本",
            text.contains("\"WMB1:\"")
        )
    }

    @Test
    fun `前缀值确实是 WMB1 协议头`() {
        // 该值是**已发布图片的格式契约**：改了它，旧图再也检不出来。
        // 所以这里显式钉死，防止「顺手改个更漂亮的前缀」。
        assertEquals("WMB1:", ExifTextCodec.TAG_PREFIX)
    }

    // =================================================================
    // 二、MIN_ALPHA：滑块下限不得硬编码
    // =================================================================

    private val valueRange = Regex("""valueRange\s*=""")

    @Test
    fun `透明度滑块区间没有硬编码下限`() {
        val bad = hits(valueRange) { it.contains("0.01") }

        assertTrue(
            "滑块区间写死了 0.01 —— 必须改成 WatermarkRenderer.MIN_ALPHA，\n" +
                "否则改常量时滑块不跟随，界面能拖到 clamp 会抬走的值：\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    @Test
    fun `三处透明度滑块都引用了 MIN_ALPHA 常量`() {
        val expected = listOf(
            "ui/screen/editor/WatermarkPanelContent.kt",      // 文字透明度 + 图片透明度
            "ui/screen/settings/SettingsWatermarkCategory.kt" // 设置页默认透明度
        )
        expected.forEach { rel ->
            val f = file("com/watermarkbox/$rel")
            assertTrue(
                "$rel 里没有引用 WatermarkRenderer.MIN_ALPHA —— 下限可能又被写死了",
                f.readText().contains("WatermarkRenderer.MIN_ALPHA")
            )
        }
    }

    // =================================================================
    // 三、MAX_DATA_BYTES：文案必须参数化
    // =================================================================

    /** 抓取 `<string name="editor_invisible_content_limit">…</string>` 的内容。 */
    private val limitString =
        Regex("""<string name="editor_invisible_content_limit">(.*?)</string>""")

    @Test
    fun `上限文案是中英双语且参数化的`() {
        val files = listOf(
            res("values/strings.xml"),
            res("values-en/strings.xml")
        )
        files.forEach { f ->
            val m = limitString.find(f.readText())
            assertNotNull("${f.path} 里没有 editor_invisible_content_limit", m)

            val body = m!!.groupValues[1]
            assertTrue(
                "${f.path} 的文案缺少 %1\$d（字节上限）占位符：$body",
                body.contains("%1\$d")
            )
            assertTrue(
                "${f.path} 的文案缺少 %2\$d（约几个汉字）占位符：$body",
                body.contains("%2\$d")
            )
            assertFalse(
                "${f.path} 的文案把上限写死成 255 了 —— 改 MAX_DATA_BYTES 时不会跟随：$body",
                body.contains("255")
            )
        }
    }

    @Test
    fun `上限文案的调用点取自常量`() {
        val f = file("com/watermarkbox/ui/screen/editor/WatermarkPanelInvisible.kt")

        assertTrue(
            "调用点没有引用 WatermarkPayload.MAX_DATA_BYTES —— 上限可能又被写死",
            f.readText().contains("WatermarkPayload.MAX_DATA_BYTES")
        )
    }
}
