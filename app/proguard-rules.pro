# The YouTube player page calls back into the app through these.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
