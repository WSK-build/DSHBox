pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DSHapp"

include(":app")
include(":common")
include(":sandbox-manager")
include(":bridge")
include(":terminal-emulator")
include(":terminal-view")
include(":terminal-session")
