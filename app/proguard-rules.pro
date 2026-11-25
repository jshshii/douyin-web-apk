# Keep WebView bridge/JS interface members if any are added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# androidx.activity dispatcher is reflective in parts.
-keep class androidx.activity.** { *; }