# 开发者模式 ADB 运行时资产清单
#
# 来源：Debian 官方归档（deb.debian.org），arm64 架构。
# 用途：随 APK 分发，在设备侧解包进运行环境层，为沙箱提供完整 ADB 客户端。
#
# 校验：本清单的 SHA-256 为分发时的实测值，构建期据此核对资产完整性。
# 依赖闭包：adb 的动态库依赖（libbase/libcutils/liblog/libziparchive/libcrypto/libssl/
# libprotobuf/libusb/libfmt）不在运行环境层内，故一并随包分发。

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `adb_35.0.2-1~exp7_arm64.deb` | 346208 | `676d5943a0d962e3111dc175c589706fb33c3a28f98f30a3e7ce54894dc7bac3` |
| `android-libbase_35.0.2-1~exp7_arm64.deb` | 46260 | `f1f841f39077eb5d56042a1b7afac04bb5b9ef6a72493cfe0cfd6102bf03f302` |
| `android-libboringssl_14.0.0+r45-3+b2_arm64.deb` | 566684 | `d91e99628c49781f53277a5ba06ae3e6f7b1d5b1bb46661762bb7fd3ee7edf64` |
| `android-libcutils_35.0.2-1~exp7_arm64.deb` | 36560 | `184cde9cd3f6847ac4b7b89035d64212d7a403b4d274defc1a72e9be7f3e365d` |
| `android-liblog_35.0.2-1~exp7_arm64.deb` | 35744 | `92a953120f9406251aeae466ec2f12bd8fdfea930e1b0507f2bb8c6fa42247f4` |
| `android-libziparchive_35.0.2-1~exp7_arm64.deb` | 44936 | `2fd774fa3c57ed44539a9b79e9846e0dd728b4d2c7d97e3f9d9b4de6f2032d65` |
| `libfmt11_11.1.1+ds1-2_arm64.deb` | 82136 | `d9522c2a9873b6836cf6d5105f444b137341524727d370756410a574c0d3a2c1` |
| `libprotobuf32t64_3.21.12-17_arm64.deb` | 890348 | `12cf22c111f13078a460f4824905df6727834b4631d87bb23e8f929a28040020` |
| `libusb-1.0-0_1.0.30-1_arm64.deb` | 56724 | `b6462753bc435306e2f99349f54cacf26b2a4c49d64f4888e52dc8d2a40394bd` |

合计 2105600 字节。
