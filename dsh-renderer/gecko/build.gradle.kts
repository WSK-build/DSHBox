plugins {
    alias(libs.plugins.android.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.dshbox.dsh.renderer.gecko"
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

    // Fixed arm64-only release; the Mozilla repository is group-scoped in settings.gradle.kts.
    implementation(libs.geckoview.arm64)
}

// Keep the page-world compatibility implementation in the renderer API asset
// tree, and copy that single source into the built-in extension at build time.
abstract class CopyPageCompatAsset : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyAsset() {
        val targetDirectory = outputDirectory.get().dir("dsh-status-bridge").asFile
        targetDirectory.mkdirs()
        sourceFile.get().asFile.copyTo(
            targetDirectory.resolve("dsh-page-compat.js"),
            overwrite = true,
        )
    }
}

abstract class GeneratePageCompatConfig : DefaultTask() {
    @get:InputFile
    abstract val constantsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = constantsFile.get().asFile.readText()
        val host = Regex("const\\s+val\\s+DSH_DEFAULT_HOST\\s*=\\s*\"([^\"]+)\"")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: error("DSH_DEFAULT_HOST not found in Constants.kt")
        val port = Regex("const\\s+val\\s+DSH_DEFAULT_PORT\\s*=\\s*(\\d+)")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: error("DSH_DEFAULT_PORT not found in Constants.kt")
        val trustedOrigin = "http://$host:$port"
        val targetDirectory = outputDirectory.get().dir("dsh-status-bridge").asFile
        targetDirectory.mkdirs()
        targetDirectory.resolve("page-compat-config.js").writeText(
            """
            /*
             * Generated from common Constants.DSH_DEFAULT_HOST/PORT.
             * The shared page compatibility script verifies this exact origin.
             */
            (function () {
              'use strict';
              var key = Symbol.for('com.dshbox.renderer.dsh-page-compat-config.v1');
              Object.defineProperty(globalThis, key, {
                configurable: true,
                enumerable: false,
                writable: false,
                value: Object.freeze({
                  trustedOrigin: "$trustedOrigin"
                })
              });
            }());
            """.trimIndent() + "\n",
        )
    }
}

val copyPageCompatAsset = tasks.register<CopyPageCompatAsset>("copyPageCompatAsset") {
    sourceFile.set(rootProject.layout.projectDirectory.file(
        "dsh-renderer/api/src/main/assets/dsh-renderer/dsh-page-compat.js"
    ))
    outputDirectory.set(layout.buildDirectory.dir("generated/dshPageCompatAssets"))
}

val generatePageCompatConfig = tasks.register<GeneratePageCompatConfig>(
    "generatePageCompatConfig",
) {
    constantsFile.set(rootProject.layout.projectDirectory.file(
        "common/src/main/java/com/dshbox/app/common/Constants.kt"
    ))
    outputDirectory.set(layout.buildDirectory.dir("generated/dshPageCompatConfigAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyPageCompatAsset,
            CopyPageCompatAsset::outputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            generatePageCompatConfig,
            GeneratePageCompatConfig::outputDirectory,
        )
    }
}

val generatedPageCompatConfigFile =
    layout.buildDirectory.file("generated/dshPageCompatConfigAssets/dsh-status-bridge/page-compat-config.js")

val testStatusBridgeJavaScript = tasks.register<Exec>("testStatusBridgeJavaScript") {
    group = "verification"
    description = "Run Gecko status bridge Node behavior tests"
    dependsOn(generatePageCompatConfig)
    inputs.files(
        "src/main/assets/dsh-status-bridge/manifest.json",
        "src/main/assets/dsh-status-bridge/background.js",
        "src/main/assets/dsh-status-bridge/content.js",
        "src/test/js/gecko-status-bridge.test.cjs",
        generatedPageCompatConfigFile,
    )
    workingDir(projectDir)
    commandLine(
        "node",
        "src/test/js/gecko-status-bridge.test.cjs",
        generatedPageCompatConfigFile.get().asFile.absolutePath,
    )
}

tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }
    .configureEach {
        dependsOn(testStatusBridgeJavaScript)
    }

gradle.projectsEvaluated {
    rootProject.project(":app").tasks.matching {
        it.name == "testGeckoDebugUnitTest"
    }.configureEach {
        dependsOn(testStatusBridgeJavaScript)
    }
}
