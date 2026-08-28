// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

// 单一事实源: 与 gradle.properties 的 mengpaw.version 对齐, JitPack 发布时以 git tag 覆盖
val publishGroup: String = providers.gradleProperty("mengpaw.group").orElse("com.github.WowBlueStudio.MengPaw").get()
group = publishGroup
version = providers.gradleProperty("mengpaw.version").orElse("0.0.0").get()

android {
    namespace = "com.mengpaw.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // Library module — let app module handle minification
        }
    }
}

// Android library: 发布 release AAR 变体
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "mengpaw-core"
            }
        }
    }
}

dependencies {
    // Microkernel (pure Kotlin — CLI, LLM, session, plugin framework, security)
    implementation(project(":mengpaw-kernel"))

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Coroutines (Android flavor for Vault and platform code)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Encrypted SharedPreferences for secure API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.13.1")

    // Testing (JVM 单元测试 — 仅纯逻辑, 不引入 Robolectric/mockito)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
