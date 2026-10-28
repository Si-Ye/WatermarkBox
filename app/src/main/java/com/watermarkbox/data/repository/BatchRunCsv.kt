package com.watermarkbox.data.repository

/**
 * 把一次批量导出的清单渲染成 CSV。
 *
 * ## 为什么单独一层、且不碰 Android API
 *
 * CSV 真正容易错的地方只有三件事：**转义**、**顺序**、**编码**。
 * 这三件都不需要真机就能验证，所以这里做成纯 Kotlin 函数，
 * 让单测直接覆盖 —— 而不是塞进 ViewModel 里靠手动点按钮去试。
 *
 * uri → 文件名这一步要查 `ContentResolver`，属于 Android 侧，
 * 通过 [build] 的 `nameOf` 参数注入，本文件因此保持无依赖。
 *
 * ## 为什么是 CSV
 *
 * 清单的用途是**对账**：哪张图导出成了哪个文件、哪张失败了为什么。
 * 用户拿到手第一件事是丢进 Excel / WPS 看。CSV 是唯一
 * 「双击就能打开」又不需要我们实现 xlsx 写出的格式。
 *
 * ## 为什么开头有 BOM
 *
 * 中文 Windows 上 Excel 对**没有 BOM** 的 CSV 默认按 GBK 解释，
 * 中文全是乱码 —— 这是 CSV 导出最常收到的反馈。一个 `\uFEFF` 就解决。
 * 代价是极少数只认纯 ASCII 的老工具会多看到一个不可见字符，
 * 相比「打开就是乱码」这个代价可以忽略。
 */
object BatchRunCsv {

    /** 行分隔符。RFC 4180 规定 CRLF；Excel 两种都认，但 CRLF 更保险。 */
    private const val CRLF = "\r\n"

    /** UTF-8 BOM。见类注释「为什么开头有 BOM」。 */
    private const val BOM = '\uFEFF'

    /**
     * 生成 CSV 全文（含表头与 BOM）。
     *
     * @param result      落盘的清单
     * @param texts       表头与「成功/失败」文案（由调用方按当前语言提供）
     * @param nameOf      由 uri 查显示名；查不到返回 null（该单元格留空）
     * @param reasonLabel 失败原因的**枚举名** → 本地化文案；
     *                    认不出的名字原样返回（旧版本写入的新枚举值不至于丢信息）
     */
    fun build(
        result: BatchRunResult,
        texts: CsvTexts,
        nameOf: (String) -> String? = { null },
        reasonLabel: (String) -> String = { it }
    ): String {
        val rows = buildList {
            result.success.forEachIndexed { position, uri ->
                add(
                    Row(
                        // 序号与 success 同序存于 successIndices；旧记录没有这个字段
                        index = result.successIndices.getOrNull(position),
                        ok = true,
                        name = nameOf(uri),
                        uri = uri,
                        reason = ""
                    )
                )
            }
            result.failed.forEach { item ->
                add(
                    Row(
                        index = item.index,
                        ok = false,
                        name = nameOf(item.uri),
                        uri = item.uri,
                        reason = reasonLabel(item.reason)
                    )
                )
            }
        }

        // 按导出序号排序：CSV 的行序必须与用户当初选图的顺序一致 ——
        // 对账时「第 3 张」得指同一张图。序号缺失（旧记录）的排最后，
        // 用稳定排序保证同序号时仍是原始先后。
        val ordered = rows.sortedBy { it.index ?: Int.MAX_VALUE }

        return buildString {
            append(BOM)
            append(
                listOf(
                    texts.columnIndex,
                    texts.columnResult,
                    texts.columnName,
                    texts.columnLocation,
                    texts.columnReason
                ).joinToString(",") { escape(it) }
            ).append(CRLF)

            ordered.forEach { row ->
                append(row.index?.toString().orEmpty()).append(',')
                append(if (row.ok) texts.success else texts.failed).append(',')
                append(escape(row.name.orEmpty())).append(',')
                append(escape(row.uri)).append(',')
                append(escape(row.reason)).append(CRLF)
            }
        }
    }

    /**
     * 一行。
     *
     * `index` 为 null 表示「这条记录没带序号」——
     * 2.3.1 之前写入的旧清单就是这种（[BatchRunResult.success] 只有 uri）。
     */
    private data class Row(
        val index: Int?,
        val ok: Boolean,
        val name: String?,
        val uri: String,
        val reason: String
    )

    /**
     * RFC 4180 转义：字段含逗号 / 双引号 / 换行时整体加引号，内部引号翻倍。
     *
     * 不转义的后果不是「显示难看」，而是**整列错位** ——
     * 一个带逗号的文件名会把后面所有列顶到右边一格，
     * 用户按列读就会把失败原因读成文件名。
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

/**
 * CSV 的文案。
 *
 * 单独拎出来是因为 [BatchRunCsv] 是纯 Kotlin：它拿不到 `Context`，
 * 也就不该自己去取字符串资源。由调用方按当前语言填好传进来。
 */
data class CsvTexts(
    val columnIndex: String,
    val columnResult: String,
    val columnName: String,
    val columnLocation: String,
    val columnReason: String,
    val success: String,
    val failed: String
)
