[English](README.en.md) | **简体中文**

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

**DSHBox** is an open-source app that runs the full **DeepSeek Harness (DSH)** locally on Android phones and tablets. It brings DSH, PRoot, System WebView or embedded GeckoView, Debian and Node.js together with a terminal, file manager, AI browser assistant, phone assistant and optional developer tools to form a personal AI workbench. No root access or separate Termux installation is required for normal use.

**DSHBox** 是一个可在安卓手机和平板上本机运行完整 **DeepSeek Harness（DSH）** 的开源应用。它将 DSH、PRoot、系统 WebView 或内置 GeckoView、Debian、Node.js 集成协调在一起，配套终端、文件管理、AI 浏览器助手、手机助手与可选开发者工具，组成个人 AI 工作台。日常使用无需 Root，也无需单独安装 Termux。

---

## 快速安装

| 项目 | 说明 |
|---|---|
| 系统要求 | Android 10+ · ARM64 |
| 权限 | 日常使用无需 Root · 无需 Termux；开发者模式中的 Root 通道为可选能力 |
| 发布版本 | **系统 WebView 版**：APK 较小，复用手机系统 WebView；**内置 Gecko 版**：随包提供 GeckoView 158，不依赖系统 WebView |
| 内置 | DSH `0.2.1-rc.5` · PRoot；系统 WebView 或 GeckoView；Debian 与 Node.js 在首次运行时通过多镜像源检测在线获取，或离线导入 |
| 首次准备 | 按分步引导安装精简 Debian 与 Node.js，支持多镜像源检测、失败重试或离线导入；系统 WebView 版还会检测 provider 与启动前兼容能力 |
| **如何选择** | 系统 WebView 较新且工作正常时，可优先选择体积较小的系统 WebView 版；系统 WebView 较旧、白屏、受厂商定制影响，或希望固定渲染内核时，选择内置 Gecko 版。两个 APK 同包名、同签名，覆盖安装会保留 Debian、sandbox 与 user-data。 |

**[下载系统 WebView 版或内置 Gecko 版](https://github.com/WSK-build/DSHBox/releases/latest)** → 覆盖安装 → 启动应用 → 按引导获取运行环境（在线多镜像源，或沿用离线导入）→ 打开 DSH。

---

## 更新记录

当前版本 **v1.5.1**（2026-10-31）：**系统 WebView / 内置 Gecko 双渲染 · 开发者模式 · 插件市场增强。**

1. 新增**系统 WebView 版与内置 Gecko 版双 APK**，共用同一套 DSH 页面和 App UI；补齐 System WebView 启动期兼容能力，并由 GeckoView 158 为低版本或特殊 WebView 环境提供独立渲染路径；
2. 新增**开发者模式**，提供独立于 Pilot 审批体系和 Shizuku 的完整 ADB / Root 通道；ADB 可在开关开启时挂载进 Debian 沙箱，既可连接本机服务，也可调试外部设备；
3. **插件市场与工具链升级**：内置 pnpm 11.1.2、插件兼容与安装守卫、已安装状态和目录刷新机制；修复 DSH“添加插件”、第三方安装顶出随包插件等问题；
4. **DSH 页面兼容与交互修复**：修复链接错误刷新 DSH、账号登录不跳转、右栏资源、配置文件打开、401 恢复、Gecko 键盘顶栏及低版本 WebView 白屏等问题；
5. **官网与发布流程升级**：官网分别提供两个 APK，Release 统一输出 `dshbox-v1.5.1-system-release.apk` 与 `dshbox-v1.5.1-gecko-release.apk` 并执行发布契约检查。

各版本完整变更见 **[CHANGES.md](CHANGES.md)**。

---

## 核心特性

### DeepSeek Harness 全内嵌与双渲染（v1.5.1）

- DSH `0.2.1-rc.5` 随 APK 内置，层内容保持上游纯净；首启按**版本仲裁**装配到 `runtime-current/dsh`，换层时旧层单份备份到 `previous/dsh`，不触碰用户数据。
- **系统 WebView 版 / 内置 Gecko 版**：两个发布变体共用唯一 DSH 页面、宿主导航、文件交付、诊断与日志实现，差异集中在 Renderer Adapter；两个 APK 同为 `com.dshbox.app`、同一签名，可覆盖切换并保留 `filesDir`。
- **全屏 DSH 工作区**：DSH 标签页在本地地址上自动解析 launchToken、使用移动 UA、处理键盘与视觉视口并支持双指缩放；可拖动悬浮球继续提供刷新和首页、文件、终端、Browser 快捷切换，首页保留“浏览器”和“更 多”入口。
- **系统 WebView 兼容**：在 DSH 主脚本执行前补齐启动期所需 API，并通过单次 document-start 注入进入页面；支持 document-start 的内核继续复用系统 WebView，不支持的内核会明确阻止导航并提示升级。
- **内置 GeckoView 158**：完全使用随包渲染内核，不依赖系统 WebView，不静默回退；处理 DSH 账号 popup、外链、401、文件选择、页面状态、右侧栏和键盘视口等差异。
- **DSH 访问设置**：首页 DSH 卡片可进入全屏访问设置页，查看并复制带 token 的完整地址、设置外部打开方式、复制连接信息并查看运行时间。
- **运行期硬链接兼容垫片**：安卓应用数据分区（FBE/FUSE）拒绝硬链接，而 DSH 使用 `link()` 作为“不覆盖发布文件”的原语，会导致会话持久化、文件工具和附件发布失败。App 启动时通过 `node --import` 替换 `node:fs/promises.link`；遇到平台拒绝时退化为保留不覆盖语义的内容拷贝。
- **DSH 源码零修改**：兼容逻辑位于 App、渲染适配层、内置扩展和运行环境接线；随包 DSH 层保持上游文件与业务逻辑不变。
- 前台服务通知带「启动 / 重启 / 关闭」快捷操作；页面刷新和重新认证统一使用当前 launchToken，DSH 重启或健康循环换 token 后不会继续请求旧地址。

### AI 浏览器助手（Browser，v1.5.0 新增）

独立的内置浏览器工作区，通过随包 `browser-use` 插件与沙箱 CLI 向 DSH 开放 **24 项工具**。新用户默认装配，可在首页 Cordis 页签的「DSH浏览器自动化」开关中独立控制。

- **网页操作**：搜索、导航、多页面管理、读取内容、定位元素、点击、输入、滚动及条件等待；搜索引擎可选必应、谷歌和百度，默认必应。
- **选区反馈与本地预览**：选取网页元素或区域，附截图和评论提交给已绑定的 DSH 会话；沙箱中的 HTML 等文件可通过 CLI 启动本地静态服务，也可登记已有服务，在 Browser 中预览并反馈修改意见。
- **截图与调试**：支持前台和后台网页截图；普通后台截图失败时，已开启高级调试才会串行尝试 CDP 后备，否则返回明确提示。高级调试提供 DOM、控制台、脚本求值及网络抓取、HAR 导出等能力，请求拦截、修改与重放按独立授权开放。
- **文件与记录**：持久历史、收藏、多页面和下载管理；AI 可准备上传文件，经用户批准后交给网页。任务记录可查看、编辑、删除并向 AI 发送续接摘要。
- **控制权与界面**：用户接管 AI 操作前确认，AI 控制关闭时可申请授权；支持主题与六语跟随、移动/桌面/宽屏模式、可设置的闲置关闭时间，以及点击 AI 标记快速返回 DSH。

Browser 负责**内置网页**，Pilot 负责**手机原生应用**，由 DSH 选择调用。复杂跨页面任务恢复、长时流式响应及部分媒体、站点兼容场景仍待完善，不保证所有任务均可全自动完成。

### 手机助手（Pilot，v1.4.0 新增）

内建 **39 项手机操控能力表**，并在内嵌沙箱与安卓应用层之间建立一条**信箱通道**（文件投递，无网络、无共享内存），让沙箱内的 DSH 安全可控地操控与连接手机。采用「UI 适配 + 内核平台」两层结构嵌入；内核以 **Apache-2.0** 独立发布于 [interlock-relay](https://github.com/WSK-build/interlock-relay) 仓库，本仓库调用并做 UI 层适配。

| 执行通路（三类） | 说明 |
|---|---|
| 无障碍模式 | AI 在前台直接操控屏幕：点击、滑动、长按等等 |
| Shizuku 特权模式 | 打通系统 shell 级（adb）权限；危险操作（如删除类）被严格限制，部分权限留给用户自行开启、风险自担 |
| 平台直连调用 | 走系统正常渠道的一系列功能调用 |

| 执行模式（两种） | 说明 |
|---|---|
| 前台 | 用户可见地直接操作，配合无障碍 / 平台直连通路 |
| 后台虚拟屏 | 用户在前台正常使用设备、AI 在后台操控；在部分应用的限制下自动降级回前台操作 |

- **审批与交互约束**：上述每条链路都施加约束，可按能力选择「禁止 / 询问审批 / 完全访问」；通过弹窗与用户交互。
- **悬浮窗提问**：离开 DSHBox 页面时，AI 可召唤悬浮窗提问来与用户实现沟通，用户可选择相关条目、全部驳回或重新提问。
- **给 DSH 的原生工具**：随包 DSH 插件 `@local/mobile-pilot` 提供一组 `phone_*` 工具，agent 可直接调用我们的内核信箱机制。此插件可在「Cordis」内的「DSH连接手机」处自由开启。

### 开发者模式（v1.5.1 新增）

从 Pilot 进入独立的“开发者模式”面板。该面板提供的是面向高级用户的完整能力，不属于 Pilot 手机助手的受控审批体系：

| 通道 | 能力与边界 |
|---|---|
| ADB | 开关开启时把完整 ADB 客户端及依赖挂载到 Debian 沙箱，关闭时移出；支持本机服务、无线调试配对、外部设备调试、端口与自定义密钥。 |
| Root | 使用设备现有的 `su` 实现，在 guest 内提供 `android-root` CLI；支持访问码、失败锁定、Unix/TCP 传输与权限预检。 |

- **与手机助手隔离**：ADB / Root 不经过 Pilot 的“禁止 / 询问审批 / 完全访问”逐项审批、权限分级或动作拦截。用户开启开关后自行决定用途，并对自己执行的调试、系统或 Root 操作及其后果负责；原手机助手通道继续保留受控权限和审批保护。
- **与 Shizuku 隔离**：开发者模式 ADB 不依赖 Shizuku，可作为部分 Shizuku 兼容性或未解决问题的替代路径；两者的开关、密钥、server 配置和生命周期独立。
- **外部设备调试**：该 ADB 不仅能连接手机本机服务，也可通过网络连接其他设备。ADB 通道关闭后，相关命令、server 入口和挂载均从沙箱撤除。
- **使用前须知**：无线调试要求设备支持该能力且网络可互通；Root 要求设备已有可用 `su`，并可能受 Magisk、KernelSU、APatch、隐藏策略和厂商 ROM 差异影响。

### 插件管理与插件市场（v1.4.0 新增，v1.5.1 增强）

从首页 Cordis 页签进入，插件相关能力集中管理：

| 能力 | 说明 |
|---|---|
| 随包自制插件开关 | 「DSH移动端页面适配」（`dsh-mobile-adapt`）、「DSH连接手机」（`mobile-pilot`）与「DSH浏览器自动化」（`browser-use`）；新用户默认装配，升级保留明确关闭的设置，装配与更新按 DSH 就绪状态处理。 |
| DSH 官方插件 | 把上游默认关闭的官方条目做成开关（如右侧边栏内置浏览器） |
| 绝对安全模式 | 不加载任何第三方插件启动，用于第三方插件导致 DSH 崩溃时恢复启动 |
| 插件加载记录 | 按启动分段查看插件加载情况，便于排错 |
| 崩溃修复辅助 | 内置终端 Agent **opencode**，一键安装 / 更新 / 卸载；终端输入 `opencode` 唤起 TUI，用自然语言交互，完成修复 |
| 插件市场 | 位于 App 层，提供分类浏览、搜索、兼容判定、安装守卫及第三方插件安装 / 更新 / 删除入口 |
| 已安装插件 | 先显示本地清单，再异步加载远程目录；区分已生效、重启后生效、待停用、宿主拒绝、不兼容和损坏状态，避免弱网下整个页面持续等待 |
| 随包插件保护 | 移动端适配、Pilot 与 browser-use 写入 profile dependencies；安装、更新或删除第三方插件后，不会被 pnpm 重算 `node_modules` 顶出 |

插件市场内置 **awesome-dsh-plugin** 与 **[awesome-dsh-mobile-plugins](https://github.com/WSK-build/awesome-dsh-mobile-plugins)** 两大实时数据源，后者为 DSHBox 专为移动端 DSH 插件生态新建的仓库，用户可以提交 PR，共建生态。

- **内置 pnpm 11.1.2**：随包提供 Corepack 缓存，预置 npm 官方与中国大陆镜像探测；pnpm 的 store、registry 和 Corepack 环境在 DSH、官方“添加插件”入口、插件市场与终端中使用同一配置。
- **官方“添加插件”已接通**：支持输入包名或链接后拉取插件信息、安装及卸载；修复 `pnpm` 找不到、registry 参数不被接受和 store 路径不一致等问题。
- **已安装状态按生效时机展示**：启用或停用后需要重启 DSH 才能生效的条目会显示“重启后生效”，宿主拒绝、不可兼容、基础库与市场自身等不可操作项不再显示无效开关。
- **兼容边界**：市场会提前检查已知宿主版本、载体和依赖问题，但不能保证所有第三方插件在任意 DSH 版本上可用。升级 Alpha 前仍应备份；从 Alpha 回退 RC 可能出现会话数据兼容问题。

### DSH 移动端与双渲染适配（v1.5.1）

内置 DSH 已升级到 **0.2.1-rc.5**。System WebView 与 Gecko 两条渲染路径共用同一套 DSH 页面和宿主逻辑；适配集中在 App、Renderer Adapter、内置页面兼容资源与扩展中，**不修改 DSH 源码或业务逻辑**：

| 组件 | 移动端差异或断点 | 处理方式 |
|---|---|---|
| System WebView 启动 | 旧内核缺少 DSH 依赖的标准 API，或没有 document-start 能力 | 在页面脚本执行前补齐能力；无法安全进入启动前阶段的 provider 明确阻断并提示升级 |
| Gecko 页面桥接 | GeckoView 不具备 System WebView 相同的回调和扩展环境 | 使用内置扩展、页面状态桥、导航代次和原生事件实现 DSH 所需能力 |
| 账号登录 | DSH 先打开空白 popup，再异步取得授权地址；不同内核处理不同 | System 与 Gecko 都在受信 DSH 来源下识别 popup，需要浏览器授权时跳转系统默认浏览器 |
| DSH 内链接 | 直接在主框架加载链接会覆盖当前工作区；侧边栏 iframe 需要保留 | “默认浏览器”把顶层非同源 HTTP(S) 交给系统浏览器；“应用内侧边栏”目标保持 iframe，不误拦截 |
| 401 与重新登录 | 并发导航、重定向和迟到回调可能把状态归到错误文档 | 以导航 nonce、请求头状态和文档状态关联当前代次；旧代次结果丢弃 |
| 附件上传 | 文件选择、多选和来源判定需要原生接线 | 提供拍照、录像、相册、手机文件、音频、工作区与共享区；去重多选结果并清理 `accept` 哨兵 |
| 语音输入 | WebView 录音需要额外的音频设置权限 | 声明所需权限并接入 DSH 语音输入 |
| 右栏文件预览 | 非标准 scheme 的 authority/host 解析存在内核差异 | 在 App 层补齐 `dsh-resource://` 解析；System 与 Gecko 使用同一语义 |
| “打开配置文件” | 旧方案依赖页面拦截，插件关闭或执行时机会导致失败 | 运行环境提供原生 `xdg-open` 链接，由 App 打开配置文件查看器；已存在文件不覆盖 |
| 键盘与顶栏 | Gecko 弹起键盘时可能上移整个布局视口 | 仅对 Gecko 补足视觉视口差额并固定会话顶栏；System WebView 保持原行为 |
| 右侧边栏终端 | guest 的 `passwd` 缺运行期 uid，`os.userInfo()` 可能失败 | 运行期把当前 uid 补齐进 guest `passwd` |
| 右侧边栏浏览器 | 上游默认关闭对应官方插件 | 在插件管理面板提供开关，重启 DSH 后生效 |
| 拍照 / 录像 | DSH 页面需要系统相机 / 录像入口 | 通过 `onShowFileChooser` 的 accept 哨兵改走系统拍摄界面 |
| 页面诊断 | 加载完成不等于页面已就绪，主线程能力也不等于 Worker 能力 | 诊断分别记录文档、主线程 API、Worker、Gecko 与页面状态，不伪造“已支持” |

设备返回键、自动聚焦、悬浮快捷切换和附件来源面板沿用 v1.5.0 完成的能力。v1.5.1 进一步把与插件开关绑定的 URL 解析、配置文件打开和 Gecko 键盘处理迁入 App 或明确限定为 Gecko 分支，使 System WebView 不会被 Gecko 专属逻辑干扰。

### PRoot 分层运行环境（无需 Root）

四层独立装配，PRoot 用户态沙箱与 Android 宿主隔离，运行环境与用户数据（`user-data/` → guest `/root/projects`）互不写入：

| 层 | 内容 | guest 挂载点 |
|---|---|---|
| base | Debian 13 (trixie) 精简版 rootfs | `/`（rootfs） |
| node | Node.js 24（npm / npx / corepack） | `/usr/local` |
| dsh | DeepSeek Harness `0.2.1-rc.5`（npm 包，纯净上游） | `/opt/dshapp/runtime` |
| android-side | PRoot / loader / shmem（宿主侧） | — |

- 沙箱 keepalive 与 DSH 为两个独立 PRoot 进程；停机按 `/proc` 枚举整棵进程树、子进程优先 SIGKILL，不留孤儿、不占端口
- 每层带 SHA-256 哨兵，启动时逐层校验完整性，损坏可识别、可重装
- v1.4.0 起，**`base` 与 `node` 两层不再随包内嵌**（APK 内只留 `dsh` 与 `android-side`）：它们由「在线获取运行环境」（精简 Debian 层 / Node.js 层，多镜像源可选、可重置）或「离线导入整包」安装。在线组装出来的层会裁掉非必要语言包与文档等无关组件（即「精简 Debian」），占用更小

### 系统文件共享区（v1.5.0 新增）

- 授权后在手机公共存储创建或沿用 **DSHBox** 文件夹，文件管理、DSH、沙箱与终端可访问同一份本地文件，也便于通过其他应用或电脑传入、取出。
- 可选定其他系统公共目录，以独立名称挂载到沙箱共享区；文件管理和附件来源面板均提供入口。新挂载按提示重启相关运行会话后生效。
- **取消挂载只解除关系，不删除源目录或文件**。公共共享目录内容不会随 App 卸载或重装而自动删除；重装后需重新授权，额外挂载关系需重新配置。用户主动删除文件仍会生效。

### 文件管理（v1.2.0 重点增强）

- **目录与视图**：工作区 `/root/projects`、沙盒根（叠加 node、DSH 层）与共享区入口，面包屑导航、列表 / 网格、按名称 / 时间 / 大小排序、新建文件夹、多选批量操作
- **新建文件**（v1.4.0 新增）：可在当前目录直接创建空文件
- **安装到沙箱**（v1.4.0 新增）：选中 `.deb` 可直接解包安装进沙箱
- **移动到指定文件夹**：全屏目标选择器（沙盒 / 工作区切换、可新建文件夹、源自身及子孙目录置灰防环、跨挂载点落点提示）；冲突三策略（覆盖 / 跳过 / 自动改名 + 应用到其余全部）；同卷 `renameTo` 优先、失败复制兜底（保留权限位 / 时间戳）；「重命名」走同一引擎
- **导入**：文件多选批量导入、压缩包解压导入，逐件冲突决策、可取消、完成汇总；ZIP 中文名编码修复，加密 zip 明确拒绝
- **导出**：多选导出到目录（SAF），或打包为 ZIP
- **外部接收与分享**（v1.5.0 新增）：接收其他应用「分享到」或「用其他应用打开」的文件并选择保存目录，支持多文件与纯文字；文件菜单可直接调起系统分享面板，与导出功能分开
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
| 在线获取运行环境 | 按 **Node 层 / 精简 Debian 层** 分别探测对应镜像源（可达性与延迟）、下载、GPG 验签、裁剪与组装成层；分步进度、可取消，失败按原因分类提示（镜像缺包 / 验签失败 / 空间不足等）；可随时重置任意层 |
| 离线导入运行环境包 | 整包替换 base / node / android-side，逐层 SHA-256 校验，`previous/` 单份可回滚（详见下文）；v1.4.0 起不随 APK 分发，但仍会在 Release 页面单独提供，与在线获取等效。 |
| 插件管理 / 插件市场 | 由首页 Cordis 页签进入：市场浏览与安装、已安装管理、官方插件开关、绝对安全模式、插件加载记录、崩溃修复辅助（详见上文「插件管理与插件市场」及兼容说明） |
| 诊断与日志 | DSH / 沙箱 / 访客命令日志可滚动、复制与导出；渲染诊断区分 System provider、Gecko 版本、页面文档状态、主线程 API、Worker 环境和已知视觉能力，不把加载完成或版本号单独当作页面可用结论 |
| 用户反馈 | GitHub Star 与问题反馈两条入口，均先弹窗说明再跳转 |

### 存储占用与清理

- 占用按系统同口径统计（分配块、硬链接去重、含应用缓存），进设置页自动刷新、可手动刷新
- 清理项独立勾选：应用缓存 / 访客临时文件（运行中仅清 24h 前条目）/ 运行日志（截断）/ apt 下载缓存；回滚备份可选并明确警告
- 清理与后台安装 / 导入互斥，绝不触碰 `user-data/.dsh` 与运行环境本体

### 终端

- 多窗口：沙盒终端（PRoot Debian 完整环境，bash / vim / htop / node / npm / apt / git / python3 / ssh 开箱可用）/ 受限 shell 兜底，浮动控制面板新建 / 切换 / 关闭
- **直接操作 DSH CLI**（v1.4.0 新增）：沙箱内可直接运行官方 `dsh` 命令（web / headless / tui / plugin），与网页端同一份 DSH、同一个 profile
- **开发者模式命令**：开启对应开关后，sandbox 内可使用完整 `adb`；Root 通道开启且设备已授权时，可使用 `android-root`。关闭开关会撤除对应命令入口
- **随包小工具补齐**（v1.4.0 新增）：`jq`、`sqlite3`、`patch`、`nano`、`strings`（binutils）等 AI 执行任务时常用的系统工具；工具包**自愈** —— 换层或升级后检测到缺失会自动补装，不必清数据重来
- 两行辅助按键栏（ESC / TAB / HOME / END / CTRL 粘滞 / 粘贴 / 方向键 / 翻页 / 退格 / 删除，DECCKM 感知），双指缩放字号 8–40sp
- 基于 Termux terminal-emulator / terminal-view（v0.118.0，未修改）+ 自研 `terminal-session` 会话层

### 多语言适配（v1.3.0）

- 界面全面本地化：联合国六种官方语言——English / 简体中文 / العربية / Español / Français / Русский；默认跟随系统，未支持语言回退英文
- 设置 → 外观新增「语言」选择器：六语 + 跟随系统，即选即生效、无需重启；通知、弹窗、终端菜单等均随语言切换
- 数量文案按语言复数规则处理（阿拉伯语 6 形态 / 俄语 4 形态等）；文件大小、时间戳、Hex 偏移等技术性数字保持西文
- 布局方向：全局恒 LTR（阿拉伯语文本按 Unicode bidi 正常渲染），页面导航与布局不整体镜像
- 内置 i18n 一致性门禁（键位 / 占位符 / 复数形态 / 硬编码扫描），随 CI 校验六语资源

## 界面（底部 5 个标签）

| 标签 | 功能 |
|---|---|
| 首页 | 控制 / Pilot / Cordis 三页签：沙箱与 DSH 状态、启动 / 重启 / 关闭、系统状态、手机助手、开发者模式入口与插件管理；设置入口位于右上角 |
| 文件 | 工作区 / 沙盒 / 共享区浏览、移动 / 重命名 / 删除、多选批量、导入 / 导出 / 分享、外部文件接收、查看器 / 编辑器、搜索 / 排序、新建文件 / 文件夹、`.deb` 安装到沙箱 |
| DSH | 使用 System WebView 或内置 GeckoView 加载 `http://127.0.0.1:3080`，自动认证、键盘与页面兼容、悬浮球刷新和模块快捷切换 |
| 终端 | 多窗口终端、辅助按键栏、控制面板、可直接运行 `dsh` CLI |
| Browser | 内置浏览器与 AI 网页助手、元素 / 区域反馈、本地网页预览、历史 / 收藏 / 下载及调试设置 |

设置已迁至首页右上角，保留外观（主题 / 语言）、存储与清理、App 更新、DSH 与运行环境更新 / 导入、诊断日志、反馈与关于。

## 已知限制

- **System WebView 兼容范围取决于 provider 能力**：已实测部分 WebView 101 / 124 环境可完成主要 DSH 页面流程；缺少 document-start 或所需标准 API 的旧内核会被阻止进入并提示升级。系统 WebView 版本号不能单独保证所有页面均正常。
- **Worker / PDF 不等同于主页面兼容**：System WebView 的 Worker 使用独立环境，当前提供诊断而不注入 Worker 兼容补丁；PDF、Excel 或依赖高版本 Worker API 的流程可能需要更新 WebView，或使用内置 Gecko 版。
- **两个 APK 不能同时安装**：系统 WebView 版与 Gecko 版同包名、同签名，只能互相覆盖；安装另一个版本不会清空 Debian、sandbox、user-data 或已创建的工作区。
- **开发者模式属于高级能力**：ADB / Root 不经过 Pilot 审批体系。用户应理解无线调试、外部设备访问和 Root 命令的风险，并自行承担操作后果。不同 ROM 的 `su`、无线调试、隐藏与授权行为可能不同。
- **插件兼容由宿主与插件共同决定**：市场会检查已知宿主能力、载体和依赖差异，但不能保证任意第三方插件在所有 DSH 版本上均可安装或运行。
- **Gecko 静态 content script 在个别新系统上存在平台差异**：当前需要桥接的登录、外链、配置文件与导航关键路径已使用原生事件、扩展状态桥或运行环境链路；未来新增页面主世界注入功能时仍需重新验证。
- **升级 Alpha 前应备份**：从稳定 RC 升级到 DSH Alpha 后，回退 RC 可能出现会话数据兼容问题。

## 从源码构建

| 环境 | 版本 |
|---|---|
| JDK | 21（官方 CI 使用 Temurin 21） |
| Android SDK | compileSdk 37.2 · targetSdk 36 · minSdk 29 |
| Gradle | wrapper 9.5.0（AGP 9.3.1 · Kotlin/Compose 编译插件 2.4.20） |
| Kotlin | 编译器版本 2.4.20；源码语言/API 固定 2.2，JVM target 17 |

> 运行环境大层**不在本仓库**；由于体积较大，改为在 GitHub Release 中作为独立附件发布（见下节）。构建前请先获取 `../runtime/`。

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleSystemWebViewRelease :app:assembleGeckoRelease
pwsh tools/assemble_release_apks.ps1
```

Release 归档后输出：

- `apk/dshbox-v<version>-system-release.apk`
- `apk/dshbox-v<version>-gecko-release.apk`

归档脚本会继续执行发布契约门禁，检查两个 APK 的 DSH 兼容资产、Gecko 扩展权限、脚本哈希、包名和签名。

| 模块 | 职责 |
|---|---|
| `app` | 全部 UI（5 个标签页、DSH 访问设置、查看器、设置 / 诊断 / 更新页）、双渲染变体、前台服务、在线更新编排与运行环境获取 |
| `dsh-renderer` | `api` 共享渲染契约、导航鉴权与文件交付；`system` 系统 WebView 兼容与能力探针；`gecko` GeckoView 158 渲染、扩展、弹窗与文件选择 |
| `sandbox-manager` | 分层运行时装配、PRoot 进程管理、DSH 层仲裁与更新、导入 / 校验 / 清理 |
| `common` | 常量、npm 镜像源、版本比较、日志脱敏 |
| `bridge` | WebView JS Bridge 安全框架（预留 stub） |
| `terminal-session` | 终端会话层（多窗口、PRoot 终端命令构建） |
| `terminal-view` / `terminal-emulator` | Termux 终端库（v0.118.0，未修改） |
| `plugin-manager` | 插件管理：装配控制、插件市场（目录取数 / 兼容判定 / 安装守卫）、官方插件开关、绝对安全模式、插件加载记录、崩溃修复辅助（opencode） |
| `pilot`（`interlock-relay-core` + `dshbox-adapter` + `developer-access`） | 手机助手内核与 UI 适配；开发者模式的 ADB、Root、无线配对、设备、密钥和通道面板 |
| `browser`（`browser-api` / `browser-runtime` / `browser-bridge` / `browser-devtools`） | 浏览器契约、WebView 会话运行时、沙箱工具桥接与受控高级调试；界面由 app 接入 |

运行环境层构建脚本见 `runtime-bundle/`（各层 `tar.zst` 由 `build_base.sh` / `build_node.sh` / `build_android_side.sh` 在 Linux/WSL2 构建）；构建手册见 `docs/BUILD_RUNBOOK.md`，预检脚本 `tools/pipeline_dryrun.sh`。注意：开发者基于源码自行构建时，必须先从 Release 发布的运行环境压缩包中解压并提取 `android-side` 层，放到构建所需的 `runtime/android-assets/runtime/android-side.tar.zst`；否则编译出的 APK 缺少该运行层，无法正常安装运行环境。

注：Release 页面随版本一并提供打包好的运行环境整包（三层：base / node / android-side，独立于 APK），可直接下载使用。v1.4.0 起 APK 内只内嵌 `dsh` 与 `android-side` 两层；`base` 与 `node` 两层不再进包，由「在线获取运行环境」现场组装，或由该整包离线导入。

## 运行环境大文件（不在本仓库）

| 发布包内路径 | 内容 |
|---|---|
| `runtime/android-assets/runtime/android-side.tar.zst` | 宿主侧 PRoot / loader / shmem，带 `.sha256` 侧车（**内嵌进 APK**） |
| `runtime/android-assets/dsh/0.2.1-rc.5.tar.zst` | DSH 层 + `.sha256`（纯净上游，不打补丁；**内嵌进 APK**） |
| `runtime/offline-baseline/{base,node}.tar.zst` | Debian 层与 Node 层，各带 `.sha256` 侧车与 `runtime-profile.json`；**不进 APK**，作为离线整包的一部分交付 |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | 对外交付的运行环境整包（离线导入用） |

- 引用方式：`app/build.gradle.kts` 通过 `assets.directories.add("../../runtime/android-assets")` 引用发布目录，构建时内嵌进 `assets/runtime/*` 与 `assets/dsh/*`
  - 判定机制就在这句话里：**放进 `assets.directories.add` 指向的目录才会进包**；`base` / `node` 移到 `runtime/offline-baseline/` 之后即不再内嵌。实测 APK 内条目与此吻合——只有 `assets/dsh/*` 与 `assets/runtime/android-side.tar.zst`
- 构建前置：`assembleSystemWebViewRelease` / `assembleGeckoRelease` 前需先获取 `runtime/`，目录缺失则无法内嵌完整运行环境；v1.4.0 起该前置只对 `dsh` 与 `android-side` 两层成立
- 签名密钥（`keystore.properties`、`local.properties`）不在仓库内：用 `tools/create_keystore.sh` 自建开发签名，未配置时 release 构建回退 debug 签名

## 离线导入运行环境包

- 交付形式：单文件 `dshapp-runtime-debian-arm64-0.1.0.zip`，设置页「离线导入运行环境包」选择即可；与「在线获取运行环境」并存
- 布局与格式：外层 zip / tar；层归档 `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz`（按魔数识别），兼容一层目录前缀与外层 tar 装层归档
- 校验：逐层 SHA-256（侧车与 `runtime-profile.json` 交叉核对）+ Zip-Slip 防护；损坏 / 截断 / 加密包返回可读错误
- 替换：旧本体移入 `previous/`（单份可回滚），绝不触碰 DSH 层与 `user-data/.dsh`
- DSH 离线更新走「更新 DSH（离线导入）」单文件层包（如 `0.2.1-rc.5.tar.zst`），与在线更新并存。
## 用户交流与反馈：  
- **QQ交流群**：2163082322
- **Bilibili账号**:搜索“文大师啵啵”
- **问题反馈**：[GitHub Issues](https://github.com/WSK-build/DSHBox/issues) 
## 许可证

本项目采用 **GPL v3**（见 [LICENSE](LICENSE)）。第三方组件按其各自原许可继续适用，详见 `THIRD_PARTY_NOTICES.md`：

- PRoot（GPL-2+）· talloc（LGPL-3+）· Debian rootfs（按各包 Debian 版权文件）
- DeepSeek Harness / Cordis（MIT）· Node.js（MIT）
- Termux terminal-emulator / terminal-view（Apache-2.0，v0.118.0 未修改）
- GeckoView 158（MPL-2.0 及内含组件各自许可；仅内置 Gecko 版包含，未修改上游 AAR）
- ZXing Core 3.5.3（Apache-2.0，开发者模式访问码二维码）
- sora-editor（LGPL-2.1-or-later，aar 未修改）· Markwon（Apache-2.0）· commons-compress（Apache-2.0）· zstd-jni（JNI 包装层 BSD-2-Clause，Zstandard 内核 BSD-3-Clause）
- interlock-relay-core（Apache-2.0，手机助手平台层，独立发布，源码随本仓库分发）· Shizuku（MIT，手机助手的 shell 身份执行通路）

三个自有插件 `dsh-mobile-adapt`、`mobile-pilot` 与 `browser-use` 各附完整 LICENSE、NOTICE，并声明为 **GPL-3.0-only**；第三方依赖保留各自原许可。可在「设置 → 关于 → 开源许可」离线查看随包许可，来源与源码重建说明见 [`licenses/README.md`](licenses/README.md)。

**品牌标识不在上述授权范围内。** App 名称、应用图标与应用内自有品牌图形（清单见 [`TRADEMARK.md`](TRADEMARK.md)）由项目权利人独占持有，全部权利保留；未经许可不得用于修改版 / 衍生版，或用于可能造成混淆的标识。
