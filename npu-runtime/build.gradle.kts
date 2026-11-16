plugins {
    alias(libs.plugins.android.library)
}

/*
 * 端侧 NPU 推理模块（Hexagon HTP / QNN / Genie）。
 *
 * 分层：
 *  - native 层（npu_genie.c）：dlopen 厂商运行时并直接调 Genie C API。
 *    **不打包任何厂商二进制**——libcdsprpc.so 在设备的
 *    /vendor/etc/public.libraries.txt 里，打进 APK 会遮蔽真库并引入坏依赖
 *    （实测 dlopen "libvndksupport.so" 失败）；其余 QNN/Genie 库体积大且受
 *    各自许可约束，随 APK 分发需另行确认。因此全部走 dlopen(soname)，
 *    由系统 linker namespace 解析到设备上的那一份。
 *  - Kotlin 层：配置生成、前置检查、独立进程 Service、看门狗。
 *
 * 独立进程（android:process=":npu"）是**必需**的，不是可选优化：
 * DSP 会话独占，且 GenieDialog_create 存在实测可复现的永久卡死；
 * 唯一恢复手段是杀掉持有会话的进程。
 */
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.app.npu"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // 本模块只用 framework API（Context/Service/ActivityManager…），无第三方依赖。

    testImplementation(libs.junit)
    // android.jar 里的 org.json 是空壳，单元测试需要真实实现。
    // 不加这条，JSON 解析相关断言会以 "not mocked" 失败。
    testImplementation("org.json:json:20240303")
}
