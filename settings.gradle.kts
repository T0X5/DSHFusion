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
// `:backup` 承载 v5 备份归档的格式与预检（阶段 2.1）。它一行 Android API 都不用，
// 之所以建成 android-library 而不是 java-library，是为了让它的单测被 CI 的
// `./gradlew testDebugUnitTest` 匹配到 —— 纯 JVM 模块的任务名是 `:backup:test`，
// 会被那条命令漏掉，负面用例就等于没跑。见 backup/build.gradle.kts 顶部说明。
include(":backup")
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
