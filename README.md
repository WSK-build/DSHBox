# DSHBox — Run DeepSeek Harness Locally on Android

<img width="1772" height="884" alt="DSHBox running DeepSeek Harness locally on Android phones and tablets" src="https://github.com/user-attachments/assets/a9622b15-a348-4c81-a708-3684a208e59e" />

[![Latest Release](https://img.shields.io/github/v/release/WSK-build/DSHBox?display_name=tag&sort=semver)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64-0091BD?logo=arm&logoColor=white)](https://github.com/WSK-build/DSHBox)
[![License](https://img.shields.io/github/license/WSK-build/DSHBox)](https://github.com/WSK-build/DSHBox/blob/main/LICENSE)
[![Build Status](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml)
[![Download APK](https://img.shields.io/badge/Download-APK-2EA44F?logo=github)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Community Discussion](https://img.shields.io/badge/DSH-Community%20Discussion-8250DF?logo=github)](https://github.com/deepseek-ai/deepseek-harness/discussions/5801)
[![Website](https://img.shields.io/badge/Website-Official%20Site-10A37F?logo=github&logoColor=white)](https://wsk-build.github.io/DSHBox/)

**DSHBox** runs the full DeepSeek Harness locally on Android phones and tablets. It bundles Debian, Node.js, DSH, PRoot, and an embedded WebView into one APK, with no root access or separate Termux installation required.

**DSHBox** 是一个可在安卓手机和平板上本机运行完整 **DeepSeek Harness（DSH）** 的开源应用。它将 Debian、Node.js、DSH、PRoot 和 WebView 集成在一个 APK 中，无需 Root，也无需单独安装 Termux。

---

## 快速安装

| 项目 | 说明 |
|---|---|
| 系统要求 | Android 10+ · ARM64 |
| 权限 | 无需 Root · 无需 Termux |
| 内置 | Debian · Node.js · DSH · WebView |

**[下载最新 APK](https://github.com/WSK-build/DSHBox/releases/latest)** → 安装 → 启动应用 → 等待运行环境初始化 → 打开 DSH。

---

## 更新记录

当前版本 **v1.2.0**：文件管理增强——移动到指定文件夹、通用文件查看器/编辑器（文本编辑 / 图片 / PDF / 压缩包 / 十六进制 / Office 抽文本）、外部应用打开/编辑/分享、导入多选。

各版本完整变更见 **[CHANGES.md](CHANGES.md)**。

---

## 核心特性

### DeepSeek Harness 全内嵌

- DSH 随 APK 内置，首启按**版本仲裁**装配到 `runtime-current/dsh`：已装较新则保留，换层时旧层备份到 `previous/dsh`（单份），不触碰用户数据
- `DSH` 标签页内嵌 WebView 打开 `http://127.0.0.1:3080`：自动解析 launchToken 完成会话认证（兼容 DSH 0.1.2-rc.1）、移动 UA、键盘自适应、双指缩放、悬浮刷新
- 首页可复制地址 / 一键用系统浏览器打开；前台服务通知带「打开 / 启动 / 重启 / 停止」快捷操作

### PRoot 分层运行环境（无需 Root）

四层独立装配，PRoot 用户态沙箱与 Android 宿主隔离，运行环境与用户数据（`user-data/` → guest `/root/projects`）互不写入：

| 层 | 内容 | guest 挂载点 |
|---|---|---|
| base | Debian 13 (trixie) rootfs | `/`（rootfs） |
| node | Node.js 24（npm / npx / corepack） | `/usr/local` |
| dsh | DeepSeek Harness（npm 包） | `/opt/dshapp/runtime` |
| android-side | PRoot / loader / shmem（宿主侧） | — |

- 沙箱 keepalive 与 DSH 为两个独立 PRoot 进程；停机按 `/proc` 枚举整棵进程树、子进程优先 SIGKILL，不留孤儿、不占端口
- 每层带 SHA-256 哨兵，启动时逐层校验完整性，损坏可识别、可重装

### 文件管理（v1.2.0 重点增强）

- **双视图**：工作区 `/root/projects` + 沙盒根（叠加 node、DSH 层），面包屑导航、列表 / 网格、按名称 / 时间 / 大小排序、新建文件夹、多选批量操作
- **移动到指定文件夹**：全屏目标选择器（沙盒 / 工作区切换、可新建文件夹、源自身及子孙目录置灰防环、跨挂载点落点提示）；冲突三策略（覆盖 / 跳过 / 自动改名 + 应用到其余全部）；同卷 `renameTo` 优先、失败复制兜底（保留权限位 / 时间戳）；「重命名」走同一引擎
- **导入**：文件多选批量导入、压缩包解压导入，逐件冲突决策、可取消、完成汇总；ZIP 中文名编码修复，加密 zip 明确拒绝
- **导出**：多选导出到目录（SAF），或打包为 ZIP
- **全局搜索**：跨沙盒 + 工作区，同时搜文件名与内容，结果带匹配片段
- **风险保护**：系统目录 / node / DSH 层 / `.dsh` 分级标注，写操作前强确认

- **通用文件查看器 / 编辑器** —— 魔数 + 内容嗅探 + 扩展名三级分类，任何文件必有界面：

| 类型 | 能力 |
|---|---|
| 文本 / 代码 | Sora Editor 编辑（行号 / 撤销重做 / 搜索 / 自动换行），json / yaml / shell / python / js / java+kotlin 高亮；编码自动探测 + 手动切换，有损解码强制只读；大文件分级（≤2MB 可编辑 · 2–10MB 确认后编辑 · >10MB 只读尾窗）；原子保存 + 外部变更检测 + 未保存拦截 |
| 图片 | 双指缩放 / 双击放大，超长图条带加载，GIF / 动态 WebP 动图，AVIF（Android 12+） |
| PDF | 原生分页渲染；加密 PDF Android 15+ 可输密码，低版本引导外部打开 |
| 压缩包 | zip / jar / apk / epub 与 tar 系只读浏览（目录折叠、加密条目标记）、包内文本预览、单条目 / 全部导出；ZIP 中文名不乱码；7z / RAR 信息卡 + 外部打开 |
| 十六进制 | 偏移 / Hex / ASCII 三栏，64KB 块随机读，熵估计 |
| Office | docx / xlsx 抽纯文本只读；doc / xls / ppt 信息卡 + 外部打开 |
| Markdown / HTML / SVG | md 源文编辑 + Markwon 预览（含表格）；html / svg 离线 WebView 渲染（禁 JS、禁网络、退出即销毁） |
| 未知 / 二进制 | 十六进制查看 + 文件信息卡；外部打开 / 编辑 / 分享 / 导出兜底 |

### 更新与导入管理（设置页）

| 功能 | 说明 |
|---|---|
| 更新 DSH（在线） | 并行探测 npm 官方 / 阿里 / 腾讯云 / 华为云镜像的版本与延迟 → 选源选版本（降级二次确认）→ 沙箱内 npm 拉取完整依赖树 → 换层自动重启；后台运行、实时日志、可取消（进程树 SIGKILL） |
| 更新 DSH（离线导入） | 单文件层包 `.tar.zst / .tar.gz / .tar / .tgz`（或 zip 内含层包），暂存解压 → 形态校验 → 原子换层，失败不留半成品 |
| 离线导入运行环境包 | 整包替换 base / node / android-side，逐层 SHA-256 校验，`previous/` 单份可回滚（详见下文） |
| 装配移动端适配包 | cordis 插件 `@local/dsh-mobile-adapt`，开关即装即卸、可反复切换，不干预 DSH 生命周期 |
| 诊断 | DSH / 沙箱 / 访客命令日志各 150 行，可滚动、可导出合并 |

### 存储占用与清理

- 占用按系统同口径统计（分配块、硬链接去重、含应用缓存），进设置页自动刷新、可手动刷新
- 清理项独立勾选：应用缓存 / 访客临时文件（运行中仅清 24h 前条目）/ 运行日志（截断）/ apt 下载缓存；回滚备份可选并明确警告
- 清理与后台安装 / 导入互斥，绝不触碰 `user-data/.dsh` 与运行环境本体

### 终端

- 多窗口：沙盒终端（PRoot Debian 完整环境，bash / vim / htop / node / npm / apt / git / python3 / ssh 开箱可用）/ 受限 shell 兜底，浮动控制面板新建 / 切换 / 关闭
- 两行辅助按键栏（ESC / TAB / HOME / END / CTRL 粘滞 / 粘贴 / 方向键 / 翻页 / 退格 / 删除，DECCKM 感知），双指缩放字号 8–40sp
- 基于 Termux terminal-emulator / terminal-view（v0.118.0，未修改）+ 自研 `terminal-session` 会话层

## 界面（底部 5 个标签）

| 标签 | 功能 |
|---|---|
| 首页 | 沙箱 / DSH 状态卡片、启动 / 停止 / 重启、运行时长、复制地址、系统浏览器打开 DSH |
| 文件 | 双视图浏览、移动 / 重命名 / 删除、多选批量、导入 / 导出、查看器 / 编辑器、搜索 / 排序 |
| DSH | 内嵌 WebView 加载 `http://127.0.0.1:3080`（自动认证、键盘自适应、悬浮刷新） |
| 终端 | 多窗口终端、辅助按键栏、控制面板 |
| 设置 | 存储与清理、DSH 更新（在线 / 离线）、运行环境包导入、移动端适配装配、诊断、关于 |

## 从源码构建

| 环境 | 版本 |
|---|---|
| JDK | 21（官方 CI 使用 Temurin 21） |
| Android SDK | compileSdk / targetSdk 36 · build-tools 36.0.0 |
| Gradle | wrapper 8.11.1（AGP 8.9.2 · Kotlin 2.0.21） |

> 运行环境大层**不在本仓库**（见下节），构建前请先获取 `../runtime/`。

```bash
./gradlew testDebugUnitTest     # 全量 JVM 单测（v1.2.0 共 172 例）
./gradlew :app:assembleRelease  # 产物：app/build/outputs/apk/release/app-release.apk
```

| 模块 | 职责 |
|---|---|
| `app` | 全部 UI（5 个标签页、查看器、设置 / 诊断 / 更新页）、前台服务、在线更新编排 |
| `sandbox-manager` | 分层运行时装配、PRoot 进程管理、DSH 层仲裁与更新、导入 / 校验 / 清理 |
| `common` | 常量、npm 镜像源、版本比较、日志脱敏 |
| `bridge` | WebView JS Bridge 安全框架（预留 stub） |
| `terminal-session` | 终端会话层（多窗口、PRoot 终端命令构建） |
| `terminal-view` / `terminal-emulator` | Termux 终端库（v0.118.0，未修改） |

运行环境层构建脚本见 `runtime-bundle/`（各层 `tar.zst` 由 `build_base.sh` / `build_node.sh` / `build_android_side.sh` 在 Linux/WSL2 构建）；构建手册见 `docs/BUILD_RUNBOOK.md`，预检脚本 `tools/pipeline_dryrun.sh`。

## 运行环境大文件（不在本仓库）

| 发布包内路径 | 内容 |
|---|---|
| `runtime/android-assets/runtime/{base,node,android-side}.tar.zst` | 三层运行环境，各带 `.sha256` 侧车与 `runtime-profile.json` |
| `runtime/android-assets/dsh/0.1.1-rc.2-patched.tar.zst` | DSH 层 + `.sha256` |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | 对外交付的运行环境整包（离线导入用） |

- 引用方式：`app/build.gradle.kts` 通过 `assets.srcDirs("../../runtime/android-assets")` 引用发布目录，构建时内嵌进 `assets/runtime/*` 与 `assets/dsh/*`
- 构建前置：`assembleRelease` 前需先获取 `runtime/`，目录缺失则无法内嵌完整运行环境
- 签名密钥（`keystore.properties`、`local.properties`）不在仓库内：用 `tools/create_keystore.sh` 自建开发签名，未配置时 release 构建回退 debug 签名

## 离线导入运行环境包

- 交付形式：单文件 `dshapp-runtime-debian-arm64-0.1.0.zip`，设置页「离线导入运行环境包」选择即可
- 布局与格式：外层 zip / tar；层归档 `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz`（按魔数识别），兼容一层目录前缀与外层 tar 装层归档
- 校验：逐层 SHA-256（侧车与 `runtime-profile.json` 交叉核对）+ Zip-Slip 防护；损坏 / 截断 / 加密包返回可读错误
- 替换：旧本体移入 `previous/`（单份可回滚），绝不触碰 DSH 层与 `user-data/.dsh`
- DSH 离线更新走「更新 DSH（离线导入）」单文件层包（如 `0.1.1-rc.2-patched.tar.zst`）

## 许可证

本项目采用 **GPL v3**（见 [LICENSE](LICENSE)）。第三方组件按其各自原许可继续适用，详见 `THIRD_PARTY_NOTICES.md`：

- PRoot（GPL-2+）· talloc（LGPL-3+）· Debian rootfs（按各包 Debian 版权文件）
- DeepSeek Harness / Cordis（MIT）· Node.js（MIT）
- Termux terminal-emulator / terminal-view（Apache-2.0，v0.118.0 未修改）
- sora-editor（LGPL-2.1-or-later，aar 未修改）· Markwon（Apache-2.0）· commons-compress（Apache-2.0）· zstd-jni（BSD-3-Clause）
