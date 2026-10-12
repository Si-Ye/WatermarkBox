package com.watermarkbox.data.model

import androidx.annotation.StringRes

import com.watermarkbox.R

/**
 * 一条更新记录。
 *
 * @param version 版本号（与 `BuildConfig.VERSION_NAME` 对应）
 * @param date    发布日期（`yyyy-MM-dd`）
 * @param titleRes 这一版的**主题**资源 ID（一句话概括）
 * @param changes 具体变更条目
 * @param type    变更规模，决定列表里徽章的颜色
 *
 * ## 为什么标题/描述存资源 ID 而不是字符串
 *
 * 本应用支持中英双语。若这里存中文原文，英文界面就会显示中文日志。
 * 存资源 ID 后，界面层用 `stringResource(titleRes)` 取当前语言文案。
 *
 * 代价是数据层多一个 `androidx.annotation` 依赖（**仅注解**，
 * 编译期擦除，不影响单元测试运行）；测试改为校验资源 ID 是否有效。
 */
data class ReleaseNote(
    val version: String,
    val date: String,
    @param:StringRes val titleRes: Int,
    val changes: List<Change>,
    val type: ReleaseType
) {
    /**
     * 单条变更。
     *
     * @param textRes 描述的资源 ID
     * @param kind 这条属于「新增 / 修复 / 优化 / 变更」
     */
    data class Change(@param:StringRes val textRes: Int, val kind: ChangeKind)
}

/**
 * 变更规模。
 *
 * labelRes 存资源 ID 而非字符串：英文界面需显示 "Major" / "Minor"。
 */
enum class ReleaseType(@param:StringRes val labelRes: Int) {
    /** 大版本：新功能 / 重构 */
    MAJOR(R.string.changelog_type_major),

    /** 小版本：修 bug / 微调 */
    MINOR(R.string.changelog_type_minor)
}

/** 单条变更的性质。symbol 是纯符号（＋ ✓ ↑ ~），各语言通用，无需本地化。 */
enum class ChangeKind(
    @param:StringRes val labelRes: Int,
    val symbol: String
) {
    ADDED(R.string.changelog_kind_added, "＋"),
    FIXED(R.string.changelog_kind_fixed, "✓"),
    IMPROVED(R.string.changelog_kind_improved, "↑"),
    CHANGED(R.string.changelog_kind_changed, "~")
}

/**
 * 应用更新日志。
 *
 * ## 为什么放在数据层而不是写死在 UI 里
 *
 * - **可单测**：能校验版本号唯一、倒序排列、当前版本在列表中
 * - **加版本只改一处**：不用翻 UI 代码找位置
 * - 与「关于页」的制作人名单（[AppCredits]）同样的思路
 *
 * ## 维护约定
 *
 * - **最新的在最前面**（[releases] 的顺序即展示顺序）
 * - 每次发版后在此追加一条
 */
object ReleaseHistory {

    val releases: List<ReleaseNote> = listOf(

        ReleaseNote(
            version = "1.5.0",
            date = "2026-10-12",
            titleRes = R.string.release_1_5_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                // ---- 国际化 ----
                ReleaseNote.Change(R.string.release_1_5_0_c1, ChangeKind.ADDED),
                // ---- 检查更新 ----
                ReleaseNote.Change(R.string.release_1_5_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_5_0_c3, ChangeKind.ADDED),
                // ---- Markdown 渲染 ----
                ReleaseNote.Change(R.string.release_1_5_0_c4, ChangeKind.IMPROVED),
                // ---- 目录统一 ----
                ReleaseNote.Change(R.string.release_1_5_0_c5, ChangeKind.CHANGED)
            )
        ),

        ReleaseNote(
            version = "1.4.1",
            date = "2026-10-12",
            titleRes = R.string.release_1_4_1_title,
            type = ReleaseType.MINOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_4_1_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_1_c2, ChangeKind.IMPROVED)
            )
        ),

        ReleaseNote(
            version = "1.4.0",
            date = "2026-10-12",
            titleRes = R.string.release_1_4_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                // ---- 预设体系 ----
                ReleaseNote.Change(R.string.release_1_4_0_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c3, ChangeKind.IMPROVED),
                ReleaseNote.Change(R.string.release_1_4_0_c4, ChangeKind.FIXED),

                // ---- 批量处理 ----
                ReleaseNote.Change(R.string.release_1_4_0_c5, ChangeKind.CHANGED),
                ReleaseNote.Change(R.string.release_1_4_0_c6, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c7, ChangeKind.FIXED),

                // ---- 隐藏水印 ----
                ReleaseNote.Change(R.string.release_1_4_0_c8, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c9, ChangeKind.ADDED),

                // ---- 设置 ----
                ReleaseNote.Change(R.string.release_1_4_0_c10, ChangeKind.IMPROVED),
                ReleaseNote.Change(R.string.release_1_4_0_c11, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c12, ChangeKind.ADDED),

                // ---- 关于 ----
                ReleaseNote.Change(R.string.release_1_4_0_c13, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_4_0_c14, ChangeKind.ADDED)
            )
        ),

        ReleaseNote(
            version = "1.3.1",
            date = "2026-10-12",
            titleRes = R.string.release_1_3_1_title,
            type = ReleaseType.MINOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_3_1_c1, ChangeKind.FIXED),
                ReleaseNote.Change(R.string.release_1_3_1_c2, ChangeKind.IMPROVED)
            )
        ),

        ReleaseNote(
            version = "1.3.0",
            date = "2026-10-11",
            titleRes = R.string.release_1_3_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_3_0_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_3_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_3_0_c3, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_3_0_c4, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_3_0_c5, ChangeKind.FIXED)
            )
        ),

        ReleaseNote(
            version = "1.2.0",
            date = "2026-10-11",
            titleRes = R.string.release_1_2_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_2_0_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_2_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_2_0_c3, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_2_0_c4, ChangeKind.ADDED)
            )
        ),

        ReleaseNote(
            version = "1.1.0",
            date = "2026-10-11",
            titleRes = R.string.release_1_1_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_1_0_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_1_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_1_0_c3, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_1_0_c4, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_1_0_c5, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_1_0_c6, ChangeKind.ADDED)
            )
        ),

        ReleaseNote(
            version = "1.0.0",
            date = "2026-10-11",
            titleRes = R.string.release_1_0_0_title,
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(R.string.release_1_0_0_c1, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_0_0_c2, ChangeKind.ADDED),
                ReleaseNote.Change(R.string.release_1_0_0_c3, ChangeKind.ADDED)
            )
        )
    )

    /** 最新版本（列表第一项）。 */
    val latest: ReleaseNote get() = releases.first()

    /** 变更总条数（用于「关于」页显示规模）。 */
    val totalChanges: Int get() = releases.sumOf { it.changes.size }
}