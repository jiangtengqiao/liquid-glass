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
            // v2.9.3：升级版本号，避免 jpackage 相同版本号触发 repair 模式不替换文件
            packageVersion = "2.9.3"
            description = "LiquidGlass - Liquid Glass Smart Toolbox"
            vendor = "LiquidGlass"
            windows {
                menuGroup = "LiquidGlass"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                // 开始菜单快捷方式
                shortcut = true
                // 当前用户安装（无需管理员权限）
                perUserInstall = true
                // 安装向导显示"创建桌面快捷方式"选项（jpackage Inno Setup 支持）
                // 配合应用内首次启动询问，双保险
            }
        }
    }
}
