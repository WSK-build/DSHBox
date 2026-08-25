plugins {
    alias(libs.plugins.android.library)
}

// Termux terminal-view v0.118.0 (Apache-2.0).
// Sources are copied verbatim from termux/termux-app; only build-script
// values are adapted to the DSHBox project (compileSdk/minSdk/JDK,
// publishing removed). Do not modify anything under src/.
android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation(libs.androidx.annotation)
}
