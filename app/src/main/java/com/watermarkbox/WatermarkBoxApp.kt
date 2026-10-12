package com.watermarkbox

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 应用级入口，目前只做一件事：**恢复用户上次选择的语言**。
 *
 * ## 为什么需要它
 *
 * `AppCompatDelegate.setApplicationLocales()` 在 **Android 13+** 由系统负责
 * 持久化，重启后自动生效，无需额外代码。
 *
 * 但在 **Android 12 及以下**，AppCompat 需要一个时机把「已保存的语言」
 * 应用到 Configuration 上。若完全不管，可能出现：
 * - 用户切到英文 → 杀进程重开 → 界面又变回中文
 *
 * 在 [attachBaseContext] 里触发一次即可。这里传入的是**已保存的值**
 * （`getApplicationLocales()`），所以不会覆盖用户的选择。
 *
 * ## 为什么放在 attachBaseContext 而不是 onCreate
 *
 * `attachBaseContext` 早于任何 Activity 创建，能保证 Configuration
 * 在 UI 构建前就已带正确语言，避免「先闪一下中文再变英文」。
 */
class WatermarkBoxApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 读取已保存的 per-app locale 并应用。
        // 未设置过时返回空列表，等价于「跟随系统」，不会产生副作用。
        AppCompatDelegate.setApplicationLocales(
            AppCompatDelegate.getApplicationLocales()
        )
    }
}