# DSHBox — DeepSeek Harness Mobile Agent Sandbox

DSHBox 是在 Android 上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）的沙箱 App：Android 原生 UI + PRoot 用户态 Linux 沙箱，**DSH WebUI 通过系统浏览器使用**。

## 使用（装 APK 即用）

1. 从 [Releases](https://github.com/your-name/DSHBox/releases) 下载 APK（完整版约 236MB，已内置运行环境+DSH）并安装。
2. 打开 App，DSH 在沙箱内自动启动（首次启动需 1~3 分钟解包运行环境）。
3. 点击首页「打开 DSH」按钮，或在系统浏览器访问 `http://127.0.0.1:3080`，进入 DSH WebUI。
4. 首次使用请在 WebUI 的模型设置中填入你的 **DeepSeek API Key**。

> 说明：DSH 以 WebUI 形式运行在沙箱内（App 私有空间），App 通过系统浏览器承载访问。沙箱与 Android 宿主隔离，无需 root；运行环境与用户数据相互独立。

## 浏览器适配提示

- 安卓**平板**浏览器展示效果最佳；**手机**浏览器页面较拥挤（可横屏使用），功能均可正常使用。
- 安装有谷歌浏览器时，建议用 Chrome 打开 `http://127.0.0.1:3080` 并「添加到主屏幕 / 安装为桌面 App」，画面显示更稳定。

## 功能

- **DSH Agent（WebUI 内）**：模型对话、文件工具、bash 终端、子代理、目标管理
- **文件页**：沙盒文件 / 工作区双视图浏览，导入/导出引导，目录 ZIP，搜索、排序、网格/列表切换
- **沙盒终端**：App 内持久 shell 会话
- **运行环境管理**：内置运行环境、更新导入、回滚；前台服务保活

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

## 许可证

本项目采用 **GPL v3** 许可（见 [LICENSE](LICENSE)）。第三方组件许可见 `THIRD_PARTY_NOTICES.md`。
