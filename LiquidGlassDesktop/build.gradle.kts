plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}
repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}
dependencies {
    implementation(compose.desktop.currentOs)
    // KMPLiquidGlass - Compose Multiplatform 液态玻璃库（支持 Desktop）
    implementation("io.github.kashif-mehmood-km:backdrop:0.0.1-alpha02")
    // HTTP 客户端（公告拉取/日志上传/beta版本拉取）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(17) }
compose.desktop {
    application {
        mainClass = "com.liquidglass.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "LiquidGlass"
            packageVersion = "2.9.1"
            description = "灵工坊 - 液态玻璃智能工具箱"
            vendor = "LiquidGlass"
            windows {
                menuGroup = "LiquidGlass"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
