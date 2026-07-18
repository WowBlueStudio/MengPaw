// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android { namespace = "com.mengpaw.plugin.render"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }
dependencies { implementation(project(":mengpaw-core")); implementation("io.ktor:ktor-client-core:3.0.3"); implementation("io.ktor:ktor-client-okhttp:3.0.3"); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") }
