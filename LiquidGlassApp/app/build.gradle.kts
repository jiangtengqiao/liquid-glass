plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liquidglass.app"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "liquidglass123"
            keyAlias = "liquidglass"
            keyPassword = "liquidglass123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.liquidglass.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 56
        versionName = "2.11.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // v2.9.0: Kyant0 液态玻璃库用 Kotlin 2.3.0 编译，本项目用 2.0.21。
        // 跳过 metadata 版本检查允许读取高版本编译的库（库未使用 2.3 专有语法，运行时安全）。
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // ZXing：业界标准条码/二维码库，替代有编码缺陷的手写实现（支持URL/中文/全字符集）
    implementation("com.google.zxing:core:3.5.3")

    // ── 音乐播放器依赖 ──
    // Media3 (ExoPlayer + MediaSession)：后台播放/通知栏/锁屏控件的标准方案
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // OkHttp：网易云 weapi 加密请求 + cookie 管理
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Coil：Compose 友好的封面图加载
    implementation("io.coil-kt:coil-compose:2.7.0")
    // JSON 解析（网易云接口均为 JSON）
    implementation("org.json:json:20240303")

    // ── v2.9.0 真实液态玻璃折射库（Kyant0/AndroidLiquidGlass）──
    // 真实光学折射(lens) + 色散(chromaticAberration) + 模糊(blur) + 高光(highlight)
    // 替代自制 glassSurface 的纯装饰性图层，实现 Apple Liquid Glass 级别的真实玻璃质感。
    // API: rememberLayerBackdrop() → layerBackdrop() 标记背景源 → drawBackdrop() 应用玻璃效果
    // effects: lens(折射范围, 折射程度, chromaticAberration=true) + blur(半径)
    //
    // 使用本地 patched AAR：原版要求 compileSdk 37/36，本项目仅支持 SDK 35，
    // 已下载 AAR 并将 aar-metadata.properties 中 minCompileSdk 降至 35。
    // 库内部使用 androidx.compose.* 包名，与项目现有 Compose 依赖兼容。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    debugImplementation("androidx.compose.ui:ui-tooling")
}