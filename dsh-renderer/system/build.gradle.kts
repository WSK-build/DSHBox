plugins {
    alias(libs.plugins.android.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.dsh.renderer.system"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":dsh-renderer:api"))
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
}

val compatibilitySemantics = tasks.register<Exec>("testCompatibilityJavaScript") {
    group = "verification"
    inputs.files("src/main/assets/dsh-compat/standard-bootstrap.js",
        "src/main/assets/dsh-compat/worker-capability-probe.js",
        "src/test/js/standard-bootstrap.test.cjs",
        "src/test/js/dsh-page-compat.test.cjs",
        "src/test/js/system-renderer-contract.test.cjs",
        "src/test/js/worker-capability-probe.test.cjs",
        "src/test/js/test-compatibility.cjs",
        "../api/src/main/assets/dsh-renderer/capability-probe.js",
        "../api/src/main/assets/dsh-renderer/dsh-page-compat.js",
        "../gecko/src/main/assets/dsh-status-bridge/manifest.json",
        "../gecko/build.gradle.kts",
        "../../app/src/main/java/com/dshbox/app/ui/webview/DshWebViewScreen.kt",
        "../../common/src/main/java/com/dshbox/app/common/Constants.kt")
    workingDir(projectDir)
    commandLine("node", "src/test/js/test-compatibility.cjs")
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(compatibilitySemantics)
}
