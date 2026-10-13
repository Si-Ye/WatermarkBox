package com.watermarkbox.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 教程文档的**格式守护**测试。
 *
 * ## 为什么要有这个测试
 *
 * 教程是纯文本资源，写错了编译器一句话都不说 —— 但用户会在界面上
 * 看到两个孤零零的 `**` 挂在句子里。这类问题只能在真机上肉眼发现，
 * 成本极高（本次就是这样：加粗标记跨了行，`**` 变成字面量）。
 *
 * 所以把「肉眼检查」换成机器检查：扫描两份教程，找出
 * **行内标记不闭合**的行。
 *
 * ## 为什么用相对路径读文件
 *
 * 单测跑在 JVM 上，`app/src/main/assets` 不在 classpath 里。
 * 但 Gradle 执行单测时工作目录就是模块目录（`app/`），
 * 因此相对路径能直接读到源文件。工作目录对不上时**跳过**而不是失败 ——
 * 环境差异不该产生假红灯。
 */
class TutorialMarkdownFormatTest {

    private val assets = listOf("tutorial.md", "tutorial.en.md")

    private fun lines(name: String): List<String> {
        val file = File("src/main/assets/$name")
        assumeTrue(
            "读不到 $name（工作目录 ${File(".").absolutePath}），跳过",
            file.exists()
        )
        return file.readText().replace("\r\n", "\n").split("\n")
    }

    /** 非重叠地数出子串出现次数（`**` 用窗口数会重复计数）。 */
    private fun countOf(line: String, token: String): Int =
        line.split(token).size - 1

    @Test
    fun `两份教程都在且内容量正常`() {
        for (name in assets) {
            val count = lines(name).size
            assertTrue("$name 只有 $count 行，像是被截断了", count > 50)
        }
    }

    /**
     * 加粗标记必须**在单行内闭合**。
     *
     * 这是本次真实踩到的坑：句子太长时把 `**加粗**` 拆成两行写，
     * 解析器逐行处理，两个 `**` 都找不到配对，于是原样显示。
     */
    @Test
    fun `加粗标记不跨行`() {
        for (name in assets) {
            lines(name).forEachIndexed { index, line ->
                assertEquals(
                    "$name 第 ${index + 1} 行的 ** 个数是奇数，" +
                        "会原样显示出来：\n  $line",
                    0,
                    countOf(line, "**") % 2
                )
            }
        }
    }

    /** 行内代码同理：`code` 也要在单行内闭合。 */
    @Test
    fun `行内代码标记不跨行`() {
        for (name in assets) {
            lines(name).forEachIndexed { index, line ->
                assertEquals(
                    "$name 第 ${index + 1} 行的反引号个数是奇数：\n  $line",
                    0,
                    countOf(line, "`") % 2
                )
            }
        }
    }

    /**
     * 表格的列数必须一致。
     *
     * 列数不一致时，渲染出来的表格会参差不齐 ——
     * 也是那种「肉眼能看出别扭、但没人说得清哪里错」的问题。
     */
    @Test
    fun `表格每行列数一致`() {
        for (name in assets) {
            val all = lines(name)
            // 把连续的表格行（含 `|---|` 分隔行）当作一个块来比较
            var blockStart = -1
            val blocks = mutableListOf<List<Pair<Int, String>>>()

            fun flush(endExclusive: Int) {
                if (blockStart >= 0 && endExclusive - blockStart > 1) {
                    blocks.add((blockStart until endExclusive).map { it to all[it] })
                }
                blockStart = -1
            }

            all.forEachIndexed { i, raw ->
                val isTable = raw.trimStart().startsWith("|")
                if (isTable && blockStart < 0) blockStart = i
                if (!isTable) flush(i)
            }
            flush(all.size)

            for (block in blocks) {
                // 注意：不能用 splitTableRow 数 —— 它对 `|---|` 分隔行返回 null。
                // 这里只关心「竖线切出来几格」，分隔行同样算一格，才能一起比。
                val widths = block.map { (_, l) -> tableColumnCount(l) }
                val expected = widths.first()
                widths.forEachIndexed { idx, w ->
                    assertEquals(
                        "$name 第 ${block[idx].first + 1} 行单元格数与表头不一致" +
                            "（表头 $expected 列，本行 $w 列）：\n  ${block[idx].second}",
                        expected, w
                    )
                }
            }
        }
    }

    /** 表格行列数（含 `|---|` 分隔行）。 */
    private fun tableColumnCount(line: String): Int =
        line.trim().trim('|').split('|').size
}
