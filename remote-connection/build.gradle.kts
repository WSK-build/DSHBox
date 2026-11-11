plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.remote"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // JVM 单测里 android.util.Log（下载分支的落盘日志）等框架调用按默认值
        // 处理而非抛「not mocked」：代理的下载日志不阻塞纯 JVM 集成测试。
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // 显示槽在后续阶段经渲染器 API 创建远程会话的渲染器；适配器实例由宿主 app 注入。
    implementation(project(":dsh-renderer:api"))
    // 图标与主题资源复用。
    implementation(project(":common"))

    implementation(libs.androidx.core.ktx)
    // 扫码器（CameraX 预览与帧分析）与二维码解码（zxing core，目录既有条目）
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // 渲染器宿主的视图树生命周期 owner / 观察者（findViewTreeLifecycleOwner）。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // 会话表面的返回接管（BackHandler）：复用版本目录既有条目，非新增外部依赖。
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
