plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.pluginmanager"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // DSHBox 自有插件（移动端适配包 / 连接垫片）的源码与载荷集中在 dshbox-plugins/，
    // 见该目录 README.md。其中 assets/ 是随 APK 交付的运行期载荷，而 Gradle 默认只认
    // src/main/assets —— 这里额外登记它。asset 合并以各 srcDir 为根，故打包后的路径
    // 仍是 dshbox/… 与 plugins/…，运行期读取代码无需改动。
    sourceSets.named("main") { assets.directories.add("dshbox-plugins/assets") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // 层读写要复用 DSH 接入层的路径约定（我们的 --patch 层落点）。
    implementation(project(":common"))
    // 一次性 guest 命令（安装/卸载/重启）与沙箱状态都经它。
    implementation(project(":sandbox-manager"))

    implementation(libs.androidx.core.ktx)
    // 整页覆盖的返回键处理（BackHandler）：与 app 内其它整页覆盖一致。
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    // 设备上用 Android 内置的 org.json；JVM 单测里那套是抛 not-mocked 的桩，
    // 因此给单测挂一份真实的实现，否则解析/落盘的用例会静默走默认值。
    testImplementation("org.json:json:20231013")
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// mobile-adapt 顶栏钉住的 standalone 行为测试（Gecko 钉住/还原、System 零动作、
// 开关联动、幂等与 composer 共存）。脚本仅依赖 Node 内置模块，毫秒级完成。
val mobileAdaptKeyboardSemantics = tasks.register<Exec>("testMobileAdaptKeyboardJavaScript") {
    group = "verification"
    inputs.files(
        "dshbox-plugins/assets/plugins/dsh-mobile-adapt/plugin/lib/client.js",
        "dshbox-plugins/tools/mobile-adapt/client-keyboard-gecko.test.cjs",
    )
    workingDir(projectDir)
    commandLine("node", "dshbox-plugins/tools/mobile-adapt/client-keyboard-gecko.test.cjs")
}

// mobile-adapt 设置开关行与返回键分层的 standalone 行为测试（会话日志行锚点
// 文案匹配与回退、注入幂等、点击翻转、面板关闭清理、返回键按原生状态分层：
// 原生抽屉 / 设置一级 / 设置二级页 / 无层放行）。同样只依赖 Node 内置模块。
val mobileAdaptSettingsSwitchBackSemantics =
    tasks.register<Exec>("testMobileAdaptSettingsSwitchBackJavaScript") {
        group = "verification"
        inputs.files(
            "dshbox-plugins/assets/plugins/dsh-mobile-adapt/plugin/lib/client.js",
            "dshbox-plugins/assets/plugins/dsh-mobile-adapt/plugin/package.json",
            "dshbox-plugins/tools/mobile-adapt/client-settings-switch-back.test.cjs",
        )
        workingDir(projectDir)
        commandLine("node", "dshbox-plugins/tools/mobile-adapt/client-settings-switch-back.test.cjs")
    }

tasks.matching { it.name.startsWith("testDebugUnitTest") }.configureEach {
    dependsOn(mobileAdaptKeyboardSemantics)
    dependsOn(mobileAdaptSettingsSwitchBackSemantics)
}
