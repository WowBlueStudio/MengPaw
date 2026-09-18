// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

// 单一事实源: 与 gradle.properties 的 mengpaw.version 对齐, JitPack 发布时以 git tag 覆盖
val publishGroup: String = providers.gradleProperty("mengpaw.group").orElse("com.github.WowBlueStudio.MengPaw").get()
group = publishGroup
version = providers.gradleProperty("mengpaw.version").orElse("0.1.0").get()

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// ── Generate MengPawVersion.kt from gradle.properties ──
val mengpawVersion: String = project.findProperty("mengpaw.version") as? String ?: "0.0.0"
val generatedDir = layout.buildDirectory.dir("generated/version")

sourceSets["main"].kotlin.srcDir(generatedDir)

val genVersionTask = tasks.register("generateVersion") {
    // Use Provider-based inputs for configuration-cache compatibility
    val versionProvider: Provider<String> = providers.gradleProperty("mengpaw.version").orElse("0.0.0")
    val outputDir = layout.buildDirectory.dir("generated/version/com/mengpaw/kernel")
    inputs.property("mengpawVersion", versionProvider)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        File(dir, "MengPawVersion.kt").writeText("""
            // AUTO-GENERATED from gradle.properties — do not edit
            package com.mengpaw.kernel

            object MengPawVersion {
                const val FRAMEWORK: String = "${versionProvider.get()}"
            }
        """.trimIndent())
    }
}

tasks.named("compileKotlin") { dependsOn(genVersionTask) }

dependencies {
    // ── 跨平台 Harness 核心 (独立仓库, 经 settings.gradle.kts 的 includeBuild 共享源码) ──
    // 提供: 平台抽象层 (com.mengpaw.harness.*) + LLM 模型层与 ReAct 解析层
    // (com.mengpaw.kernel.llm.* / .session.* / KernelLog)。
    // 这些类此前内联在本模块, 现归属 harness 仓库 — 单一事实源, 不再双份维护。
    // 用 api 而非 implementation: 插件只声明 `implementation(project(":mengpaw-kernel"))`,
    // 而 SkillPlugin/MemoryTwinPlugin 等需要 LlmProvider 等类型 — implementation 不传递,
    // 插件模块会 Unresolved reference 'LlmProvider' (plugin-skill 编译中断实测)。
    api("com.github.WowBlueStudio:MengPaw-harness")

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
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
}
