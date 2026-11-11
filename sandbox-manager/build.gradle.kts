plugins {
    alias(libs.plugins.android.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.app.sandbox"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            // 启动确认门的代次时序单测直接实例化 DefaultSandboxManager，其中
            // android.util.Log 在 JVM 上没有真实现：未打桩方法返回默认值而不是
            // 抛出「not mocked」。仅影响测试运行时，不改变生产行为。
            isReturnDefaultValues = true
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
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    // Debian Release.gpg OpenPGP 验签（在线导入的信任链加固）。
    implementation(libs.bcpg)
    // XZ 解压需要 org.tukaani:xz（commons-compress 的 XZ 支持不内嵌该实现；
    // BZip2 为 commons-compress 自带，无需额外依赖）。
    implementation("org.tukaani:xz:1.9")
    // zstd-jni (Android AAR classes) so BundleManager can decompress zstd layers.
    // The arm64-v8a .so is packaged via app/src/main/jniLibs (see app build).
    implementation(files("$rootDir/libs/zstd-jni-1.5.7-15-classes.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
