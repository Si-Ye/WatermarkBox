package com.watermarkbox.ui.screen.presets

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.R
import com.watermarkbox.util.UiMessage
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.util.PresetFileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 导入结果（用于弹出结果对话框）。
 *
 * 失败时带上 [PresetFileCodec.FailureReason] 而不是一个笼统的布尔值 ——
 * 「文件是空的」和「这不是水印盒导出的文件」对用户的下一步行动
 * 指引完全不同，必须能区分。
 */
sealed interface PresetImportOutcome {

    /**
     * 导入成功。
     *
     * @param imported     导入的预设数量
     * @param renamed      其中因重名而被自动改名的数量
     * @param imagePresets 其中图片水印预设的数量。
     *                     用于决定是否提示「logo 需重新选择」——
     *                     只有真的导入了图片预设，这个提示才有意义，
     *                     否则每次导入都弹一句无关的话很烦。
     */
    data class Success(
        val imported: Int,
        val renamed: Int,
        val imagePresets: Int
    ) : PresetImportOutcome

    /** 导入失败。 */
    data class Failure(
        val reason: PresetFileCodec.FailureReason,
        val detail: String?
    ) : PresetImportOutcome
}

/**
 * 首页「水印预设」页的 UI 状态。
 */
data class PresetsUiState(
    val presets: List<WatermarkPreset> = emptyList(),
    /** 是否已从磁盘加载完成（避免闪一下空状态） */
    val loaded: Boolean = false,
    /** 待删除确认的预设 */
    val pendingDelete: WatermarkPreset? = null,
    /** 操作反馈 */
    val message: UiMessage? = null,
    /**
     * 导入 / 导出进行中。
     *
     * 用于禁用菜单项 —— 导出是大文件写入，导入要先读文件再解析，
     * 过程中重复触发会互相干扰。
     */
    val transferBusy: Boolean = false,
    /** 导入结果（非空时弹对话框） */
    val importOutcome: PresetImportOutcome? = null
)

/**
 * 首页「水印预设」页 ViewModel。
 *
 * 从首页进入时，这里只做**管理**（查看 / 重命名 / 删除 / 导入 / 导出）。
 * 「应用」一个预设需要图片，因此由 UI 层负责：
 * 点预设 → 触发选图 → 带着 presetId 进编辑页。
 *
 * 为什么不在这里直接做「应用」：
 * 编辑页的 ViewModel 与图片加载强绑定，从首页跨越过去
 * 需要把 presetId 通过路由传递，由编辑页自己应用。
 */
class PresetsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(PresetsUiState())
    val uiState: StateFlow<PresetsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.presets.collect { list ->
                _uiState.update { it.copy(presets = list, loaded = true) }
            }
        }
    }

    // ==================================================================
    // 删除
    // ==================================================================

    fun requestDelete(preset: WatermarkPreset) =
        _uiState.update { it.copy(pendingDelete = preset) }

    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val target = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            settingsRepository.deletePreset(target.id)
            _uiState.update {
                it.copy(pendingDelete = null, message = UiMessage(R.string.preset_info_deleted, target.name))
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    // ==================================================================
    // 导出
    // ==================================================================

    /** 导出全部预设到 [uri]。 */
    fun exportAll(uri: Uri) = exportTo(uri, _uiState.value.presets)

    /** 导出单个预设到 [uri]。 */
    fun exportOne(uri: Uri, preset: WatermarkPreset) = exportTo(uri, listOf(preset))

    /**
     * 把预设写入用户选定的 Uri。
     *
     * @param presets 要写出的预设；为空时直接提示失败 ——
     *                导出空文件没有意义，而且导入端会报 NO_PRESETS，
     *                让用户白跑一趟不如当场说明。
     */
    private fun exportTo(uri: Uri, presets: List<WatermarkPreset>) {
        if (presets.isEmpty()) {
            _uiState.update { it.copy(message = UiMessage(R.string.presets_export_nothing)) }
            return
        }
        if (_uiState.value.transferBusy) return

        viewModelScope.launch {
            _uiState.update { it.copy(transferBusy = true) }

            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = PresetFileCodec.encode(presets, System.currentTimeMillis())
                    // "wt" = write + truncate：SAF 返回的 Uri 可能指向已存在的
                    // 文件（用户选了同一个名字），不截断会留下尾部旧内容
                    resolver().openOutputStream(uri, "wt")?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }

            _uiState.update {
                it.copy(
                    transferBusy = false,
                    message = if (ok) {
                        // 走 plurals：英文下 "1 preset" 与 "2 presets" 形式不同，
                        // 用固定字符串会出现 "1 presets" 这种错误
                        UiMessage.plural(R.plurals.presets_export_done, presets.size, presets.size)
                    } else {
                        UiMessage(R.string.presets_export_failed)
                    }
                )
            }
        }
    }

    // ==================================================================
    // 导入
    // ==================================================================

    /**
     * 从用户选定的 Uri 读取并导入预设。
     *
     * 重名一律「自动改名保留」，不弹窗询问 —— 理由见
     * [SettingsRepository.addPresets] 的注释。
     */
    fun importFrom(uri: Uri) {
        if (_uiState.value.transferBusy) return

        viewModelScope.launch {
            _uiState.update { it.copy(transferBusy = true) }

            val parsed = withContext(Dispatchers.IO) { readAndParse(uri) }

            when (parsed) {
                is PresetFileCodec.ParseResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            transferBusy = false,
                            importOutcome = PresetImportOutcome.Failure(parsed.reason, parsed.detail)
                        )
                    }
                }

                is PresetFileCodec.ParseResult.Ok -> {
                    // 写库在主线程之外；addPresets 内部会回到 DataStore 自己的调度器，
                    // 这里保持 IO 上下文即可
                    val finalNames = withContext(Dispatchers.IO) {
                        settingsRepository.addPresets(parsed.presets)
                    }
                    val renamed = parsed.presets
                        .zip(finalNames)
                        .count { (original, actual) -> original.name != actual }
                    val imagePresets = parsed.presets.count { it.type == WatermarkType.IMAGE }

                    _uiState.update {
                        it.copy(
                            transferBusy = false,
                            importOutcome = PresetImportOutcome.Success(
                                imported = finalNames.size,
                                renamed = renamed,
                                imagePresets = imagePresets
                            )
                        )
                    }
                }
            }
        }
    }

    /** 关闭导入结果对话框。 */
    fun dismissImportOutcome() = _uiState.update { it.copy(importOutcome = null) }

    /**
     * 读取并解析文件。
     *
     * ## 为什么要「边读边检查大小」而不是先 `readBytes()`
     *
     * `readBytes()` 会把整个文件读进内存 —— 若用户误选了一个
     * 几个 GB 的视频，就直接 OOM 崩溃了。这里分两步防：
     *
     * 1. **先问大小**：`OpenableColumns.SIZE` 是 ContentProvider
     *    主动提供的，通常很准，能在读之前就拒掉超大文件
     * 2. **读的时候再数**：但那个值**可能缺失**（某些 Provider 返回
     *    null）或是假的，所以循环读取时累计字节数，超限立刻停止
     *
     * 只有第 2 步才是真正的保证，第 1 步只是让常见情况更早失败。
     */
    private fun readAndParse(uri: Uri): PresetFileCodec.ParseResult {
        val resolver = resolver()

        // ---- 1. 先看声明的大小 ----
        val declaredSize = runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    -1L
                }
            } ?: -1L
        }.getOrDefault(-1L)

        if (declaredSize > MAX_FILE_BYTES) {
            return PresetFileCodec.ParseResult.Failed(
                PresetFileCodec.FailureReason.TOO_LARGE,
                "声明大小 $declaredSize 字节"
            )
        }

        // ---- 2. 读取（边读边数）----
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_FILE_BYTES) {
                        return@use null // 超限，放弃
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull()

        if (bytes == null) {
            // 可能打开失败，也可能读超限。区分不了就报一个兜底原因：
            // 打开失败最常见的原因是文件已被删除/权限失效，
            // 而那对用户来说和"文件不对"是同一类体验（重选一个即可）。
            return PresetFileCodec.ParseResult.Failed(
                PresetFileCodec.FailureReason.TOO_LARGE,
                "文件无法读取或超过 $MAX_FILE_BYTES 字节"
            )
        }

        // ---- 3. 解码 ----
        // 不做 BOM / 编码嗅探：预设文件由本应用写出，固定 UTF-8。
        // 若是别的来源，非法字节会变成替换字符，JSON 解析随即失败，
        // 最终落到 MALFORMED —— 这个结果是对的。
        return PresetFileCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    private fun resolver(): ContentResolver = getApplication<Application>().contentResolver

    private companion object {
        /**
         * 读取上限（字节）。
         *
         * [PresetFileCodec.MAX_FILE_CHARS] 是**字符**上限，UTF-8 下
         * 一个字符最多占 4 字节，所以字节上限取 4 倍 —— 这样
         * 不会出现「文件可以读进来但解码时被判超限」的错位。
         */
        const val MAX_FILE_BYTES = PresetFileCodec.MAX_FILE_CHARS * 4
    }
}