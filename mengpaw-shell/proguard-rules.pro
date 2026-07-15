# MengPaw Shell ProGuard Rules
-keepattributes *Annotation*
-keep class com.mengpaw.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-keep class * extends androidx.compose.ui.platform.ComposeView { *; }

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# R8/Kotlin/Compose compatibility
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.ClassValue
-dontwarn kotlin.Result
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.parcelize.**
-dontwarn androidx.compose.**
