package com.watermarkbox.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.watermarkbox.ui.screen.presets.PresetEditScreen
import com.watermarkbox.ui.screen.presets.PresetEditViewModel
import com.watermarkbox.ui.screen.presets.PresetsScreen
import com.watermarkbox.ui.screen.presets.PresetsViewModel
import com.watermarkbox.ui.screen.settings.AboutScreen
import com.watermarkbox.ui.screen.settings.ChangelogScreen
import com.watermarkbox.ui.screen.settings.SettingsScreen
import com.watermarkbox.ui.screen.settings.SettingsViewModel

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
}

/**
 * 应用导航图。
 *
 * @param onPickSingleImage 发起单张 Photo Picker
 * @param onPickBatchImages 发起多选 Photo Picker（最多 50 张）
 * @param onPickLogo        发起 logo 选择
 */
@Composable
fun WatermarkBoxNavHost(
    onPickSingleImage: (onResult: (Uri) -> Unit) -> Unit,
    onPickBatchImages: (onResult: (List<Uri>) -> Unit) -> Unit,
    onPickLogo: (onResult: (Uri) -> Unit) -> Unit,
    onPickFont: (onResult: (Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

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
        NavHost(navController = navController, startDestination = Routes.HOME) {

            // ---------------- 首页 ----------------
            composable(Routes.HOME) {
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
                    onOpenPresets = { navController.navigate(Routes.PRESETS) }
                )
            }

            // ---------------- 首页的预设管理 ----------------
            composable(Routes.PRESETS) {
                val presetsViewModel: PresetsViewModel = viewModel()
                PresetsScreen(
                    viewModel = presetsViewModel,
                    onBack = { navController.popBackStack() },
                    onEditPreset = { preset ->
                        navController.navigate(Routes.presetEdit(preset.id))
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
                    onBack = { navController.popBackStack() },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenChangelog = { navController.navigate(Routes.CHANGELOG) },
                    onPickLogo = onPickLogo
                )
            }

            // ---------------- 关于 ----------------
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }

            // ---------------- 更新说明 ----------------
            composable(Routes.CHANGELOG) {
                ChangelogScreen(onBack = { navController.popBackStack() })
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