package com.watermarkbox.data.model

/**
 * 一次「检查更新」的结果。
 *
 * ## 为什么把「版本号」和「原始 tag」分开存
 *
 * GitHub Release 的 `tag_name` 通常是 `v1.5.0`，但**规范并不强制带 v** ——
 * 手工创建的 Release 完全可能是 `1.5.0` 甚至 `release-1.5.0`。
 *
 * 所以：
 * - [tagName]：**原样保留**，用于展示与拼接下载链接
 * - [version]：**归一化后的纯数字版本**（已剥离 `v` 前缀），用于比较
 *
 * 把解析结果固化在数据层，UI 与比较逻辑就都不用再关心格式问题。
 *
 * @param version    归一化版本号，如 `1.5.0`（已去 `v`）
 * @param tagName    原始 tag，如 `v1.5.0`
 * @param title      Release 标题（可能为空）
 * @param notes      Release 说明（Markdown 原文，可能为空）
 * @param htmlUrl    Release 网页地址（跳浏览器用）
 * @param apkUrl     能直接下载的 APK 链接；若 Release 附件里没有 .apk 则为 null
 */
data class UpdateInfo(
    val version: String,
    val tagName: String,
    val title: String?,
    val notes: String?,
    val htmlUrl: String,
    val apkUrl: String?
)
