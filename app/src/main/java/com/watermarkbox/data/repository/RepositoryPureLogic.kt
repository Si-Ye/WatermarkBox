package com.watermarkbox.data.repository

/**
 * **仓库层的纯逻辑工具**（不含任何 Android 依赖，可直接 JVM 单测）。
 *
 * ## 为什么单独拿出来
 *
 * 五个 Repository 都持有 `Context`，纯 JVM 单测跑不起来
 * （`Context`、`Uri`、`Bitmap` 全是 Android 类）。但仓库里真正
 * **容易出错、又值得测**的部分其实不是「调系统 API」，而是这些
 * 字符串 / 数值判断 —— 把它们的逻辑抽到这里，就能测了：
 *
 * | 工具 | 用在哪 | 出错会怎样 |
 * |---|---|---|
 * | [isAllowedFontExtension] | `FontRepository` 导入校验 | 放进来一堆 `.exe`，解析失败，白占空间 |
 * | [sanitizeFileName] | `FontRepository` 落盘命名 | 带 `/` 的名字会写到别的目录 |
 * | [isFontSizeAllowed] | `FontRepository` 体积上限 | 8MB 限制形同虚设，复制大文件卡住 IO |
 * | [isStorageWritableState] | `ExportRepository` 可写判断 | 在未挂载状态下写，静默失败 |
 * | [normalizeAlbumPath] | `ExportRepository` 相册路径 | 多余的斜杠会建出奇怪的目录 |
 * | [isCacheableProfile] | `CreditsRepository` 缓存判定 | 缓存永不过期，改了头像大半年不更新 |
 *
 * 这些逻辑原本散落在各仓库的私有函数里，测不到；抽出来之后
 * 测试是**直接针对生产代码**跑的（不是复制一份逻辑），所以不会腐化。
 */
internal object RepositoryPureLogic {

    /** 允许的字体扩展名（与 `FontRepository` 保持一致）。 */
    val ALLOWED_FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf", "ttc")

    /** 单字体文件体积上限：8 MB。 */
    const val MAX_FONT_BYTES: Long = 8L * 1024 * 1024

    /** 在线档案缓存时长：6 小时。 */
    const val PROFILE_CACHE_TTL_MS: Long = 6 * 60 * 60 * 1000L

    /**
     * 扩展名是否允许导入。
     *
     * 大小写不敏感（`.TTF` 与 `.ttf` 等价）；没有扩展名一律拒绝；
     * 只取**最后一段**（`a.b.ttf` → `ttf`），避免文件名里带点就误判。
     */
    fun isAllowedFontExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in ALLOWED_FONT_EXTENSIONS
    }

    /** 体积是否在上限内（等于上限算通过，与「> MAX 才拒绝」的写法一致）。 */
    fun isFontSizeAllowed(bytes: Long): Boolean = bytes >= 0 && bytes <= MAX_FONT_BYTES

    /**
     * 清理文件名中的非法字符。
     *
     * 覆盖 Windows 保留字符 + 路径分隔符 + 换行/制表符。
     * 之所以连 `\n` 都换掉：文件名里带换行会让「最近文件」这类列表
     * 直接错行，而且用户根本看不出来。
     */
    fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")

    /**
     * 判断「存储状态字符串」是否表示可写。
     *
     * 抽成纯函数是为了让调用点不再直接依赖
     * `Environment.getExternalStorageState()`（Android 静态方法，
     * 单测里拿到的是 null）。
     */
    fun isStorageWritableState(state: String?): Boolean = state == "mounted"

    /**
     * 规范化相册相对路径。
     *
     * 规则：去掉尾部斜杠；空串或只有斜杠时退回 [default]。
     * MediaStore 对 `RELATIVE_PATH` 里多余的分隔符很敏感 ——
     * `Pictures/WatermarkBox/` 这种带尾斜杠的值会让系统容忍，
     * 但某些 ROM 上会建出空名字的子目录，所以统一抹掉。
     */
    fun normalizeAlbumPath(path: String?, default: String): String {
        val trimmed = path?.trim()?.trimEnd('/')
        return if (trimmed.isNullOrEmpty()) default else trimmed
    }

    /**
     * 缓存的档案是否可以继续用。
     *
     * @param cachedAtMs 缓存写入时刻；0 表示从未缓存
     * @param nowMs      当前时刻
     */
    fun isCacheableProfile(
        cachedAtMs: Long,
        nowMs: Long,
        ttlMs: Long = PROFILE_CACHE_TTL_MS
    ): Boolean {
        if (cachedAtMs <= 0L) return false
        // 时钟回拨（用户改时间 / NTP 校正）时差值可能为负 ——
        // 那种情况下视为「缓存还有效」，总比疯狂重试接口好。
        val age = nowMs - cachedAtMs
        return age < ttlMs
    }
}