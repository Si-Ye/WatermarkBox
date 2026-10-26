package com.watermarkbox.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [BatchRunStore] 的单元测试。
 *
 * ## 测的是什么
 *
 * 这个类只依赖 `java.io.File`（不碰 `Context`），所以能在纯 JVM 上跑。
 * 重点不是「JSON 编码对不对」（kotlinx 保证），而是三条**容错约定**：
 *
 * 1. 写进去能原样读出来（含失败项的序号与原因）；
 * 2. 文件不存在 / 内容损坏，一律返回 `null`，**绝不抛异常** ——
 *    结果页读到 null 只是少两个按钮，抛异常就是整页崩；
 * 3. `runId` 里可能混进路径分隔符，必须被清洗掉，否则会写到目标目录之外。
 */
class BatchRunStoreTest {

    private lateinit var dir: File
    private lateinit var store: BatchRunStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("batch-runs-test").toFile()
        store = BatchRunStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `写入后能原样读回`() {
        val result = BatchRunResult(
            success = listOf("content://media/1", "content://media/2"),
            failed = listOf(
                BatchFailureItem("content://media/3", index = 3, reason = "SOURCE_TOO_LARGE")
            )
        )
        store.write("run-1", result)

        val read = store.read("run-1")
        assertNotNull(read)
        assertEquals(listOf("content://media/1", "content://media/2"), read!!.success)
        assertEquals(1, read.failed.size)
        // 序号必须原样保留：重跑失败项时靠它保号
        assertEquals(3, read.failed[0].index)
        assertEquals("SOURCE_TOO_LARGE", read.failed[0].reason)
        assertEquals("content://media/3", read.failed[0].uri)
    }

    @Test
    fun `没有结果时返回 null`() {
        assertNull(store.read("never-written"))
    }

    @Test
    fun `内容损坏时返回 null 而不是抛异常`() {
        dir.mkdirs()
        File(dir, "broken.json").writeText("{not json")

        assertNull(store.read("broken"))
    }

    @Test
    fun `runId 里的路径分隔符会被清洗`() {
        store.write("../../evil", BatchRunResult(success = listOf("content://media/1")))

        // 文件必须落在 dir 里，不能跑到上一级
        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files[0].parentFile == dir)
        assertEquals(listOf("content://media/1"), store.read("../../evil")!!.success)
    }

    @Test
    fun `prune 只保留最近几份`() {
        store.write("a", BatchRunResult(success = listOf("u1")))
        store.write("b", BatchRunResult(success = listOf("u2")))
        store.write("c", BatchRunResult(success = listOf("u3")))

        store.prune(keep = 1)

        val remaining = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        assertEquals(1, remaining.size)
    }

    @Test
    fun `重复写同一个 runId 会覆盖而不是追加`() {
        store.write("same", BatchRunResult(success = listOf("u1")))
        store.write("same", BatchRunResult(success = listOf("u2")))

        assertEquals(listOf("u2"), store.read("same")!!.success)
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.endsWith(".json") })
    }
}
