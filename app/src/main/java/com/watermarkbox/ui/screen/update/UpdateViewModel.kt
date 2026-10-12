package com.watermarkbox.ui.screen.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.BuildConfig
import com.watermarkbox.data.model.UpdateInfo
import com.watermarkbox.data.repository.SettingsRepository
import com.watermarkbox.util.ApkInstaller
import com.watermarkbox.util.ApkVerifier
import com.watermarkbox.util.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「检查更新」的界面状态。
 *
 * 用一个密封类表达互斥的若干阶段，而不是散落的多个 Boolean
 * （isChecking / isDownloading / hasUpdate …）—— 那些可以组合出
 * 非法状态（既在检查又在下载），密封类从类型上杜绝。
 */
sealed interface UpdateUiState {
    /** 空闲，无对话框。 */
    data object Idle : UpdateUiState

    /** 正在检查。 */
    data object Checking : UpdateUiState

    /** 已是最新。 */
    data class UpToDate(val currentVersion: String) : UpdateUiState

    /** 发现新版本。 */
    data class Available(val info: UpdateInfo) : UpdateUiState

    /** 检查失败。 */
    data class Failed(val kind: UpdateChecker.ErrorKind) : UpdateUiState

    /** 应用内下载中。 */
    data class Downloading(val percent: Int?) : UpdateUiState

    /**
     * 下载完成，正在做完整性校验（SHA-256 / 包名 / 签名）。
     *
     * 单独成一个状态是为了给用户反馈：大 APK 算哈希要一两秒，
     * 卡在「下载 100%」不动会让人以为死机。
     */
    data object Verifying : UpdateUiState

    /** 下载完成，且校验通过，等待确认安装。 */
    data object ReadyToInstall : UpdateUiState

    /**
     * 校验失败（文件损坏 / 包名不符 / 签名不符）。
     *
     * 走到这里说明**文件已被删除**，绝不会安装。
     * [reason] 用于选文案，[detail] 供排查（一般不展示给用户）。
     */
    data class VerifyFailed(
        val reason: ApkVerifier.FailureReason,
        val detail: String? = null
    ) : UpdateUiState

    /** 需要授予「安装未知应用」权限。 */
    data object NeedPermission : UpdateUiState
}

/**
 * 「检查更新」的专用 ViewModel。
 *
 * ## 为什么独立于 SettingsViewModel
 *
 * 更新流程有两个入口（设置页的「检查更新」行、关于页的版本徽章），
 * 且要支持「启动时自动检查」。如果逻辑挂在 SettingsViewModel 上：
 * - 两个入口分属不同 NavBackStackEntry → 各自一个实例 → 状态不同步
 * - 自动检查发生在启动时，彼时设置页可能还没被创建
 *
 * 独立出来后在导航层以 **Activity 作用域** 创建一份（见 AppNavigation），
 * 全应用共享同一个实例，无论从哪个入口触发，状态与对话框都一致。
 *
 * ## 开关的读写
 *
 * 「自动检查」「应用内下载」两个开关属于设置数据，仍由
 * [SettingsRepository] 持久化；这里只负责流程编排。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /** 开关状态镜像（供对话框判断是否显示「应用内下载」按钮）。 */
    private val _inAppEnabled = MutableStateFlow(false)
    val inAppEnabled: StateFlow<Boolean> = _inAppEnabled.asStateFlow()

    /** 正在进行的检查/下载任务，用于「取消旧的、只保留最新」。 */
    private var job: Job? = null

    /** 已下载完成的 APK 文件（等待安装时用）。 */
    private var downloadedApk: File? = null

    /**
     * 最近一次「发现新版本」的完整信息。
     *
     * 用途：校验失败后 [retryDownload] 需要还原到
     * [UpdateUiState.Available] 才能重新下载，而那个状态需要 info。
     * 缓存下来避免重试时再请求一次 GitHub API。
     */
    private var lastInfo: UpdateInfo? = null

    /** 是否已经跑过启动自动检查（避免每次重组都触发）。 */
    private var autoCheckStarted = false

    init {
        viewModelScope.launch {
            settingsRepository.inAppDownload.collect { _inAppEnabled.value = it }
        }
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /**
     * 检查更新。
     *
     * @param manual true 表示用户手动点击（无论结果如何都要给反馈）；
     *               false 表示启动时自动检查（已最新 / 失败时静默，避免打扰）
     */
    fun check(manual: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)

            _uiState.value = when (result) {
                is UpdateChecker.Result.Available -> {
                    // 缓存下来：校验失败重试时要靠它还原 Available 状态
                    lastInfo = result.info
                    UpdateUiState.Available(result.info)
                }

                is UpdateChecker.Result.UpToDate ->
                    if (manual) UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                    else UpdateUiState.Idle

                is UpdateChecker.Result.Failed ->
                    if (manual) UpdateUiState.Failed(result.kind)
                    else UpdateUiState.Idle
            }

            // 无论成功失败都记录时间，避免同一天反复自动请求
            settingsRepository.setLastCheckTime(System.currentTimeMillis())
        }
    }

    /**
     * 启动时的自动检查（带「每天最多一次」节流）。
     *
     * 幂等：内部只真正执行一次，重复调用无副作用 ——
     * UI 层可在 LaunchedEffect 里放心调用。
     */
    fun autoCheckIfDue() {
        if (autoCheckStarted) return
        autoCheckStarted = true
        viewModelScope.launch {
            if (!settingsRepository.getAutoCheckUpdate()) return@launch
            val last = settingsRepository.getLastCheckTime()
            val dayMs = 24 * 60 * 60 * 1000L
            if (System.currentTimeMillis() - last < dayMs) return@launch
            check(manual = false)
        }
    }

    // ------------------------------------------------------------------
    // 下载与安装
    // ------------------------------------------------------------------

    /**
     * 应用内下载。
     *
     * 前置：下载前再查一次权限 —— 用户可能在别处撤销过授权。
     * 无权限则转 [UpdateUiState.NeedPermission]，由 UI 引导授权。
     */
    fun downloadInApp() {
        val info = (_uiState.value as? UpdateUiState.Available)?.info ?: return
        val url = info.apkUrl ?: return

        val context = getApplication<Application>()
        if (!ApkInstaller.canInstall(context)) {
            _uiState.value = UpdateUiState.NeedPermission
            return
        }

        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = UpdateUiState.Downloading(null)
            try {
                val file = ApkInstaller.download(context, url) { percent ->
                    _uiState.value = UpdateUiState.Downloading(percent)
                }

                // ---- 下载完成 → 校验 ----
                //
                // 校验期间显示一个「正在校验」的进行中状态：大文件算
                // SHA-256 需要一两秒，没反馈会让用户以为卡住。
                _uiState.value = UpdateUiState.Verifying

                when (val verified = ApkVerifier.verify(
                    context = context,
                    apk = file,
                    expectedSha256 = info.apkSha256,
                    expectedSize = info.apkSize
                )) {
                    is ApkVerifier.Result.Ok -> {
                        downloadedApk = file
                        _uiState.value = UpdateUiState.ReadyToInstall
                    }

                    is ApkVerifier.Result.Failed -> {
                        // 校验失败必须删掉文件，绝不交给系统安装器
                        runCatching { file.delete() }
                        _uiState.value = UpdateUiState.VerifyFailed(
                            reason = verified.reason,
                            detail = verified.detail
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UpdateUiState.Failed(UpdateChecker.ErrorKind.NETWORK)
            }
        }
    }

    /**
     * 校验失败后重试下载。
     *
     * ## 为什么要先回到「有新版本」状态
     *
     * [downloadInApp] 的前置条件是状态必须是 [UpdateUiState.Available]
     * ——它从这里取 `info`（含下载地址与预期哈希）。
     * 校验失败时状态是 [UpdateUiState.VerifyFailed]，已不含 info，
     * 所以必须先补回 Available 再走下载。
     *
     * 用保存的 [lastInfo] 还原，避免重新请求一次 GitHub API
     * （既慢又可能因为限流失败）。
     */
    fun retryDownload() {
        val info = lastInfo ?: run {
            // 理论上不会走到（有 info 才会进下载流程），兜底回空闲
            dismiss()
            return
        }
        _uiState.value = UpdateUiState.Available(info)
        downloadInApp()
    }

    /**
     * 唤起系统安装器。
     *
     * ## 为什么成功唤起后**不立即**清理文件
     *
     * 系统安装器（PackageInstaller）是**独立进程**。`startActivity` 返回
     * 只代表「Intent 已发出」，不代表安装器已经读完 APK。
     *
     * 早期实现在此处立刻 `cleanup()` 删除文件，导致安装器稍后打开 URI 时
     * 报 `ENOENT (No such file or directory)`。
     *
     * 现在改为：**把清理推迟到下次下载前**（[downloadInApp] 开头会 cleanup），
     * 那时安装器一定早已读完。代价只是文件多留一会儿（几 MB，可接受）。
     *
     * @return true 表示成功唤起；false 表示仍无权限或文件缺失
     */
    fun installDownloaded(): Boolean {
        val apk = downloadedApk ?: return false
        val context = getApplication<Application>()
        // 唤起安装器前释放 job 引用，避免安装期间被误 cancel
        job = null
        val ok = ApkInstaller.install(context, apk)
        if (ok) {
            _uiState.value = UpdateUiState.Idle
        } else {
            _uiState.value = UpdateUiState.NeedPermission
        }
        return ok
    }

    /** 跳转到「安装未知应用」授权页。 */
    fun openInstallPermissionSettings() {
        ApkInstaller.openInstallPermissionSettings(getApplication())
    }

    /** 关闭对话框 / 取消流程：清理下载文件并回到空闲。 */
    fun dismiss() {
        job?.cancel()
        val context = getApplication<Application>()
        if (downloadedApk != null) {
            ApkInstaller.cleanup(context)
            downloadedApk = null
        }
        _uiState.value = UpdateUiState.Idle
    }
}
