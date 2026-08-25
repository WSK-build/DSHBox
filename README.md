# DSHBox — DeepSeek Harness Android Runtime

DSHBox 是在 Android（ARM64）上运行 **DeepSeek Harness（DSH）** 的完整运行环境应用。
它把 Debian 根文件系统 + Node.js + DSH（DeepSeek Agent Runtime）打包成分层运行环境，
用 **PRoot** 做用户态 Linux 沙箱（无需 root），并在 App 内以 **WebView 内嵌** DSH 的 WebUI，
附加文件管理、持久终端与一体化的运行环境/DSH 更新管理。**装 APK 即用**。

---

## 核心特性

- **DeepSeek Harness 全内嵌**：DSH（Agent Runtime）随 APK 内置，`DSH` 标签页通过内嵌 WebView
  打开 `http://127.0.0.1:3080`（移动模式 WebView，原生键盘自适应）；首页也可一键用**系统浏览器**打开。
- **完整分层运行环境**：Debian（base）+ Node.js（node）+ PRoot（android-side）+ DSH 层，
  PRoot 用户态沙箱，与 Android 宿主隔离，无需 root；运行环境与用户数据相互独立。
- **更新/导入管理**：设置页可**离线导入运行环境包**、**离线/在线更新 DSH**（内置多镜像源）、
  装配 DSH 移动端适配插件（cordis `@local/dsh-mobile-adapt`），并带实时进度与失败回滚。
- **文件管理**：沙盒/工作区双视图（`/root/projects` 工作区 + 沙盒根），导入/导出引导，目录 ZIP，
  搜索、排序、网格/列表切换。
- **持久终端**：App 内常驻 shell 会话（`terminal-session` 模块）。
- **前台服务保活** + 常驻通知（Android 13+ 已支持 `POST_NOTIFICATIONS` 运行时权限）。
- **运行环境独立性**：`src/main/assets` 不含运行环境大层；运行时从分层包/导入包装配到
  `runtime-current/{base,node,android-side,dsh}`，互不写入用户数据。

## 界面（底部 5 个标签）

| 标签 | 功能 |
|---|---|
| 首页 | 沙箱/DSH 状态，启动/停止/重启，复制地址，在系统浏览器中打开 DSH |
| 文件 | 沙盒 + 工作区双视图浏览、导入/导出、目录 ZIP、搜索/排序 |
| DSH | 内嵌 WebView 加载 `http://127.0.0.1:3080` 的 DSH WebUI |
| 终端 | App 内持久 shell 会话 |
| 设置 | 运行环境包导入、DSH 离线/在线更新、移动端适配装配、诊断、版本信息 |

## 从源码构建

环境要求：JDK 21、Android SDK（compileSdk/targetSdk 36、build-tools 36.0.0）、Gradle wrapper 8.11.1（离线缓存）。
运行环境大层**不在本仓库**（见下节），构建前请先获取 `../runtime/`。

```bash
./gradlew :terminal-session:testDebugUnitTest :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

运行环境层的构建脚本与说明见 `runtime-bundle/`（base/node/android-side 各层 `tar.zst` 由
`build_base.sh` / `build_node.sh` / `build_android_side.sh` 在 Linux/root 环境构建，再纳入 `runtime/android-assets`）。

## 运行环境大文件（不在本仓库，做好引用）

本仓库（`source/`）**不包含**运行环境大层：`base` / `node` / `android-side` 的 `.tar.zst`、DSH 层 `0.1.1-rc.2-patched.tar.zst`；
也**不包含**签名密钥（`keystore.properties`、`local.properties`）。

- **大层位置与获取**：这些大文件随发布包放在发布目录 `../runtime/`（整包 `dshapp-runtime-debian-arm64-0.1.0.zip` +
  `android-assets/{runtime,dsh}/` 分层 + DSH 层），或从对应发布/下载源获取。
- **引用方式**：`app/build.gradle.kts` 通过 `assets.srcDirs("../../runtime/android-assets")` **引用**发布目录的 `runtime/`，
  构建完整版 APK 时把它们内嵌进 `assets/runtime/*` 与 `assets/dsh/*`。
- **构建前置条件**：`assembleRelease` 前需先获取 `runtime/`（若目录缺失则无法内嵌完整运行环境）。
- **签名密钥**：`keystore.properties`、`local.properties` 未包含；请用 `tools/create_keystore.sh` 自建开发签名后构建 release APK。

## 离线导入运行环境包

运行环境主体（base + node + android-side）对外以**一个文件** `dshapp-runtime-debian-arm64-0.1.0.zip`
交付，在设置页「离线导入运行环境包」选择即可；DSH 更新走「更新 DSH（离线）」单文件 `0.1.1-rc.2-patched.tar.zst`。

## 许可证

本项目采用 **GPL v3** 许可（见 [LICENSE](LICENSE)）。第三方组件许可见 `THIRD_PARTY_NOTICES.md`：
PRoot（GPL-2+）、talloc（LGPL-3+）、Debian rootfs（各包按 Debian 版权文件）、DeepSeek Harness / Cordis（MIT）、
Termux terminal-emulator / terminal-view（Apache-2.0，未修改）等，均按其各自原许可继续适用。
