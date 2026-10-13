package com.watermarkbox.data.repository
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.model.ImageWatermarkStyle
import com.watermarkbox.data.model.PresetNaming
import com.watermarkbox.data.model.TextWatermarkStyle
import com.watermarkbox.data.model.WatermarkPreset
import com.watermarkbox.data.model.WatermarkType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** DataStore 实例（应用级单例，通过扩展属性挂到 Context）。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "watermark_box_settings"
)

/**
 * 全局共享的 Json 配置。
 *
 * 放在 object 里而非每次在类内 `Json { }`：
 * - Json 实例持有 schema 缓存，复用可显著减少重复解析开销
 * - 三个 Repository 共用一份，避免配置漂移
 *
 * `ignoreUnknownKeys` 保证向前兼容：旧数据缺字段、新数据多字段都能读。
 */
internal object AppJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/**
 * 设置与预设的持久化仓库（DataStore Preferences + kotlinx.serialization）。
 *
 * 存两类数据：
 * 1. 预设列表  —— 序列化为 JSON 字符串存单个 key
 * 2. 导出设置  —— 同样序列化为 JSON
 *
 * 选择 JSON-in-Preferences 而非 Proto DataStore 的原因：
 * 数据量小（预设通常 < 50 个），结构可能演进，
 * JSON 便于调试与向前兼容（新增字段带默认值即可）。
 */
class SettingsRepository(private val context: Context) {

    private val json: Json get() = AppJson.instance

    private val presetListSerializer = ListSerializer(WatermarkPreset.serializer())

    // ------------------------------------------------------------------
    // 预设
    // ------------------------------------------------------------------

    /** 观察所有预设（按创建时间倒序，最新在前）。 */
    val presets: Flow<List<WatermarkPreset>> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_PRESETS]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(presetListSerializer, raw) }
                .getOrDefault(emptyList())
                .sortedByDescending { it.createdAt }
        }
    }

    /** 读取当前预设列表快照。 */
    suspend fun getPresets(): List<WatermarkPreset> = presets.first()

    /**
     * 保存预设。
     *
     * ## 命名冲突的处理（由调用方决定）
     *
     * 本方法**不自行决定**重名时怎么办，而是由参数 [overwrite] 明确指定：
     *
     * - `overwrite = true`：同名则**替换**（旧记录的 id 与创建时间被丢弃，
     *   用新记录代替）—— 适合用户明确选择"替换"的场景
     * - `overwrite = false`：同名则**重命名保留**（在名称后追加 " (2)"、
     *   " (3)"…直到不重名）—— 适合用户选择"保留"的场景
     *
     * ## 为什么把决策权交给调用方
     *
     * 早期实现是无条件覆盖（`filterNot { name } + preset`）。
     * 这会让用户在不知情的情况下丢掉旧预设 —— 属于静默数据丢失。
     * 现在 UI 会先检测重名并询问用户，再把结果传进来。
     *
     * @return 实际保存后的名称（`overwrite=false` 时可能与传入名称不同）
     */
    suspend fun savePreset(preset: WatermarkPreset, overwrite: Boolean = true): String {
        var finalName = preset.name
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString(presetListSerializer, raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()

            val updated = if (overwrite) {
                // 替换：移除同名（按名称匹配，不按 id —— 用户认知里"同名"就是同一条）
                current.filterNot { it.name == preset.name } + preset
            } else {
                // 保留：起一个不重名的新名称
                finalName = PresetNaming.uniqueName(preset.name, current.map { it.name })
                current + preset.copy(name = finalName)
            }
            prefs[KEY_PRESETS] = json.encodeToString(presetListSerializer, updated)
        }
        return finalName
    }

    /**
     * 批量添加预设（供「导入预设文件」使用）。
     *
     * ## 为什么不复用 [savePreset] 循环调用
     *
     * 1. **性能**：`savePreset` 每次都完整读一遍 JSON、加一条、再写回。
     *    导入 50 条就是 50 次读写，而这里只需一次。
     * 2. **原子性**：中途失败时不会留下「导入了一半」的状态。
     * 3. **名称冲突的连锁处理**：同一批次内也可能有重名
     *    （比如文件里两条都叫「我的水印」），必须把**本批已用掉的名称**
     *    也算进"已占用"，否则两条会拿到同一个名字。
     *
     * ## 冲突策略：一律重命名保留
     *
     * 导入是批量操作，逐条弹窗询问会让用户点到崩溃；
     * 而「覆盖」会静默丢掉用户原有的预设 —— 那是不可接受的。
     * 所以固定采用「重命名保留」（`原名 (2)`、`原名 (3)`…）。
     *
     * @return 实际写入的名称列表，与 [presets] 顺序一一对应
     *         （冲突时与传入名称不同）
     */
    suspend fun addPresets(presets: List<WatermarkPreset>): List<String> {
        if (presets.isEmpty()) return emptyList()

        val finalNames = mutableListOf<String>()

        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString(presetListSerializer, raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()

            // 已占用名称：现有预设 + 本批次已确定的名称
            val taken = current.map { it.name }.toMutableList()

            val added = presets.map { preset ->
                val unique = PresetNaming.uniqueName(preset.name, taken)
                taken += unique
                finalNames += unique
                preset.copy(name = unique)
            }

            prefs[KEY_PRESETS] = json.encodeToString(presetListSerializer, current + added)
        }

        return finalNames
    }

    /** 按 id 删除预设。 */
    suspend fun deletePreset(id: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString(presetListSerializer, raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[KEY_PRESETS] = json.encodeToString(
                presetListSerializer,
                current.filterNot { it.id == id }
            )
        }
    }

    /** 重命名预设。 */
    suspend fun renamePreset(id: String, newName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString(presetListSerializer, raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[KEY_PRESETS] = json.encodeToString(
                presetListSerializer,
                current.map { if (it.id == id) it.copy(name = newName) else it }
            )
        }
    }

    /**
     * 更新预设的**水印样式**（首页预设编辑页用）。
     *
     * 与 [savePreset] 的区别：这里按 **id** 定位那一条记录原地更新，
     * 只替换样式字段（[type] / [textStyle] / [imageStyle]），
     * **保留** `id` 与 `createdAt`。
     *
     * 为什么不复用 [savePreset]：后者按**名称**匹配（"同名即同一条"），
     * 那是「保存当前配置」语义下用户的认知。但编辑已有预设时，
     * 若用户在编辑页里把名字也改了，按名称匹配就会**找不到记录**，
     * 结果变成新增一条而不是更新 —— 不符合"编辑"的预期。
     *
     * @return 是否找到了该 id 的记录并更新
     */
    suspend fun updatePresetStyle(
        id: String,
        name: String,
        type: WatermarkType,
        textStyle: TextWatermarkStyle,
        imageStyle: ImageWatermarkStyle
    ): Boolean {
        var found = false
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString(presetListSerializer, raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            val updated = current.map { p ->
                if (p.id == id) {
                    found = true
                    p.copy(name = name, type = type, textStyle = textStyle, imageStyle = imageStyle)
                } else {
                    p
                }
            }
            if (found) {
                prefs[KEY_PRESETS] = json.encodeToString(presetListSerializer, updated)
            }
        }
        return found
    }

    // ------------------------------------------------------------------
    // 导出设置
    // ------------------------------------------------------------------

    /** 观察导出设置。 */
    val exportSettings: Flow<ExportSettings> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_EXPORT_SETTINGS]
        if (raw.isNullOrBlank()) {
            ExportSettings()
        } else {
            runCatching { json.decodeFromString(ExportSettings.serializer(), raw) }
                .getOrDefault(ExportSettings())
        }
    }

    /** 读取导出设置快照。 */
    suspend fun getExportSettings(): ExportSettings = exportSettings.first()

    /** 更新导出设置。 */
    suspend fun updateExportSettings(settings: ExportSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_EXPORT_SETTINGS] = json.encodeToString(ExportSettings.serializer(), settings)
        }
    }

    // ------------------------------------------------------------------
    // 检查更新
    // ------------------------------------------------------------------

    /**
     * 是否自动检查更新（默认开启）。
     *
     * 存成简单的 Boolean Preferences 而非塞进 ExportSettings ——
     * 后者是「导出相关」的语义，把更新开关混进去会让模型职责不清。
     */
    val autoCheckUpdate: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_CHECK_UPDATE] ?: true
    }

    suspend fun getAutoCheckUpdate(): Boolean = autoCheckUpdate.first()

    suspend fun setAutoCheckUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_CHECK_UPDATE] = enabled
        }
    }

    /**
     * 是否启用应用内下载更新（默认关闭，实验性）。
     *
     * 默认关闭意味着：不申请安装权限、不下载、只跳浏览器。
     */
    val inAppDownload: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_INAPP_DOWNLOAD] ?: false
    }

    suspend fun getInAppDownload(): Boolean = inAppDownload.first()

    suspend fun setInAppDownload(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_INAPP_DOWNLOAD] = enabled
        }
    }

    /**
     * 上次自动检查更新的时间戳（毫秒）。0 表示从未检查过。
     *
     * 用于「每天最多检查一次」的节流：启动时若距上次 < 24h 且是自动检查，就跳过。
     * 手动检查不受此限制。
     */
    val lastCheckTime: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_LAST_CHECK_TIME] ?: 0L
    }

    suspend fun getLastCheckTime(): Long = lastCheckTime.first()

    suspend fun setLastCheckTime(timestamp: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LAST_CHECK_TIME] = timestamp
        }
    }

    private companion object {
        val KEY_PRESETS = stringPreferencesKey("watermark_presets")
        val KEY_EXPORT_SETTINGS = stringPreferencesKey("export_settings")
        val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        val KEY_INAPP_DOWNLOAD = booleanPreferencesKey("in_app_download")
        val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
    }
}