plugins {
    alias(libs.plugins.android.library)
}

// Termux terminal-emulator v0.118.0 (Apache-2.0).
// Sources are copied verbatim from termux/termux-app; only build-script
// values are adapted to the DSHBox project (compileSdk/minSdk/ABI/JDK,
// publishing removed). Do not modify anything under src/.
android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            ndkBuild {
                cFlags.addAll(
                    listOf(
                        "-std=c11",
                        "-Os",
                        "-fno-stack-protector",
                    ),
                )
                // 16KB page alignment is set via LOCAL_LDFLAGS in Android.mk:
                // Gradle's ndkBuild.cFlags only affects compilation, not linking.
            }
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
