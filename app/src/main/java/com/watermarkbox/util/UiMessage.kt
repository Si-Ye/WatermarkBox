package com.watermarkbox.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
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
 * // ViewModel 侧
 * _uiState.update { it.copy(message = UiMessage(R.string.editor_error_load_failed)) }
 * _uiState.update { it.copy(message = UiMessage(R.string.editor_font_imported, saved)) }
 *
 * // UI 侧
 * state.message?.let { snackbarHostState.showSnackbar(it.resolve()) }
 *
 * // 该类型自带的 @Composable 扩展
 * @Composable fun UiMessage.resolve(): String = stringResource(resId, *args)
 * ```
 *
 * @param resId 字符串资源 ID
 * @param args  格式化参数（对应 strings.xml 里的 %1$s / %1$d 等占位符）
 */
data class UiMessage(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList()
) {
    constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
}

/**
 * 在 Composable 上下文中把 [UiMessage] 解析为当前语言的字符串。
 *
 * 放在 UI 层调用，而不是在 ViewModel 里 —— 这样：
 * - 消息总是以**显示那一刻**的语言呈现
 * - 用户切语言后，新产生的消息自动是新语言
 */
@Composable
fun UiMessage.resolve(): String = stringResource(resId, *args.toTypedArray())