plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
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
                // 原生库由 scripts/build-llama-htp.sh 产出到 jniLibs/，
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
    kotlinOptions { jvmTarget = "17" }
}

// 本模块不依赖 :common —— JNI + Kotlin 都是自包含的，减少与宿主的耦合。
