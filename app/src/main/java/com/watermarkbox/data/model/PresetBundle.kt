package com.watermarkbox.data.model

import kotlinx.serialization.Serializable

/**
 * 预设导出文件的顶层结构。
 *
 * ## 为什么不是「直接把 List<WatermarkPreset> 序列化出去」
 *
 * 裸数组没有**自描述性**：
 *
 * - 拿到一个 `[{...}]` 文件，无法知道它是谁导出的、什么版本
 * - 将来格式演进时，无法区分「旧版本的合法文件」和「恰好字段名相同的
 *   别的什么 JSON」
 *
 * 加一层信封后，解析时可以明确回答「这是不是本应用导出的文件」
 * （靠 [app]）以及「我的版本能不能读懂它」（靠 [formatVersion]）。
 *
 * ## app 的默认值是**空字符串**，不是 [APP_TAG]
 *
 * 这看着别扭（默认值怎么会是不合法的值？），但是**故意的**：
 *
 * 若默认写成 `APP_TAG`，那么一个**根本没有 `app` 字段**的 JSON
 * （比如某个结构偶然相似的文件）反序列化后会得到 "WatermarkBox"，
 * 于是顺利通过「是不是本应用的文件」这道检查 ——
 * 校验形同虚设。
 *
 * 默认空串则相反：没有声明来源 ⇒ 不是我们的文件 ⇒ 明确报 NOT_OURS。
 * 而且不会抛异常（缺字段有默认值），所以能给用户一句有用的提示，
 * 而不是一个 `MissingFieldException` 堆栈。
 *
 * @param formatVersion 文件格式版本（见 [CURRENT_FORMAT_VERSION]）
 * @param app           导出者标识；导出时总是写入 [APP_TAG]
 * @param exportedAt    导出时刻（毫秒时间戳），仅作展示
 * @param presets       预设列表（按导出时的显示顺序）
 */
@Serializable
data class PresetBundle(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val app: String = "",
    val exportedAt: Long = 0L,
    val presets: List<WatermarkPreset> = emptyList()
) {
    companion object {
        /**
         * 当前应用能写出的格式版本。
         *
         * ## 版本号规则
         *
         * 只在**破坏性变更**时递增 —— 即旧版应用读不懂新文件的那种变更。
         * 例如：删掉某个字段、改变字段语义、改变枚举取值。
         *
         * **新增可选字段不算**破坏性变更：因为反序列化配置了
         * `ignoreUnknownKeys`，旧版应用会忽略它不认识的字段，
         * 仍能读出自己认识的那部分。
         *
         * ## 读到更高版本时怎么办
         *
         * **拒绝导入**，而不是「尽力解析」。
         * 若新版本改变了某字段的语义（比如 `alpha` 从 0~1 改成 0~255），
         * 强行解析会得到**看起来正常但完全错误**的结果 ——
         * 用户会以为导入成功了，实际上预设是坏的。
         * 明确报错比静默出错好。
         */
        const val CURRENT_FORMAT_VERSION = 1

        /** 导出者标识。用于识别「这是不是本应用的文件」。 */
        const val APP_TAG = "WatermarkBox"
    }
}
