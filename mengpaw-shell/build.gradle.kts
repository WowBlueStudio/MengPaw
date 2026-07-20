// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mengpaw.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mengpaw.shell"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
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
                        it.outputFileName = "mengpaw-shell-v${versionName}-debug.apk"
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
                        it.outputFileName = "mengpaw-shell-v${versionName}-release.apk"
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
    implementation(project(":mengpaw-core"))
    implementation(project(":mengpaw-design-system"))

    // Bundled plugins (pre-installed in base APK)
    implementation(project(":plugin-memory"))
    implementation(project(":plugin-skill"))
    implementation(project(":plugin-pad"))
    implementation(project(":plugin-dev"))

    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

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

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
