package com.watermarkbox.data.model

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
 */
object AppCredits {

    /** 主创肆叶的头像 drawable 名称（用于按名称解析资源，避免在 model 层依赖 R）。 */
    const val LEAD_AVATAR_NAME: String = "siye_avatar"

    val entries: List<Credit> = listOf(
        Credit(name = "肆叶🍃", isLead = true, role = "主创", qq = "3992766503"),
        Credit(name = "Operit", isLead = false, role = "开发协助")
    )

    /** 主创（第 1 位）。 */
    val lead: Credit get() = entries.first()
}