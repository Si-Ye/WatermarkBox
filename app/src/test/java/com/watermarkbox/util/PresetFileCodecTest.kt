package com.watermarkbox.util

import com.watermarkbox.data.model.FontFamilyOption
import com.watermarkbox.data.model.GravityPosition
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.LayoutMode
import com.watermarkbox.data.model.PresetBundle
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.watermark.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设文件编解码测试。
 *
 * ## 为什么值得写这么多
 *
 * 这个功能的输入**来自文件** —— 可能来自另一台设备、可能是手工改过的、
 * 可能只是传坏了。也就是说：**每一条防御都可能真的被触发**。
 *
 * 而且失败方式很隐蔽：一个 NaN 不会报错，只会让水印莫名消失；
 * 一个重复 id 不会报错，只在用户删预设时**连带删掉另一条**。
 * 这些都不是"跑一遍看看"能发现的，必须逐条构造出来。
 */
class PresetFileCodecTest {

    private companion object {
        /** 固定的 id 基准，让测试结果可预期。 */
        const val SEED = 1_700_000_000_000L

        fun textPreset(
            id: String = "p1",
            name: String = "我的水印",
            createdAt: Long = 1_600_000_000_000L,
            textStyle: TextWatermarkStyle = TextWatermarkStyle.Default
        ) = WatermarkPreset(
            id = id,
            name = name,
            createdAt = createdAt,
            textStyle = textStyle,
            imageStyle = ImageWatermarkStyle.Default,
            type = WatermarkType.TEXT
        )

        fun imagePreset(
            id: String = "p2",
            name: String = "我的 Logo",
            logoUri: String? = "content://media/external/images/media/123"
        ) = WatermarkPreset(
            id = id,
            name = name,
            createdAt = 1_600_000_000_001L,
            textStyle = TextWatermarkStyle.Default,
            imageStyle = ImageWatermarkStyle.Default.copy(logoUri = logoUri),
            type = WatermarkType.IMAGE
        )

        fun decodeOk(text: String): PresetFileCodec.ParseResult.Ok {
            val result = PresetFileCodec.decode(text, SEED)
            assertTrue("期望解析成功，实际是 $result", result is PresetFileCodec.ParseResult.Ok)
            return result as PresetFileCodec.ParseResult.Ok
        }

        fun decodeFailure(text: String): PresetFileCodec.FailureReason {
            val result = PresetFileCodec.decode(text, SEED)
            assertTrue("期望解析失败，实际是 $result", result is PresetFileCodec.ParseResult.Failed)
            return (result as PresetFileCodec.ParseResult.Failed).reason
        }
    }

    // ==================================================================
    // 一、往返：导出再导入，内容不变
    // ==================================================================

    @Test
    fun `单条文字预设往返后字段一致`() {
        val original = textPreset(
            name = "博客水印",
            textStyle = TextWatermarkStyle.Default.copy(
                text = "© 2026 肆叶",
                fontSize = 42f,
                bold = true,
                alpha = 0.55f,
                layoutMode = LayoutMode.TILED,
                tileCount = 7,
                rotationDegrees = -30f,
                gravity = GravityPosition.TOP_LEFT,
                margin = 60f
            )
        )

        val restored = decodeOk(PresetFileCodec.encode(listOf(original), SEED)).presets.single()

        assertEquals(original.name, restored.name)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.textStyle.text, restored.textStyle.text)
        assertEquals(original.textStyle.fontSize, restored.textStyle.fontSize, 1e-6f)
        assertTrue(restored.textStyle.bold)
        assertEquals(original.textStyle.alpha, restored.textStyle.alpha, 1e-6f)
        assertEquals(LayoutMode.TILED, restored.textStyle.layoutMode)
        assertEquals(7, restored.textStyle.tileCount)
        assertEquals(-30f, restored.textStyle.rotationDegrees, 1e-6f)
        assertEquals(GravityPosition.TOP_LEFT, restored.textStyle.gravity)
        assertEquals(60f, restored.textStyle.margin, 1e-6f)
    }

    @Test
    fun `自定义位置坐标往返后保留`() {
        val original = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(
                gravity = GravityPosition.CUSTOM,
                customX = 0.234f,
                customY = 0.876f
            )
        )

        val restored = decodeOk(PresetFileCodec.encode(listOf(original), SEED)).presets.single()

        assertEquals(GravityPosition.CUSTOM, restored.textStyle.gravity)
        assertEquals(0.234f, restored.textStyle.customX, 1e-5f)
        assertEquals(0.876f, restored.textStyle.customY, 1e-5f)
    }

    @Test
    fun `多条预设往返后顺序保持不变`() {
        val list = listOf(
            textPreset(id = "a", name = "第一条"),
            textPreset(id = "b", name = "第二条"),
            textPreset(id = "c", name = "第三条")
        )

        val restored = decodeOk(PresetFileCodec.encode(list, SEED)).presets

        assertEquals(listOf("第一条", "第二条", "第三条"), restored.map { it.name })
        assertEquals(3, restored.size)
    }

    @Test
    fun `导出内容带格式版本与应用标识`() {
        val text = PresetFileCodec.encode(listOf(textPreset()), SEED)

        assertTrue("缺少 formatVersion", text.contains("\"formatVersion\""))
        assertTrue("缺少 app 标识", text.contains("\"${PresetBundle.APP_TAG}\""))
        assertTrue("缺少 exportedAt", text.contains("\"exportedAt\""))
    }

    // ==================================================================
    // 二、不可移植字段必须被剥离
    // ==================================================================

    @Test
    fun `导出时清空 logoUri`() {
        val preset = imagePreset(logoUri = "content://media/external/images/media/999")

        val restored = decodeOk(PresetFileCodec.encode(listOf(preset), SEED)).presets.single()

        assertNull("logoUri 跨设备无效，必须清空", restored.imageStyle.logoUri)
    }

    @Test
    fun `导出时清空自定义字体名`() {
        val preset = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(
                fontFamily = FontFamilyOption.CUSTOM,
                customFontName = "my-font.ttf"
            )
        )

        val restored = decodeOk(PresetFileCodec.encode(listOf(preset), SEED)).presets.single()

        assertNull(restored.textStyle.customFontName)
    }

    @Test
    fun `图片预设的参数在清空 logoUri 后仍然保留`() {
        val preset = imagePreset().let {
            it.copy(
                imageStyle = it.imageStyle.copy(
                    widthPercent = 0.35f,
                    alpha = 0.9f,
                    layoutMode = LayoutMode.DIAGONAL,
                    gravity = GravityPosition.CENTER
                )
            )
        }

        val restored = decodeOk(PresetFileCodec.encode(listOf(preset), SEED)).presets.single()

        assertNull(restored.imageStyle.logoUri)
        // 参数都还在 —— 用户重新选一张图就能直接用
        assertEquals(0.35f, restored.imageStyle.widthPercent, 1e-6f)
        assertEquals(0.9f, restored.imageStyle.alpha, 1e-6f)
        assertEquals(LayoutMode.DIAGONAL, restored.imageStyle.layoutMode)
        assertEquals(GravityPosition.CENTER, restored.imageStyle.gravity)
    }

    // ==================================================================
    // 三、拒绝非法输入（每种失败原因）
    // ==================================================================

    @Test
    fun `空文件报 EMPTY`() {
        assertEquals(PresetFileCodec.FailureReason.EMPTY, decodeFailure(""))
    }

    @Test
    fun `只有空白字符报 EMPTY`() {
        assertEquals(PresetFileCodec.FailureReason.EMPTY, decodeFailure("   \n\t  "))
    }

    @Test
    fun `非 JSON 文本报 MALFORMED`() {
        assertEquals(PresetFileCodec.FailureReason.MALFORMED, decodeFailure("这不是 JSON"))
    }

    @Test
    fun `结构不符的 JSON 报 MALFORMED`() {
        // 合法 JSON，但不是我们期望的对象结构
        assertEquals(PresetFileCodec.FailureReason.MALFORMED, decodeFailure("[1, 2, 3]"))
    }

    @Test
    fun `字段类型错误的 JSON 报 MALFORMED`() {
        assertEquals(
            PresetFileCodec.FailureReason.MALFORMED,
            decodeFailure("""{"app":"WatermarkBox","presets":"不是数组"}""")
        )
    }

    @Test
    fun `其它应用导出的文件报 NOT_OURS`() {
        assertEquals(
            PresetFileCodec.FailureReason.NOT_OURS,
            decodeFailure("""{"formatVersion":1,"app":"OtherApp","presets":[]}""")
        )
    }

    @Test
    fun `缺少 app 字段报 NOT_OURS`() {
        // 默认值是空字符串 → 与 APP_TAG 不符 → NOT_OURS
        // 这正是「字段带默认值」的好处：不抛异常，而是给出明确原因
        assertEquals(
            PresetFileCodec.FailureReason.NOT_OURS,
            decodeFailure("""{"formatVersion":1,"presets":[]}""")
        )
    }

    @Test
    fun `版本号高于当前支持时报 VERSION_TOO_NEW`() {
        val future = PresetBundle.CURRENT_FORMAT_VERSION + 1
        assertEquals(
            PresetFileCodec.FailureReason.VERSION_TOO_NEW,
            decodeFailure(
                """{"formatVersion":$future,"app":"${PresetBundle.APP_TAG}","presets":[]}"""
            )
        )
    }

    @Test
    fun `预设列表为空报 NO_PRESETS`() {
        assertEquals(
            PresetFileCodec.FailureReason.NO_PRESETS,
            decodeFailure("""{"formatVersion":1,"app":"${PresetBundle.APP_TAG}","presets":[]}""")
        )
    }

    @Test
    fun `超大文件报 TOO_LARGE`() {
        // 构造一个超长但结构合法的字符串
        val huge = " " + "x".repeat(PresetFileCodec.MAX_FILE_CHARS + 10)
        assertEquals(PresetFileCodec.FailureReason.TOO_LARGE, decodeFailure(huge))
    }

    @Test
    fun `预设数量超过上限报 MALFORMED`() {
        // 直接拼 JSON，避免真的构造 500+ 个对象
        val one = """
            {"id":"x","name":"n","createdAt":1,
             "textStyle":${jsonOfTextStyle()},
             "imageStyle":${jsonOfImageStyle()},
             "type":"TEXT"}
        """.trimIndent()
        val many = (0..PresetFileCodec.MAX_PRESETS).joinToString(",") { one }
        val text = """{"formatVersion":1,"app":"${PresetBundle.APP_TAG}","presets":[$many]}"""

        assertEquals(PresetFileCodec.FailureReason.MALFORMED, decodeFailure(text))
    }

    @Test
    fun `版本号低于当前支持时正常读取`() {
        // 未来若升级到 formatVersion=2，仍应接受 v1 的老文件
        val text = """{"formatVersion":0,"app":"${PresetBundle.APP_TAG}","presets":[
            {"id":"x","name":"老预设","createdAt":1,
             "textStyle":${jsonOfTextStyle()},
             "imageStyle":${jsonOfImageStyle()},
             "type":"TEXT"}
        ]}"""
        val ok = decodeOk(text)
        assertEquals("老预设", ok.presets.single().name)
    }

    // ==================================================================
    // 四、内容净化
    // ==================================================================

    @Test
    fun `超出区间的透明度被夹到合法范围`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(alpha = 5.0f)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(1f, ok.presets.single().textStyle.alpha, 1e-6f)
    }

    @Test
    fun `低于下限的透明度被夹到渲染器下限`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(alpha = 0.0001f)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        // 与渲染器的下限保持一致，避免「显示一个值、画另一个值」
        assertEquals(
            WatermarkRenderer.MIN_ALPHA,
            ok.presets.single().textStyle.alpha,
            1e-6f
        )
    }

    @Test
    fun `超大的字号被夹到上限`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(fontSize = 999999f)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        // 不夹住的后果是渲染时申请巨大位图 → OOM
        assertTrue(
            "字号应被夹到合理范围，实际 ${ok.presets.single().textStyle.fontSize}",
            ok.presets.single().textStyle.fontSize <= 2000f
        )
    }

    @Test
    fun `平铺数量被夹到 1 至 20`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(tileCount = 9999)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(20, ok.presets.single().textStyle.tileCount)
    }

    @Test
    fun `logo 宽度百分比被夹到合法范围`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle(widthPercent = 50f)},
                "type":"IMAGE"}"""
        )

        val ok = decodeOk(text)

        assertEquals(0.8f, ok.presets.single().imageStyle.widthPercent, 1e-6f)
    }

    @Test
    fun `自定义坐标被夹到零至一`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(customX = -5f, customY = 12f)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(0f, ok.presets.single().textStyle.customX, 1e-6f)
        assertEquals(1f, ok.presets.single().textStyle.customY, 1e-6f)
    }

    @Test
    fun `空名称被替换为兜底名称`() {
        val text = bundleWith(
            """{"id":"x","name":"","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(PresetFileCodec.FALLBACK_NAME, ok.presets.single().name)
    }

    @Test
    fun `只有空白的名称被替换为兜底名称`() {
        val text = bundleWith(
            """{"id":"x","name":"   ","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        assertEquals(PresetFileCodec.FALLBACK_NAME, decodeOk(text).presets.single().name)
    }

    @Test
    fun `超长名称被截断`() {
        val longName = "名".repeat(PresetFileCodec.MAX_NAME_LENGTH + 50)
        val text = bundleWith(
            """{"id":"x","name":"$longName","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(PresetFileCodec.MAX_NAME_LENGTH, ok.presets.single().name.length)
    }

    @Test
    fun `名称中的控制字符被替换`() {
        // 换行会破坏列表项布局。
        //
        // 注意转义层级：这是 Kotlin 的 raw string（三引号），
        // 反斜杠**不是**转义字符 —— 写 `\\n` 会得到「反斜杠 + n」两个
        // 可见字符，而那并不是控制字符，测试就测不到东西了。
        // 要的就是 JSON 层面的转义序列 `\n`，所以这里写单个反斜杠。
        val text = bundleWith(
            """{"id":"x","name":"前\n后\t尾","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val name = decodeOk(text).presets.single().name

        assertTrue(
            "名称不应含控制字符，实际 \"$name\"",
            name.none { it.isISOControl() }
        )
        assertEquals("前 后 尾", name)
    }

    @Test
    fun `时间戳为零时用当前时间兜底`() {
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":0,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(SEED, ok.presets.single().createdAt)
    }

    @Test
    fun `自定义字体声明被回落到默认字体`() {
        // 导入时 customFontName 一定被清空，此时 CUSTOM 指向不存在的字体，
        // 留着会导致文字渲染不出来
        val text = bundleWith(
            """{"id":"x","name":"n","createdAt":1,
                "textStyle":${jsonOfTextStyle(fontFamily = "CUSTOM", customFontName = "gone.ttf")},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val style = decodeOk(text).presets.single().textStyle

        assertEquals(FontFamilyOption.DEFAULT, style.fontFamily)
        assertNull(style.customFontName)
    }

    // ==================================================================
    // 五、id 重建（防「删一条连带删两条」）
    // ==================================================================

    @Test
    fun `重复 id 的输入会被重建为不同 id`() {
        // 同一个文件导入两次，或手工复制粘贴 —— 都会产生重复 id。
        // 若不重建，deletePreset(id) 会把两条一起删掉。
        val text = bundleWith(
            """{"id":"SAME","name":"甲","createdAt":1,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"},
               {"id":"SAME","name":"乙","createdAt":2,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)
        val ids = ok.presets.map { it.id }

        assertEquals(2, ids.size)
        assertNotEquals("两条预设的 id 必须不同", ids[0], ids[1])
    }

    @Test
    fun `重建后的 id 在同一批内唯一`() {
        var text = """{"formatVersion":1,"app":"${PresetBundle.APP_TAG}","presets":["""
        text += (1..20).joinToString(",") { i ->
            """{"id":"same","name":"第 $i 条","createdAt":$i,
                "textStyle":${jsonOfTextStyle()},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        }
        text += "]}"

        val ids = decodeOk(text).presets.map { it.id }

        assertEquals(20, ids.size)
        assertEquals("id 必须两两不同", ids.size, ids.toSet().size)
    }

    // ==================================================================
    // 六、非法浮点值（渲染器挡不住的那类）
    // ==================================================================

    @Test
    fun `含 NaN 的预设导出时不会抛异常`() {
        // Json 未开 allowSpecialFloatingPointValues，序列化 NaN 会抛异常。
        // 导出前统一夹紧就是为了避免「整次导出莫名失败」。
        val preset = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(alpha = Float.NaN)
        )

        // 不抛异常即通过
        val text = PresetFileCodec.encode(listOf(preset), SEED)

        val restored = decodeOk(text).presets.single()
        assertEquals("NaN 应被替换为默认透明度", 0.7f, restored.textStyle.alpha, 1e-6f)
    }

    @Test
    fun `含 Infinity 的预设导出时不会抛异常`() {
        val preset = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(fontSize = Float.POSITIVE_INFINITY)
        )

        val text = PresetFileCodec.encode(listOf(preset), SEED)

        val restored = decodeOk(text).presets.single()
        assertEquals(
            TextWatermarkStyle.Default.fontSize,
            restored.textStyle.fontSize,
            1e-6f
        )
    }

    @Test
    fun `负无穷的边距被替换为默认值`() {
        val preset = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(margin = Float.NEGATIVE_INFINITY)
        )

        val text = PresetFileCodec.encode(listOf(preset), SEED)

        val restored = decodeOk(text).presets.single()
        assertEquals(48f, restored.textStyle.margin, 1e-6f)
    }

    @Test
    fun `导出结果始终可以被再次解析`() {
        // 这条是"导出必须产出合法文件"的总括性断言
        val weird = textPreset(
            textStyle = TextWatermarkStyle.Default.copy(
                alpha = Float.NaN,
                fontSize = Float.POSITIVE_INFINITY,
                margin = Float.NEGATIVE_INFINITY,
                customX = Float.NaN
            )
        )

        val text = PresetFileCodec.encode(listOf(weird), SEED)

        val ok = decodeOk(text)
        assertEquals(1, ok.presets.size)
    }

    // ==================================================================
    // 七、向前兼容
    // ==================================================================

    @Test
    fun `未知字段被忽略而不是报错`() {
        // 模拟「更新版本的应用导出的文件，多了一个我不认识的字段」
        val text = """{"formatVersion":1,"app":"${PresetBundle.APP_TAG}",
            "futureField":"some value",
            "presets":[
            {"id":"x","name":"带未来字段","createdAt":1,
             "unknownStyleField":123,
             "textStyle":${jsonOfTextStyle()},
             "imageStyle":${jsonOfImageStyle()},
             "type":"TEXT"}
        ]}"""

        val ok = decodeOk(text)

        assertEquals("带未来字段", ok.presets.single().name)
    }

    @Test
    fun `缺失的可选字段用默认值填充`() {
        // 只给必需字段，样式字段全省略
        val text = """{"formatVersion":1,"app":"${PresetBundle.APP_TAG}","presets":[
            {"id":"x","name":"极简","createdAt":1,"textStyle":{},"imageStyle":{},"type":"TEXT"}
        ]}"""

        val ok = decodeOk(text)
        val preset = ok.presets.single()

        assertEquals("极简", preset.name)
        assertEquals(TextWatermarkStyle.Default.alpha, preset.textStyle.alpha, 1e-6f)
        assertEquals(TextWatermarkStyle.Default.fontSize, preset.textStyle.fontSize, 1e-6f)
    }

    // ==================================================================
    // 八、cleaned 计数
    // ==================================================================

    @Test
    fun `无需修正时 cleaned 为零`() {
        val text = PresetFileCodec.encode(listOf(textPreset(name = "干净的预设")), SEED)

        val ok = decodeOk(text)

        assertEquals(0, ok.cleaned)
    }

    @Test
    fun `修正过的条目会被计入 cleaned`() {
        val text = bundleWith(
            """{"id":"x","name":"","createdAt":1,
                "textStyle":${jsonOfTextStyle(alpha = 9f)},
                "imageStyle":${jsonOfImageStyle()},
                "type":"TEXT"}"""
        )

        val ok = decodeOk(text)

        assertEquals(1, ok.cleaned)
    }

    // ==================================================================
    // 九、导出文件名
    // ==================================================================

    @Test
    fun `导出全部的文件名带 presets 标记`() {
        val name = PresetFileCodec.suggestFileName(presetName = null, now = SEED)

        assertTrue("实际 \"$name\"", name.startsWith("WatermarkBox-presets-"))
        assertTrue("实际 \"$name\"", name.endsWith(".json"))
    }

    @Test
    fun `导出单个的文件名含预设名`() {
        val name = PresetFileCodec.suggestFileName(presetName = "我的博客水印", now = SEED)

        assertTrue("实际 \"$name\"", name.contains("我的博客水印"))
        assertTrue("实际 \"$name\"", name.endsWith(".json"))
    }

    @Test
    fun `文件名中的非法字符被替换`() {
        val name = PresetFileCodec.suggestFileName(presetName = "a/b:c*d?e\"f<g>h|i", now = SEED)

        // 只检查"预设名"那一段，前后缀本身含 '-' 是合法的
        val middle = name.removePrefix("WatermarkBox-").substringBeforeLast("-")
        assertTrue("不应含斜杠，实际 \"$middle\"", '/' !in middle)
        assertTrue("不应含冒号，实际 \"$middle\"", ':' !in middle)
        assertTrue("不应含星号，实际 \"$middle\"", '*' !in middle)
        assertTrue("不应含问号，实际 \"$middle\"", '?' !in middle)
        assertTrue("不应含引号，实际 \"$middle\"", '"' !in middle)
        assertTrue("不应含竖线，实际 \"$middle\"", '|' !in middle)
    }

    @Test
    fun `全为非法的预设名回落到兜底名称`() {
        val name = PresetFileCodec.suggestFileName(presetName = "///", now = SEED)

        // '///' 全被替换成下划线，不为空 → 保留；这里验证的是"不会产生
        // 空的名字片段"，即不会被替换成空串导致文件名变成 "WatermarkBox--xxx.json"
        assertTrue("实际 \"$name\"", name.contains(PresetFileCodec.FALLBACK_NAME) || name.contains("___"))
    }

    @Test
    fun `名称为纯空白时回落到兜底名称`() {
        val name = PresetFileCodec.suggestFileName(presetName = "   ", now = SEED)

        assertTrue("实际 \"$name\"", name.contains(PresetFileCodec.FALLBACK_NAME))
    }

    @Test
    fun `前导点被去掉避免生成隐藏文件`() {
        val name = PresetFileCodec.suggestFileName(presetName = "..隐藏", now = SEED)

        // 预设名那段不应以 '.' 开头
        val middle = name.removePrefix("WatermarkBox-")
        assertTrue("预设名片段不应以点开头，实际 \"$middle\"", !middle.startsWith("."))
    }

    @Test
    fun `超长的预设名被截断`() {
        val long = "超".repeat(200)
        val name = PresetFileCodec.suggestFileName(presetName = long, now = SEED)

        // 文件名总长应有界（前缀 + 40 + 时间戳 + .json）
        assertTrue("文件名过长：${name.length}", name.length < 80)
    }

    // ==================================================================
    // 测试辅助：手工拼 JSON 片段
    // ==================================================================

    /** 把若干预设片段包成完整的文件内容。 */
    private fun bundleWith(vararg presetJson: String): String =
        """{"formatVersion":${PresetBundle.CURRENT_FORMAT_VERSION},
            "app":"${PresetBundle.APP_TAG}",
            "exportedAt":$SEED,
            "presets":[${presetJson.joinToString(",")}]}"""

    /**
     * 文字样式 JSON 片段。
     *
     * 只列出需要覆写的字段，其余靠反序列化默认值补齐 ——
     * 这样测试关注点更集中，也不会因为模型新增字段而全部失效。
     */
    private fun jsonOfTextStyle(
        alpha: Float? = null,
        fontSize: Float? = null,
        tileCount: Int? = null,
        customX: Float? = null,
        customY: Float? = null,
        fontFamily: String? = null,
        customFontName: String? = null
    ): String {
        val parts = mutableListOf<String>()
        alpha?.let { parts += "\"alpha\":$it" }
        fontSize?.let { parts += "\"fontSize\":$it" }
        tileCount?.let { parts += "\"tileCount\":$it" }
        customX?.let { parts += "\"customX\":$it" }
        customY?.let { parts += "\"customY\":$it" }
        fontFamily?.let { parts += "\"fontFamily\":\"$it\"" }
        customFontName?.let { parts += "\"customFontName\":\"$it\"" }
        return "{${parts.joinToString(",")}}"
    }

    /** 图片样式 JSON 片段。 */
    private fun jsonOfImageStyle(widthPercent: Float? = null): String {
        val parts = mutableListOf<String>()
        widthPercent?.let { parts += "\"widthPercent\":$it" }
        return "{${parts.joinToString(",")}}"
    }
}