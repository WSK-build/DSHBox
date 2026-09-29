plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
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
    sourceSets["main"].assets.srcDir("dshbox-plugins/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

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
