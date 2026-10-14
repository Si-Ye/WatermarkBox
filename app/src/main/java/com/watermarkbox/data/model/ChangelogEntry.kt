package com.watermarkbox.data.model

/**
 * 一条**已本地化**的更新记录 —— UI 直接渲染这个。
 *
 * ## 为什么需要它（与 [ReleaseNote] 的分工）
 *
 * 更新说明有**两个来源**：
 *
 * | 来源 | 何时用 | 文案形态 |
 * |---|---|---|
 * | 远程 JSON（GitHub） | 正常情况 | 中英内联在 JSON 里 |
 * | 内置 [ReleaseHistory] | 离线 / 拉取失败 | `@StringRes` 资源 ID |
 *
 * 两者结构相似但**文案的取法完全不同**：前者要在解析时按语言选一份，
 * 后者要交给 Compose 的 `stringResource` 解析。
 *
 * 若让 UI 分别处理两种形态，每个用到的地方都要写 `if (来自远程)` ——
 * 于是有了本类：**把「来源差异」在数据层抹平**，
 * UI 只认识 [ChangelogEntry]。
 *
 * ## 与 [ReleaseType] / [ChangeKind] 的关系
 *
 * 后两者是**枚举**（MAJOR/MINOR、ADDED/FIXED/…），它们的标签文案
 * 是固定且少量的一组，继续放资源里（`changelog_type_*` / `changelog_kind_*`）
 * 更合适 —— 所以这里**直接复用**，不做转换。
 *
 * 只有 `title` 与每条 `changes[].text` 是「每个版本都不一样」的长文案，
 * 才需要从资源里解放出来。
 */
data class ChangelogEntry(
    val version: String,
    val date: String,
    val type: ReleaseType,
    /** 这一版的主题（一句话概括），已按当前语言解析好 */
    val title: String,
    val changes: List<Item>
) {
    /**
     * 单条变更。
     *
     * @param kind 属于「新增 / 修复 / 优化 / 变更」（决定符号与标签）
     * @param text 描述文本，已按当前语言解析好
     */
    data class Item(val kind: ChangeKind, val text: String)
}

/**
 * 更新说明的**总条数**统计（供概览卡片显示）。
 *
 * 抽成扩展而不是塞进 [ChangelogEntry]，是因为它天然是对**整个列表**的聚合。
 */
val List<ChangelogEntry>.totalChanges: Int get() = sumOf { it.changes.size }
