plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}
repositories {
    // 国内镜像优先
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.aliyun.com/repository/jcenter")
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}
dependencies {
    implementation(compose.desktop.currentOs)
    // JSON 解析（公告/Beta 版本信息/翻译结果）
    implementation("org.json:json:20240303")
    // HTTP 客户端（在线翻译/资源下载，已缓存于 Gradle 本地仓库）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
kotlin { jvmToolchain(17) }
compose.desktop {
    application {
        mainClass = "com.liquidglass.desktop.MainKt"
        nativeDistributions {
            // Windows 端仅产出 EXE（AppImage 为 Linux 格式，在 Windows runner 上会导致 jpackage 失败）
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "LiquidGlass"
            packageVersion = "2.9.2"
            description = "灵工坊 - 液态玻璃智能工具箱"
            vendor = "LiquidGlass"
            // 安装包向导：让 jpackage 生成自带快捷方式与启动菜单的 EXE/MSI
            // 注：jpackage 自带的安装界面相对简陋，
            //     应用内的 Beta 下载向导提供更完整的"快捷方式选项 + 实时文件列表 + 展开/隐藏"体验。
            windows {
                menuGroup = "LiquidGlass"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                // 让 jpackage 安装时创建桌面快捷方式（Windows 安装向导的"创建快捷方式"步骤由此驱动）
                shortcut = true
                // 安装时建议为当前用户安装（无需管理员权限）
                perUserInstall = true
            }
        }
    }
}
