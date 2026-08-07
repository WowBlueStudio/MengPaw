// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mengpawVersion: String = project.findProperty("mengpaw.version") as? String ?: "0.0.0"

// 浏览器独立版本节奏 (不跟随主项目 mengpaw.version) — 单点数据源, 版本迭代只改这里
val browserVersion: String = "0.7.3"

android {
    namespace = "com.mengpaw.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpaw.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = browserVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Debug: no minification, fast builds
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "../mengpaw-shell/proguard-common.pro"
            )
            isShrinkResources = false
        }
    }

    // Per-variant output naming. Must be inside buildTypes to avoid Kotlin DSL
    // type-inference issues with Boolean-returning lambdas in AGP 8.x.
    buildTypes {
        debug {
            applicationVariants.all {
                if (buildType.name == "debug") {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-browser-v${browserVersion}-debug.apk"
                        }
                    }
                } else {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-browser-v${browserVersion}-release.apk"
                        }
                    }
                }
            }
        }
    }

    val keystoreFile = project.findProperty("keystore.file") as? String ?: "mengpaw-release.jks"
    val keystoreStorePass = project.findProperty("keystore.storepass") as? String ?: ""
    val keystoreKeyPass = project.findProperty("keystore.keypass") as? String ?: ""
    val releaseKeystoreFile = rootProject.file(keystoreFile)
    if (releaseKeystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreStorePass
                keyAlias = "mengpaw"
                keyPassword = keystoreKeyPass
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    // Apply same theme as main app
    implementation(project(":mengpaw-kernel"))
    implementation(project(":mengpaw-design-system"))
    implementation(project(":mengpaw-core"))

    // Kotlin
    // CommonMark md→HTML 渲染 (与 design-system 同版本; design-system 以 implementation 声明不传递)
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
