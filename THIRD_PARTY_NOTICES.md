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
| zstd-jni（`libs/zstd-jni-*.jar` + `jniLibs/*/libzstd-jni-*.so`） | zstd 层解压及 tar.zst 条目枚举 | JNI 包装层 BSD-2-Clause；Zstandard 内核 BSD-3-Clause | 当前 1.5.7-15；原文见 licenses/Zstd-JNI-LICENSE.txt 与 licenses/Zstd-LICENSE.txt |
| commons-compress（`org.apache.commons:commons-compress` 1.27.1） | 压缩包枚举与解压 | Apache-2.0 | 随 APK 分发；实际传递依赖含 commons-codec 1.17.1、commons-io 2.16.1 与 commons-lang3 3.16.0，保留各自 LICENSE/NOTICE；https://commons.apache.org/proper/commons-compress/ |
| Apache Commons Codec / IO / Lang | commons-compress 的运行时传递依赖 | Apache-2.0 | 版本与文件以 APK 内 DEPENDENCIES.txt 为准，各自的 NOTICE 隔离保留 |
| Bouncy Castle（`org.bouncycastle:bcpg-jdk18on` 1.78.1，传递 `bcprov-jdk18on`/`bcutil-jdk18on`） | Debian `Release.gpg` OpenPGP 验签（在线导入 Linux 层的信任链锚点） | MIT（Bouncy Castle Licence；bcprov 内部分组件为 Apache-2.0，POM 双许可声明） | Gradle 依赖；随 APK 分发；预埋公钥取自 ftp-master.debian.org/keys（Debian 官方归档钥，非第三方许可物）；上游 https://www.bouncycastle.org/java.html |
| interlock-relay-core（`pilot/interlock-relay-core/`） | 手机助手平台层：通道与协议、审批闸门、三个执行后端、存储与配额、审计日志、宿主扩展点 | Apache-2.0 | 上游 https://github.com/WSK-build/interlock-relay ；版本 0.1.0；版权 Copyright 2026 WSK-build；以源码形式随本仓库分发；许可原文与依赖清单见 `pilot/interlock-relay-core/{LICENSE,NOTICE}`；在本应用内随整体以 GPL-3.0 分发，单独取用时适用 Apache-2.0 |
| Shizuku API（`dev.rikka.shizuku:api` / `:provider` 13.1.5，传递 `:aidl` / `:shared`） | shell 身份执行通路（手机助手核心层经 Shizuku 以 shell 权限调用系统能力） | MIT | Gradle 依赖；随 APK 分发；坐标未进版本目录，由手机助手核心层直接声明；`provider` 声明的组件已随清单合并生效、无额外运行时下载；上游 https://github.com/RikkaApps/Shizuku ，许可原文 https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE |
| AndroidX / Jetpack（含 androidx.appcompat:appcompat 1.7.0，1.2.1 起用于应用内语言切换） | Android 兼容层 | Apache-2.0 | Gradle 依赖 |
| AndroidX WebKit（`androidx.webkit:webkit` 1.12.1） | Browser WebView 主题与能力兼容检测 | Apache-2.0 | Gradle 依赖；随 APK 分发；上游 https://android.googlesource.com/platform/frameworks/support/+/androidx-main/webkit/ |
| AndroidX Test JUnit / Runner（`androidx.test.ext:junit` 1.2.1、`androidx.test:runner` 1.6.2） | Browser SQLite 设备仪器测试 | Apache-2.0 | 仅测试期，不随正式 APK 分发；上游 https://github.com/android/android-test |
| Jetpack Compose / Material3 / material-icons | UI 框架与图标 | Apache-2.0 | Gradle 依赖 |
| Tabler Icons outline | common 导航及 Browser 描边图标 | MIT | Copyright (c) 2020-2026 Paweł Kuna；上游 https://github.com/tabler/tabler-icons ；许可副本见 `common/LICENSE-TABLER.txt`；SVG 路径转换为 Android vector drawable |
| Kotlin stdlib / Coroutines | 语言运行时与异步 | Apache-2.0 | Gradle 依赖 |
| dshmarket（插件市场，npm 包 `dshmarket`） | 插件市场的设计参照：本项目以 Kotlin **重写**其数据层与界面（非逐字复制，未随包分发其代码与资源） | MIT（Copyright (c) 2026 fkysly and dsh-market contributors） | 上游 https://github.com/dsh-market/dsh-market ；MIT 要求保留版权与许可声明，本清单即该声明 |
| Termux terminal-emulator（`terminal-emulator/` 模块） | 终端模拟器（VT100/xterm 解析 + pty JNI） | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改；源自 jackpal/Android-Terminal-Emulator |
| Termux terminal-view（`terminal-view/` 模块） | 终端视图渲染与输入 | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改 |
| Sora Editor（`io.github.Rosemoe.sora-editor:editor` 0.23.5） | 代码编辑核心（撤销/重做、搜索、行号、括号匹配） | LGPL-2.1-or-later | Gradle 依赖（1.2.0 起）；以未修改 aar 形式使用，上游源码见下方合规说明 |
| Markwon（`io.noties.markwon:core` 4.6.2 + `ext-tables` 4.6.2） | Markdown 预览（CommonMark 规范，原生 Spannable 渲染；表格为 GFM 扩展） | Apache-2.0 | Gradle 依赖（1.2.0 起）；传递依赖 `com.atlassian.commonmark:commonmark` 0.13.0（BSD-2-Clause，随之上列）；ext-tables 与 core 同仓库 https://github.com/noties/Markwon |
| commonmark-java（`com.atlassian.commonmark:commonmark` 0.13.0） | Markdown 解析（Markwon 传递依赖） | BSD-2-Clause | Gradle 传递依赖（1.2.0 起）；源码 https://github.com/atlassian/commonmark-java |
| JUnit（`junit:junit` 4.13.2） | JVM 单测框架（**仅测试期**，不随 APK 分发） | EPL-1.0 | 以实际 4.13.2 POM 与 LICENSE-junit.txt 为准 |
| JSON-java（`org.json:json` 20240303 / 20231013） | Browser 与 plugin-manager 等模块的真实 JSON 契约单测（**仅 JVM 测试期**，不随 APK 分发） | Public Domain（对应发布 POM 声明） | 生产使用 Android 平台 org.json；上游 https://github.com/stleary/JSON-java |
| AndroidX Test Espresso（`androidx.test.espresso:espresso-core`） | UI 测试（**仅测试期**，不随 APK 分发） | Apache-2.0 | Gradle 测试依赖 |
| Compose UI Test（`androidx.compose.ui:ui-test-junit4`） | Compose UI 测试（**仅测试期**，不随 APK 分发） | Apache-2.0 | Gradle 测试依赖 |
| kxml2（`net.sf.kxml:kxml2` 2.3.0） | XmlPullParser 实现（docx/xlsx 文本抽取，**仅 JVM 单测期**，不随 APK 分发） | BSD style（kXML2 类）；XmlPull API（org.xmlpull.v1）属 Public Domain | Gradle 测试依赖（1.2.0 起）；真机运行时使用 Android 平台自带 XmlPullParser 实现；上游 http://kxml.sourceforge.net / http://www.xmlpull.org |
| XZ for Java (`org.tukaani:xz` 1.9) | sandbox-manager 的 XZ 解压，随 APK 分发 | Public Domain；特定文件按制品内声明 | 保留归档内 COPYING 等原文；https://tukaani.org/xz/java.html |

## DSHBox 自有组件授权

以下为自有代码，不属于第三方依赖。独立许可随插件装配交付，不表示脱离 DSHBox 即可独立运行。

| 组件 | 许可证 | 许可与范围说明 |
|---|---|---|
| `@local/dsh-mobile-adapt` | GPL-3.0-only | `plugin-manager/dshbox-plugins/assets/plugins/dsh-mobile-adapt/plugin/{LICENSE,NOTICE}` |
| `@local/mobile-pilot` | GPL-3.0-only | `plugin-manager/dshbox-plugins/assets/plugins/mobile-pilot/plugin/{LICENSE,NOTICE}`；不改变 interlock-relay-core 的 Apache-2.0 授权 |
| `@local/browser-use` | GPL-3.0-only | `plugin-manager/dshbox-plugins/assets/plugins/browser-use/plugin/{LICENSE,NOTICE}`；非 Python browser-use 项目 |
| Browser 四个 Gradle 模块及自有 CLI | 随 DSHBox 的 GPLv3 | 自研 WebView、协议桥接与调试实现；没有因实现 CDP 而引入 Playwright、Puppeteer 或完整 Chromium 分发 |

## APK 内的许可交付

构建按实际变体 runtime classpath 收集 JAR/AAR 及内嵌 JAR 的 LICENSE、NOTICE、COPYING 与版权文本，按依赖坐标隔离存储；同时交付本清单、自有插件声明、Tabler 原文、通用许可及补充上游许可。测试依赖不作为运行时组件收集。

在「设置 → 关于 → 开源许可」离线查看；APK 路径为 `assets/licenses/`，`INDEX.txt` 为文件索引，`DEPENDENCIES.txt` 为实际依赖制品清单。插件自己的 LICENSE/NOTICE 也随目录复制进入 DSH。许可收集不改变第三方授权，不能替代 GPL/LGPL 对应源码和重建材料交付；来源与重建说明见 `licenses/README.md`。

## 再分发合规说明

- **dshmarket（MIT）**：`:plugin-manager` 的市场能力按其数据模型与交互设计重写（未逐字复制源码、未随包分发其代码与资源）。上游仓库与版权行见上表；许可原文随其 npm 包分发（`node_modules/dshmarket/LICENSE`）。
- **插件目录数据源（awesome-dsh-plugin，CC0-1.0）**：市场在运行时按其公开 JSON 接口（`awesome-dsh-plugin.com/plugins.json`）读取第三方精选列表，**不随包分发其数据**；该目录仓库以 CC0-1.0 发布（https://github.com/awesome-dsh-plugin/awesome-dsh-plugin ）。

- **Termux terminal-emulator / terminal-view（Apache-2.0）**：源码原样引入（仅构建脚本数值适配）。termux-app 仓库整体为 GPLv3，但其 LICENSE.md 明示这两个库模块为 Apache-2.0 例外（代码源自 https://github.com/jackpal/Android-Terminal-Emulator ）。上游地址：https://github.com/termux/termux-app 。严禁从该仓库的 GPLv3 部分（termux-shared、app 层等）复制代码。
- **Sora Editor（LGPL-2.1-or-later）**：以未修改 AAR 引入（`io.github.Rosemoe.sora-editor:editor` 0.23.5），不改变上游授权；许可副本见 `licenses/Sora-Editor-LICENSE.txt`，对应源码 https://github.com/Rosemoe/sora-editor/tree/0.23.5 。静态打包不自动满足重链接或源码交付义务，应用整体 GPLv3 也不能替代这些义务。发布二进制时须依适用条款提供对应库源码与重建/替换所需的应用源码、构建材料或同处可获取的源码交付；重建位置见 `licenses/README.md`。
- **PRoot（GPL-2+）**：本项目以二进制形式内置 PRoot 及配套 loader/shmem。对应源码可通过以下地址获取，或从 termux-packages 的 proot 包导出：
  - https://github.com/termux/termux-packages （proot 包）
  - https://github.com/proot-me/PRoot
- **talloc（LGPL-3+）**：动态链接使用，不修改源码；源码见 https://git.samba.org/talloc/ 或 termux-packages。
- **Debian rootfs**：由 Debian 官方仓库构建，仅作运行环境，未修改上游包源码；各包许可证遵循 Debian 版权文件。
- **DeepSeek Harness / Cordis**：MIT，以 npm 包形式随运行环境分发，未修改源码。
- **interlock-relay-core（Apache-2.0）**：手机助手平台层，以源码形式随本仓库分发、编译时静态链接。Apache-2.0 要求随分发提供许可副本并保留 NOTICE —— 许可原文见 `pilot/interlock-relay-core/LICENSE`，NOTICE（内含其自身第三方依赖清单）见同目录 `NOTICE`，两者随源码一并公开。该层与本项目自有的适配层 `pilot/dshbox-adapter/`（GPL-3.0）分属不同授权，复制或再分发时不要混为一谈，见 `pilot/NOTICE`；层本身可独立发布，单独取用时适用 Apache-2.0。

## 维护说明

更新运行环境或依赖后，请用 `gradle :app:dependencies`（或其他模块） 与运行环境内 npm 依赖树核对本清单，保持组件与许可证同步；涉及 GPL/LGPL 组件的版本变更时同步更新上方源码链接。
