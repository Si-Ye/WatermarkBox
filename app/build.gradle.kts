import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- Release 签名配置 ----
// 从 keystore.properties 读取，该文件已被 .gitignore 排除，不会进版本库。
// 若文件不存在（例如 CI 或他人 clone），release 走未签名流程，不会让构建失败。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.watermarkbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watermarkbox"
        minSdk = 26            // Android 8.0
        targetSdk = 35         // Android 15
        versionCode = 11
        versionName = "2.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ---- 并行安装测试（仅用于本机验证，不影响正式构建）----
    //
    // 传 `-PparallelSuffix=.test` 时，applicationId 变为 com.watermarkbox.test，
    // 可与正式版共存安装，用于「切语言看效果」这类验证而不丢用户数据。
    //
    // 不传该参数时行为**完全不变** —— 默认值就是空串。
    if (project.hasProperty("parallelSuffix")) {
        val suffix = project.property("parallelSuffix") as String
        defaultConfig {
            applicationIdSuffix = suffix
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 使用 v1 + v2 + v3 全签名方案，兼容 Android 8.0 ~ 15
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true          // 开启 R8 代码压缩与混淆
            isShrinkResources = false       // 资源压缩与 AGP9 打包步骤冲突，由 externalNativeBuild 之外的脚本处理
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true   // 设置页需要读取 VERSION_NAME
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ------------------------------------------------------------------
    // Android Lint
    // ------------------------------------------------------------------
    //
    // ## 为什么用 baseline 而不是直接开 warningsAsErrors
    //
    // 当前有 84 条 Warning（0 条 Error），主要是：
    //   - DefaultLocale：字符串格式化未指定 Locale
    //   - GradleDependency：依赖有新版本
    //   - OldTargetApi：compileSdk 不是最新
    //
    // 直接开 `warningsAsErrors` 会让 CI 立刻变红，必须一次性修完 84 条，
    // 而其中相当一部分（如「有新版本可升级」）**不该由 lint 决定何时升级**。
    //
    // 用 baseline 的做法：把这 84 条**冻结**在 `lint-baseline.xml`，
    // 之后**新增**的问题才会让 CI 失败。既立刻有约束力，
    // 又不用为了过 CI 而做无意义的大改。
    //
    // ## 什么时候该更新 baseline
    //
    // **只在「确认某条问题不打算修」时**手动更新：
    //
    //     ./gradlew updateLintBaseline
    //
    // 但**不要**用它来「消除新增问题」—— 那等于把刚加的闸门又打开。
    //
    // ## 为什么要把 aborted 也设为 true（默认值，显式写出来）
    //
    // `abortOnError` 决定「发现 Error 是否让构建失败」。
    // lint 的默认值是 true，这里显式声明是为了让意图一目了然
    // —— 读代码的人不需要去查默认值。
    lint {
        // 已有问题冻结在这里，只有新增的才会报
        baseline = file("lint-baseline.xml")

        // 有 Error 就让构建失败（显式声明默认行为）
        abortOnError = true

        // 严重问题即使在 release 构建也不放过
        checkReleaseBuilds = true

        // 不在控制台刷 84 条明细（要看就跑 lintDebug 看 HTML 报告）
        textReport = true
        htmlReport = true
        xmlReport = true

        // 这些检查对本项目无意义，直接关掉，减少噪声：
        //   - GradleDependency：何时升级依赖由人决定，不该由 lint 推着走
        //   - AndroidGradlePluginVersion：同上
        //   - OldTargetApi：升 compileSdk 是有成本的决定，不适合自动提醒
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "OldTargetApi"
        )
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

/**
 * Compose 编译器报告。
 *
 * ## 为什么需要它
 *
 * Compose 卡顿的两大来源，**看代码完全看不出来**，只能靠编译器告诉我们：
 *
 * 1. **「restartable but not skippable」的组件** ——
 *    参数被判定为不稳定时，父组件每次重组都会把它从头执行一遍，
 *    即使它的输入压根没变
 * 2. **不稳定（unstable）的参数类型** ——
 *    通常是 `List`/`Set` 这类集合，或含可变字段的 data class
 *
 * 产物在 `app/build/compose-reports/`，只用于人工分析，不参与打包。
 */
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
}

dependencies {
    // ---- AndroidX 基础 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ---- 应用内语言切换 ----
    // AppCompatDelegate.setApplicationLocales() 是官方 per-app language API，
    // 在 Android 13+ 直接走系统能力，低版本由 AppCompat 自己接管。
    // 项目本身是纯 Compose，引入 AppCompat 只为这一个能力。
    implementation(libs.androidx.appcompat)

    // ---- Compose UI ----
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.exifinterface)

    // ---- Navigation / ViewModel ----
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ---- 协程 ----
    implementation(libs.kotlinx.coroutines.android)

    // ---- 图片加载（预览缩略图）----
    implementation(libs.coil.compose)

    // ---- 批量处理 ----
    implementation(libs.androidx.work.runtime.ktx)

    // ---- 配置持久化 + 预设（阶段三）----
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}