# ---------------------------------------------------------------------------
# BouncyCastle（bcprov/bcpg）：**算法实现是声明式注册的，必须整体保留**
#
# BC 的 provider 用「config 字符串 → 反射实例化 SPI」的方式注册算法表，R8 的静态分析
# 看不到这些引用，会把 org.bouncycastle.jcajce.provider.** 整片剥掉；被剥掉后运行期报：
#   cannot create signature: no such algorithm: SHA256withRSA for provider BC
#
# 后果：在线导入 Linux（精简 Debian）层时 `Release.gpg` 验签必失败 → 该功能在 release 包
# 上**从来不可用**（app 侧只是把它当成"镜像源都不可用"）。
#
# ⚠️ 这类缺陷 debug 包**测不出来**（debug 不做 R8 处理），所以发布前必须用 release 包
#    做一次在线导入冒烟；排查方法：直接扫 APK 的 dex 里有没有
#    `org.bouncycastle.jcajce.provider.asymmetric.rsa` 与字符串 `SHA256withRSA`。
#
# 只保留 jce/jcajce 的 provider 实现（crypto 等实现由可达性自动保留）；若将来新增对
# BC 其它入口的直接反射调用，需要把对应包一并加入。
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
