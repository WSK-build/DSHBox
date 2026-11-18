# 静态链接 libc++：避免为本模块再引入 libc++_shared.so 依赖
# （宿主 App 已经有自己那份，两份同名 .so 会打架）。
APP_STL := c++_static
APP_PLATFORM := android-29
APP_ABI := arm64-v8a
