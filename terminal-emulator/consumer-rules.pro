# Keep JNI entry points: native method names are bound at runtime by name
# through libtermux.so, so R8 must not rename or strip them.
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}
