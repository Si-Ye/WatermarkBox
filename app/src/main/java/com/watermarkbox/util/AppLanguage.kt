package com.watermarkbox.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用内语言切换。
 *
 * ## 为什么需要它
 *
 * 系统设置里确实有「应用 → 语言」（Android 13+），但：
 * - 路径太深，多数用户找不到
 * - Android 12 及以下没有这个入口
 *
 * 所以在应用内提供一个入口，让用户点几下就能切换。
 *
 * ## 实现原理
 *
 * 用 [AppCompatDelegate.setApplicationLocales] —— 这是 AndroidX 提供的官方
 * per-app language API：
 *
 * - **Android 13+**：直接调用系统的按应用语言能力，与系统设置里的选择**双向同步**
 * - **Android 12 及以下**：AppCompat 自己接管，通过 `attachBaseContext`
 *   注入带语言信息的 Configuration，并持久化到 SharedPreferences
 *
 * 两种情况下，调用后系统都会**自动重建 Activity**，界面立即变成新语言，
 * 无需手动重启应用。
 *
 * ## 为什么必须传空 locale 表示「跟随系统」
 *
 * [AppCompatDelegate.setApplicationLocales] 传入**空列表**才是「跟随系统」；
 * 传 `null` 行为不确定。这是官方 API 的约定，容易踩坑。
 */
object AppLanguage {

    /**
     * 可选语言。
     *
     * @param tag         BCP-47 语言标签；**空串表示跟随系统**
     * @param displayName 语言自称（如「中文」「English」）
     *
     * ## 为什么语言名不放进 strings.xml
     *
     * 语言名应当用**各语言自己的写法**，而不是跟随当前界面语言翻译：
     * - 中文用户看到 `中文` / `English`
     * - 英文用户看到的还是 `中文` / `English`
     *
     * 这样无论当前界面是什么语言，用户都能认出自己那一项。
     * 若跟随界面翻译成 `Chinese`，对只懂中文的用户反而更难识别。
     */
    enum class Option(
        val tag: String,
        val displayName: String
    ) {
        /** 跟随系统语言（默认）。 */
        SYSTEM("", ""),

        /** 简体中文。 */
        CHINESE("zh-CN", "中文"),

        /** 英语。 */
        ENGLISH("en", "English")
    }

    /**
     * 当前生效的语言选项。
     *
     * 通过比对已设置的语言标签得出。注意 Android 13+ 与低版本的行为一致：
     * 都返回「开发者显式设置过的语言」，未设置过时返回 [Option.SYSTEM]。
     */
    fun current(): Option {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return Option.SYSTEM
        val tag = locales.toLanguageTags()
        // 系统可能把 zh-CN 规范化成 zh-CN、zh-Hans-CN 等形式，
        // 因此用前缀匹配而非全等，避免「明明是中文却识别不出来」。
        return Option.entries.firstOrNull { option ->
            option.tag.isNotEmpty() && (
                tag.equals(option.tag, ignoreCase = true) ||
                    tag.startsWith("${option.tag}-", ignoreCase = true) ||
                    tag.substringBefore('-').equals(
                        option.tag.substringBefore('-'),
                        ignoreCase = true
                    )
                )
        } ?: Option.SYSTEM
    }

    /**
     * 切换语言。
     *
     * 调用后系统会自动重建 Activity，当前界面立即刷新，无需手动重启。
     *
     * @param option 目标语言；[Option.SYSTEM] 表示跟随系统
     */
    fun apply(option: Option) {
        val locales = if (option.tag.isEmpty()) {
            // 空列表 = 跟随系统（不能用 null）
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(option.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
