package com.watermarkbox.data.model

/**
 * 一条更新记录。
 *
 * @param version 版本号（与 `BuildConfig.VERSION_NAME` 对应）
 * @param date    发布日期（`yyyy-MM-dd`）
 * @param title   这一版的**主题**（一句话概括）
 * @param changes 具体变更条目
 * @param type    变更规模，决定列表里徽章的颜色
 */
data class ReleaseNote(
    val version: String,
    val date: String,
    val title: String,
    val changes: List<Change>,
    val type: ReleaseType
) {
    /**
     * 单条变更。
     *
     * @param text 描述
     * @param kind 这条属于「新增 / 修复 / 优化 / 变更」
     */
    data class Change(val text: String, val kind: ChangeKind)
}

/** 变更规模。 */
enum class ReleaseType(val label: String) {
    /** 大版本：新功能 / 重构 */
    MAJOR("大版本"),

    /** 小版本：修 bug / 微调 */
    MINOR("小版本")
}

/** 单条变更的性质。 */
enum class ChangeKind(val label: String, val symbol: String) {
    ADDED("新增", "＋"),
    FIXED("修复", "✓"),
    IMPROVED("优化", "↑"),
    CHANGED("调整", "~")
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
            version = "1.4.1",
            date = "2026-10-12",
            title = "更新说明页",
            type = ReleaseType.MINOR,
            changes = listOf(
                ReleaseNote.Change(
                    "设置 → 其他 新增「更新说明」，可查看从 1.0.0 至今的全部变更",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "设置里「更新说明」与「关于」归入同一分组，样式统一",
                    ChangeKind.IMPROVED
                )
            )
        ),

        ReleaseNote(
            version = "1.4.0",
            date = "2026-10-12",
            title = "预设可独立编辑 · 批量页大改",
            type = ReleaseType.MAJOR,
            changes = listOf(
                // ---- 预设体系 ----
                ReleaseNote.Change(
                    "首页右上角新增「水印预设」入口，可集中管理预设",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增预设参数编辑页：不选图片也能直接改预设的名称与全部水印参数",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "预设卡片新增编辑图标，重命名并入编辑页（少一个入口，少一次跳转）",
                    ChangeKind.IMPROVED
                ),
                ReleaseNote.Change(
                    "修复保存同名预设时静默覆盖旧预设的问题（属数据丢失），" +
                        "现在会先询问保留 / 替换 / 取消",
                    ChangeKind.FIXED
                ),

                // ---- 批量处理 ----
                ReleaseNote.Change(
                    "批量处理页整体重做，界面与单张编辑保持一致：" +
                        "同样的参数面板、可拖拽抽屉、同一套预设与导出设置",
                    ChangeKind.CHANGED
                ),
                ReleaseNote.Change(
                    "批量预览支持左右滑动切换图片，末页为「添加更多」，可继续追加图片",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "修复批量预览无法滑动的问题：预览组件的手势会拦截横滑，现已在批量页让出手势",
                    ChangeKind.FIXED
                ),

                // ---- 隐藏水印 ----
                ReleaseNote.Change(
                    "隐藏水印新增 DCT 频域开关：可单独控制是否写入频域，" +
                        "并明确标注其对画质的影响，由用户按场景自行取舍",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "设置中新增「开启隐藏水印时默认启用 DCT」选项",
                    ChangeKind.ADDED
                ),

                // ---- 设置 ----
                ReleaseNote.Change(
                    "设置页顶部新增分类菜单：水印 / 导出 / 其他",
                    ChangeKind.IMPROVED
                ),
                ReleaseNote.Change(
                    "新增「默认水印文字」设置：选图进编辑页时自动填入",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增「默认水印图」设置：选图进编辑页时自动载入该 logo",
                    ChangeKind.ADDED
                ),

                // ---- 关于 ----
                ReleaseNote.Change(
                    "新增独立「关于」页：应用图标、版本、制作人（含头像与 QQ）、开源许可",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增本次「更新说明」页",
                    ChangeKind.ADDED
                )
            )
        ),

        ReleaseNote(
            version = "1.3.1",
            date = "2026-10-12",
            title = "预设重名保护",
            type = ReleaseType.MINOR,
            changes = listOf(
                ReleaseNote.Change(
                    "保存预设遇到重名时，弹出「保留两者 / 替换 / 取消」确认框，" +
                        "替代此前的静默覆盖",
                    ChangeKind.FIXED
                ),
                ReleaseNote.Change(
                    "输入预设名称时实时检测重名并提示，不用点保存才知道",
                    ChangeKind.IMPROVED
                )
            )
        ),

        ReleaseNote(
            version = "1.3.0",
            date = "2026-10-11",
            title = "隐藏水印溯源 · EXIF 保留",
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(
                    "新增隐藏水印功能：把溯源信息写入图片，" +
                        "包含 EXIF 元数据与 DCT 频域两条通道",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增隐藏水印检测：可判断图片是否含本应用写入的隐藏水印",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "支持自定义隐藏水印内容，或自动使用「设备 ID + 时间戳」",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "导出时可选保留原图 EXIF 信息（拍摄时间、机型、GPS 等 35 项）",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "修复隐藏水印使用中文时检测结果变成 `?` 的问题" +
                        "（EXIF UserComment 的字符集编码限制）",
                    ChangeKind.FIXED
                )
            )
        ),

        ReleaseNote(
            version = "1.2.0",
            date = "2026-10-11",
            title = "批量处理 · 自定义字体",
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(
                    "新增批量处理：一次最多 50 张，后台处理并显示进度",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "支持导入自定义字体（ttf / otf / ttc）用于文字水印",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增水印预设：可保存常用配置并快速套用",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "文字水印支持 {filename} 等占位符",
                    ChangeKind.ADDED
                )
            )
        ),

        ReleaseNote(
            version = "1.1.0",
            date = "2026-10-11",
            title = "参数完善 · 导出设置",
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(
                    "文字水印支持字号、字体、加粗、斜体、描边、阴影、颜色",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "支持单个 / 平铺 / 对角线三种布局，可调旋转与透明度",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "支持九宫格定位与边距调整",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "图片水印：可选择 logo、调整大小与位置",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "新增导出设置：格式（PNG / JPEG）、质量、文件名模板、输出目录",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "参数面板支持上下拖拽调整高度",
                    ChangeKind.ADDED
                )
            )
        ),

        ReleaseNote(
            version = "1.0.0",
            date = "2026-10-11",
            title = "首个版本",
            type = ReleaseType.MAJOR,
            changes = listOf(
                ReleaseNote.Change(
                    "水印盒诞生：单张图片添加水印，实时预览",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "支持文字水印与图片水印",
                    ChangeKind.ADDED
                ),
                ReleaseNote.Change(
                    "导出到相册",
                    ChangeKind.ADDED
                )
            )
        )
    )

    /** 最新版本（列表第一项）。 */
    val latest: ReleaseNote get() = releases.first()

    /** 变更总条数（用于「关于」页显示规模）。 */
    val totalChanges: Int get() = releases.sumOf { it.changes.size }
}