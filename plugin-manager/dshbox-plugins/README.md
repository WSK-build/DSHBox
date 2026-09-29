# dshbox-plugins — DSHBox 自有插件的源码与载荷

本目录集中存放 **DSHBox 自己维护**的插件相关内容（既非上游 DSH 自带，也非插件市场里的
第三方插件）。以后新增自有插件也放这里。

## 目录结构

```
dshbox-plugins/
├── assets/                     运行期载荷 —— 随 APK 交付，运行期按 asset 路径读取
│   ├── plugins/dsh-mobile-adapt/   移动端适配包（install.sh / uninstall.sh / plugin/）
│   ├── plugins/mobile-pilot/       DSH连接手机包（同形；插件本体当前是空壳）
│   └── dshbox/                     连接垫片交付物（link-shim.mjs / libdshbox-link.so）
└── tools/                      构建期源码 —— 不进 APK，仅开发 / 构建 / 测试时使用
    ├── link-shim/                  连接垫片的 C 源码与构建脚本（产出 libdshbox-link.so）
    ├── link-shim-test/             垫片的真机验证脚本（node .mjs）
    └── mobile-adapt/               装配态探测脚本的矩阵测试（真实 shell 里跑同一模板）
```

`assets/` 与 `tools/` 的分界只有一条依据：**是否要打进 APK**。前者要，后者不要。

## 为什么 `assets/` 不在默认位置

Gradle 默认只把 `src/main/assets` 当资产目录。本模块在 `build.gradle.kts` 里额外登记了
`dshbox-plugins/assets`（`sourceSets["main"].assets.srcDir(...)`），以便把载荷与构建期源码
放在同一处。asset 合并**以各 srcDir 为根**，所以打包后的 asset 路径不变
（仍是 `dshbox/…` 与 `plugins/…`），运行期读取代码无需任何改动。

> 推论：放进 `dshbox-plugins/assets/` 的文件会被自动打包；误放到 `tools/` 下则不会。

## 运行期落到设备上的布局

构建期的 APK 资产（本目录的 `assets/`）只是"素材"；装到设备后，app 会把它们铺进工作区，
与运行期自有的层放在一起。工作区里的固定位置是 `user-data/.dsh/dshbox/`：

| 运行期目录 / 文件 | 里面是什么 | 谁写 | 来源 |
|---|---|---|---|
| `bin/` | `dsh` / `pnpm` / `apt` / `apt-get` / `dpkg` 包装脚本 | app 启动时生成 | 生成（非本目录资产） |
| `dshbox-plugins/mobile-adapt/` | 移动端适配包的装配暂存 | app 启动时从 APK 资产重铺 | 本目录 `assets/plugins/dsh-mobile-adapt/` |
| `dshbox-plugins/mobile-pilot/` | DSH连接手机包的装配暂存 | 同上 | 本目录 `assets/plugins/mobile-pilot/` |
| `dsh-official-plugin/dsh-official-plugin-overlay.yml` | 「DSH官方插件」的覆盖层 | 面板开关 + 启动时重写 | 运行期生成 |
| `plugin-market/market-and-safe-mode-overlay.yml` | 停用行（**插件市场与安全模式共写**） | 市场 + 安全模式 | 运行期生成 |
| `safe-mode/absolute-safe-mode.yml` | 绝对安全模式独占层（关闭 = 删文件） | 绝对安全模式 | 运行期生成 |
| `safe-mode/boot-log-previous.txt` | 上一次启动的 DSH 日志 —— **已封存**：写者随安全模式停用，不再更新；面板「上次启动」栏也已取消（现按启动分段展示同一份 DSH 日志） | 安全模式（封存中） | 遗留文件 |
| `plugin-guard.json` | 安全模式的隔离清单与归因记录 | 安全模式 | 运行期生成 |
| `absolute-targets.json` | 绝对安全模式的目标缓存 | 绝对安全模式 | 运行期生成 |

三条要点：

- **只有 `dshbox-plugins/` 来自本目录的资产**；其余都是运行期生成/维护的状态，不要手改。
- 三个层（官方插件 / 停用行 / 绝对安全模式）都以 `--patch` 传给 dsh，**顺序固定**：
  官方插件 → 停用行 → 绝对安全模式（最后者优先级最高）。
- 停用行那一份**故意由市场与安全模式共用**（同一个集合、互为可见），**不要拆成两份** ——
  拆开会让一方"恢复启用"另一方看不见，历史上真机出过"隔离的坏插件被放回、DSH 起不来"的事故。

## 三块内容各自是什么

### 1. 移动端适配包（`dsh-mobile-adapt`）

DSH 网页端在手机窄屏下的适配层，以插件形式装配进 DSH 的 `web` profile。

- **载荷**：`install.sh` / `uninstall.sh` + `plugin/`（插件本体）。
- **装配落点**：`<profile>/node_modules/@local/dsh-mobile-adapt/`，并把包名写进
  `<profile>/package.json` 的 `dsh.profile.bundles`。
- **开关语义**：插件面板那条开关 = 「要不要这个包」。开 → 装配；关 → 卸载。
  启动时还会按它对齐一次 profile：缺就装、内容旧就刷新、一致就跳过。
  若 profile 目录尚不存在（DSH 从没运行过），启动期不装配，也不替 DSH 创建目录。
- `tools/mobile-adapt/` 里的矩阵测试用真实 shell 跑与生产同构的探测模板，
  锁定「要不要装配」的四态判定（含负向对照，可复现"两侧指纹文件同时缺失"这一误判缺陷）。

### 2. DSH连接手机（`mobile-pilot`）

插件面板里「DSH连接手机」那条开关对应的包，形态与移动端适配包**完全同形**：
同一个 `install.sh` / `uninstall.sh` 结构、同样装配进 `node_modules` 并登记 `dsh.profile.bundles`、
同样的开关语义（含启动期对齐），包名为 `@local/mobile-pilot`。

插件本体目前是**空壳**（`lib/index.js` 的 `apply()` 不做任何事），能力待实现；
但装配 / 卸载这条链路是完整的 —— 有内容时直接填进 `plugin/` 即可，不必再动接线。

它**不涉及任何 `--patch` 覆盖层**：自有插件的注入方式统一走 profile 装配这一条路。

### 3. 连接垫片（link-shim）

Android 应用数据分区**不支持硬链接**，而 DSH 与 dpkg 都会用 `link(2)`：

- Node 侧：`link-shim.mjs` 以 `node --import` 预加载，替换 `node:fs/promises` 的 `link`；
- 包管理器侧：`libdshbox-link.so`（aarch64 / glibc）经 `LD_PRELOAD` 注入。

两者都不改写 DSH 源码，因此不存在"上游重构导致补丁锚点漂移"的问题。

**编译方式参考**：`libdshbox-link.so` 的目标环境是 guest 的 glibc，**不能**用 Android NDK
（那是 bionic）。需要在 x86_64 宿主上借项目自带的 arm64 rootfs 配合 qemu-aarch64-static
交叉编译（用 user namespace 取得假 root 后 chroot 进 rootfs，免宿主 root、免 Docker）。
具体步骤写在 `tools/link-shim/build_link_shim.sh` 顶部；构建产物会由该脚本自动复制回
`assets/dshbox/` 作为交付载荷。
