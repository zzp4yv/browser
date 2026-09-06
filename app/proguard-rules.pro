# Keep WebView JavaScript interface methods
-keepclassmembers class com.tvbrowser.app.* {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
