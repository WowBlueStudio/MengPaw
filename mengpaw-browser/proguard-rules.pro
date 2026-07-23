# MengPaw Browser ProGuard Rules
# SECURITY: R8 enabled - keep critical runtime classes

# ── Kernel classes (com.mengpaw.kernel.**) ──
-keep class com.mengpaw.kernel.DataPaths { *; }
-keep class com.mengpaw.kernel.KernelLog { public *; }
-keep class com.mengpaw.kernel.cli.** { *; }
-keep class com.mengpaw.kernel.llm.LlmProvider { *; }
-keep class com.mengpaw.kernel.plugin.Plugin { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionContext { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionResult { *; }
-keep class com.mengpaw.kernel.plugin.ErrorCodes { *; }
-keep class com.mengpaw.kernel.security.SecurityPolicy { public *; }
-keep class com.mengpaw.kernel.security.Sanitizer { public *; }

# ── Core classes (com.mengpaw.core.**) ──
-keep class com.mengpaw.core.security.Vault { *; }
-keep class com.mengpaw.core.security.IntegrityGuard { *; }
-keep class com.mengpaw.core.DataPathsInitializer { *; }

# ── Kotlin serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mengpaw.kernel.**$$serializer { *; }
-keep,includedescriptorclasses class com.mengpaw.core.**$$serializer { *; }

# ── WebView JS bridge ──
-keepclassmembers class com.mengpaw.browser.bridge.BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Android Security Crypto ──
-keep class androidx.security.crypto.** { *; }

# ── Ktor HTTP client ──
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ── Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class kotlin.Metadata { *; }

# ── R8: suppress warnings ──
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ── Tink crypto (required by EncryptedSharedPreferences) ──
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }
