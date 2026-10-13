package com.watermarkbox.ui.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.watermarkbox.ui.screen.batch.BatchConfigScreen
import com.watermarkbox.ui.screen.batch.BatchViewModel
import com.watermarkbox.ui.screen.detect.DetectScreen
import com.watermarkbox.ui.screen.detect.DetectViewModel
import com.watermarkbox.ui.screen.editor.EditorScreen
import com.watermarkbox.ui.screen.editor.EditorViewModel
import com.watermarkbox.ui.screen.home.HomeScreen
import com.watermarkbox.ui.screen.home.OnboardingViewModel
import com.watermarkbox.ui.onboarding.OnboardingOverlay
import com.watermarkbox.ui.onboarding.OnboardingTour
import com.watermarkbox.ui.screen.presets.PresetEditScreen
import com.watermarkbox.ui.screen.presets.PresetEditViewModel
import com.watermarkbox.ui.screen.presets.PresetsScreen
import com.watermarkbox.ui.screen.presets.PresetsViewModel
import com.watermarkbox.ui.screen.settings.AboutScreen
import com.watermarkbox.ui.screen.settings.ChangelogScreen
import com.watermarkbox.ui.screen.settings.SettingsScreen
import com.watermarkbox.ui.screen.tutorial.TutorialScreen
import com.watermarkbox.ui.screen.settings.SettingsViewModel
import com.watermarkbox.ui.screen.update.UpdateViewModel
import com.watermarkbox.util.PresetFileCodec

/**
 * 页面切换动效时长（毫秒）。
 *
 * ## 为什么是 200，而不是 Material 默认的 300~400
 *
 * 前提是「流畅优先」：转场只该负责说明**谁进谁出**，不该让用户等它演完。
 * 200ms 足够看出方向感，又几乎察觉不到等待。
 *
 * ## 为什么只做「水平位移 + 透明度」
 *
 * 这两种只改 layer，不触发重新测量与布局；而 scale、共享轴（shared axis）
 * 之类的转场会让整棵子树每帧重新布局 —— 在低端机上那就是掉帧来源。
 * 出场位移取屏幕宽度的 1/8（不整屏滑），视觉上更轻，也一样省。
 */
private const val NAV_TRANSITION_MS = 200

/**
 * 导航目的地定义。
 */
object Routes {
    const val HOME = "home"

    /** 设置页 */
    const val SETTINGS = "settings"

    /** 隐藏水印检测页 */
    const val DETECT = "detect"

    /** 首页的预设管理页 */
    const val PRESETS = "presets"

    /** 单张编辑页：通过 encoded Uri 传入图片 */
    const val EDITOR = "editor?uri={uri}"
    fun editor(uri: Uri) = "editor?uri=${Uri.encode(uri.toString())}"

    /**
     * 批量配置页：把已选 Uri 列表编码进路由。
     * 数量上限 50，Uri 长度可控。
     */
    const val BATCH = "batch?uris={uris}"
    fun batch(uris: List<Uri>): String {
        val joined = uris.joinToString(",") { Uri.encode(it.toString()) }
        return "batch?uris=$joined"
    }

    /**
     * 预设参数编辑页（无图片）。
     *
     * 只传 id 而不传整个预设 —— 预设可能体积不小（内嵌 logo 位图），
     * 塞进路由字符串既难看又可能超长。页面自己按 id 从 DataStore 读。
     */
    const val PRESET_EDIT = "preset_edit?presetId={presetId}"
    fun presetEdit(presetId: String) = "preset_edit?presetId=${Uri.encode(presetId)}"

    /** 关于页（版本 / 制作人 / 开源许可）。 */
    const val ABOUT = "about"

    /** 更新说明页（各版本的变更记录）。 */
    const val CHANGELOG = "changelog"

    /** 使用教程页。 */
    const val TUTORIAL = "tutorial"
}

/**
 * 应用导航图。
 *
 * @param onPickSingleImage 发起单张 Photo Picker
 * @param onPickBatchImages 发起多选 Photo Picker（最多 50 张）
 * @param onPickLogo        发起 logo 选择
 * @param onPickFont        发起字体文件选择
 * @param onExportPresetFile 发起「创建文件」（导出预设）。
 *        第一个参数是建议的文件名，第二个是拿到 Uri 后的回调。
 *        Uri 为 null（用户取消）时不会回调。
 * @param onImportPresetFile 发起「选择文件」（导入预设）
 */
@Composable
fun WatermarkBoxNavHost(
    onPickSingleImage: (onResult: (Uri) -> Unit) -> Unit,
    onPickBatchImages: (onResult: (List<Uri>) -> Unit) -> Unit,
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit,
    onPickFont: (onResult: (Uri) -> Unit) -> Unit,
    onExportPresetFile: (suggestedName: String, onResult: (Uri) -> Unit) -> Unit,
    onImportPresetFile: (onResult: (Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    // 「检查更新」的全应用共享状态。
    //
    // 关键：作用域必须是 Activity（而非默认的 NavBackStackEntry），
    // 这样设置页与关于页拿到的是**同一个** ViewModel 实例 ——
    // 在设置页触发的检查，切到关于页时状态仍一致。
    // 同时它比 NavHost 活得更久，启动自动检查在首页也能跑。
    val activity = LocalContext.current as ComponentActivity
    val updateViewModel: UpdateViewModel = viewModel(viewModelStoreOwner = activity)

    // 新手引导的状态用 **Activity 作用域**。
    //
    // 首页要用它挂目标坐标，教程页要用它重置「已看过」标记 ——
    // 两个页面必须拿到**同一个实例**。用 composable 默认作用域的话，
    // 每页各拿一份，教程页里的「重新过一遍」就只会改到自己那份，白点。
    val onboardingViewModel: OnboardingViewModel =
        viewModel(viewModelStoreOwner = activity)

    // 应用启动即尝试自动检查（内部有「每天一次」节流，幂等）
    LaunchedEffect(Unit) { updateViewModel.autoCheckIfDue() }

    // 当前编辑页 ViewModel（供 logo picker 回调）
    val activeEditorViewModel = remember { mutableStateOf<EditorViewModel?>(null) }
    // 当前批量页 ViewModel（供 logo picker 回调）
    val activeBatchViewModel = remember { mutableStateOf<BatchViewModel?>(null) }
    // 当前检测页 ViewModel（供单图 picker 回调）
    val activeDetectViewModel = remember { mutableStateOf<DetectViewModel?>(null) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // 页面转场：小幅水平位移 + 淡入淡出（时长与取舍见 NAV_TRANSITION_MS 注释）。
            // 前进时新页从右滑入、旧页向左退出；返回时相反。
            enterTransition = {
                slideInHorizontally(tween(NAV_TRANSITION_MS)) { it / 8 } +
                    fadeIn(tween(NAV_TRANSITION_MS))
            },
            exitTransition = {
                slideOutHorizontally(tween(NAV_TRANSITION_MS)) { -it / 8 } +
                    fadeOut(tween(NAV_TRANSITION_MS))
            },
            popEnterTransition = {
                slideInHorizontally(tween(NAV_TRANSITION_MS)) { -it / 8 } +
                    fadeIn(tween(NAV_TRANSITION_MS))
            },
            popExitTransition = {
                slideOutHorizontally(tween(NAV_TRANSITION_MS)) { it / 8 } +
                    fadeOut(tween(NAV_TRANSITION_MS))
            }
        ) {

            // ---------------- 首页 ----------------
            composable(Routes.HOME) {
                // 进首页时尝试开始首页引导（没看过的才开）
                LaunchedEffect(Unit) {
                    onboardingViewModel.startIfNeeded(OnboardingTour.HOME)
                }

                HomeScreen(
                    onPickSingleImage = {
                        onPickSingleImage { uri ->
                            navController.navigate(Routes.editor(uri))
                        }
                    },
                    onPickBatchImages = {
                        onPickBatchImages { uris ->
                            if (uris.isNotEmpty()) {
                                navController.navigate(Routes.batch(uris))
                            }
                        }
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenDetect = { navController.navigate(Routes.DETECT) },
                    onOpenPresets = { navController.navigate(Routes.PRESETS) },
                    // 控件会把自身在窗口中的位置上报给它，引导层据此挖孔。
                    // 传下去之后，首页那几个入口就成了「可被聚焦的目标」。
                    onboardingRegistry = onboardingViewModel.registry
                )

                // 引导遮罩层。只有当前引导属于本页时才渲染 ——
                // 否则切到别的页面时遮罩会跟着飘过去，指向一个根本不在屏幕上的控件。
                OnboardingHost(onboardingViewModel, OnboardingTour.HOME)
            }

            // ---------------- 首页的预设管理 ----------------
            composable(Routes.PRESETS) {
                // ⚠️ 本页**故意不做**新手引导。
                //
                // 曾经给这一页加过一条「指向右上角 ⋮」的引导，实测体验很糟：
                // 这是用户**主动点进来、目标明确**的管理页，进门先被蒙版罩住
                // 属于「突袭」；而「导入 / 导出在这里」这件事，
                // 首页引导的第 4 步（指向书签图标）已经讲过了。
                //
                // 结论：引导只保留首页那一套，不再做页面级引导。
                val presetsViewModel: PresetsViewModel = viewModel()
                PresetsScreen(
                    viewModel = presetsViewModel,
                    onBack = { navController.popBackStack() },
                    onEditPreset = { preset ->
                        navController.navigate(Routes.presetEdit(preset.id))
                    },
                    // 文件名在这里算：它是纯逻辑（在 PresetFileCodec 里），
                    // 而「导出哪个预设」是 PresetsScreen 才知道的信息。
                    // 两边各出一半，汇到这里。
                    onExportAll = {
                        onExportPresetFile(
                            PresetFileCodec.suggestFileName(
                                presetName = null,
                                now = System.currentTimeMillis()
                            )
                        ) { uri -> presetsViewModel.exportAll(uri) }
                    },
                    onExportOne = { preset ->
                        onExportPresetFile(
                            PresetFileCodec.suggestFileName(
                                presetName = preset.name,
                                now = System.currentTimeMillis()
                            )
                        ) { uri -> presetsViewModel.exportOne(uri, preset) }
                    },
                    onImport = {
                        onImportPresetFile { uri -> presetsViewModel.importFrom(uri) }
                    }
                )
            }

            // ---------------- 预设参数编辑（无图片）----------------
            composable(
                route = Routes.PRESET_EDIT,
                arguments = listOf(navArgument("presetId") { type = NavType.StringType })
            ) { backStackEntry ->
                val presetId = backStackEntry.arguments?.getString("presetId").orEmpty()
                val presetEditViewModel: PresetEditViewModel = viewModel()
                PresetEditScreen(
                    presetId = presetId,
                    viewModel = presetEditViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // ---------------- 隐藏水印检测 ----------------
            composable(Routes.DETECT) {
                val detectViewModel: DetectViewModel = viewModel()

                LaunchedEffect(detectViewModel) {
                    activeDetectViewModel.value = detectViewModel
                }

                DetectScreen(
                    viewModel = detectViewModel,
                    onBack = { navController.popBackStack() },
                    onPickImage = {
                        onPickSingleImage { uri ->
                            activeDetectViewModel.value?.detect(uri)
                        }
                    }
                )
            }
// ---------------- 设置 ----------------
            composable(Routes.SETTINGS) {
                val settingsViewModel: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenChangelog = { navController.navigate(Routes.CHANGELOG) },
                    onOpenTutorial = { navController.navigate(Routes.TUTORIAL) },
                    onPickLogo = onPickLogo
                )
            }

            // ---------------- 关于 ----------------
            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    // 版本徽章点击 → 发起手动检查（对话框统一在 NavHost 外层渲染）
                    onCheckUpdate = { updateViewModel.check(manual = true) }
                )
            }

            // ---------------- 更新说明 ----------------
            composable(Routes.CHANGELOG) {
                ChangelogScreen(onBack = { navController.popBackStack() })
            }

            // ---------------- 使用教程 ----------------
            composable(Routes.TUTORIAL) {
                TutorialScreen(
                    onBack = { navController.popBackStack() },
                    onRestartOnboarding = {
                        // 重置引导状态（清掉「已看过」标记），再退回首页 ——
                        // 首页一组合出来就会弹出引导，用户不必自己再找回去
                        onboardingViewModel.restart(OnboardingTour.HOME)
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }


            // ---------------- 单张编辑 ----------------
            composable(
                route = Routes.EDITOR,
                arguments = listOf(
                    navArgument("uri") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("uri").orEmpty()
                val uri = encoded.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

                val editorViewModel: EditorViewModel = viewModel()

                LaunchedEffect(editorViewModel) {
                    activeEditorViewModel.value = editorViewModel
                }
                LaunchedEffect(uri) {
                    if (uri != null) editorViewModel.loadImage(uri)
                }

                EditorScreen(
                    viewModel = editorViewModel,
                    onBack = { navController.popBackStack() },
                    onPickLogo = {
                        onPickLogo { logoUri ->
                            activeEditorViewModel.value?.loadLogo(logoUri)
                        }
                    },
                    onPickFont = {
                        onPickFont { fontUri ->
                            activeEditorViewModel.value?.importCustomFont(fontUri)
                        }
                    }
                )
            }

            // ---------------- 批量配置 ----------------
            composable(
                route = Routes.BATCH,
                arguments = listOf(
                    navArgument("uris") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val raw = backStackEntry.arguments?.getString("uris").orEmpty()
                val uris = raw.split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Uri.parse(Uri.decode(it)) }.getOrNull() }

                val batchViewModel: BatchViewModel = viewModel()

                LaunchedEffect(batchViewModel) {
                    activeBatchViewModel.value = batchViewModel
                }
                LaunchedEffect(uris) {
                    batchViewModel.setImages(uris)
                }

                BatchConfigScreen(
                    viewModel = batchViewModel,
                    onBack = { navController.popBackStack() },
                    onPickLogo = {
                        onPickLogo { logoUri ->
                            activeBatchViewModel.value?.loadLogo(logoUri)
                        }
                    },
                    // 「添加更多」页：唤起多选，选完追加到当前列表。
                    // 不新建路由 —— 就在本页 append，用户视野不跳走。
                    onAddMore = {
                        onPickBatchImages { moreUris ->
                            activeBatchViewModel.value?.appendImages(moreUris)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 渲染某一套新手引导的遮罩层。
 *
 * ## 为什么用 Dialog，而不是在页面里套一个 Box
 *
 * 引导要盖住**状态栏与导航栏**。直接放在页面的 Composable 里的话，
 * 它只活在内容区内 —— 系统栏那两条仍然是亮的，看起来"没铺满"。
 * Dialog 自带独立窗口，才能做到真正的全屏遮罩。
 *
 * ## 为什么必须把 window 背景设成透明（关键坑）
 *
 * [OnboardingOverlay] 用 `BlendMode.Clear` 把遮罩擦出一个洞，
 * 期待孔里透出**底下的真实界面**。但 `Clear` 擦掉的只是 Dialog
 * 这一层自己画的东西 —— 露出来的是 Dialog window 的**背景**，
 * 而它默认是不透明的，于是孔里依旧一片暗色，聚焦效果完全失效。
 *
 * 所以要把 window 的背景置成透明。（`decorFitsSystemWindows = false`
 * 解决的是"铺满系统栏"，解决不了"孔里透不透" —— 两件事得分别设置。）
 *
 * ## 为什么只在「本页的这一套」才渲染
 *
 * 一套引导只属于一个页面。若在 NavHost 外层统一渲染，切页时遮罩会
 * "飘过去"指向一个根本不在屏幕上的控件 —— registry 里还留着旧页面的坐标。
 * 让每个页面渲染自己那套，天然就没有这个问题。
 *
 * ## 为什么 onDismissRequest 是 finish 而不是空实现
 *
 * 遮罩本身就是"窗"：点哪里都会被 Overlay 的 pointerInput 接住并分流
 * （孔内 = 下一步，孔外 = 跳过）。所以这里只兜底**返回键** ——
 * 用户按返回时按"跳过"处理，否则引导会锁住返回键，体验很像卡死。
 *
 * @param tour 本页负责的那一套引导
 */
@Composable
private fun OnboardingHost(
    viewModel: OnboardingViewModel,
    tour: OnboardingTour
) {
    val snapshot by viewModel.current.collectAsStateWithLifecycle()
    val current = snapshot ?: return

    // 不是本页的引导就不渲染（见上方注释）
    if (current.tour != tour) return

    // 把 Dialog window 的背景擦成透明，否则挖出来的孔还是暗的
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)
            ?.window
            ?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    }

    Dialog(
        onDismissRequest = { viewModel.finish() },
        properties = DialogProperties(
            // 关掉默认宽度限制 —— 否则遮罩只占中间一小块
            usePlatformDefaultWidth = false,
            // 点窗外不关闭：遮罩铺满全屏，"窗外"根本不存在，
            // 所有点击都交给 Overlay 自己分流
            dismissOnClickOutside = false,
            // 让 Dialog 窗口延伸到系统栏区域
            decorFitsSystemWindows = false
        )
    ) {
        OnboardingOverlay(
            step = current.step,
            stepIndex = current.stepIndex,
            stepCount = current.stepCount,
            anchor = current.anchor,
            onSkip = viewModel::finish,
            onNext = viewModel::next
        )
    }
}