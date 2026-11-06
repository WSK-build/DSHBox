# 安全修复说明 — 2026-11-06

> English version: [SECURITY-FIXES-2026-11-06.en.md](SECURITY-FIXES-2026-11-06.en.md)

本文件说明分支 `fix/bump-vulnerable-deps`（基点 v1.5.1，commit e9ec1e8）所做的安全修复：改了什么、为什么改、如何验证、哪些没有验证、以及请作者发版时注意的后续动作。本分支只动版本钉版与配套清单/文案，不改任何功能代码。

## ① 改了什么

| 序号 | 组件 | 原版本 | 新版本 | 改动点 |
|---|---|---|---|---|
| 1 | Node.js（运行时层） | 24.19.0（旧 Dockerfile 线为 22.17.0） | **24.22.2** | `NodeSources.kt` 的 `NODE_VERSION` 常量、`build_node.sh`、`bundle.yaml`、`gen_profile.sh`、`Dockerfile`、`tools/` 两脚本，全部统一到 24.22.2 |
| 2 | Bouncy Castle（`bcpg-jdk18on`，传递 `bcprov`/`bcutil`） | 1.78.1 | **1.86** | `gradle/libs.versions.toml` 一处定版 |
| 3 | commons-lang3（commons-compress 的传递依赖） | 3.16.0（仅传递引入） | **3.18.0**（显式钉版） | 版本目录新增条目；`app` 与 `sandbox-manager` 两个使用 commons-compress 的模块各加一行显式依赖，覆盖传递版本 |
| 4 | pnpm（仅旧构建线，不随 APK 分发） | 9.15.0（另一脚本为浮动的 `pnpm@latest`） | **10.34.9** | `Dockerfile` 钉版更新；`install_dsh.sh` 的 `pnpm@latest` 改为钉死同一版本，保证可复现 |

改动文件共 23 个：上述钉版点，加上配套一致性同步——`THIRD_PARTY_NOTICES.md` 对应三行、6 个语种的运行时导入确认文案、构建文档与嵌入版本号的测试夹具。除版本与文案外无其他改动。

## ② 为什么改

| 序号 | 组件 | 漏洞与影响（一句话） |
|---|---|---|
| 1 | Node.js | **CVE-2026-111497**：Brotli 解码器越界写，高危，可达远程代码执行；已被 **CISA 列入 KEV（已知在野利用）清单**（2026-10-27），且有针对 arm64 的公开利用代码 proof-of-concept。Node 是本应用中智能体与插件代码的执行引擎，属本次最高优先级。另含同批修复的 TLS 主机名校验绕过（CVE-2026-106069 / CVE-2026-105483）。24.22.1 为官方安全修复版，24.22.2 为当前最新 24.x，在修复线之上。 |
| 2 | Bouncy Castle | OSV 共 6 条：**CVE-2025-14813（Critical）**、**CVE-2026-8763（Critical，证书约束绕过）**、**CVE-2026-3505（High，OpenPGP 解析资源耗尽拒绝服务——正打在本项目用它验签 Debian `Release.gpg` 的路径上）**、CVE-2026-5598（High）、CVE-2026-13506（High）、CVE-2026-0636（Moderate）。 |
| 3 | commons-lang3 | **CVE-2025-48924**（Moderate）：处理长输入时不受控递归，可致拒绝服务。随 commons-compress 传递进入 APK，3.18.0 为修复版。 |
| 4 | pnpm | OSV 对 9.15.0 共 25 条（12 High / 13 Moderate），含命令注入（CVE-2025-69262）与锁文件完整性绕过（CVE-2025-69263）。仅影响旧构建线在构建机上的安全，不进 APK，但一并修复并消除浮动版本。 |

## ③ 怎么验证的

| 序号 | 验证项 | 结果 |
|---|---|---|
| 1 | 新版本 OSV 复扫 | `bcpg-jdk18on` 1.86、`bcprov-jdk18on` 1.86、`bcutil-jdk18on` 1.86、`commons-lang3` 3.18.0、`pnpm` 10.34.9 全部 **0 命中** |
| 2 | Node 分发可用性 | `NodeSources` 配置的 6 个分发源（官方 + 5 镜像）均已提供 `v24.22.2/node-v24.22.2-linux-arm64.tar.xz`（实查 HTTP 200/302）；App 既有的 SHASUMS256 上游校验流程不变、直接适用 |
| 3 | 旧版号全仓 grep | 功能钉版已清零。仅两类例外，均为有意保留：`docs/TERMINAL_CLI_DETECTION_RECORD.md` 是带日期的历史检测记录（改了即改史，不动）；两处注释/清单文字中提及被覆盖的传递旧版本 3.16.0（是说明文字，不是钉版） |

## ④ 没验证的（请如实看待）

| 序号 | 未验项 | 说明 |
|---|---|---|
| 1 | **未做构建验证** | 审计环境无 JDK / Android SDK，Gradle 未能运行，实际解析出的依赖图（含传递依赖最终生效版本）未经机器验证。本 PR 的升版均为版本目录层面的常规变更，commons-lang3 钉版依据 Gradle「最高版本胜出」加显式直接依赖，但**请以作者侧 CI 构建结果为最终判定** |
| 2 | Debian 根文件系统不可定版审计 | rootfs 的 apt 包无版本锁定、无 SBOM 清单，出厂版本随构建日漂移，源码层无法逐包比对漏洞 |
| 3 | DSH 的 npm 依赖树不可全扫 | 随包的 DSH 层不在本仓、无 lockfile，主包无命中不等于整棵传递树无漏洞 |
| 4 | zstd-jni 无上游校验和 | 仓内三处副本内部一致性已核对一致，但仓库未记录上游制品校验和，无法与官方制品离线比对 |
| 5 | Node 层包构建期校验为 best-effort | `build_node.sh` 在本地无缓存校验值时可跳过校验，且不现场比对官方 SHASUMS256.txt（运行时在线导入路径的校验是完整的，此条仅指构建期脚本） |

## ⑤ 给作者的后续动作

| 序号 | 动作 | 说明 |
|---|---|---|
| 1 | 发版时按新版号重打包运行时层 | 源码钉版已先行；正式发版请用 24.22.2 重新构建 node 层包（`bundle.yaml` 的 sha256/size 由打包脚本回填） |
| 2 | 旧构建线版本脱节提示 | `runtime-bundle/Dockerfile` 与 `install_dsh.sh` 所在旧线仍钉 DSH 0.1.0-rc.6，与现行 0.2.1-rc.5 脱节；本 PR 已把该线的 Node/pnpm 升到安全版本，但该线是保留还是退役，请作者定夺 |
| 3 | 建议（非阻塞） | 条件允许时为 rootfs 产出 SBOM、为 DSH 层保留 lockfile，可让后续安全审计从「抽查」变「全扫」 |

—— Nikita Law，2026 年 11 月 6 日
