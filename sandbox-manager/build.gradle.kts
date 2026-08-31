plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dshbox.app.sandbox"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    // 1.1.0 (M12.4)：XZ 解压需要 org.tukaani:xz（commons-compress 的 XZ 支持不内嵌该实现；
    // BZip2 为 commons-compress 自带，无需额外依赖）。
    implementation("org.tukaani:xz:1.9")
    // zstd-jni (Android AAR classes) so BundleManager can decompress zstd layers.
    // The arm64-v8a .so is packaged via app/src/main/jniLibs (see app build).
    implementation(files("$rootDir/libs/zstd-jni-1.5.7-15-classes.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
