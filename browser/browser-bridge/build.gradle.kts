plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dshbox.browser.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
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
    implementation(project(":browser:browser-api"))
    testImplementation(libs.junit)
    // Real JSON parsing for JVM contract tests; Android's unit-test jar contains throwing stubs.
    testImplementation("org.json:json:20240303")
}
