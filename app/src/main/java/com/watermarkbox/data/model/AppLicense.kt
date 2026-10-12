package com.watermarkbox.data.model

/**
 * 应用自身的许可与来源信息。
 *
 * 单独建一份数据、而不是散在 UI 里，理由和 [AppCredits] 相同：
 * - 可单测（校验仓库地址格式、许可证名是否为合法 SPDX 标识）
 * - 改动时只改一处
 * - 将来若接入「打开浏览器」「复制链接」，数据层已就绪
 */
object AppLicense {

    /** 本应用使用的许可证名称（SPDX 标识）。 */
    const val LICENSE_NAME: String = "Apache License 2.0"

    /** 许可证 SPDX 短标识。 */
    const val LICENSE_SPDX: String = "Apache-2.0"

    /** 源码仓库地址。 */
    const val REPOSITORY_URL: String = "https://github.com/Si-Ye/WatermarkBox"

    /** 仓库地址的展示文本（去掉协议头，短一些更耐看）。 */
    const val REPOSITORY_DISPLAY: String = "github.com/Si-Ye/WatermarkBox"

    /** 许可证全文的在线地址。 */
    const val LICENSE_URL: String = "https://www.apache.org/licenses/LICENSE-2.0"

    /** 一句话说明，展示在许可证名称下方。 */
    const val LICENSE_SUMMARY: String =
        "本应用为自由软件，你可以在许可证条款下自由使用、修改与分发。"
}
