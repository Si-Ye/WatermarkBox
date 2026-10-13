package com.watermarkbox.util

import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.PresetBundle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.watermark.WatermarkRenderer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预设文件（JSON）的编解码。
 *
 * ## 为什么单独抽一个 object 而不是写在 ViewModel 里
 *
 * 这里全是**纯逻辑**：字符串进、字符串出，不碰 Context、不碰文件系统。
 * 抽出来后可以直接单元测试 —— 尤其是下面这些用真实文件很难构造的场景：
 * 空文件、坏 JSON、字段缺失、数值是 NaN、名称超长、版本号过高。
 *
 * ## 两道防线
 *
 * 预设文件是**外部输入**：可能来自另一台设备、可能被人手工编辑过、
 * 也可能只是传输时损坏了。因此：
 *
 * 1. **格式校验** —— 不是本应用的文件、版本过高：明确拒绝并说明原因
 * 2. **内容净化** —— 数值夹到合法区间、名称清理、id 重建
 *
 * 第 2 点常被忽略，但很关键：见 [sanitize] 的注释。
 */
object PresetFileCodec {

    /**
     * 文件字符数上限。
     *
     * 正常预设文件只有几十 KB（一条预设约 1 KB）。设 2 MB 的上限是为了
     * 防止把超大文件读进内存 —— 导入时我们**先整体读成字符串**，
     * 若用户误选了一个几百 MB 的文件（比如视频），会直接 OOM。
     *
     * 用字符数而非字节数：调用方可能是 Reader，拿不到字节长度。
     */
    const val MAX_FILE_CHARS = 2 * 1024 * 1024

    /** 预设数量上限。正常用户不会有几百个预设，超了大概率是恶意/损坏文件。 */
    const val MAX_PRESETS = 500

    /** 预设名称长度上限（字符）。 */
    const val MAX_NAME_LENGTH = 60

    /** 名称缺失时使用的兜底名称。 */
    const val FALLBACK_NAME = "预设"

    /** 水印文字长度上限（字符）。 */
    private const val MAX_TEXT_LENGTH = 500

    /** 字号合理上限（px，基于导出原图坐标系）。 */
    private const val MAX_FONT_SIZE = 2000f

    /** 边距合理上限（px）。 */
    private const val MAX_MARGIN = 100000f

    /**
     * 导出专用 Json 配置。
     *
     * 与存储用的（`AppJson`）有两点不同：
     * - `prettyPrint`：导出文件是给人看的，也便于手工排查问题。
     *   存储用的是紧凑格式（省空间，没人看）。
     * - `ignoreUnknownKeys`：导入别人（可能是更新版本应用）导出的文件时，
     *   忽略自己不认识的字段，而不是报错。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // ==================================================================
    // 解析结果
    // ==================================================================

    /**
     * 解析结果。
     *
     * 用 sealed interface 而非「返回 null 或抛异常」：
     * UI 需要按**失败原因**给不同文案（「文件是空的」和「这不是水印盒的文件」
     * 对用户的行动指引完全不同），光知道"失败了"没用。
     */
    sealed interface ParseResult {

        /**
         * 解析成功。
         *
         * @param presets 已净化的预设（id 已重建、数值已夹紧、logoUri 已清空）
         * @param cleaned 净化过程中**修正过内容**的预设条数（用于提示用户）
         */
        data class Ok(val presets: List<WatermarkPreset>, val cleaned: Int = 0) : ParseResult

        /** 解析失败。 */
        data class Failed(val reason: FailureReason, val detail: String? = null) : ParseResult
    }

    /** 失败原因分类（UI 据此选文案）。 */
    enum class FailureReason {
        /** 文件为空或只有空白字符 */
        EMPTY,

        /** 文件过大（超过 [MAX_FILE_CHARS]） */
        TOO_LARGE,

        /** 不是合法的 JSON，或 JSON 结构与本应用预期不符 */
        MALFORMED,

        /** 是合法 JSON，但不是本应用导出的文件（`app` 标记不匹配） */
        NOT_OURS,

        /** 文件的格式版本高于当前应用能读懂的版本 */
        VERSION_TOO_NEW,

        /** 文件合法但没有包含任何预设 */
        NO_PRESETS
    }

    // ==================================================================
    // 编码（导出）
    // ==================================================================

    /**
     * 把预设列表编码为可写出的 JSON 文本。
     *
     * @param presets    要导出的预设（顺序即文件中的顺序）
     * @param exportedAt 导出时刻（毫秒），仅作记录
     */
    fun encode(presets: List<WatermarkPreset>, exportedAt: Long): String {
        val bundle = PresetBundle(
            formatVersion = PresetBundle.CURRENT_FORMAT_VERSION,
            app = PresetBundle.APP_TAG,
            exportedAt = exportedAt,
            presets = presets.map { normalizeForExport(it) }
        )
        return json.encodeToString(PresetBundle.serializer(), bundle)
    }

    /**
     * 导出前的规范化：剥离不可移植字段 + 数值夹紧。
     *
     * ## 为什么要做「数值夹紧」（不只是剥离 logo）
     *
     * 因为 [json] 没有开 `allowSpecialFloatingPointValues`，
     * 遇到 NaN / Infinity 时**序列化会直接抛异常**。
     * 也就是说：只要内存里有任何一条预设带了非法浮点值，
     * **整次导出都会失败**，用户看到的是"导出失败"却不知为什么。
     *
     * 正常操作路径下用户设不出 NaN（滑块是离散的），但
     * 「存储里已有残留脏数据」（比如早期版本写入的、
     * 或手工改过 DataStore）是可能的。在出口处统一夹一道，
     * 保证**写出去的文件永远是合法且可再次解析的**。
     *
     * ## 剥离非可移植字段
     *
     * - `logoUri`：`content://` 授权只在本机有效，换设备/重装即失效
     * - `customFontName`：指向应用私有目录里的字体文件，同理
     *
     * 若原样写进文件，对方导入后会拿到指向虚无的引用 ——
     * 表现是**静默失败**（图片水印不显示 / 文字渲染不出来），
     * 用户只会觉得"这个预设坏了"，却不知道该重新选一张图。
     *
     * 清空后水印的**参数**（宽度、位置、透明度……）都还在，
     * 重新选一张 logo / 字体就能直接用。
     */
    private fun normalizeForExport(preset: WatermarkPreset): WatermarkPreset =
        preset.copy(
            textStyle = normalizeTextStyle(preset.textStyle).copy(customFontName = null),
            imageStyle = normalizeImageStyle(preset.imageStyle).copy(logoUri = null)
        )

    // ==================================================================
    // 解码（导入）
    // ==================================================================

    /**
     * 解析预设文件内容。
     *
     * @param text    文件全文
     * @param idSeed  重建 id 时使用的时间基准。默认取当前时间；
     *                测试可传入固定值以获得可预期的 id。
     */
    fun decode(text: String, idSeed: Long = System.currentTimeMillis()): ParseResult {
        // ---- 1. 基本检查 ----
        if (text.isBlank()) {
            return ParseResult.Failed(FailureReason.EMPTY)
        }
        if (text.length > MAX_FILE_CHARS) {
            return ParseResult.Failed(
                FailureReason.TOO_LARGE,
                "长度 ${text.length} 超过上限 $MAX_FILE_CHARS"
            )
        }

        // ---- 2. 反序列化 ----
        // 包在 runCatching 里：kotlinx.serialization 对各种结构问题
        // 会抛不同异常（SerializationException / IllegalArgumentException /
        // MissingFieldException…），逐个判断类型既繁琐又容易漏。
        // 这里统一归为 MALFORMED，具体异常信息放进 detail 便于排查。
        val bundle = runCatching {
            json.decodeFromString(PresetBundle.serializer(), text)
        }.getOrElse { e ->
            return ParseResult.Failed(
                FailureReason.MALFORMED,
                "${e::class.simpleName}: ${e.message?.take(200)}"
            )
        }

        // ---- 3. 是否本应用的文件 ----
        if (bundle.app != PresetBundle.APP_TAG) {
            return ParseResult.Failed(
                FailureReason.NOT_OURS,
                "app=\"${bundle.app.take(40)}\""
            )
        }

        // ---- 4. 版本兼容 ----
        if (bundle.formatVersion > PresetBundle.CURRENT_FORMAT_VERSION) {
            return ParseResult.Failed(
                FailureReason.VERSION_TOO_NEW,
                "文件版本 ${bundle.formatVersion}，本应用支持到 ${PresetBundle.CURRENT_FORMAT_VERSION}"
            )
        }
        // 低于当前版本：正常读取。字段都有默认值，旧文件缺的字段会自动补上。
        // （这也是为什么「新增字段不算破坏性变更」—— 见 PresetBundle 的注释）

        // ---- 5. 内容检查 ----
        if (bundle.presets.isEmpty()) {
            return ParseResult.Failed(FailureReason.NO_PRESETS)
        }
        if (bundle.presets.size > MAX_PRESETS) {
            return ParseResult.Failed(
                FailureReason.MALFORMED,
                "预设数量 ${bundle.presets.size} 超过上限 $MAX_PRESETS"
            )
        }

        // ---- 6. 净化 ----
        return sanitize(bundle.presets, idSeed)
    }

    /**
     * 净化导入的预设。
     *
     * ## 为什么必须做这一步
     *
     * 渲染器（[WatermarkRenderer]）里有很多 `coerceIn`，看起来「渲染层
     * 已经挡住越界值了」。但有一个**它挡不住的**：
     *
     * ### NaN / Infinity
     *
     * `Float.NaN.coerceIn(0f, 1f)` 返回的还是 **NaN** ——
     * 因为 `coerceIn` 内部是两次比较，而 NaN 与任何值比较都是 false，
     * 两次判断都不成立时就返回原值。
     *
     * 于是 NaN 会一路传到 `Canvas` 的绘制参数里，行为未定义
     * （典型表现是水印整个消失，或者某些 ROM 上直接抛异常）。
     *
     * JSON 里 **可以** 表达 NaN 吗？标准 JSON 不能，但
     * kotlinx.serialization 在宽松配置下可接受 `NaN` / `Infinity`
     * 字面量，而且文件本来就是用户可以手工编辑的 ——
     * 我们不该假设输入是"善意"的。
     *
     * ### 其它
     *
     * - **id 重建**：若沿用文件里的 id，两次导入同一个文件会产生
     *   两条 id 相同的记录。而 `deletePreset(id)` 是按 id 过滤的，
     *   删一条会把两条都删掉 —— 用户会莫名其妙丢数据。
     * - **名称**：空名、超长名、含换行/控制字符都会破坏列表布局。
     * - **数值**：`fontSize = 1e9` 会让渲染时申请巨大位图而 OOM。
     *
     * @return 净化后的结果；[ParseResult.Ok.cleaned] 记录被修正过的条数
     */
    private fun sanitize(presets: List<WatermarkPreset>, idSeed: Long): ParseResult.Ok {
        var cleanedCount = 0

        val result = presets.mapIndexed { index, original ->
            val name = sanitizeName(original.name)
            val createdAt = if (original.createdAt > 0L) original.createdAt else idSeed
            // id 重建：seed + 序号，保证同一批内唯一；跨批次由 seed（毫秒）区分
            val newId = "${idSeed}_$index"

            val fixedText = normalizeTextStyle(original.textStyle).copy(
                // 自定义字体跨设备无效 → 清空声明并回落到内置字体，
                // 否则 fontFamily=CUSTOM 会指向不存在的字体，文字渲染不出来
                customFontName = null,
                fontFamily = sanitizeFontFamily(original.textStyle.fontFamily)
            )
            val fixedImage = normalizeImageStyle(original.imageStyle).copy(
                logoUri = null // content:// 授权只在本机有效，见 normalizeForExport
            )

            val rebuilt = original.copy(
                id = newId,
                name = name,
                createdAt = createdAt,
                textStyle = fixedText,
                imageStyle = fixedImage
            )

            // 记录"这一条是否被改动过"。
            // 注意比较时要用 rebuilt 与 original —— 由于 id 必然变化，
            // 只比较**用户可见的**字段，否则 cleaned 永远是全量。
            val changed = name != original.name ||
                fixedText != original.textStyle ||
                fixedImage != original.imageStyle
            if (changed) cleanedCount++

            rebuilt
        }

        return ParseResult.Ok(result, cleanedCount)
    }

    /** 清理名称：去首尾空白、去掉控制字符、补空名、截断超长。 */
    private fun sanitizeName(raw: String): String {
        // 去掉换行/制表等控制字符 —— 它们会破坏列表项布局
        val stripped = raw.map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()

        return when {
            stripped.isEmpty() -> FALLBACK_NAME
            stripped.length > MAX_NAME_LENGTH -> stripped.take(MAX_NAME_LENGTH)
            else -> stripped
        }
    }

    /**
     * 文字水印样式的**数值夹紧**（导入/导出共用）。
     *
     * 只处理数值区间，**不动** `customFontName` / `fontFamily` ——
     * 那两者的处理在导入与导出路径上不同：
     * - 导出：只需清空 `customFontName`（字体本来就在本机，声明保持不变）
     * - 导入：除清空外，还要把 `fontFamily = CUSTOM` 回落到默认
     *
     * 区间与 [WatermarkRenderer] 里的 `coerceIn` 保持一致 ——
     * 这样"导入后保存"的数值不会在渲染时被再次改动，
     * 避免出现「编辑页显示 0.5，画出来却是另一个值」这种不一致。
     */
    private fun normalizeTextStyle(style: TextWatermarkStyle): TextWatermarkStyle =
        style.copy(
            text = style.text.take(MAX_TEXT_LENGTH),
            fontSize = safeFloat(style.fontSize, TextWatermarkStyle.Default.fontSize, 1f, MAX_FONT_SIZE),
            strokeWidth = safeFloat(style.strokeWidth, 2f, 0f, 100f),
            shadowRadius = safeFloat(style.shadowRadius, 4f, 0f, 100f),
            tileCount = style.tileCount.coerceIn(1, 20),
            rotationDegrees = safeFloat(style.rotationDegrees, 0f, -90f, 90f),
            alpha = safeFloat(style.alpha, 0.7f, WatermarkRenderer.MIN_ALPHA, 1f),
            margin = safeFloat(style.margin, 48f, 0f, MAX_MARGIN),
            customX = safeFloat(style.customX, 0.5f, 0f, 1f),
            customY = safeFloat(style.customY, 0.5f, 0f, 1f)
        )

    /** 图片水印样式的**数值夹紧**（导入/导出共用）。 */
    private fun normalizeImageStyle(style: ImageWatermarkStyle): ImageWatermarkStyle =
        style.copy(
            widthPercent = safeFloat(style.widthPercent, 0.2f, 0.05f, 0.8f),
            tileCount = style.tileCount.coerceIn(1, 20),
            rotationDegrees = safeFloat(style.rotationDegrees, 0f, -90f, 90f),
            alpha = safeFloat(style.alpha, 0.7f, WatermarkRenderer.MIN_ALPHA, 1f),
            margin = safeFloat(style.margin, 48f, 0f, MAX_MARGIN),
            customX = safeFloat(style.customX, 0.5f, 0f, 1f),
            customY = safeFloat(style.customY, 0.5f, 0f, 1f)
        )

    /**
     * 字体族兜底：自定义字体一律回落到内置默认字体。
     *
     * 自定义字体存在应用私有目录里，跨设备无效；导入时
     * [TextWatermarkStyle.customFontName] 一定被清空，
     * 因此 `fontFamily == CUSTOM` 必然指向不存在的字体 ——
     * 留着它会导致**水印文字渲染不出来**（不是"用默认字体画"，
     * 而是取不到 Typeface 时直接不出字）。
     */
    private fun sanitizeFontFamily(family: FontFamilyOption): FontFamilyOption =
        if (family == FontFamilyOption.CUSTOM) FontFamilyOption.DEFAULT else family

    /**
     * 浮点数的安全处理：非有限值（NaN / ±Infinity）回落到默认值，
     * 有限值则夹到区间内。
     *
     * **不能直接用 `coerceIn`** —— 见 [sanitize] 的注释说明。
     */
    private fun safeFloat(value: Float, fallback: Float, min: Float, max: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else fallback

    // ==================================================================
    // 文件名
    // ==================================================================

    /**
     * 生成默认导出文件名（供系统文件选择器预填）。
     *
     * @param presetName 单个预设的名称；传 `null` 表示导出全部
     * @param now        当前时刻（毫秒）
     *
     * ## 为什么单个导出也带时间戳
     *
     * 若只叫 `WatermarkBox-我的水印.json`，第二次导出同名文件时
     * 系统会问「是否覆盖」—— 用户很可能顺手点确定，
     * 于是**上一次的导出被静默替换**。带上时间戳就没这个问题。
     *
     * ## 为什么名称要过滤
     *
     * 预设名是用户随便取的，可能含 `/`、`:` 等字符 ——
     * 它们在不同文件系统上有不同含义，可能被拒绝或产生意料之外的路径。
     * 统一替换成下划线最省事。
     */
    fun suggestFileName(presetName: String?, now: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return if (presetName == null) {
            "$FILE_NAME_PREFIX-presets-$stamp.json"
        } else {
            "$FILE_NAME_PREFIX-${sanitizeForFileName(presetName)}-$stamp.json"
        }
    }

    /** 文件名中不允许出现的字符（跨平台取并集）。 */
    private const val ILLEGAL_IN_FILE_NAME = "/\\:*?\"<>|"

    /** 文件名前缀。 */
    private const val FILE_NAME_PREFIX = "WatermarkBox"

    /** 文件名中「预设名」部分的最大长度。 */
    private const val MAX_FILE_NAME_PART = 40

    /**
     * 把预设名变成安全的文件名片段。
     *
     * 除了替换非法字符，还要**去掉首尾的点和空格** ——
     * 前者会生成隐藏文件（以 `.` 开头），
     * 后者在某些系统上会被静默截断，导致名字与预期不符。
     */
    private fun sanitizeForFileName(raw: String): String {
        val cleaned = raw
            .map { if (it in ILLEGAL_IN_FILE_NAME || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .take(MAX_FILE_NAME_PART)

        return cleaned.ifEmpty { FALLBACK_NAME }
    }
}