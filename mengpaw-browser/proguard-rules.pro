# Keep JavascriptInterface bridge (used by WebView.addJavascriptInterface)
-keepclassmembers class com.mengpaw.browser.bridge.BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep all mengpaw-core classes (referenced by browser)
-keep class com.mengpaw.core.** { *; }
-dontwarn com.mengpaw.core.**

# Keep plugin classes
-keep class com.mengpaw.browser.plugin.** { *; }

# Google error-prone annotations (unused at runtime)
-dontwarn com.google.errorprone.annotations.**
