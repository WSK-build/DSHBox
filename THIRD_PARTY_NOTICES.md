# Third-Party Notices（第三方组件与许可证清单）

本项目包含或依赖以下第三方组件。各组件许可证与再分发义务说明如下。

| 组件 | 用途 | 许可证 | 说明 |
|---|---|---|---|
| DeepSeek Harness（`@deepseek-ai/dsh` 及子包） | Agent Runtime | MIT | 以 npm 包形式随运行环境分发 |
| Cordis（`@deepseek-ai/cordis`） | 插件框架 | MIT | npm 包 |
| Node.js 22.x | JavaScript Runtime | MIT（详见 Node 发行版 LICENSE） | 运行环境内置 |
| npm / pnpm | 包管理器 | Artistic-2.0（npm）/ MIT（pnpm） | 随 Node 分发 |
| Debian GNU/Linux rootfs | Linux 用户空间 | 各包按 Debian 版权文件分别授权 | debootstrap 构建；各包许可证见 rootfs 内 `/usr/share/doc/*/copyright` |
| PRoot（`libproot.so` / `libproot-loader.so` / `libandroid-shmem.so`） | 用户态沙箱（chroot 替代） | GPL-2+（以源码 COPYING 为准） | 二进制来自 termux-packages 构建；源码见下方链接 |
| talloc（`libtalloc.so`） | 内存池（PRoot 依赖） | LGPL-3+ | 动态链接使用；源码见下方链接 |
| AndroidX / Jetpack | Android 兼容层 | Apache-2.0 | Gradle 依赖 |
| Jetpack Compose / Material3 / material-icons | UI 框架与图标 | Apache-2.0 | Gradle 依赖 |
| Kotlin stdlib / Coroutines | 语言运行时与异步 | Apache-2.0 | Gradle 依赖 |

## 再分发合规说明

- **PRoot（GPL-2+）**：本项目以二进制形式内置 PRoot 及配套 loader/shmem。对应源码可通过以下地址获取，或从 termux-packages 的 proot 包导出：
  - https://github.com/termux/termux-packages （proot 包）
  - https://github.com/proot-me/PRoot
- **talloc（LGPL-3+）**：动态链接使用，不修改源码；源码见 https://git.samba.org/talloc/ 或 termux-packages。
- **Debian rootfs**：由 Debian 官方仓库构建，仅作运行环境，未修改上游包源码；各包许可证遵循 Debian 版权文件。
- **DeepSeek Harness / Cordis**：MIT，以 npm 包形式随运行环境分发，未修改源码。

## 维护说明

更新运行环境或依赖后，请用 `gradle :app:dependencies` 与运行环境内 npm 依赖树核对本清单，保持组件与许可证同步；涉及 GPL/LGPL 组件的版本变更时同步更新上方源码链接。
