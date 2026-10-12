package com.watermarkbox

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.watermarkbox.data.model.BatchLimits
import com.watermarkbox.ui.navigation.WatermarkBoxNavHost
import com.watermarkbox.ui.theme.WatermarkBoxTheme
import kotlinx.coroutines.launch

/**
 * 应用唯一 Activity，承载 Compose 导航。
 *
 * Photo Picker：
 * - 单张：PickVisualMedia
 * - 批量：PickMultipleVisualMedia（上限见 [BatchLimits]）
 * - Logo：PickVisualMedia
 * - 字体：OpenDocument
 *
 * ## 注册时机（重要）
 *
 * `registerForActivityResult` **必须在 STARTED 之前**调用，
 * 否则抛 `IllegalStateException: attempting to register while current state is RESUMED`。
 *
 * 因此所有 launcher 都声明为**字段**（在构造期注册），
 * 且批量上限用一个**编译期常量**传入 —— 不能在 onCreate 里动态算。
 *
 * ## 为什么继承 AppCompatActivity 而不是 ComponentActivity（重要）
 *
 * 纯粹为了**应用内语言切换**能生效。
 *
 * `AppCompatDelegate.setApplicationLocales()` 需要一个「活跃的 delegate」
 * 来接收语言变化并触发 Activity 重建。而这个 delegate 只在
 * **AppCompatActivity** 构造时才会被注册 —— 用 ComponentActivity 时，
 * 调用了也不会报错，只是**静默无效**（设置在静态变量里，无人消费，
 * 界面纹丝不动）。这是最容易踩的坑，且没有任何日志提示。
 *
 * Compose 本身与 Activity 类型无关，继承 AppCompatActivity 不影响任何 UI 行为。
 */
class MainActivity : AppCompatActivity() {

    /** 挂起的单张选择回调。 */
    private var pendingSinglePick: ((Uri) -> Unit)? = null

    /** 挂起的批量选择回调。 */
    private var pendingBatchPick: ((List<Uri>) -> Unit)? = null

    /** 挂起的 Logo 选择回调。 */
    private var pendingLogoPick: ((Uri) -> Unit)? = null

    /** 挂起的字体选择回调。 */
    private var pendingFontPick: ((Uri) -> Unit)? = null

    /** 单张图片选择。 */
    private val pickSingleMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) pendingSinglePick?.invoke(uri)
        pendingSinglePick = null
    }

    /**
     * 多张图片选择。
     *
     * 上限使用 [BatchLimits.REQUEST_MAX_ITEMS]（编译期常量）。
     *
     * 为什么不在这里查 `MediaStore.getPickImagesMaxLimit()`：
     * 该字段在**构造期**就要注册，而查询系统值需要 Context 已就绪；
     * 更重要的是，即使我们把 n 传大，Photo Picker 自身也会内部夹紧，
     * 不会因为「传得比系统值大」而崩溃 —— 真正的崩溃源是
     * 在 RESUMED 之后才注册（已修复）。
     */
    private val pickBatchMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(BatchLimits.REQUEST_MAX_ITEMS)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) pendingBatchPick?.invoke(uris)
        pendingBatchPick = null
    }

    /** Logo 图片选择。 */
    private val pickLogoMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) pendingLogoPick?.invoke(uri)
        pendingLogoPick = null
    }

    /**
     * 字体文件选择（阶段四）。
     *
     * 用 OpenDocument 而非 Photo Picker —— 字体不是图片，
     * 需按 MIME 过滤到 font/ttf、font/otf、application/x-font-ttf 等。
     */
    private val pickFontFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) pendingFontPick?.invoke(uri)
        pendingFontPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatermarkBoxTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                WatermarkBoxNavHost(
                    onPickSingleImage = { onResult ->
                        pendingSinglePick = onResult
                        pickSingleMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onPickBatchImages = { onResult ->
                        pendingBatchPick = onResult
                        pickBatchMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onPickLogo = { onResult ->
                        pendingLogoPick = onResult
                        pickLogoMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onPickFont = { onResult ->
                        pendingFontPick = onResult
                        // 字体常见 MIME：不同文件管理器上报并不统一，多列几种
                        pickFontFile.launch(
                            arrayOf(
                                "font/ttf",
                                "font/otf",
                                "font/ttc",
                                "application/x-font-ttf",
                                "application/x-font-otf",
                                "application/octet-stream"
                            )
                        )
                    }
                )

                SnackbarHost(hostState = snackbarHostState)
            }
        }
    }

    private companion object {
        // 上限统一由 BatchLimits 管理，此处不再重复定义
    }
}