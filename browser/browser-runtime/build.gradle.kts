plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dshbox.browser.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":browser:browser-api"))
    implementation(project(":browser:browser-devtools"))
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    // Android's JVM unit-test jar contains throwing org.json stubs; use the same
    // real JSON implementation as browser-bridge contract tests, test scope only.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(project(":browser:browser-bridge"))
    androidTestImplementation("androidx.test:runner:1.6.2")
}
