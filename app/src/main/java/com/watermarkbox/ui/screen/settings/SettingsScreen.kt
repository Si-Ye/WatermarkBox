package com.watermarkbox.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watermarkbox.BuildConfig
import com.watermarkbox.R
import com.watermarkbox.data.model.ExportFormat
import com.watermarkbox.data.model.ExportSettings
import com.watermarkbox.data.repository.ExportRepository
import com.watermarkbox.ui.component.LabeledSlider
import com.watermarkbox.ui.component.SectionLabel
import com.watermarkbox.ui.component.SegmentedControl
import com.watermarkbox.ui.component.ToggleRow
import com.watermarkbox.ui.screen.update.UpdateAvailableDialog
import com.watermarkbox.ui.screen.update.UpdateCheckingDialog
import com.watermarkbox.ui.screen.update.UpdateDownloadingDialog
import com.watermarkbox.ui.screen.update.UpdateFailedDialog
import com.watermarkbox.ui.screen.update.UpdatePermissionDialog
import com.watermarkbox.ui.screen.update.UpdateReadyToInstallDialog
import com.watermarkbox.ui.screen.update.UpdateUiState
import com.watermarkbox.ui.screen.update.UpdateUpToDateDialog
import com.watermarkbox.ui.screen.update.UpdateVerifyingDialog
import com.watermarkbox.ui.screen.update.UpdateVerifyFailedDialog
import com.watermarkbox.ui.screen.update.UpdateViewModel
import com.watermarkbox.ui.theme.Spacing
import com.watermarkbox.util.AppLanguage
import com.watermarkbox.util.ApkInstaller
import com.watermarkbox.util.UpdateChecker
import kotlin.math.roundToInt

/**
 * 设置页的分类。
 *
 * 按「这个设置影响什么」划分，而不是按实现顺序堆成一条长列表：
 *
 * - [WATERMARK]：决定**水印长什么样**（默认文字 / 默认图 / 透明度）
 * - [EXPORT]：决定**文件怎么存**（格式 / 质量 / 命名 / 目录 / EXIF）
 * - [MISC]：预设统计与关于入口
 *
 * 顺序即 Tab 顺序，默认停在第一项。
 *
 * 注意：label 存的是**资源 ID** 而非字符串 ——
 * enum 构造器在编译期执行，拿不到 Context，无法直接取本地化文案。
 *
 * 可见性为 `internal`（而非 `private`）：分类 Tab 栏已抽到
 * [SettingsCategoryTabs] 单独文件，需要跨文件访问本枚举。
 */
internal enum class SettingsCategory(@param:StringRes val labelRes: Int) {
    WATERMARK(R.string.settings_category_watermark),
    EXPORT(R.string.settings_category_export),
    MISC(R.string.settings_category_other)
}

/**
 * 设置页（模块 3）。
 *
 * 布局：顶部横向分类菜单（[SettingsCategory]）→ 该分类下的设置项。
 * 切换分类只换内容区，顶栏与返回按钮保持不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    val inAppEnabled by updateViewModel.inAppEnabled.collectAsStateWithLifecycle()
    val settings = state.exportSettings
    val context = LocalContext.current

    /** 是否显示「应用内下载需要授权」的说明对话框。 */
    var showInAppPermissionDialog by remember { mutableStateOf(false) }

    /**
     * 是否正在「等待用户从系统授权页返回」。
     *
     * ## 为什么需要这个状态
     *
     * 授权是**异步的「去-回」往返**：应用跳出去 → 用户在系统设置里操作
     * → 应用回到前台。跳转的那一刻**无法知道**用户最终是否授权。
     *
     * 所以不能在跳转前就把开关置为「开」—— 否则用户点了「去授权」
     * 但没真的打开开关就返回，界面会显示「开」，与实际权限不符。
     *
     * 正确做法：记住「我们正等一个授权结果」，等应用回到前台时
     * 复查一次权限，用**真实结果**决定开关状态。
     */
    var awaitingPermission by remember { mutableStateOf(false) }

    // 从系统授权页返回时复查权限。
    //
    // 用 ON_RESUME 而不是 ON_START：授权页覆盖在本应用之上时，
    // 本 Activity 只是 STOPPED；用户返回时先 START 再 RESUME，
    // 在 RESUME 复查可保证读取到最新的权限状态。
    //
    // ## 为什么每次 RESUME 都要查，而不只在「等待授权」时查
    //
    // 还有一条对称的路径：用户此前已授权（开关为开），之后跑到
    // 系统设置里**撤销**了权限。此时回到应用，开关若仍显示「开」
    // 就与实际不符 —— 点下载会在唤起安装器时失败。
    //
    // 所以只要「开关当前是开的」或「正在等待授权结果」，
    // 每次回到前台都校正一次，让它始终等于真实权限。
    val lifecycleOwner = LocalLifecycleOwner.current
    val needPermissionAudit = awaitingPermission || state.inAppDownload
    DisposableEffect(lifecycleOwner, needPermissionAudit) {
        if (!needPermissionAudit) {
            onDispose {}
        } else {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val granted = ApkInstaller.canInstall(context)
                    // 只在真实状态与界面状态不一致时写入，避免无谓的 DataStore 写入
                    if (granted != state.inAppDownload) {
                        viewModel.setInAppDownload(granted)
                    }
                    if (awaitingPermission) awaitingPermission = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // 进入设置页时若距上次检查已过一天，顺带查一次（静默）。
    // 这里也调一次是因为用户可能长时间不重启应用，只切页面。
    LaunchedEffect(Unit) { updateViewModel.autoCheckIfDue() }

    // 用 rememberSaveable 而非 remember：
    // 切换应用语言会重建 Activity，若用 remember，
    // 用户会被弹回「水印」分类 —— 而他明明是在「其他」里点的语言切换。
    // rememberSaveable 能跨配置变更保留选中项。
    var category by rememberSaveable { mutableStateOf(SettingsCategory.WATERMARK) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---- 分类菜单：固定在顶部，不随内容滚动 ----
            SettingsCategoryTabs(selected = category, onSelect = { category = it })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            // ---- 内容区 ----
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.PageHorizontal)
            ) {
                Spacer(Modifier.height(Spacing.Item))

                when (category) {
                    SettingsCategory.WATERMARK -> WatermarkCategory(
                        settings = settings,
                        viewModel = viewModel,
                        onPickLogo = onPickLogo
                    )
                    SettingsCategory.EXPORT -> ExportCategory(
                        settings = settings,
                        viewModel = viewModel
                    )
                    SettingsCategory.MISC -> MiscCategory(
                        presetCount = state.presetCount,
                        autoCheckUpdate = state.autoCheckUpdate,
                        inAppDownload = state.inAppDownload,
                        onToggleAutoCheck = { viewModel.setAutoCheckUpdate(it) },
                        onToggleInApp = { enabled ->
                            // 开启时才需要授权：先弹说明，用户同意后再跳系统设置
                            if (enabled && !ApkInstaller.canInstall(context)) {
                                showInAppPermissionDialog = true
                            } else {
                                viewModel.setInAppDownload(enabled)
                            }
                        },
                        onCheckUpdate = { updateViewModel.check(manual = true) },
                        onOpenAbout = onOpenAbout,
                        onOpenChangelog = onOpenChangelog
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ---- 更新流程对话框 ----
    // 挂在 Scaffold 之后、函数闭合前：它们是「覆盖层」，
    // 不应参与 Scaffold 的内容布局。
    when (val us = updateState) {
        UpdateUiState.Idle -> Unit

        UpdateUiState.Checking -> UpdateCheckingDialog()

        is UpdateUiState.UpToDate -> UpdateUpToDateDialog(
            currentVersion = us.currentVersion,
            onDismiss = { updateViewModel.dismiss() }
        )

        is UpdateUiState.Available -> UpdateAvailableDialog(
            currentVersion = BuildConfig.VERSION_NAME,
            info = us.info,
            // 只有「开关打开」且「真有 APK 附件」才给应用内下载选项
            inAppAvailable = inAppEnabled && us.info.apkUrl != null,
            onDismiss = { updateViewModel.dismiss() },
            onDownloadInApp = { updateViewModel.downloadInApp() },
            onOpenBrowser = {
                openUrl(context, us.info.htmlUrl)
                updateViewModel.dismiss()
            }
        )

        is UpdateUiState.Failed -> UpdateFailedDialog(
            message = stringResource(us.kind.messageRes()),
            onDismiss = { updateViewModel.dismiss() }
        )

        is UpdateUiState.Downloading -> UpdateDownloadingDialog(percent = us.percent)

        // 下载完成 → 校验中（大 APK 算哈希要一两秒，给个反馈）
        UpdateUiState.Verifying -> UpdateVerifyingDialog()

        // 校验失败：文件已被 ViewModel 删除，这里只负责提示与重试
        is UpdateUiState.VerifyFailed -> UpdateVerifyFailedDialog(
            reason = us.reason,
            detail = us.detail,
            // 只在 debug 包展示技术细节，release 包不吓普通用户
            showDetail = BuildConfig.DEBUG,
            onRetry = { updateViewModel.retryDownload() },
            onDismiss = { updateViewModel.dismiss() }
        )

        UpdateUiState.ReadyToInstall -> UpdateReadyToInstallDialog(
            onInstall = { updateViewModel.installDownloaded() },
            onCancel = { updateViewModel.dismiss() }
        )

        UpdateUiState.NeedPermission -> UpdatePermissionDialog(
            onGoSettings = { updateViewModel.openInstallPermissionSettings() },
            onCancel = { updateViewModel.dismiss() }
        )
    }

    // 「开启应用内下载」时的授权说明。
    // 用户确认后才真正跳系统设置 —— 避免莫名其妙被弹出应用。
    if (showInAppPermissionDialog) {
        UpdatePermissionDialog(
            onGoSettings = {
                showInAppPermissionDialog = false
                // 关键：**不在此处**把开关置为「开」。
                //
                // 跳转只是「请求授权」，用户完全可能到设置页后直接返回、
                // 并未真正打开开关。此时若先置 true，界面就会与真实权限不符。
                //
                // 改为标记「正在等待授权结果」，待应用回到前台时
                // 由上面的 ON_RESUME 复查用真实权限决定开关。
                awaitingPermission = true
                updateViewModel.openInstallPermissionSettings()
            },
            onCancel = { showInAppPermissionDialog = false }
        )
    }
}

/**
 * 把 [UpdateChecker.ErrorKind] 映射到本地化文案。
 *
 * 为什么映射放在 UI 层而非 ViewModel：ViewModel 不该持有资源 ID
 * （它是平台无关的），错误分类由它给出、文案由界面决定，
 * 这与项目里 [UiMessage] 的设计思路一致。
 */
@StringRes
private fun UpdateChecker.ErrorKind.messageRes(): Int = when (this) {
    UpdateChecker.ErrorKind.NETWORK -> R.string.update_error_network
    UpdateChecker.ErrorKind.TIMEOUT -> R.string.update_error_timeout
    UpdateChecker.ErrorKind.HTTP -> R.string.update_error_not_found
    UpdateChecker.ErrorKind.PARSE -> R.string.update_error_parse
    UpdateChecker.ErrorKind.UNKNOWN -> R.string.update_error_unknown
}

/** 用系统浏览器打开 URL；无浏览器时静默忽略。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

