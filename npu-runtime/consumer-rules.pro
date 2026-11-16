# NPU native 入口：JNI 符号名形如 Java_com_dshbox_app_npu_NpuNative_nativeRun，
# 由 libnpu-genie.so 在运行时按名字绑定。R8 一旦重命名类或裁剪 native 方法，
# 绑定就会失败（UnsatisfiedLinkError），而本模块用 ensureLoaded() 把这类失败
# 降级成「NPU 不可用」——**release 包上会静默退化成 CPU 路径，debug 包测不出来**。
#
# includedescriptorclasses 一并保留参数类型的名字（jstring 等无影响，
# 但习惯上与 Android 官方推荐写法一致）。
-keepclasseswithmembernames,includedescriptorclasses class com.dshbox.app.npu.NpuNative {
    native <methods>;
}
