# MengPaw Core ProGuard Rules
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class com.mengpaw.core.** { *; }

# R8/Kotlin compatibility
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.ClassValue
-dontwarn kotlin.Result
-dontwarn org.jetbrains.annotations.**
