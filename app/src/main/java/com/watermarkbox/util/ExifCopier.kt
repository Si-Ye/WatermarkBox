package com.watermarkbox.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.io.OutputStream

/**
 * EXIF 元数据拷贝工具。
 *
 * ## 为什么需要它
 *
 * `Bitmap.compress()` **只写像素**，不会写任何 EXIF —— 拍完照的
 * 拍摄时间、机型、GPS、光圈等全部丢失。用户开启「保留原图 EXIF」
 * 后，导出图必须把源图的这些信息搬过去。
 *
 * ## 写法选择
 *
 * 采用「读源 → 写目标」而非「把源文件整体拷贝再覆盖像素」：
 * - 前者只搬运我们认可的标签，能主动剔除不该传播的字段（如缩略图）
 * - 后者会把源图的缩略图一起带过去，导致缩略图与新像素不一致
 *
 * ## 注意
 *
 * `ExifInterface` 的 `saveAttributes()` 对 JPEG 是原地改写；
 * 对 PNG 它会把 EXIF 写进 `eXIf` chunk（AndroidX 1.3+ 支持）。
 * 若目标格式不支持（极旧实现），静默失败不影响导出主流程。
 */
object ExifCopier {

    /**
     * 需要复制的 EXIF 标签白名单。
     *
     * 故意**排除**：
     * - `TAG_ORIENTATION`：像素已被我们旋正，再带方向会导致二次旋转
     * - `TAG_THUMBNAIL*`：缩略图与新像素不一致，带过去是错误信息
     * - `TAG_IMAGE_LENGTH/WIDTH`：由新图决定
     * - `TAG_USER_COMMENT`：这是隐藏水印要独占的字段，不能被原图覆盖
     */
    private val COPIABLE_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_MAKER_NOTE
    )

    /**
     * 把 [sourceUri] 的 EXIF 复制到已经写好的 [targetUri]。
     *
     * @return 成功复制的标签数；0 表示源无 EXIF 或全部失败
     */
    fun copyExif(context: Context, sourceUri: Uri, targetUri: Uri): Int {
        return try {
            val sourceExif = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                readExif(input)
            } ?: return 0

            var copied = 0
            context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                val targetExif = ExifInterface(pfd.fileDescriptor)
                COPIABLE_TAGS.forEach { tag ->
                    sourceExif.getAttribute(tag)?.let { value ->
                        targetExif.setAttribute(tag, value)
                        copied++
                    }
                }
                // 写入必须发生在 fd 仍持有时
                targetExif.saveAttributes()
            }
            copied
        } catch (t: Throwable) {
            // EXIF 是「锦上添花」，失败绝不能影响图片本身已成功导出的事实
            0
        }
    }

    /** 从输入流读取 ExifInterface；任何异常返回 null。 */
    private fun readExif(input: InputStream): ExifInterface? = try {
        ExifInterface(input)
    } catch (t: Throwable) {
        null
    }

    /**
     * 直接向目标写入一个自定义标签（供隐藏水印复用）。
     *
     * 与 [copyExif] 分开是因为隐藏水印要求**优先级最高**：
     * 必须在 EXIF 拷贝**之后**写入，避免被源图的同名标签覆盖。
     *
     * ## 编码处理（重要）
     *
     * AndroidX ExifInterface 对 UserComment 用 **US-ASCII** 编码
     * （已反编译确认），中文会被替换成 `?`。因此这里先经
     * [ExifTextCodec.encode] 转成纯 ASCII（必要时 Base64），
     * 读取时再由 [ExifTextCodec.decode] 还原。
     */
    fun writeUserComment(context: Context, targetUri: Uri, comment: String): Boolean {
        return try {
            val encoded = ExifTextCodec.encode(comment)
            context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, encoded)
                exif.saveAttributes()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 读取目标文件的 UserComment，并**自动解码** Base64 中转格式。
     *
     * @return 原始可读文本（中文完整）；无标签时返回 null
     */
    fun readUserComment(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val raw = ExifInterface(input)
                .getAttribute(ExifInterface.TAG_USER_COMMENT)
            ExifTextCodec.decode(raw)
        }
    } catch (t: Throwable) {
        null
    }

    /** 供测试使用的白名单副本。 */
    internal fun copiableTags(): List<String> = COPIABLE_TAGS
}
