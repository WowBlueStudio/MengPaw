# MengPaw Core ProGuard Rules (Android adapters + consumer rules)
# Kernel classes are in com.mengpaw.kernel.**; core classes in com.mengpaw.core.**
# This file defines consumer rules for core AND kernel classes referenced via core.

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Microkernel public API (com.mengpaw.kernel.** — split from core in v0.5.0)
-keep class com.mengpaw.kernel.AgentEngine { public *; }
-keep class com.mengpaw.kernel.AgentState { *; }
-keep class com.mengpaw.kernel.cli.** { public *; }
-keep class com.mengpaw.kernel.llm.LlmProvider { *; }
-keep class com.mengpaw.kernel.llm.ProviderInfo { *; }
-keep class com.mengpaw.kernel.llm.ProviderType { *; }
-keep class com.mengpaw.kernel.plugin.** { *; }
-keep class com.mengpaw.kernel.security.SecurityPolicy { public *; }
-keep class com.mengpaw.kernel.security.Sanitizer { public *; }
-keep class com.mengpaw.kernel.security.IntegrityProvider { *; }
-keep class com.mengpaw.kernel.session.** { public *; }
-keep class com.mengpaw.kernel.namespace.SelfExecutor { public *; }
-keep class com.mengpaw.kernel.DataPaths { *; }
-keep class com.mengpaw.kernel.KernelLog { public *; }

# Android-specific core adapters (com.mengpaw.core.**)
-keep class com.mengpaw.core.DataPathsInitializer { *; }
-keep class com.mengpaw.core.AndroidLogger { *; }
-keep class com.mengpaw.core.AgentTemplates { *; }
-keep class com.mengpaw.core.security.Vault { public *; }
-keep class com.mengpaw.core.security.IntegrityGuard { public *; }
-keep class com.mengpaw.core.security.StorageMonitor { public *; }
-keep class com.mengpaw.core.namespace.SysExecutor { public *; }

# R8/Kotlin compatibility
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.ClassValue
-dontwarn kotlin.Result
-dontwarn org.jetbrains.annotations.**
