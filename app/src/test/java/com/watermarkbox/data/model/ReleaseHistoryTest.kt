package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志的数据一致性测试。
 *
 * ## 为什么值得测
 *
 * [ReleaseHistory] 是纯数据，写错不会编译失败、也不会崩 ——
 * 只会在用户打开「更新说明」时表现为"某个版本不见了"或"顺序乱了"。
 * 这类错误一旦发出去就没人回头查，所以用测试兜住。
 *
 * 另外 `versionCode` 校验能防止「改了版本号却忘了记一笔」。
 */
class ReleaseHistoryTest {

    @Test
    fun `版本号不重复`() {
        val versions = ReleaseHistory.releases.map { it.version }
        assertEquals(
            "同一个版本号出现了多次，说明添加新版本时复制粘贴忘了改",
            versions.size,
            versions.distinct().size
        )
    }

    @Test
    fun `按版本倒序排列（最新在最前）`() {
        val parsed = ReleaseHistory.releases.map { parse(it.version) }
        val sortedDesc = parsed.sortedWith(compareByDescending<IntArray> { it[0] }
            .thenByDescending { it[1] }
            .thenByDescending { it[2] })
        assertEquals(
            "更新日志必须按版本从新到旧排列，否则用户第一眼看到的是老版本",
            sortedDesc,
            parsed
        )
    }

    @Test
    fun `每个版本至少有一条变更`() {
        ReleaseHistory.releases.forEach { note ->
            assertTrue(
                "版本 ${note.version} 没有任何变更条目",
                note.changes.isNotEmpty()
            )
        }
    }

    @Test
    fun `版本号和日期都不为空`() {
        ReleaseHistory.releases.forEach { note ->
            assertTrue("版本号为空", note.version.isNotBlank())
            assertTrue("版本 ${note.version} 的日期为空", note.date.isNotBlank())
            assertTrue(
                "版本 ${note.version} 的主题资源 ID 为 0（忘了接上字符串资源）",
                note.titleRes != 0
            )
            note.changes.forEach { c ->
                assertTrue(
                    "版本 ${note.version} 里有变更条目的资源 ID 为 0",
                    c.textRes != 0
                )
            }
        }
    }

    /**
     * 版本内不应复用同一个字符串资源。
     *
     * 复制粘贴新增日志时最容易犯的错：改了下一条的文字，
     * 忘了把资源名也改掉 —— 结果两条显示同一句话。
     * 这里按「同一版本内 textRes 不重复」把这类错误挡在编译期之后、发版之前。
     */
    @Test
    fun `同一版本内的变更描述不重复引用同一资源`() {
        ReleaseHistory.releases.forEach { note ->
            val ids = note.changes.map { it.textRes }
            assertEquals(
                "版本 ${note.version} 里有两条变更引用了同一字符串资源",
                ids.size,
                ids.distinct().size
            )
        }
    }

    @Test
    fun `latest 指向列表第一项`() {
        assertEquals(ReleaseHistory.releases.first(), ReleaseHistory.latest)
    }

    @Test
    fun `变更总数等于各版本之和`() {
        assertEquals(
            ReleaseHistory.releases.sumOf { it.changes.size },
            ReleaseHistory.totalChanges
        )
    }

    /**
     * 当前打包版本**应当**出现在更新日志里。
     *
     * 这条规则的实际作用：以后改 `versionName` 时必须同步加一条日志，
     * 否则这里会红。属于"防忘记"而非"防错误"。
     */
    @Test
    fun `更新日志覆盖当前版本`() {
        val declared = BuildConfigForTest.VERSION_NAME
        val exists = ReleaseHistory.releases.any { it.version == declared }
        assertTrue(
            "当前版本 $declared 不在更新日志里 —— 改了版本号就要加一条记录",
            exists
        )
    }

    /** 把 "1.4.0" 解析成 [1,4,0]，用于排序比较。 */
    private fun parse(version: String): IntArray =
        version.split(".").map { it.toInt() }.toIntArray()
}

/**
 * 测试里拿不到的 `BuildConfig` 的替身。
 *
 * 单元测试运行在 JVM 上，`com.watermarkbox.BuildConfig` 由 AGP 生成、
 * 不在单元测试的类路径里（它是 `androidTest`/`assemble` 的产物）。
 * 所以这里显式声明当前版本，**改版本号时这里也要改**。
 *
 * 若嫌手动同步麻烦，可改为读 `app/build.gradle.kts` 文本 ——
 * 但那样测试依赖构建脚本的文本格式，改格式就会假失败，得不偿失。
 */
private object BuildConfigForTest {
    const val VERSION_NAME = "1.4.1"
}
