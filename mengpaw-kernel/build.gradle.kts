// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.mengpaw"
version = "0.1.0"

// ── Generate MengPawVersion.kt from gradle.properties ──
val mengpawVersion: String = project.findProperty("mengpaw.version") as? String ?: "0.0.0"
val generatedDir = layout.buildDirectory.dir("generated/version")

sourceSets["main"].kotlin.srcDir(generatedDir)

tasks.register("generateVersion") {
    val outputDir = layout.buildDirectory.dir("generated/version/com/mengpaw/kernel")
    // Declare inputs so Gradle detects when version changes
    inputs.property("mengpawVersion", mengpawVersion)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        File(dir, "MengPawVersion.kt").writeText("""
            // AUTO-GENERATED from gradle.properties — do not edit
            package com.mengpaw.kernel

            object MengPawVersion {
                const val FRAMEWORK: String = "$mengpawVersion"
            }
        """.trimIndent())
    }
}

tasks.named("compileKotlin") { dependsOn("generateVersion") }

dependencies {
    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Ktor (HTTP client) - for LLM API calls and net plugin
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
