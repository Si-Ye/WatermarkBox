package com.watermarkbox.util

import androidx.annotation.AnyRes
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * 一条待显示的界面消息。
 *
 * ## 为什么需要这个类型
 *
 * ViewModel 里不能调用 `stringResource`（那不是 Composable 上下文），
 * 但业务逻辑又需要产出「图片加载失败」这类**跟随语言变化**的提示。
 *
 * 若在 ViewModel 里直接存中文，英文界面会冒出中文提示；
 * 若在 ViewModel 里解析成字符串，切换语言后已显示的消息不会更新。
 *
 * 所以改存**资源 ID + 参数**，由 UI 层在真正显示时解析 —— 那时
 * 一定在 Composable 上下文里，且用的是当前语言。
 *
 * ## 用法
 *
 * ```kotlin
 * // 普通字符串资源
 * UiMessage(R.string.editor_error_load_failed)
 * UiMessage(R.string.editor_font_imported, savedFontName)
 *
 * // 带数量的文案（走 plurals 资源）
 * UiMessage.plural(R.plurals.presets_export_done, count = 3, 3)
 *
 * // UI 侧
 * state.message?.let { snackbarHostState.showSnackbar(it.resolve()) }
 * ```
 *
 * ## 为什么资源 ID 标注为 [AnyRes] 而不是 `@StringRes`
 *
 * 因为同一个字段要同时容纳**字符串**资源和 **plurals** 资源
 * （两者在运行时都只是 `Int` 资源 ID，差别只在取的时候调哪个 API）。
 * 标成 `@StringRes` 的话，传 plurals ID 会被静态检查判为错误。
 *
 * 代价是失去了「这个 ID 一定是字符串资源」的检查 —— 所以
 * 必须靠 [pluralCount] 明确区分，用它来决定解析方式。
 *
 * @param resId       资源 ID（字符串或 plurals）
 * @param args        格式化参数（对应 `%1$s` / `%1$d` 等占位符）
 * @param pluralCount 非 null 时表示 [resId] 指向 **plurals** 资源，
 *                    此值即用于选择单复数形式的数量
 */
@Immutable
data class UiMessage(
    @param:AnyRes val resId: Int,
    val args: List<Any> = emptyList(),
    val pluralCount: Int? = null
) {
    constructor(@AnyRes resId: Int, vararg args: Any) : this(resId, args.toList())

    companion object {
        /**
         * 构造一条使用 plurals 资源的消息。
         *
         * [count] 既用于选择单复数形式，通常也作为 `%1$d` 传给文案 ——
         * 所以要显式传两次（这里不自动补，避免和调用方想展示的数量不一致）。
         */
        fun plural(@PluralsRes resId: Int, count: Int, vararg args: Any): UiMessage =
            UiMessage(resId, args.toList(), pluralCount = count)
    }
}

/**
 * 在 Composable 上下文中把 [UiMessage] 解析为当前语言的字符串。
 *
 * 放在 UI 层调用，而不是在 ViewModel 里 —— 这样：
 * - 消息总是以**显示那一刻**的语言呈现
 * - 用户切语言后，新产生的消息自动是新语言
 */
@Composable
fun UiMessage.resolve(): String {
    val formatArgs = args.toTypedArray()
    return pluralCount?.let { pluralStringResource(resId, it, *formatArgs) }
        ?: stringResource(resId, *formatArgs)
}