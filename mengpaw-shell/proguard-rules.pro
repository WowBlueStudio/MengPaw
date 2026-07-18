# MengPaw Shell ProGuard Rules
-keepattributes *Annotation*

# Core microkernel API (tightened from com.mengpaw.**)
-keep class com.mengpaw.core.AgentEngine { public *; }
-keep class com.mengpaw.core.AgentState { *; }
-keep class com.mengpaw.core.llm.LlmProvider { *; }
-keep class com.mengpaw.core.llm.ProviderInfo { *; }
-keep class com.mengpaw.core.llm.ProviderType { *; }
-keep class com.mengpaw.core.plugin.** { *; }
-keep class com.mengpaw.core.cli.ExecutionResult { *; }
-keep class com.mengpaw.core.cli.ExecutionContext { *; }

# Design system (keep all)
-keep class com.mengpaw.design.** { *; }

# Shell classes (keep all)
-keep class com.mengpaw.shell.** { *; }

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
