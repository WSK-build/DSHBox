# Third-Party Notices（第三方组件与许可证清单）

本项目自身采用 **GPL v3**（GNU General Public License, version 3，见 `LICENSE`）。以下为项目包含或依赖的第三方组件及各组件许可证与再分发义务。注意：本项目整体以 GPL v3 发布，但内含的第三方组件按其**各自原有许可证**继续适用（PRoot GPL-2+、talloc LGPL-3+、Termux terminal-emulator/view Apache-2.0、DeepSeek Harness/Cordis MIT、AndroidX/Kotlin Apache-2.0 等），未因本项目整体采用 GPL v3 而改变其许可。

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| DeepSeek Harness（`@deepseek-ai/dsh` 及子包） | Agent Runtime | MIT | 以 npm 包形式随运行环境分发 |
| Cordis（`@deepseek-ai/cordis`） | 插件框架 | MIT | npm 包 |
| Node.js 22.x | JavaScript Runtime | MIT（详见 Node 发行版 LICENSE） | 运行环境内置 |
| npm / pnpm | 包管理器 | Artistic-2.0（npm）/ MIT（pnpm） | 随 Node 分发 |
| Debian GNU/Linux rootfs | Linux 用户空间 | 各包按 Debian 版权文件分别授权 | debootstrap 构建；各包许可证见 rootfs 内 `/usr/share/doc/*/copyright` |
| PRoot（`libproot.so` / `libproot-loader.so` / `libandroid-shmem.so`） | 用户态沙箱（chroot 替代） | GPL-2+（以源码 COPYING 为准） | 二进制来自 termux-packages 构建；源码见下方链接 |
| talloc（`libtalloc.so`） | 内存池（PRoot 依赖） | LGPL-3+ | 动态链接使用；源码见下方链接 |
| zstd-jni（`libs/zstd-jni-*.jar` + `jniLibs/*/libzstd-jni-*.so`） | zstd 层解压（运行环境层 / DSH 层） | BSD-3-Clause | 1.1.0 起随 APK 分发（凭 magic 识别，不依赖带扩展名） |
| AndroidX / Jetpack | Android 兼容层 | Apache-2.0 | Gradle 依赖 |
| Jetpack Compose / Material3 / material-icons | UI 框架与图标 | Apache-2.0 | Gradle 依赖 |
| Kotlin stdlib / Coroutines | 语言运行时与异步 | Apache-2.0 | Gradle 依赖 |
| Termux terminal-emulator（`terminal-emulator/` 模块） | 终端模拟器（VT100/xterm 解析 + pty JNI） | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改；源自 jackpal/Android-Terminal-Emulator |
| Termux terminal-view（`terminal-view/` 模块） | 终端视图渲染与输入 | Apache-2.0 | 源码取自 termux/termux-app v0.118.0，未修改 |

## 再分发合规说明

- **Termux terminal-emulator / terminal-view（Apache-2.0）**：源码原样引入（仅构建脚本数值适配）。termux-app 仓库整体为 GPLv3，但其 LICENSE.md 明示这两个库模块为 Apache-2.0 例外（代码源自 https://github.com/jackpal/Android-Terminal-Emulator ）。上游地址：https://github.com/termux/termux-app 。严禁从该仓库的 GPLv3 部分（termux-shared、app 层等）复制代码。
- **PRoot（GPL-2+）**：本项目以二进制形式内置 PRoot 及配套 loader/shmem。对应源码可通过以下地址获取，或从 termux-packages 的 proot 包导出：
  - https://github.com/termux/termux-packages （proot 包）
  - https://github.com/proot-me/PRoot
- **talloc（LGPL-3+）**：动态链接使用，不修改源码；源码见 https://git.samba.org/talloc/ 或 termux-packages。
- **Debian rootfs**：由 Debian 官方仓库构建，仅作运行环境，未修改上游包源码；各包许可证遵循 Debian 版权文件。
- **DeepSeek Harness / Cordis**：MIT，以 npm 包形式随运行环境分发，未修改源码。

## 维护说明

更新运行环境或依赖后，请用 `gradle :app:dependencies` 与运行环境内 npm 依赖树核对本清单，保持组件与许可证同步；涉及 GPL/LGPL 组件的版本变更时同步更新上方源码链接。
