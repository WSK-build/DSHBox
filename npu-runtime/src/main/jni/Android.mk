LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := npu-llama
LOCAL_SRC_FILES := npu_llama.cpp

# 只依赖自己的 JNI 边界；llama.cpp 通过 dlopen 使用，避免把 40+ MB 的
# libllama.so 变成硬链接依赖（没装模型的设备也能正常启动 App）。
# llama.h 等头文件随仓库提交（体积小），保证没有 .so 时模块也能编译通过
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../cpp/include
LOCAL_CPPFLAGS  := -std=c++17 -O2 -fvisibility=hidden -Wall -Wextra
LOCAL_LDFLAGS   := -Wl,-z,max-page-size=16384
LOCAL_LDLIBS    := -llog -ldl

include $(BUILD_SHARED_LIBRARY)
