# JNI 入口按名字绑定：native 方法在运行时由 libnpu-genie.so 解析，
# R8 不得重命名或裁剪它们。
-keepclasseswithmembernames class com.dshbox.app.npu.NpuNative {
    native <methods>;
}
