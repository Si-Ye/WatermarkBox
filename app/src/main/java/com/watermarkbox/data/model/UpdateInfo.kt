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
 * @param apkSha256  该 APK 的 SHA-256（**小写十六进制**）。来自 GitHub API 的
 *                   `assets[].digest` 字段（形如 `sha256:abc...`，已剥前缀）。
 *                   若 GitHub 未提供（旧附件、或 API 版本较老）则为 null
 *                   —— 此时跳过哈希校验，但仍会做签名校验。
 * @param apkSize    附件字节数，用于下载后做一次「大小是否吻合」的快速判断。
 *                   为 0 表示未知。
 */
data class UpdateInfo(
    val version: String,
    val tagName: String,
    val title: String?,
    val notes: String?,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkSha256: String? = null,
    val apkSize: Long = 0L
)
