# MengPaw Core ProGuard Rules (Microkernel)
# Only keep the public microkernel API — unused namespace/memory/skill/extension
# classes will be stripped by R8 when not referenced from shell.

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Microkernel public API (tightened from com.mengpaw.core.**)
-keep class com.mengpaw.core.AgentEngine { public *; }
-keep class com.mengpaw.core.AgentState { *; }
-keep class com.mengpaw.core.cli.** { public *; }
-keep class com.mengpaw.core.llm.LlmProvider { *; }
-keep class com.mengpaw.core.llm.ProviderInfo { *; }
-keep class com.mengpaw.core.llm.ProviderType { *; }
-keep class com.mengpaw.core.plugin.** { *; }
-keep class com.mengpaw.core.security.** { public *; }
-keep class com.mengpaw.core.session.** { public *; }
-keep class com.mengpaw.core.namespace.SelfExecutor { public *; }

# R8/Kotlin compatibility
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.ClassValue
-dontwarn kotlin.Result
-dontwarn org.jetbrains.annotations.**
