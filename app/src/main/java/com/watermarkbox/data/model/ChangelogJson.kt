package com.watermarkbox.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `docs/changelog.json` 的解析器。
 *
 * ## 这份文档是什么
 *
 * 应用内「更新说明」的数据源。托管在仓库里，由
 * [com.watermarkbox.data.repository.ChangelogRepository] 从
 * `raw.githubusercontent.com` 拉取。
 *
 * ## 为什么用 JSON 而不是直接拉 Markdown
 *
 * GitHub Release 页面的正文（`docs/releases/vX.Y.Z.md`）是给人读的长文：
 * 有段落、表格、代码块。而应用内要渲染的是**结构化列表**
 * （每条带「新增 / 修复 / 优化」分类）。
 *
 * 从 Markdown 反推结构要写一堆容错规则，且正文格式一调整就解析错。
 * 用 JSON 则**字段即契约**：职责单一、不易漂移。
 *
 * 代价是发版时要同时维护两份 —— 为此仓库提供
 * `tools/check_release_consistency.py` 在 CI 里强制校验，
 * **漏写会让构建失败**，而不是等用户回来报「更新说明没写」。
 *
 * ## 解析策略：宽松（坏条目跳过，不整份失败）
 *
 * 单条有问题（比如 `kind` 拼错）时**跳过该条**，其余照常显示 ——
 * 尽力展示好过全废。但顶层结构必须可用（有 `releases` 且至少一条能解析），
 * 否则返回 `null`，让调用方走内置兜底。
 *
 * ## 为什么用 kotlinx.serialization 而不是 org.json
 *
 * `org.json` 是 Android stub，JVM 单测里调用会抛异常 ——
 * 本仓库 `UpdateCheckerTest` 就因此**放弃了整个 JSON 层的测试**。
 *
 * 而 `kotlinx-serialization-json` 是纯 Kotlin 库，**单测里能真跑**。
 * 于是「解析会不会因字段变动而失效」这件事有了回归防护。
 */
object ChangelogJson {

    /**
     * 当前支持的 schema 版本。
     *
     * 远程文档声明了**更高**版本时，说明它是为更新的客户端准备的 ——
     * 本客户端选择**放弃解析**（返回 null → 走兜底），
     * 而不是冒险按旧规则误读出错误内容。
     */
    const val SCHEMA_VERSION = 1

    /**
     * 与 `UpdateChecker` 用同一套宽松配置。
     *
     * `ignoreUnknownKeys` 让「服务端加了新字段」不会让旧客户端解析失败 ——
     * 这是**向前兼容**的关键（否则一次文档增强就能让老版本全部退回兜底）。
     */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // 线上格式（与 docs/changelog.json 一一对应）
    //
    // 所有字段都给了默认值：字段缺失时得到空串而非抛异常，
    // 再由下面的映射逻辑判断「这条能不能用」。这样单条数据写错
    // 只会被跳过，不会让整份文档解析失败。
    // ------------------------------------------------------------------

    @Serializable
    data class Document(
        val schemaVersion: Int = 0,
        val updatedAt: String = "",
        val releases: List<Release> = emptyList()
    )

    @Serializable
    data class Release(
        val version: String = "",
        val date: String = "",
        val type: String = "",
        val title: Localized = Localized(),
        val changes: List<Change> = emptyList()
    )

    @Serializable
    data class Change(
        val kind: String = "",
        val zh: String = "",
        val en: String = ""
    )

    /** 一组中英对照文案。 */
    @Serializable
    data class Localized(val zh: String = "", val en: String = "")

    // ------------------------------------------------------------------
    // 映射（纯函数 —— 单测覆盖重点：枚举名拼写与大小写）
    // ------------------------------------------------------------------

    /** JSON 里的 kind 名 → 枚举。未知或空返回 `null`（调用方据此跳过该条）。 */
    fun kindFromName(name: String?): ChangeKind? = when (name?.trim()?.uppercase()) {
        "ADDED" -> ChangeKind.ADDED
        "FIXED" -> ChangeKind.FIXED
        "IMPROVED" -> ChangeKind.IMPROVED
        "CHANGED" -> ChangeKind.CHANGED
        else -> null
    }

    /** JSON 里的 type 名 → 枚举。未知或空返回 `null`。 */
    fun typeFromName(name: String?): ReleaseType? = when (name?.trim()?.uppercase()) {
        "MAJOR" -> ReleaseType.MAJOR
        "MINOR" -> ReleaseType.MINOR
        else -> null
    }

    /**
     * 解析整份更新说明。
     *
     * @param raw  远程文档原文
     * @param lang 语言代码。**`"en"` 取英文字段，其它一律取中文** ——
     *             与 `TutorialScreen` 的既有约定保持一致，避免两套规则。
     * @return 可用的条目列表；顶层不可用或一条都没解析出来时返回 `null`
     */
    fun parse(raw: String, lang: String): List<ChangelogEntry>? {
        val doc = try {
            json.decodeFromString<Document>(raw)
        } catch (_: Throwable) {
            // 网络内容不可信：任何异常都当作"这份文档不可用" → 走兜底
            return null
        }

        if (doc.schemaVersion > SCHEMA_VERSION) return null

        val out = doc.releases.mapNotNull { it.toEntry(lang) }
        return out.ifEmpty { null }
    }

    /** 单个版本条目 → UI 模型；字段不全或类型未知时返回 `null`。 */
    private fun Release.toEntry(lang: String): ChangelogEntry? {
        val v = version.trim()
        if (v.isEmpty()) return null

        val releaseType = typeFromName(type) ?: return null
        val releaseTitle = title.pick(lang) ?: return null

        val items = changes.mapNotNull { change ->
            val kind = kindFromName(change.kind) ?: return@mapNotNull null
            // 单条变更的文案直接内联在 change 对象里（不像 title 那样再套一层）
            val text = if (lang == "en") {
                change.en.trim().takeIf { it.isNotEmpty() }
                    ?: change.zh.trim().takeIf { it.isNotEmpty() }
            } else {
                change.zh.trim().takeIf { it.isNotEmpty() }
                    ?: change.en.trim().takeIf { it.isNotEmpty() }
            } ?: return@mapNotNull null
            ChangelogEntry.Item(kind, text)
        }

        // 一条变更都没有的版本展示出来只是空壳 —— 跳过
        if (items.isEmpty()) return null

        return ChangelogEntry(
            version = v,
            date = date.trim(),
            type = releaseType,
            title = releaseTitle,
            changes = items
        )
    }

    /**
     * 按语言取一份文案，**双向回退**。
     *
     * 目标语言缺失时退回另一种：宁可显示另一种语言，
     * 也好过留空让用户以为「这一版没写东西」。
     */
    private fun Localized.pick(lang: String): String? {
        val z = zh.trim().takeIf { it.isNotEmpty() }
        val e = en.trim().takeIf { it.isNotEmpty() }
        return if (lang == "en") (e ?: z) else (z ?: e)
    }
}