plugins {
    // 不要加 id("org.jetbrains.kotlin.android")：上游是 AGP 9.x，Kotlin 已内置。
    // 再叠一个独立 Kotlin 插件会直接让配置期失败：
    //   AgpWithBuiltInKotlinAppliedCheck ...
    // 照抄上游 common/build.gradle.kts 的写法即可（library + 顶层 kotlin{}）。
    alias(libs.plugins.android.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.app.npu"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk {
            // 只给真机架构编；x86_64 模拟器没有 DSP，编了也用不上
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            ndkBuild {
                // 原生库由 scripts/build-llama-htp.sh 产出（或 downloadNpuLibs 下载），
                // 这里只编我们自己的 JNI 桥。
                arguments += "-j4"
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildFeatures { buildConfig = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 的内置 Kotlin 用顶层 kotlin{} 设 jvmTarget，kotlinOptions{} 已经不用了
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
