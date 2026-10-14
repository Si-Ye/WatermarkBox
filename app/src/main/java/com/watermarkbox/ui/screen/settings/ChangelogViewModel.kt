package com.watermarkbox.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.model.ChangelogEntry
import com.watermarkbox.data.model.ChangelogJson
import com.watermarkbox.data.repository.ChangelogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 更新说明页的状态。
 *
 * @param entries 来自远端或缓存的条目。
 *                **为空表示"前两级数据源都不可用"** —— 此时 UI 应退回
 *                内置的 [com.watermarkbox.data.model.ReleaseHistory]。
 *                这个回退刻意不在 ViewModel 里做：内置数据用的是资源 ID，
 *                必须在 Composable 上下文里用 `stringResource` 解析。
 * @param loading 是否正在联网拉取
 * @param remoteUnavailable 联网已尝试但失败（用于决定是否提示"用离线数据"）
 */
data class ChangelogUiState(
    val entries: List<ChangelogEntry> = emptyList(),
    val loading: Boolean = false,
    val remoteUnavailable: Boolean = false
)

/**
 * 「更新说明」页的 ViewModel。
 *
 * ## 数据来源与优先级
 *
 * ```
 * ① 网络（最新）
 *      ↓ 失败或还没拉
 * ② 本地缓存（上次拉到的）
 *      ↓ 没有
 * ③ 内置兜底（随 APK 发布）—— 由 UI 层提供，见 [ChangelogUiState.entries]
 * ```
 *
 * ## 为什么联网只做一次
 *
 * 用户可能反复进出这一页。每次都联网既慢又浪费流量，
 * 而更新说明**一天之内不会变**。所以进程内只拉一次
 * （[networkAttempted] 守卫），重进页面直接吃内存/缓存。
 *
 * ## 为什么 [load] 要传语言
 *
 * 缓存里存的是**原文**（中英都在里面）。要在展示时才知道该取哪一份 ——
 * 见 [ChangelogRepository.refreshRaw] 的说明。
 * UI 侧用 `LaunchedEffect(lang)` 调用，语言一变就重新解析（不再联网）。
 */
class ChangelogViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChangelogRepository(application)

    private val _uiState = MutableStateFlow(ChangelogUiState())
    val uiState: StateFlow<ChangelogUiState> = _uiState.asStateFlow()

    /** 拿到的文档原文（缓存或网络）。为 null 表示前两级都还没有。 */
    private var rawDoc: String? = null

    /** 是否已经尝试过联网。防止反复进出页面时反复请求。 */
    private var networkAttempted = false

    /** 当前已解析所用的语言。语言变化时需要重新解析原文。 */
    private var parsedLang: String? = null

    /**
     * 加载更新说明。可安全重复调用 —— 内部会跳过已经做过的事。
     *
     * @param lang 当前界面语言（`"en"` 取英文，其它取中文）
     */
    fun load(lang: String) {
        viewModelScope.launch {
            // ---- ① 本地缓存（含"内存里已有原文"的情况）----
            if (rawDoc == null) {
                rawDoc = repository.cachedRaw()
            }
            // 语言变了要重新解析（缓存的是原文，两种语言都在里面）
            if (parsedLang != lang) {
                parsedLang = lang
                applyRaw(lang)
            }

            // ---- ② 联网（进程内只做一次）----
            if (networkAttempted) return@launch
            networkAttempted = true
            _uiState.update { it.copy(loading = true) }

            val fresh = repository.refreshRaw()
            if (fresh == null) {
                // 网络不可用：保留已有内容，仅标注状态
                _uiState.update { it.copy(loading = false, remoteUnavailable = true) }
                return@launch
            }

            val parsed = ChangelogJson.parse(fresh, lang)
            if (parsed == null) {
                // 拉到了但内容不可用（格式变了 / 被截断）。
                // **不覆盖缓存**，也不清空已有内容 —— 宁可显示旧的有效数据。
                _uiState.update {
                    it.copy(
                        loading = false,
                        remoteUnavailable = it.entries.isEmpty()
                    )
                }
                return@launch
            }

            rawDoc = fresh
            repository.writeCache(fresh)
            _uiState.update {
                it.copy(entries = parsed, loading = false, remoteUnavailable = false)
            }
        }
    }

    /** 按语言解析当前原文并写入状态。解析失败则保持原状（不破坏已有内容）。 */
    private fun applyRaw(lang: String) {
        val raw = rawDoc ?: return
        val parsed = ChangelogJson.parse(raw, lang) ?: return
        _uiState.update { it.copy(entries = parsed) }
    }
}