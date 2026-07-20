# MengPaw Browser ProGuard Rules
# SECURITY: R8 enabled - keep critical runtime classes

# Keep core classes that use reflection
-keep class com.mengpaw.core.DataPaths { *; }
-keep class com.mengpaw.core.security.Vault { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep WebView JS bridge methods
-keepclassmembers class com.mengpaw.browser.bridge.BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Android Security Crypto
-keep class androidx.security.crypto.** { *; }

# Keep Ktor HTTP client
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Compose — prevent R8 from stripping Compose runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class kotlin.Metadata { *; }

# R8: Suppress warnings for compile-time-only annotations (referenced by tink/security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**