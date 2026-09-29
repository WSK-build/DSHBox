plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Coordinates of the published core library. The version line is independent of any
// consuming application's own version: this library is consumable on its own.
group = "interlock.relay"
version = "0.1.0"

android {
    namespace = "interlock.relay.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku client: coordinates live on Maven Central and are not tracked in the
    // version catalog; the library version line is independent of the Shizuku app release.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(libs.junit)
    // A few suites assert on org.json shapes; the android.jar stubs throw on the JVM,
    // so a real implementation is needed for those assertions to run in CI.
    testImplementation("org.json:json:20240303")
}
