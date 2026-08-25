# DSH WebUI is served over local HTTP; keep WebView and JS bridge classes.
-keep class com.dshbox.app.bridge.** { *; }
-keepclassmembers class com.dshbox.app.bridge.** { *; }

# Termux terminal-emulator: native methods are bound by name to libtermux.so.
# (Also shipped as consumer rules in the :terminal-emulator module.)
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# zstd-jni: native methods + the private long srcPos/dstPos bookkeeping fields of
# ZstdInputStream(NoFinalizer) are read by name from libzstd-jni-*.so via JNI
# Get/SetLongField. R8 renaming or removing these fields under release minify
# produces NoSuchFieldError: no "J" field "srcPos". Keep the whole zstd-jni tree
# intact (classes, methods, fields, signatures) so the native binding stays valid.
-keep class com.github.luben.zstd.** { *; }
-keepclasseswithmembernames class com.github.luben.zstd.** {
    native <methods>;
}
