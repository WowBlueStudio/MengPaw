// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.mengpaw.plugin.connector.common"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }
dependencies {
    implementation(project(":mengpaw-kernel"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // jsch (MIT) — SSH 传输, api 暴露给连接器插件 (qwenpaw ACP 通道直接使用)
    api("com.github.mwiede:jsch:0.2.26")
    // 单测: org.json 真实实现 (android.jar 中为 stub, "Method not mocked")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
