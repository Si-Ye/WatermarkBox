package com.watermarkbox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量清单 CSV 的单测。
 *
 * 守的是 CSV 真正会错的三件事：**转义、顺序、编码**。
 * 这三件都不需要真机 —— 一旦错了，用户看到的是「整列错位」或
 * 「打开全是乱码」，而这两种症状在模拟器上点按钮是看不出来的
 * （得真的把文件拷到电脑上用 Excel 打开）。
 */
class BatchRunCsvTest {

    private val texts = CsvTexts(
        columnIndex = "序号",
        columnResult = "结果",
        columnName = "文件名",
        columnLocation = "位置",
        columnReason = "失败原因",
        success = "成功",
        failed = "失败"
    )

    /** 去掉 BOM 后按 CRLF 切行（末尾空行不算）。 */
    private fun lines(csv: String): List<String> =
        csv.removePrefix("\uFEFF").split("\r\n").dropLastWhile { it.isEmpty() }

    @Test
    fun `以 BOM 开头，且 BOM 只出现一次`() {
        val csv = BatchRunCsv.build(BatchRunResult(success = listOf("content://a/1")), texts)

        assertTrue("CSV 必须以 BOM 开头，否则中文 Excel 按 GBK 解释会乱码", csv.startsWith("\uFEFF"))
        assertEquals("BOM 只该有一个，出现在文件最前面", 1, csv.count { it == '\uFEFF' })
    }

    @Test
    fun `空清单也输出表头`() {
        val csv = BatchRunCsv.build(BatchRunResult(), texts)

        assertEquals(listOf("序号,结果,文件名,位置,失败原因"), lines(csv))
    }

    @Test
    fun `行序按导出序号排列，而不是按列表下标`() {
        // 故意打乱：成功项在列表里是 3、1 的顺序，失败项序号是 2
        val csv = BatchRunCsv.build(
            BatchRunResult(
                success = listOf("content://a/3", "content://a/1"),
                failed = listOf(BatchFailureItem("content://a/2", 2, "TOO_LARGE")),
                successIndices = listOf(3, 1)
            ),
            texts
        )

        val rows = lines(csv).drop(1)
        assertEquals("对账时「第 N 张」必须指同一张图", listOf("1", "2", "3"), rows.map { it.substringBefore(',') })
    }

    @Test
    fun `旧记录没有序号时排在最后，序号列留空而不伪造`() {
        val csv = BatchRunCsv.build(
            BatchRunResult(
                success = listOf("content://old/1", "content://old/2"),
                failed = listOf(BatchFailureItem("content://new/1", 1, "DECODE_FAILED")),
                // 2.3.1 之前写入的清单没有这个字段
                successIndices = emptyList()
            ),
            texts
        )

        val rows = lines(csv).drop(1)
        assertEquals("有真实序号的排前面", "1", rows.first().substringBefore(','))
        assertTrue(
            "没序号的只能是空单元格，不能拿下标冒充",
            rows[1].startsWith(",") && rows[2].startsWith(",")
        )
    }

    @Test
    fun `字段含逗号、双引号、换行时按 RFC 4180 转义`() {
        val nasty = "a,b\"c\nd.jpg"
        val csv = BatchRunCsv.build(
            BatchRunResult(success = listOf("content://a/1"), successIndices = listOf(1)),
            texts,
            nameOf = { nasty }
        )

        assertTrue(
            "不转义会让后面所有列右移一格，用户会把失败原因读成文件名",
            csv.contains("\"a,b\"\"c\nd.jpg\"")
        )
    }

    @Test
    fun `文件名查不到时留空单元格，不影响列数`() {
        val csv = BatchRunCsv.build(
            BatchRunResult(success = listOf("content://a/1"), successIndices = listOf(1)),
            texts,
            nameOf = { null }
        )

        val row = lines(csv).drop(1).single()
        assertEquals("1,成功,,content://a/1,", row)
    }

    @Test
    fun `失败原因走注入的本地化映射，认不出的名字原样保留`() {
        val csv = BatchRunCsv.build(
            BatchRunResult(
                failed = listOf(
                    BatchFailureItem("content://a/1", 1, "TOO_LARGE"),
                    BatchFailureItem("content://a/2", 2, "FUTURE_REASON")
                )
            ),
            texts,
            reasonLabel = { name -> if (name == "TOO_LARGE") "图片太大" else name }
        )

        val rows = lines(csv).drop(1)
        assertTrue("认识的枚举值要翻译", rows[0].endsWith("图片太大"))
        assertTrue(
            "新版本写入的未知原因不能丢，原样输出好过显示空白",
            rows[1].endsWith("FUTURE_REASON")
        )
    }

    @Test
    fun `行分隔符是 CRLF`() {
        val csv = BatchRunCsv.build(
            BatchRunResult(success = listOf("content://a/1"), successIndices = listOf(1)),
            texts
        )

        assertTrue("RFC 4180 规定 CRLF", csv.contains("\r\n"))
        // 先把成对的 CRLF 去掉，剩下的若还有 \n 就是裸 LF
        assertTrue("不能出现裸 LF", !csv.replace("\r\n", "").contains('\n'))
    }
}
