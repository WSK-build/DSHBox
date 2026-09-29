import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.dshbox.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // 显式开启 v3：minSdk 29 下 v2 就够装，但 v3 才能支持**密钥轮换**，
            // 且上架校验工具链（apksigner / 商店）普遍期望 v2+v3 同时存在。
            // 只签 v2 时 apksigner 会报 v1=false v3=false，故此处补齐。
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseKeystore) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.dshbox.app"
        minSdk = 29
        targetSdk = 36
        // 多语言版本：联合国六语 + 语言选择器 + 硬编码清零 + 布局恒 LTR + i18n 门禁。
        versionCode = 8
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUGGING", "false")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // 运行环境大层（base/node/android-side/dsh）不进入源码仓库，单独放在发布目录
            // runtime/android-assets（runtime/ 与 dsh/ 子目录），保证打包后仍是 assets/runtime/*
            // 与 assets/dsh/* 路径。仓库单独 clone 时请先获取 runtime/。
            assets.srcDirs("../../runtime/android-assets")
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle（bcprov/bcutil/bcpg 三个 jar 各自带一份同名文件）会在这里撞车：
        // "3 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'"，
        // 缺这一行整个 assemble 阶段就过不去（debug/release 都一样）。
        // 该文件是给 OSGi 用的，APK 里不需要。仅打包管道层面的排除，不改任何功能代码。
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bridge"))
    implementation(project(":sandbox-manager"))
    implementation(project(":terminal-session"))
    implementation(project(":terminal-view"))
    implementation(project(":plugin-manager"))
    // 手机助手适配层。核心层（interlock-relay-core）由它 api 传递，故此处不必再写一行。
    implementation(project(":pilot:dshbox-adapter"))

    implementation(libs.androidx.core.ktx)
    // 应用内语言切换（AppCompatDelegate.setApplicationLocales；
    // API<33 走 appcompat 持久化 + Activity 重建，API 33+ 委托系统 LocaleManager）
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
    implementation(libs.commons.compress)
    // 代码编辑核心（撤销/搜索/行号），LGPL-2.1-or-later 未修改 aar
    implementation(libs.sora.editor)
    // Markdown 预览（Apache-2.0）
    implementation(libs.markwon.core)
    // Markdown 表格（GFM 扩展，ext-tables；Apache-2.0 同 markwon）
    implementation(libs.markwon.ext.tables)
    // XmlPullParser 仅测试期依赖（kxml2 供 JVM 单测；设备上走平台自带实现——
    // 若随 APK 打包会与平台库类冲突致 R8 失败，且徒增体积）
    testImplementation(libs.kxml2)
    // tar.zst 条目枚举（zstd-jni classes；arm64 .so 已在 jniLibs，零下载）
    implementation(files("$rootDir/libs/zstd-jni-1.5.7-15-classes.jar"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 注：`:pilot` 摘除后，ABI 过滤脚本（原 pilot/gradle/pilot-abi.gradle，动机来自 OpenCV/ML Kit 的
// 冗余 ABI）一并移除，恢复 AGP 默认全 ABI 打包。若将来重新引入多 ABI 依赖，按需再挂。

