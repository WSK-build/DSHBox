LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# 16KB page-size devices (Android 15+): the loader refuses to dlopen a
# 4KB-aligned shared library. (Build-script adaptation only; termux.c is
# untouched. Note: Gradle cFlags do NOT reach the linker, hence this here.)
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
