# DSHBox — DeepSeek Harness Mobile Agent Sandbox

DSHBox 是在 Android 上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）的沙箱 App：Android 原生 UI + PRoot 用户态 Linux 沙箱，**DSH WebUI 通过系统浏览器使用**。

## 使用（装 APK 即用）

1. 从 [Releases](https://github.com/your-name/DSHapp/releases) 下载 APK（约 345MB，已内置完整运行环境）并安装。
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

环境要求：JDK 17+、Android SDK；运行环境 bundle 构建需要 Linux root 环境（debootstrap/qemu）。

```bash
# 1. 构建运行环境 bundle（Debian arm64 rootfs + Node.js + DSH）
tools/build_arm64_runtime_bundle.sh

# 2. 构建 APK（bundle 内置进 APK，装 APK 即用）
tools/build_apk.sh dist/dshapp-runtime-debian-arm64-rootfs-0.1.0.tar.gz release
# 产物：app/build/outputs/apk/release/app-release.apk
```

运行环境构建与初始化脚本说明见 `runtime-bundle/README.md`。

## 许可证

[GNU General Public License v3.0](LICENSE)。第三方组件许可见 `THIRD_PARTY_NOTICES.md`。
