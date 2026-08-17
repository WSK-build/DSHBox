# DSH WebUI is served over local HTTP; keep WebView and JS bridge classes.
-keep class com.dshbox.app.bridge.** { *; }
-keepclassmembers class com.dshbox.app.bridge.** { *; }
