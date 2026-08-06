// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mengpaw.plugin.framework"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":mengpaw-kernel"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JVM 单测 (McpGateway 请求体上限 / FrameworkPeerStore 序列化)
    testImplementation("junit:junit:4.13.2")
    // Android 运行时用 SDK 内置 org.json；JVM 单测需要 maven 版（同 API）
    testImplementation("org.json:json:20240303")
}
