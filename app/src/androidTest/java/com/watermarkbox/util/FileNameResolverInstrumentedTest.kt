package com.watermarkbox.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watermarkbox.data.model.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * [FileNameResolver] 在**设备正则引擎**（Android 的 ICU `Pattern`）上的行为。
 *
 * ## 为什么单测不够，非要上真机
 *
 * `java.util.regex` 在桌面 OpenJDK 与 Android 上**不是同一套实现**：
 * 2026-10-26 的崩溃就是活例子 —— `collapseSeparators()` 里的
 * `\[\s*]`、`\{\s*}`（右侧裸括号）在桌面上编译通过，在设备上
 * 直接抛 `PatternSyntaxException`，于是「设置 → 导出」一打开就闪退
 * （实时预览会用 `index = null` 调到这里）。
 *
 * 桌面单测跑绿、设备上崩 —— 只有设备侧用例能兜住这类差异。
 * 以后往这里加正则，**必须**补一条走过该分支的用例。
 */
@RunWith(AndroidJUnit4::class)
class FileNameResolverInstrumentedTest {

    private val fixedDate = Date(1767225600000L) // 2026-01-01 00:00:00 UTC

    /** 单张导出（index = null）必须走 `collapseSeparators()` 且不抛异常。 */
    @Test
    fun singleExport_removesIndexPlaceholderAndEmptyBrackets() {
        val cases = mapOf(
            "照片({index})" to "照片.jpg",
            "照片[{index}]" to "照片.jpg",
            "照片{{index}}" to "照片.jpg",
            "照片（{index}）" to "照片.jpg",
            // 括号里带空格也算空括号
            "照片( {index} )" to "照片.jpg",
            "照片_{index}_压缩" to "照片_压缩.jpg",
            "{index}_照片" to "照片.jpg"
        )
        for ((template, expected) in cases) {
            val name = FileNameResolver.build(
                template = template,
                sourceName = "IMG_2041.jpg",
                format = ExportFormat.JPEG,
                index = null,
                now = fixedDate
            )
            assertEquals("模板「$template」", expected, name)
        }
    }

    /** 批量导出（index 非 null）时占位符正常展开，且不触发空括号收敛。 */
    @Test
    fun batchExport_formatsIndexWithPadding() {
        val name = FileNameResolver.build(
            template = "照片_{index:04}",
            sourceName = "IMG_2041.jpg",
            format = ExportFormat.JPEG,
            index = 7,
            now = fixedDate
        )
        assertEquals("照片_0007.jpg", name)
    }
}
