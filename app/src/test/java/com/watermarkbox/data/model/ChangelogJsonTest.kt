package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [ChangelogJson] 的单元测试。
 *
 * ## 为什么这个文件能测（而 UpdateChecker 不能）
 *
 * `UpdateChecker` 用 `org.json`，那是 Android stub，JVM 单测里调用即抛异常，
 * 所以它整个 JSON 层**没有测试**。
 *
 * 本类改用 `kotlinx-serialization-json`（纯 Kotlin 库），于是解析逻辑
 * 可以在纯 JVM 单测里真跑 —— 「远程文档格式变了会不会解析失败」
 * 这件事从此有了回归防护。
 *
 * ## 测试分两层
 *
 * 1. **内嵌样本**：覆盖解析规则与各种边界（坏数据、语言回退、schema 版本）
 * 2. **真实文档**：直接读仓库里的 `docs/changelog.json` 并解析 ——
 *    这是**集成校验**，能在提交前就发现"文档写错了/字段名改了"
 */
class ChangelogJsonTest {

    // ------------------------------------------------------------------
    // 内嵌样本
    // ------------------------------------------------------------------

    private val sample = """
        {
          "schemaVersion": 1,
          "updatedAt": "2026-10-13",
          "releases": [
            {
              "version": "2.1.1",
              "date": "2026-10-13",
              "type": "MINOR",
              "title": { "zh": "标题中文", "en": "Title EN" },
              "changes": [
                { "kind": "FIXED", "zh": "修复中文", "en": "Fix EN" },
                { "kind": "added", "zh": "小写枚举名", "en": "lowercase kind" }
              ]
            },
            {
              "version": "2.1.0",
              "date": "2026-10-12",
              "type": "MAJOR",
              "title": { "zh": "旧版主题", "en": "Older theme" },
              "changes": [
                { "kind": "ADDED", "zh": "新增中文", "en": "Added EN" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析中文_取 zh 字段`() {
        val entries = ChangelogJson.parse(sample, "zh")
        assertNotNull(entries)
        assertEquals(2, entries!!.size)

        val first = entries[0]
        assertEquals("2.1.1", first.version)
        assertEquals("2026-10-13", first.date)
        assertEquals(ReleaseType.MINOR, first.type)
        assertEquals("标题中文", first.title)
        assertEquals(2, first.changes.size)
        assertEquals(ChangeKind.FIXED, first.changes[0].kind)
        assertEquals("修复中文", first.changes[0].text)
    }

    @Test
    fun `解析英文_取 en 字段`() {
        val entries = ChangelogJson.parse(sample, "en")
        assertNotNull(entries)
        assertEquals("Title EN", entries!![0].title)
        assertEquals("Fix EN", entries[0].changes[0].text)
    }

    /** 语言代码只有 `en` 被特殊对待；其它一律中文（与 TutorialScreen 同规则）。 */
    @Test
    fun `非 en 的语言一律取中文`() {
        for (lang in listOf("zh", "zh-CN", "ja", "fr", "")) {
            val entries = ChangelogJson.parse(sample, lang)
            assertEquals("lang=$lang 应取中文", "标题中文", entries!![0].title)
        }
    }

    /** 顺序必须原样保留（文档里是倒序，最新的在最前）。 */
    @Test
    fun `版本顺序原样保留`() {
        val entries = ChangelogJson.parse(sample, "zh")!!
        assertEquals(listOf("2.1.1", "2.1.0"), entries.map { it.version })
    }

    // ------------------------------------------------------------------
    // 枚举映射
    // ------------------------------------------------------------------

    @Test
    fun `kind 映射大小写不敏感`() {
        assertEquals(ChangeKind.FIXED, ChangelogJson.kindFromName("FIXED"))
        assertEquals(ChangeKind.FIXED, ChangelogJson.kindFromName("fixed"))
        assertEquals(ChangeKind.FIXED, ChangelogJson.kindFromName("  Fixed  "))
        assertEquals(ChangeKind.IMPROVED, ChangelogJson.kindFromName("IMPROVED"))
        assertEquals(ChangeKind.CHANGED, ChangelogJson.kindFromName("CHANGED"))
        assertEquals(ChangeKind.ADDED, ChangelogJson.kindFromName("ADDED"))
    }

    @Test
    fun `未知 kind 返回 null`() {
        assertNull(ChangelogJson.kindFromName("WHATEVER"))
        assertNull(ChangelogJson.kindFromName(""))
        assertNull(ChangelogJson.kindFromName(null))
    }

    @Test
    fun `type 映射大小写不敏感`() {
        assertEquals(ReleaseType.MAJOR, ChangelogJson.typeFromName("MAJOR"))
        assertEquals(ReleaseType.MAJOR, ChangelogJson.typeFromName("major"))
        assertEquals(ReleaseType.MINOR, ChangelogJson.typeFromName("MinOR"))
    }

    @Test
    fun `未知 type 返回 null`() {
        assertNull(ChangelogJson.typeFromName("PATCH"))
        assertNull(ChangelogJson.typeFromName(null))
    }

    // ------------------------------------------------------------------
    // 宽松策略：坏条目跳过，好条目照常
    // ------------------------------------------------------------------

    @Test
    fun `坏条目被跳过_其余仍可用`() {
        val raw = """
            {
              "schemaVersion": 1,
              "releases": [
                { "version": "", "type": "MINOR", "title": {"zh":"空版本号"},
                  "changes": [{"kind":"FIXED","zh":"x"}] },
                { "version": "1.0.0", "type": "UNKNOWN_TYPE", "title": {"zh":"未知类型"},
                  "changes": [{"kind":"FIXED","zh":"x"}] },
                { "version": "0.9.0", "type": "MINOR", "title": {"zh":""},
                  "changes": [{"kind":"FIXED","zh":"x"}] },
                { "version": "0.8.0", "type": "MINOR", "title": {"zh":"没有变更"},
                  "changes": [] },
                { "version": "0.7.0", "type": "MINOR", "title": {"zh":"好条目"},
                  "changes": [{"kind":"FIXED","zh":"唯一可用的一条"}] }
              ]
            }
        """.trimIndent()

        val entries = ChangelogJson.parse(raw, "zh")
        assertNotNull(entries)
        assertEquals("只应剩下一条好数据", 1, entries!!.size)
        assertEquals("0.7.0", entries[0].version)
    }

    @Test
    fun `单条变更里未知 kind 被跳过`() {
        val raw = """
            {
              "schemaVersion": 1,
              "releases": [{
                "version": "1.0.0", "type": "MINOR", "title": {"zh":"标题"},
                "changes": [
                  { "kind": "BADKIND", "zh": "坏的" },
                  { "kind": "FIXED", "zh": "好的" }
                ]
              }]
            }
        """.trimIndent()

        val entries = ChangelogJson.parse(raw, "zh")!!
        assertEquals(1, entries[0].changes.size)
        assertEquals("好的", entries[0].changes[0].text)
    }

    // ------------------------------------------------------------------
    // 语言回退
    // ------------------------------------------------------------------

    /** 目标语言缺失时退回另一种 —— 宁可显示另一种语言，也不要留空。 */
    @Test
    fun `英文缺失时回退中文`() {
        val raw = """
            {
              "schemaVersion": 1,
              "releases": [{
                "version": "1.0.0", "type": "MINOR", "title": {"zh":"只有中文"},
                "changes": [{"kind":"FIXED","zh":"只有中文的变更","en":""}]
              }]
            }
        """.trimIndent()

        val entries = ChangelogJson.parse(raw, "en")!!
        assertEquals("只有中文", entries[0].title)
        assertEquals("只有中文的变更", entries[0].changes[0].text)
    }

    @Test
    fun `中文缺失时回退英文`() {
        val raw = """
            {
              "schemaVersion": 1,
              "releases": [{
                "version": "1.0.0", "type": "MINOR", "title": {"en":"Only EN"},
                "changes": [{"kind":"FIXED","en":"Only EN change"}]
              }]
            }
        """.trimIndent()

        val entries = ChangelogJson.parse(raw, "zh")!!
        assertEquals("Only EN", entries[0].title)
        assertEquals("Only EN change", entries[0].changes[0].text)
    }

    // ------------------------------------------------------------------
    // 顶层结构不可用 → null（交给兜底）
    // ------------------------------------------------------------------

    @Test
    fun `schema 版本过高时放弃解析`() {
        val raw = """
            { "schemaVersion": 99, "releases": [
                { "version": "1.0.0", "type": "MINOR", "title": {"zh":"x"},
                  "changes": [{"kind":"FIXED","zh":"y"}] } ] }
        """.trimIndent()
        assertNull(
            "版本比客户端新时必须放弃，避免按旧规则误读",
            ChangelogJson.parse(raw, "zh")
        )
    }

    @Test
    fun `无 releases 字段时返回 null`() {
        assertNull(ChangelogJson.parse("""{ "schemaVersion": 1 }""", "zh"))
    }

    @Test
    fun `releases 为空数组时返回 null`() {
        assertNull(ChangelogJson.parse("""{ "schemaVersion": 1, "releases": [] }""", "zh"))
    }

    @Test
    fun `非 JSON 内容返回 null`() {
        assertNull(ChangelogJson.parse("<html>404 Not Found</html>", "zh"))
        assertNull(ChangelogJson.parse("", "zh"))
    }

    /** 未知字段必须被忽略（服务端加字段时不能让旧客户端解析失败）。 */
    @Test
    fun `未知字段被忽略`() {
        val raw = """
            {
              "schemaVersion": 1,
              "brandNewField": {"nested": [1,2,3]},
              "releases": [{
                "version": "1.0.0", "type": "MINOR",
                "futureField": "whatever",
                "title": {"zh":"标题"},
                "changes": [{"kind":"FIXED","zh":"变更","extra":true}]
              }]
            }
        """.trimIndent()
        val entries = ChangelogJson.parse(raw, "zh")
        assertNotNull(entries)
        assertEquals("1.0.0", entries!![0].version)
    }

    // ------------------------------------------------------------------
    // 集成校验：真实的 docs/changelog.json
    // ------------------------------------------------------------------

    /**
     * 直接解析仓库里的真实文档。
     *
     * ## 为什么值得测
     *
     * 上面那些用例都基于**我手写的样本**。样本永远"格式正确"，
     * 但真实文档是人（或脚本）产出的 —— 格式漂移恰恰发生在这里。
     *
     * 这条用例能在**提交前**就发现「文档写错了 / 字段名改了」，
     * 而不是等用户打开应用看到一片空白。
     *
     * ## 为什么可能跳过
     *
     * 单测的工作目录是模块目录（`app/`），仓库根目录在 `../`。
     * 若在某些打包/隔离环境里取不到该文件，就**跳过**而不是失败 ——
     * 这条是加分项，不该阻塞构建。
     */
    @Test
    fun `真实文档可被解析`() {
        val docFile = File("../docs/changelog.json")
        assumeTrue("未找到 docs/changelog.json，跳过集成校验", docFile.isFile)

        val raw = docFile.readText()

        // 中英两份都必须能解析出同样的版本列表
        val zh = ChangelogJson.parse(raw, "zh")
        val en = ChangelogJson.parse(raw, "en")
        assertNotNull("真实文档解析失败（zh）", zh)
        assertNotNull("真实文档解析失败（en）", en)

        assertEquals("中英解析出的版本数必须一致", zh!!.size, en!!.size)
        assertTrue("真实文档不该是空的", zh.isNotEmpty())

        // 版本号不能重复（重复会导致 LazyColumn 的 key 冲突）
        val versions = zh.map { it.version }
        assertEquals("版本号必须唯一", versions.size, versions.distinct().size)

        // 每条都必须有内容
        zh.forEach { entry ->
            assertTrue("${entry.version} 标题为空", entry.title.isNotBlank())
            assertTrue("${entry.version} 没有变更条目", entry.changes.isNotEmpty())
            entry.changes.forEach { c ->
                assertTrue("${entry.version} 有空白变更文案", c.text.isNotBlank())
            }
        }

        // 中英两份的条目数应一致（防止某一语言漏写一条）
        zh.zip(en!!).forEach { (z, e) ->
            assertEquals(
                "${z.version} 的中英变更条数不一致",
                z.changes.size,
                e.changes.size
            )
        }
    }
}