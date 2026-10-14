package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志的数据一致性测试。
 *
 * ## 这个类只测「内置兜底数据自身」的一致性
 *
 * `ReleaseHistory` 是纯数据，写错不会编译失败、也不会崩 ——
 * 只会在用户打开「更新说明」时表现为"某个版本不见了"或"顺序乱了"。
 * 这类错误一旦发出去就没人回头查，所以用测试兜住。
 *
 * ## ⚠️ 这里**不再**校验「当前版本是否在列表中」（重要变更）
 *
 * 曾经有一条 `更新日志覆盖当前版本`：拿一个手写的版本号常量
 * 去比对列表，本意是"改了版本号就要加一条记录"。
 *
 * **它失败过一次**：那个常量长期停在 `1.4.1`，而 `versionName`
 * 早已升到 `2.1.0` —— 于是断言校验的是 1.4.1（列表里确实有），
 * **永远通过**。结果 2.1.0 发布时漏加了条目，测试完全没能拦住，
 * 用户在新版里看不到本版说明了什么。
 *
 * 这是「把现状写成断言，让测试失去发现能力」的又一例
 * （同类教训见 `docs/multi-session-collaboration.md` §5.9）。
 *
 * ## 现在这条防线搬到了哪里
 *
 * 更新说明已改为**从 GitHub 拉取**（`docs/changelog.json`），
 * `ReleaseHistory` 退化为「离线兜底快照」，**不再要求随版本同步**。
 *
 * 「发版必须要更新说明」这件事改由 `tools/check_release_consistency.py`
 * 在 **CI** 里守 —— 它直接读 `app/build.gradle.kts` 的 `versionName`，
 * 与 `docs/changelog.json`、`docs/releases/vX.Y.Z.md` 三方比对。
 * 那里的版本号**没有第二份手写副本**，所以不会再出现"校验了个寂寞"。
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

    /** 把 "1.4.0" 解析成 [1,4,0]，用于排序比较。 */
    private fun parse(version: String): IntArray =
        version.split(".").map { it.toInt() }.toIntArray()
}
