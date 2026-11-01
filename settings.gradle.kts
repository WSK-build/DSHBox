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
        maven("https://maven.mozilla.org/maven2/") {
            content {
                includeGroup("org.mozilla.geckoview")
            }
        }
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
// 开发者模式：设备级提权能力（ADB 通道与 Root 通道）的独立实现与面板。
include(":pilot:developer-access")
include(":browser:browser-api")
include(":browser:browser-runtime")
include(":browser:browser-bridge")
include(":browser:browser-devtools")
include(":dsh-renderer:api")
include(":dsh-renderer:system")
include(":dsh-renderer:gecko")
