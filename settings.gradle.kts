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
include(":plugin-manager")
include(":pilot")
// `:pilot` 是手机助手的模块容器：核心 interlock-relay-core（可独立发布的平台层）与
// dshbox 适配层 dshbox-adapter 分列其下；模块目录在源码树的 pilot/ 下。
include(":pilot:interlock-relay-core")
include(":pilot:dshbox-adapter")
