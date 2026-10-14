// `:backup` —— v5 备份归档的格式与预检（阶段 2.1）。
//
// 为什么是 Android library 而不是纯 `java-library`：
// 这里一行 Android API 都不用，本来更适合纯 JVM 模块。但本项目 CI 跑的是
// `./gradlew testDebugUnitTest` —— 纯 JVM 模块的任务叫 `:backup:test`，
// **不会被这个任务名匹配到**，于是这里的负面用例（截断/损坏/路径穿越）
// 会在 CI 里静默地一次都不执行。用 android-library 让它们搭上同一条命令，
// 属于「宁可多一个空壳模块，也不要一个不被执行的门禁」。
//
// 模块内的 Java 源码是 DSHA（MIT）的**逐字节原样**副本，包名保持
// `com.deepseekharness.app.*` 以便逐 blob 溯源与后续比对，见
// governance/dsha-imports.json 与 backup/README.md。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // 本模块不产出任何资源，namespace 只为满足 AGP；源码包名是 DSHA 原名，
    // 两者不同是**有意的**（见上）。不引用 R，所以不冲突。
    namespace = "com.dshbox.app.backup"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.gson)
    // 阶段 2.3：CredentialProjection 要按 DSH 自己的 `.credentials.yaml` 布局
    // 解析 / 删记录 / 再序列化回去，所以需要一个严格的 YAML 解析器。
    // 它随 APK 分发，许可 Apache-2.0（见根 THIRD_PARTY_NOTICES.md）。
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
}
