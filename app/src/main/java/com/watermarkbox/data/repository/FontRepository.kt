package com.watermarkbox.data.repository

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义字体仓库（阶段四）。
 *
 * 职责：
 * - 把用户选择的字体文件复制进应用私有目录 `files/fonts/`
 * - 缓存已加载的 [Typeface]，避免重复解析
 * - 提供字体列表与删除能力
 *
 * 安全说明：
 * - 字体文件复制到私有目录，不依赖原始 Uri 的长期授权
 * - [Typeface.createFromFile] 解析失败时返回 null，不会崩溃
 */
class FontRepository(private val context: Context) {

    /** 字体存放目录。 */
    private val fontDir: File
        get() = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    /** Typeface 缓存：文件名 → Typeface。 */
    private val cache = mutableMapOf<String, Typeface>()

    companion object {
        /** 允许的字体扩展名。 */
        private val ALLOWED_EXT = setOf("ttf", "otf", "ttc")

        /** 单文件大小上限（8MB），防止误选超大文件。 */
        private const val MAX_FONT_BYTES = 8L * 1024 * 1024
    }

    /**
     * 导入一个字体文件。
     *
     * @param uri      源 Uri（来自文件选择器）
     * @param copyName 目标文件名（含扩展名）
     * @return 导入后的文件名；失败返回 null
     */
    suspend fun importFont(uri: Uri, copyName: String): String? = withContext(Dispatchers.IO) {
        val ext = copyName.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT) return@withContext null

        runCatching {
            val target = File(fontDir, sanitize(copyName))

            context.contentResolver.openInputStream(uri)?.use { input ->
                // 先写到临时文件，成功后再改名，避免半截文件被当成有效字体
                val tmp = File(fontDir, "${target.name}.tmp")
                tmp.outputStream().use { output ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_FONT_BYTES) {
                            tmp.delete()
                            return@withContext null
                        }
                        output.write(buf, 0, n)
                    }
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            } ?: return@withContext null

            // 立即验证能否解析，避免存下坏文件
            val tf = Typeface.createFromFile(target)
            if (tf == null) {
                target.delete()
                return@withContext null
            }
            cache[target.name] = tf
            target.name
        }.getOrNull()
    }

    /**
     * 加载已导入的字体。
     *
     * @param fileName 文件名（如 "myfont.ttf"）
     * @return Typeface；不存在或解析失败返回 null
     */
    fun loadFont(fileName: String?): Typeface? {
        if (fileName.isNullOrBlank()) return null
        cache[fileName]?.let { return it }

        val file = File(fontDir, fileName)
        if (!file.exists()) return null

        return runCatching { Typeface.createFromFile(file) }
            .getOrNull()
            ?.also { cache[fileName] = it }
    }

    /** 列出已导入的字体文件名。 */
    fun listFonts(): List<String> =
        fontDir.listFiles()
            ?.filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in ALLOWED_EXT }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** 删除一个字体。 */
    fun deleteFont(fileName: String): Boolean {
        cache.remove(fileName)
        return File(fontDir, fileName).let { if (it.exists()) it.delete() else false }
    }

    /** 从 Uri 猜测字体文件名。 */
    suspend fun queryFontName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** 清理文件名中的非法字符。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
}