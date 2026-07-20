// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mengpaw.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpaw.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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

    buildTypes {
        debug {
            applicationVariants.all {
                outputs.all {
                    (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                        it.outputFileName = "mengpaw-browser-v${versionName}-debug.apk"
                    }
                }
            }
        }
        release {
            isMinifyEnabled = false  // TODO: enable after ProGuard rules fully verified
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationVariants.all {
                outputs.all {
                    (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.let {
                        it.outputFileName = "mengpaw-browser-v${versionName}-release.apk"
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
    implementation(project(":mengpaw-design-system"))
    implementation(project(":mengpaw-core"))

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
