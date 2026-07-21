# MengPaw Shell ProGuard Rules
# SECURITY: R8 enabled - keep critical runtime classes

# Keep core classes that use reflection
-keep class com.mengpaw.core.DataPaths { *; }
-keep class com.mengpaw.core.security.Vault { *; }
-keep class com.mengpaw.core.security.IntegrityGuard { *; }
-keep class com.mengpaw.core.cli.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mengpaw.core.**$$serializer { *; }
-keepclassmembers class com.mengpaw.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.mengpaw.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep plugin interface
-keep interface com.mengpaw.core.plugin.Plugin { *; }
-keep class * implements com.mengpaw.core.plugin.Plugin { *; }

# Keep Android Security Crypto
-keep class androidx.security.crypto.** { *; }

# Keep WebView JS bridge methods
-keepclassmembers class com.mengpaw.shell.ui.screens.ShellBrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Ktor HTTP client
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Compose — prevent R8 from stripping Compose runtime
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class kotlin.Metadata { *; }

# R8: Suppress warnings for compile-time-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Keep Tink crypto internals — required by EncryptedSharedPreferences
# Without these, R8 may strip classes needed for Keystore-backed decryption,
# causing data loss on app update (Vault appears empty).
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }