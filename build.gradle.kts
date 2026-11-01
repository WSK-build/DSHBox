plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val pinnedNdkVersion = "27.0.12077973"

subprojects {
    plugins.withId("com.android.library") {
        if (project.path == ":terminal-emulator") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                ndkVersion = pinnedNdkVersion
            }
        }
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
    }
}

// JVM test implementations must precede Android SDK stub classes.
gradle.projectsEvaluated {
    rootProject.subprojects.forEach { module ->
        if (module.plugins.hasPlugin("com.android.library") ||
            module.plugins.hasPlugin("com.android.application")
        ) {
            module.tasks.names.filter { it.endsWith("UnitTestKotlin") }.forEach { taskName ->
                module.tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(taskName) {
                    val sources = libraries.from.toList()
                    if (sources.size > 1) {
                        libraries.setFrom(sources.drop(1) + sources.first())
                    }
                }
            }
        }
    }
}
