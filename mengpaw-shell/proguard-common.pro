# MengPaw Common ProGuard Rules — shared by shell & browser modules
# Included via proguardFiles() in each module's build.gradle.kts

# ── Common: Kernel classes (com.mengpaw.kernel.**) ──
-keep class com.mengpaw.kernel.DataPaths { *; }
-keep class com.mengpaw.kernel.KernelLog { public *; }
-keep class com.mengpaw.kernel.cli.** { *; }
-keep class com.mengpaw.kernel.llm.LlmProvider { *; }
-keep interface com.mengpaw.kernel.plugin.Plugin { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionContext { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionResult { *; }
-keep class com.mengpaw.kernel.plugin.ErrorCodes { *; }
-keep class com.mengpaw.kernel.security.SecurityPolicy { public *; }
-keep class com.mengpaw.kernel.security.Sanitizer { public *; }

# ── Common: Core classes (com.mengpaw.core.**) ──
-keep class com.mengpaw.core.security.Vault { *; }
-keep class com.mengpaw.core.security.IntegrityGuard { *; }
-keep class com.mengpaw.core.DataPathsInitializer { *; }

# ── Common: Kotlin serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mengpaw.kernel.**$$serializer { *; }
-keep,includedescriptorclasses class com.mengpaw.core.**$$serializer { *; }

# ── Common: Android Security Crypto ──
-keep class androidx.security.crypto.** { *; }

# ── Common: Ktor HTTP client ──
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Common: Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class kotlin.Metadata { *; }

# ── Common: R8 suppress warnings ──
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ── Common: Tink crypto (required by EncryptedSharedPreferences) ──
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }
