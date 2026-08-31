// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mengpaw.plugin.office"
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
}

dependencies {
    implementation(project(":mengpaw-kernel"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Apache POI — Word(docx)/Excel(xlsx)/PowerPoint(pptx) 读写引擎 (Maven Central)。
    // 5.4.1: 含 CVE-2025-31672 修复, Android(minSdk 26) 上 OOXML 文本/单元格/段落正常。
    // poi-ooxml 5.x 自动依赖 poi-ooxml-lite (精简 schemas, ~6MB); java.awt 三铁律见 OfficePlugin。
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("org.apache.xmlbeans:xmlbeans:5.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
