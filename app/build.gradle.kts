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
        versionCode = 7
        versionName = "1.5.0"
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
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
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