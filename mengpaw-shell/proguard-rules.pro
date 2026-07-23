# MengPaw Shell ProGuard Rules
# SECURITY: R8 enabled - keep critical runtime classes

# ── Kernel classes (com.mengpaw.kernel.**) ──
# Most core classes moved to kernel in v0.5.0 microkernel split

# Keep kernel core
-keep class com.mengpaw.kernel.DataPaths { *; }
-keep class com.mengpaw.kernel.AgentEngine { public *; }
-keep class com.mengpaw.kernel.AgentState { *; }
-keep class com.mengpaw.kernel.KernelLog { public *; }
-keep class com.mengpaw.kernel.MengPawVersion { *; }

# Keep CLI pipeline
-keep class com.mengpaw.kernel.cli.** { *; }

# Keep LLM provider interface (critical for runtime)
-keep class com.mengpaw.kernel.llm.LlmProvider { *; }
-keep class com.mengpaw.kernel.llm.ProviderInfo { *; }
-keep class com.mengpaw.kernel.llm.ProviderType { *; }
-keep class com.mengpaw.kernel.llm.AdaptiveLlmProvider { public *; }
-keep class com.mengpaw.kernel.llm.LlmRequestBuilder { public *; }
-keep class com.mengpaw.kernel.llm.PromptEngine { public *; }
-keep class com.mengpaw.kernel.llm.RemoteApi { public *; }
-keep class com.mengpaw.kernel.llm.TranslateMiddleware { public *; }

# Keep session (required for conversation persistence)
-keep class com.mengpaw.kernel.session.** { public *; }

# Keep plugin framework (required for reflection-based plugin loading)
-keep interface com.mengpaw.kernel.plugin.Plugin { *; }
-keep class * implements com.mengpaw.kernel.plugin.Plugin { *; }
-keep class com.mengpaw.kernel.plugin.PluginManager { public *; }
-keep class com.mengpaw.kernel.plugin.PluginExecutor { public *; }
-keep class com.mengpaw.kernel.plugin.PluginMarketplaceClient { public *; }
-keep class com.mengpaw.kernel.plugin.PluginContext { *; }
-keep class com.mengpaw.kernel.plugin.PluginMetadata { *; }
-keep class com.mengpaw.kernel.plugin.CommandHandler { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionContext { *; }
-keep class com.mengpaw.kernel.plugin.ExecutionResult { *; }
-keep class com.mengpaw.kernel.plugin.ErrorCodes { *; }
-keep class com.mengpaw.kernel.plugin.PluginType { *; }
-keep class com.mengpaw.kernel.plugin.PluginUiButton { *; }
-keep class com.mengpaw.kernel.plugin.ButtonPlacement { *; }

# Keep security
-keep class com.mengpaw.kernel.security.SecurityPolicy { public *; }
-keep class com.mengpaw.kernel.security.Sanitizer { public *; }
-keep class com.mengpaw.kernel.security.PromptFirewall { public *; }
-keep class com.mengpaw.kernel.security.IntegrityProvider { *; }

# Keep agent runtime
-keep class com.mengpaw.kernel.agent.** { public *; }

# Keep namespace executors
-keep class com.mengpaw.kernel.namespace.SelfExecutor { public *; }
-keep class com.mengpaw.kernel.namespace.NotifyBus { public *; }

# Keep other kernel subsystems
-keep class com.mengpaw.kernel.trigger.TriggerEngine { public *; }
-keep class com.mengpaw.kernel.acp.** { public *; }
-keep class com.mengpaw.kernel.mcp.** { public *; }
-keep class com.mengpaw.kernel.mission.MissionMonitor { public *; }
-keep class com.mengpaw.kernel.error.ErrorCollector { public *; }
-keep class com.mengpaw.kernel.extension.ManifestParser { public *; }

# ── Core classes (com.mengpaw.core.**) ──
# These remain in mengpaw-core (Android adapters)
-keep class com.mengpaw.core.security.Vault { *; }
-keep class com.mengpaw.core.security.IntegrityGuard { *; }
-keep class com.mengpaw.core.security.StorageMonitor { *; }
-keep class com.mengpaw.core.DataPathsInitializer { *; }
-keep class com.mengpaw.core.AndroidLogger { *; }
-keep class com.mengpaw.core.AgentTemplates { *; }
-keep class com.mengpaw.core.namespace.SysExecutor { public *; }

# ── Kotlin serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mengpaw.kernel.**$$serializer { *; }
-keep,includedescriptorclasses class com.mengpaw.core.**$$serializer { *; }
-keepclassmembers class com.mengpaw.kernel.** {
    *** Companion;
}
-keepclassmembers class com.mengpaw.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.mengpaw.kernel.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.mengpaw.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Android Security Crypto ──
-keep class androidx.security.crypto.** { *; }

# ── WebView JS bridge ──
-keepclassmembers class com.mengpaw.shell.ui.screens.ShellBrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

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
