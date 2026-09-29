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


# ---------------------------------------------------------------------------
# BouncyCastle（bcprov/bcpg）：**算法实现必须整体保留，否则 release 包必挂**
#
# BC 的 provider 用「config 字符串 → 反射实例化 SPI」注册算法表，R8 的静态分析看不到这些
# 引用，会把 org.bouncycastle.jcajce.provider.** 整片剥掉；被剥掉后运行期报：
#   cannot create signature: no such algorithm: SHA256withRSA for provider BC
# 后果：在线导入 Linux（精简 Debian）层时 `Release.gpg` 验签必失败 —— 该功能在 release 包上
# **从来不可用**（App 只会显示"镜像源都不可用"）。
#
# ⚠️ 这类缺陷 debug 包测不出来（debug 不做 R8 处理），发布前必须用 release 包做一次在线导入冒烟。
#    排查手法：直接扫 APK 里的 dex 有没有 `org/bouncycastle/jcajce/provider` 与 `SHA256withRSA`。
#
# 注：sandbox-manager 的 consumer-rules.pro 里也写了同样的规则，但**没有进入合并后的
#    R8 配置**（configuration.txt 里查不到），故在 app 这边再落一遍（app 的规则确定生效，
#    同文件的 zstd / termux 规则即由这里生效）。
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
