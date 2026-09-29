[English](README.md) | **中文**

# Interlock Relay

> **Interlock Relay** — 一个介于沙盒化 AI 代理与宿主应用能力之间的受控中继。
>
> 它把宿主应用的设备能力经单条基于文件的通道暴露给隔离沙盒。
> 每一次跨界都要通过一道由宿主掌控、可观测、可审计的互锁（interlock）。
>
> core 提供协议、传输、互锁与扩展点；UI、对话框、策略与执行的控制权保留在宿主手中。

`interlock-relay-core` 是一个 Android 库模块。它在沙盒化代理与宿主应用之间建立邮箱/控制通道，
把每次调用路由过审批互锁，分发到三个内建执行后端（无障碍、平台直连调用、Shizuku），并持久化
产物、配额与审计记录。所有面向用户的元素——界面、对话框、文案、语言、品牌——均由宿主通过
七个小接口（SPI）注入。

## 模块结构

```
interlock-relay-core/
├── src/main/java/interlock/relay/core/
│   ├── protocol/    线上类型、能力注册表、参数规格、副作用语义
│   ├── transport/   邮箱服务器、控制通道、信封编解码、沙盒侧资产
│   ├── interlock/   闸门、审批队列/中转器、提示卡、审批分级、看门狗
│   ├── exec/        无障碍 / 直连 / Shizuku 后端及其服务
│   ├── surface/     后端分发器与执行模式策略
│   ├── storage/     路径、配额账本、回收器、产物布局
│   ├── log/         运行日志、审计日志
│   ├── runtime/     RelayRuntime（装配入口）、容器、协调器、用户确认 Activity
│   └── spi/         下文列出的七个扩展点
├── src/main/assets/relay/relay-client.cjs   沙盒侧 CLI（自定位）
├── sample/          最小宿主装配
├── PROTOCOL.md      线上格式（提取自编解码器）
└── LICENSE / NOTICE
```

## 快速开始

```kotlin
// 沙盒启动时：
RelayRuntime.start(applicationContext, RelayConfig())

// 沙盒停止时：
RelayRuntime.stop()
```

不传参数时，`RelayConfig()` 采用内建默认值：系统语言文案、无呈现面（需要用户确认的调用随即
以 `E_GATE_NO_FOREGROUND` 失败）、内建 39 项能力表、默认路径策略、`SharedPreferences` 存储，
以及恒等脱敏器。各扩展点均提供可用的默认实现，宿主可整体替换其中任意一项，只覆写自己拥有的
部分：

| SPI | 用途 | 默认值 |
|---|---|---|
| `RelayText` | 本地化文案与当前语言标签 | 应用上下文、系统语言 |
| `RelaySurfaces` | 审批浮层/通知面、提问面、前台状态、通知渠道 | `NoSurfaces`（无可呈现内容） |
| `RelayCapabilities` | 能力表与可用性闸门 | 内建 39 项能力表，全部可用 |
| `RelayPathPolicy` | 沙盒挂载点、宿主目录名、CLI 名、产物前缀 | `/opt/interlock-relay`、`relay`、`relay-` |
| `RelayExecutor` | 增加/移除/替换执行后端 | 内建无障碍 + 直连 + Shizuku |
| `RelayPrefs` | 键值偏好存储 | `SharedPreferences`（`relay`、`relay_shell_verbs`） |
| `RelayRedactor` | 运行日志字段脱敏 | 恒等 |

最小宿主装配见 `sample/`：`SampleHostService` 在沙盒启动时调用 `RelayRuntime.start`、停止时
调用 `RelayRuntime.stop`，所有覆写项均可省略并回落到内建默认值。沙盒侧使用的线上格式见
`PROTOCOL.md`。

## 沙盒侧 CLI

宿主在每次通道启动时把 `assets/relay/relay-client.cjs` 写出为 `<mount>/bin/<cli-name>`。脚本
从自身所在位置推导入口目录、从自身文件名推导命令名，因此同一份字节在 `/opt/interlock-relay`
及任何其他挂载点下都正确。它提供 `call`、`status`、`cancel`、`doctor`、`capabilities`、`home`
与 `ask` 子命令。

## 兼容性

已部署自有挂载点与命令名的宿主，可通过 `RelayPathPolicy` 传入这些值，使沙盒侧保持字节一致
（下列值为示例；每个宿主传入自己的值）：

```kotlin
RelayConfig(
    pathPolicy = object : RelayPathPolicy {
        override val guestEntry = "/opt/myassistant"          // 沙盒挂载点
        override val hostDirName = "myassistant"              // filesDir 子目录（另有 -state/-stage/-audit）
        override val cliName = "myassistant"                  // bin/ 下的脚本名
        override val artifactPrefix = "myassistant-"          // 生成产物的名称前缀
        override val assetClient = "relay/relay-client.cjs"   // 打包内资产路径（不变）
        override val mediaAlbumDir = "MyAssistant"            // 媒体库相册目录
    },
)
```

线上协议（信封字段、错误码、退出码、能力 id、控制 op、提问语义）跨版本保持不变；信封字段、
各控制 op（`submit`、`status`、`cancel`、`health`、`ask`）、终态集合与退出码表均见
`PROTOCOL.md`。

## 构建

```
./gradlew test          # JVM 单元测试
./gradlew assembleDebug # 产出 build/outputs/aar/interlock-relay-core-debug.aar
```

要求 JDK 17 及以上，Android SDK `compileSdk = 36`、`minSdk = 29`。

## 许可

Apache License 2.0 — 见 `LICENSE`；第三方组件声明见 `NOTICE`。
