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
        versionCode = 6
        versionName = "1.3.0"

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
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bridge"))
    implementation(project(":sandbox-manager"))
    implementation(project(":terminal-session"))
    implementation(project(":terminal-view"))

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
    // 代码编辑核心（撤销/搜索/行号），LGPL-2.1-or-later 未修改 aar（THIRD_PARTY_NOTICES 已登记）
    implementation(libs.sora.editor)
    // Markdown 预览（Apache-2.0，THIRD_PARTY_NOTICES 已登记）
    implementation(libs.markwon.core)
    // Markdown 表格（GFM 扩展，ext-tables；Apache-2.0 同 markwon）
    implementation(libs.markwon.ext.tables)
    // XmlPullParser 仅测试期依赖（kxml2 供 JVM 单测；真机用平台自带实现——
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
