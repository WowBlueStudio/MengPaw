# MengPaw Shell ProGuard Rules

# Keep all mengpaw-core classes (includes new MissionMonitor, TranslateMiddleware, etc.)
-keep class com.mengpaw.core.** { *; }
-dontwarn com.mengpaw.core.**

# Keep all plugins
-keep class com.mengpaw.plugin.** { *; }
-dontwarn com.mengpaw.plugin.**

# Design system
-keep class com.mengpaw.design.** { *; }

# Shell classes
-keep class com.mengpaw.shell.** { *; }

# Kotlin/compose compatibility
-dontwarn com.google.errorprone.annotations.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn kotlin.Result
-dontwarn javax.annotation.**
