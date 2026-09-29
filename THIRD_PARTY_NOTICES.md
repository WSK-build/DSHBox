# Third-Party Notices（第三方组件与许可证清单）

本项目自身采用 **GPL v3**（GNU General Public License, version 3，见 `LICENSE`）。以下为项目包含或依赖的第三方组件及各组件许可证与再分发义务。注意：本项目整体以 GPL v3 发布，但内含的第三方组件按其**各自原有许可证**继续适用（PRoot GPL-2+、talloc LGPL-3+、Termux terminal-emulator/view Apache-2.0、DeepSeek Harness/Cordis MIT、AndroidX/Kotlin Apache-2.0 等），未因本项目整体采用 GPL v3 而改变其许可。

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| DeepSeek Harness（`@deepseek-ai/dsh` 及子包） | Agent Runtime | MIT | 以 npm 包形式随运行环境分发 |
| Cordis（`@deepseek-ai/cordis`） | 插件框架 | MIT | npm 包 |
| Node.js 24.19.0 | JavaScript Runtime | MIT（详见 Node 发行版 LICENSE） | 运行环境内置 |
| npm / pnpm | 包管理器 | Artistic-2.0（npm）/ MIT（pnpm） | 随 Node 分发 |
| Debian GNU/Linux rootfs | Linux 用户空间 | 各包按 Debian 版权文件分别授权 | debootstrap 构建；各包许可证见 rootfs 内 `/usr/share/doc/*/copyright` |
| PRoot（`libproot.so` / `libproot-loader.so` / `libandroid-shmem.so`） | 用户态沙箱（chroot 替代） | GPL-2+（以源码 COPYING 为准） | 二进制来自 termux-packages 构建；源码见下方链接 |
| talloc（`libtalloc.so`） | 内存池（PRoot 依赖） | LGPL-3+ | 动态链接使用；源码见下方链接 |
| zstd-jni（`libs/zstd-jni-*.jar` + `jniLibs/*/libzstd-jni-*.so`） | zstd 层解压（运行环境层 / DSH 层）及 tar.zst 压缩包条目枚举（1.2.0 M3 起） | BSD-3-Clause | 1.1.0 起随 APK 分发（凭 magic 识别，不依赖带扩展名） |
| commons-compress（`org.apache.commons:commons-compress` 1.27.1） | 压缩包条目枚举/解压（ZIP 族、TAR 族、tar.gz/tar.bz2/tar.zst，1.2.0 M3 起） | Apache-2.0 | Gradle 依赖；随 APK 分发；POM 依赖全为 test/provided 作用域，无随包传递依赖；上游 https://commons.apache.org/proper/commons-compress/ |
| Bouncy Castle（`org.bouncycastle:bcpg-jdk18on` 1.78.1，传递 `bcprov-jdk18on`/`bcutil-jdk18on`） | Debian `Release.gpg` OpenPGP 验签（在线导入 Linux 层的信任链锚点） | MIT（Bouncy Castle Licence；bcprov 内部分组件为 Apache-2.0，POM 双许可声明） | Gradle 依赖；随 APK 分发；预埋公钥取自 ftp-master.debian.org/keys（Debian 官方归档钥，非第三方许可物）；上游 https://www.bouncycastle.org/java.html |
| interlock-relay-core（`pilot/interlock-relay-core/`） | 手机助手平台层：通道与协议、审批闸门、三个执行后端、存储与配额、审计日志、宿主扩展点 | Apache-2.0 | 上游 https://github.com/WSK-build/interlock-relay ；版本 0.1.0；版权 Copyright 2026 WSK-build；以源码形式随本仓库分发；许可原文与依赖清单见 `pilot/interlock-relay-core/{LICENSE,NOTICE}`；在本应用内随整体以 GPL-3.0 分发，单独取用时适用 Apache-2.0 |
| Shizuku API（`dev.rikka.shizuku:api` / `:provider` 13.1.5，传递 `:aidl` / `:shared`） | shell 身份执行通路（手机助手核心层经 Shizuku 以 shell 权限调用系统能力） | MIT | Gradle 依赖；随 APK 分发；坐标未进版本目录，由手机助手核心层直接声明；`provider` 声明的组件已随清单合并生效、无额外运行时下载；上游 https://github.com/RikkaApps/Shizuku ，许可原文 https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE |
| AndroidX / Jetpack（含 androidx.appcompat:appcompat 1.7.0，1.2.1 起用于应用内语言切换） | Android 兼容层 | Apache-2.0 | Gradle 依赖 |
| Jetpack Compose / Material3 / material-icons | UI 框架与图标 | Apache-2.0 | Gradle 依赖 |
| Kotlin stdlib / Coroutines | 语言运行时与异步 | Apache-2.0 | Gradle 依赖 |
| dshmarket（插件市场，npm 包 `dshmarket`） | 插件市场的设计参照：本项目以 Kotlin **重写**其数据层与界面（非逐字复制，未随包分发其代码与资源） | MIT（Copyright (c) 2026 fkysly and dsh-market contributors） | 上游 https://github.com/dsh-market/dsh-market ；MIT 要求保留版权与许可声明，本清单即该声明 |
| Termux terminal-emulator（`terminal-emulator/` 模块） | 终端模拟器（VT100/xterm 解析 + pty JNI） | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改；源自 jackpal/Android-Terminal-Emulator |
| Termux terminal-view（`terminal-view/` 模块） | 终端视图渲染与输入 | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改 |
| Sora Editor（`io.github.Rosemoe.sora-editor:editor` 0.23.5） | 代码编辑核心（撤销/重做、搜索、行号、括号匹配） | LGPL-2.1-or-later | Gradle 依赖（1.2.0 起）；以未修改 aar 形式使用，上游源码见下方合规说明 |
| Markwon（`io.noties.markwon:core` 4.6.2 + `ext-tables` 4.6.2） | Markdown 预览（CommonMark 规范，原生 Spannable 渲染；表格为 GFM 扩展） | Apache-2.0 | Gradle 依赖（1.2.0 M3 起）；传递依赖 `com.atlassian.commonmark:commonmark` 0.13.0（BSD-2-Clause，随之上列）；ext-tables 与 core 同仓库 https://github.com/noties/Markwon |
| commonmark-java（`com.atlassian.commonmark:commonmark` 0.13.0） | Markdown 解析（Markwon 传递依赖） | BSD-2-Clause | Gradle 传递依赖（1.2.0 M3 起）；源码 https://github.com/atlassian/commonmark-java |
| JUnit（`junit:junit` 4.13.2） | JVM 单测框架（**仅测试期**，不随 APK 分发） | EPL-2.0 | Gradle 测试依赖 |
| AndroidX Test Espresso（`androidx.test.espresso:espresso-core`） | UI 测试（**仅测试期**，不随 APK 分发） | Apache-2.0 | Gradle 测试依赖 |
| Compose UI Test（`androidx.compose.ui:ui-test-junit4`） | Compose UI 测试（**仅测试期**，不随 APK 分发） | Apache-2.0 | Gradle 测试依赖 |
| kxml2（`net.sf.kxml:kxml2` 2.3.0） | XmlPullParser 实现（docx/xlsx 文本抽取，**仅 JVM 单测期**，不随 APK 分发） | BSD style（kXML2 类）；XmlPull API（org.xmlpull.v1）属 Public Domain | Gradle 测试依赖（1.2.0 M3 起）；真机运行时使用 Android 平台自带 XmlPullParser 实现；上游 http://kxml.sourceforge.net / http://www.xmlpull.org |

## 再分发合规说明

- **dshmarket（MIT）**：`:plugin-manager` 的市场能力按其数据模型与交互设计重写（未逐字复制源码、未随包分发其代码与资源）。上游仓库与版权行见上表；许可原文随其 npm 包分发（`node_modules/dshmarket/LICENSE`）。
- **插件目录数据源（awesome-dsh-plugin，CC0-1.0）**：市场在运行时按其公开 JSON 接口（`awesome-dsh-plugin.com/plugins.json`）读取第三方精选列表，**不随包分发其数据**；该目录仓库以 CC0-1.0 发布（https://github.com/awesome-dsh-plugin/awesome-dsh-plugin ）。

- **Termux terminal-emulator / terminal-view（Apache-2.0）**：源码原样引入（仅构建脚本数值适配）。termux-app 仓库整体为 GPLv3，但其 LICENSE.md 明示这两个库模块为 Apache-2.0 例外（代码源自 https://github.com/jackpal/Android-Terminal-Emulator ）。上游地址：https://github.com/termux/termux-app 。严禁从该仓库的 GPLv3 部分（termux-shared、app 层等）复制代码。
- **Sora Editor（LGPL-2.1-or-later）**：以未修改 aar 依赖经 Gradle 引入（`io.github.Rosemoe.sora-editor:editor` 0.23.5），未修改其源码或字节码。上游源码：https://github.com/Rosemoe/sora-editor （LGPL-2.1-or-later）。合规说明：Android 以 aar 静态打包无法满足 LGPL「可替换库」的重链接要求，通行做法为宿主应用以 GPLv3 整体分发——本项目自身即 GPLv3，满足该要求；用户如需替换/重编译该库，可依据上游 LGPL 源码自行构建同坐标依赖（Gradle 依赖坐标不变时可直接替换本地缓存制品）。
- **PRoot（GPL-2+）**：本项目以二进制形式内置 PRoot 及配套 loader/shmem。对应源码可通过以下地址获取，或从 termux-packages 的 proot 包导出：
  - https://github.com/termux/termux-packages （proot 包）
  - https://github.com/proot-me/PRoot
- **talloc（LGPL-3+）**：动态链接使用，不修改源码；源码见 https://git.samba.org/talloc/ 或 termux-packages。
- **Debian rootfs**：由 Debian 官方仓库构建，仅作运行环境，未修改上游包源码；各包许可证遵循 Debian 版权文件。
- **DeepSeek Harness / Cordis**：MIT，以 npm 包形式随运行环境分发，未修改源码。
- **interlock-relay-core（Apache-2.0）**：手机助手平台层，以源码形式随本仓库分发、编译时静态链接。Apache-2.0 要求随分发提供许可副本并保留 NOTICE —— 许可原文见 `pilot/interlock-relay-core/LICENSE`，NOTICE（内含其自身第三方依赖清单）见同目录 `NOTICE`，两者随源码一并公开。该层与本项目自有的适配层 `pilot/dshbox-adapter/`（GPL-3.0）分属不同授权，复制或再分发时不要混为一谈，见 `pilot/NOTICE`；层本身可独立发布，单独取用时适用 Apache-2.0。

## 维护说明

更新运行环境或依赖后，请用 `gradle :app:dependencies`（或其他模块） 与运行环境内 npm 依赖树核对本清单，保持组件与许可证同步；涉及 GPL/LGPL 组件的版本变更时同步更新上方源码链接。
