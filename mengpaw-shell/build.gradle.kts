// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

val mengpawVersion: String = project.findProperty("mengpaw.version") as? String ?: "0.0.0"

android {
    namespace = "com.mengpaw.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpaw.shell"
        minSdk = 26
        targetSdk = 35
        versionCode = mengpawVersion.split(".").let { it[1].toInt() * 1000 + it[2].toInt() }
        versionName = mengpawVersion
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
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
                "proguard-common.pro"
            )
            isShrinkResources = false
        }
    }

    // Per-variant output naming. applicationVariants is resolved at android scope
    // and must be called from inside a buildTypes block to avoid Kotlin DSL
    // type-inference issues with Boolean-returning lambdas in AGP 8.x.
    // We put it in the debug block but filter by buildType.name to cover all variants.
    buildTypes {
        debug {
            applicationVariants.all {
                if (buildType.name == "debug") {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-shell-v${mengpawVersion}-debug.apk"
                        }
                    }
                } else {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                            it.outputFileName = "mengpaw-shell-v${mengpawVersion}-release.apk"
                        }
                    }
                }
            }
        }
    }

    // Release signing — loads keystore from local.properties at config time.
    // If keystore is missing, release builds still work (unsigned). Generate with:
    //   keytool -genkey -keystore mengpaw-release.jks -alias mengpaw -keyalg RSA -keysize 2048 -validity 10000
    // Then add to local.properties: keystore.file=mengpaw-release.jks, keystore.storepass=..., keystore.keypass=...
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Internal modules
    implementation(project(":mengpaw-kernel"))
    implementation(project(":mengpaw-core"))
    implementation(project(":mengpaw-design-system"))

    // Bundled plugins (pre-installed in base APK)
    implementation(project(":plugin-framework"))
    implementation(project(":plugin-skill"))
    implementation(project(":plugin-dev"))
    implementation(project(":plugin-net"))
    implementation(project(":plugin-clipboard"))
    implementation(project(":plugin-memory-twin"))
    implementation(project(":plugin-root"))
    implementation(project(":plugin-hermes"))
    implementation(project(":plugin-agent-tools"))
    implementation(project(":plugin-termux"))
    implementation(project(":plugin-dream"))
    implementation(project(":plugin-evolution"))
    implementation(project(":plugin-concise"))
    implementation(project(":plugin-tavily"))
    implementation(project(":plugin-update"))

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // WorkManager — Dream mode background scheduling
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing (JVM 单元测试 — 仅纯逻辑, 不引入 Robolectric/mockito)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
