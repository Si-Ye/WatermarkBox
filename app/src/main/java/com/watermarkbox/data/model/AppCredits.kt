package com.watermarkbox.data.model

import androidx.annotation.DrawableRes
import com.watermarkbox.R

/**
 * 制作人名单。
 *
 * 用数据描述而非散落在 UI 里，便于：
 * - 单测校验顺序（主创必须在第 1 位）
 * - 将来扩展（加头像、链接等）
 *
 * @param name   显示名
 * @param isLead 是否为主创（UI 会加粗并标记 ★）
 * @param role   角色说明
 * @param qq     QQ 号（可空；非空时关于页提供复制/跳转）
 * @param avatarRes 头像 drawable 资源 id（可空；非空时关于页显示圆形头像）
 */
data class Credit(
    val name: String,
    val isLead: Boolean = false,
    val role: String = "",
    val qq: String? = null,
    @get:androidx.annotation.DrawableRes val avatarRes: Int? = null
)

/**
 * 应用制作人。
 *
 * **顺序即展示顺序，第 1 位是主创。**
 *
 * ## 为什么头像改用 `R.drawable.*` 直接引用（曾经按名字反射）
 *
 * 早先这里只存一个名字常量（`LEAD_AVATAR_NAME = "siye_avatar"`），
 * 由界面层用 `resources.getIdentifier()` 反射查表 —— 初衷是
 * 「让数据层不依赖 R」。代价有三条，后来都真的咬到人了：
 *
 * 1. **资源会被误删**：lint 看不见反射引用，把 `siye_avatar.png`
 *    判成「未使用资源」，清理时删掉 → 头像悄悄变成占位图标
 * 2. 改名 / 挪目录在构建期无法校验，只能等运行时才发现
 * 3. lint 为此报 `DiscouragedApi` 与 `LocalContextResourcesRead`
 *
 * 直接引用 id 后这三条同时消失。数据层引用 `R` 在本项目已有先例
 * （`ReleaseHistory` 引用 `R.string.*`），不算破例。
 */
object AppCredits {

    val entries: List<Credit> = listOf(
        Credit(
            name = "肆叶🍃",
            isLead = true,
            role = "主创",
            qq = "3992766503",
            avatarRes = R.drawable.siye_avatar
        ),
        Credit(name = "Operit", isLead = false, role = "开发协助")
    )

    /** 主创（第 1 位）。 */
    val lead: Credit get() = entries.first()
}