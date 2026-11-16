LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := npu-genie
LOCAL_SRC_FILES := npu_genie.c

# Android NDK 的 jni.h 已把 JNIEXPORT 定义为
# __attribute__((visibility("default")))，因此即使整体开 -fvisibility=hidden，
# JNI 入口依然对外可见 —— 不需要（也不应该）在这里 redefine 它。
LOCAL_CFLAGS := -std=c11 -O2 -fvisibility=hidden -Wall -Wextra

# 16 KB page-size 设备（Android 15+）上，loader 拒绝 dlopen 一个
# 4 KB 对齐的共享库。Gradle 的 ndkBuild.cFlags 只影响编译、不影响链接，
# 故对齐选项必须写在这里（与 terminal-emulator 模块同一处理）。
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384

include $(BUILD_SHARED_LIBRARY)
