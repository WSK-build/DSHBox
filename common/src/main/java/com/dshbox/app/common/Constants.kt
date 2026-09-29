package com.dshbox.app.common

object Constants {
    const val DSH_DEFAULT_HOST = "127.0.0.1"
    const val DSH_DEFAULT_PORT = 3080

    /** WebView loads the DSH loopback URL. Both localhost and 127.0.0.1 are allowed by NSC. */
    const val DSH_BASE_URL = "http://$DSH_DEFAULT_HOST:$DSH_DEFAULT_PORT"

    // 旧的 DSH_MIRRORS / DSH_LAYER_BASE_URL（预构建 dsh_layer.tar.zst 下载源）已废弃——
    // 该下载源从未存在，在线更新改为「探测 npm 源 + guest 内 npm 拉包」，
    // 见 common/DshSources.kt 与 SandboxManager.installDshFromNpm。

    /** Online-update guard: minimum free bytes on the app storage before a guest npm install starts. */
    const val DSH_INSTALL_MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024 // 1 GiB

    const val MIN_SUPPORTED_SDK = 29

    /** Default Linux workspace inside the Debian sandbox. */
    const val SANDBOX_WORKSPACE = "/root/projects"

    /** Android-side sandbox directory names (App-specific storage). */
    const val DIR_RUNTIME = "runtime"
    const val DIR_SANDBOX = "sandbox"
    const val DIR_USER_DATA = "user-data"
    const val DIR_LOGS = "logs"
    const val DIR_BACKUPS = "backups"
    const val DIR_UPDATES = "updates"

    const val MAX_AUTO_RESTART_ATTEMPTS = 3

    const val HEALTHCHECK_TIMEOUT_MS = 5_000L
    const val DSH_READY_TIMEOUT_MS = 120_000L

    /** SharedPreferences key: whether the app has completed the first-run bootstrap. */
    const val PREFS_NAME = "dshapp_prefs"
    const val PREF_FIRST_RUN_COMPLETED = "first_run_completed"

    /** Marker embedded in the sandbox keepalive command to distinguish the PRoot process. */
    const val SANDBOX_KEEPALIVE_MARKER = "dshapp-sandbox-keepalive"

    /**
     * Marker used to locate the DSH PRoot process at stop time. The DSH layer is
     * mounted at /opt/dshapp/runtime (bound by BOTH sandbox and DSH proot), so
     * "/opt/dshapp/runtime" would also match the sandbox keepalive cmdline and
     * stopDsh() would kill the whole sandbox tree. Instead match the DSH-ONLY
     * entry token `@deepseek-ai/dsh/lib/bin.js` (present only in the DSH PRoot
     * cmdline: `node --expose-internals .../@deepseek-ai/dsh/lib/bin.js --profile web`).
     */
    const val DSH_START_SCRIPT = "@deepseek-ai/dsh/lib/bin.js"

    /**
     * 宿主启动 DSH 时使用的 profile 名（`dsh --profile <名>`）。
     *
     * 是**唯一真相**，有两处消费者：启动参数（`SandboxProcessRunner`）与
     * profile 级文件路径推导（`DshWebViewScreen` 打开配置文件的目标）。
     * 上游把 profile 的配置文档定在 `<DSH_HOME>/profiles/<名>/cordis.patch.yml`，
     * 因此这个名字一旦改了、路径必须跟着改，不能各写一份字面量。
     */
    const val DSH_WEB_PROFILE = "web"

    /**
     * guest 内放置「Android 硬链接兼容垫片」的目录（宿主侧目录 bind 到此）。
     *
     * 垫片是 app 侧**运行期**注入的唯一 Android 兼容手段：它只替换
     * `node:fs/promises` 的 `link`，不修改 DSH 源码，因此不存在上游改名/改形态
     * 导致的锚点漂移。DSH 入口以 `--import` 预加载它（见 buildProotDshCommand）。
     */
    const val DSH_LINK_SHIM_GUEST_DIR = "/opt/dshbox"

    /** 垫片在 guest 内的完整路径（`--import` 的目标）。 */
    const val DSH_LINK_SHIM_GUEST_PATH = "$DSH_LINK_SHIM_GUEST_DIR/link-shim.mjs"

    /**
     * 包管理器用的硬链接垫片（aarch64 `.so`，由 `LD_PRELOAD` 注入）。
     *
     * 与上面的 Node 垫片同一个宿主侧目录、同一种交付方式，但作用对象不同：dpkg 在事务末尾
     * 创建账本备份时调用 `link(2)`，而 Android 应用数据文件系统不支持硬链接——本垫片把
     * 该调用退化为复制，使包事务能够完整走完。仅由 apt/dpkg 包装脚本按需注入。
     */
    const val DSH_LINK_SHIM_SO_GUEST_PATH = "$DSH_LINK_SHIM_GUEST_DIR/libdshbox-link.so"

    /**
     * guest 内「工具链缓存」挂载点（宿主侧 `SandboxConfig.guestCacheDir` bind 到此）。
     *
     * 装 `dsh plugin` 用的 pnpm 时，pnpm 的 store 与 corepack 的缓存**默认都往 `$HOME` 写**：
     * 前者会落进 base 层（Files 页看不见、清理规则也扫不到），后者会落进工作区。
     * 统一引到这个挂载点：包装脚本把 `COREPACK_HOME` 与 `pnpm --store-dir` 都指进来。
     */
    const val DSHBOX_GUEST_CACHE_DIR = "/opt/dshbox-cache"

    /**
     * opencode 终端 Agent 的安装落点（guest 侧）。
     *
     * 宿主侧是该缓存挂载点内的同名目录：**既不在工作区里、也不在运行环境层里**，
     * 因此换层不会丢、文件页也看不到它。插件面板里的安装/更新/删除三个动作都只作用于此目录。
     */
    const val OPENCODE_GUEST_DIR = "$DSHBOX_GUEST_CACHE_DIR/opencode"

    /**
     * opencode 的安装暂存目录（与安装落点**同一个挂载点内**）。
     *
     * 装完在同一卷内 `mv` 过去，是原子动作、不会留下"装到一半"的状态；中途被杀时残留只落在这里，
     * 由设置页的清理项兜底（见 `SandboxCleanup` 的缓存类白名单）。
     */
    const val OPENCODE_STAGING_GUEST_DIR = "$DSHBOX_GUEST_CACHE_DIR/.staging-opencode"

    /**
     * opencode 的命令包装脚本名：落在 CLI 垫片目录（终端 PATH 首项），因此终端里直接敲
     * `opencode` 即可。脚本常驻，内部自行判断有没有装（未装时给出引导，而不是"命令不存在"）。
     */
    const val OPENCODE_SHIM_NAME = "opencode"

    /** opencode 的启动器路径（安装目录下的 npm 产物）；包装脚本与安装态探测共用同一来源。 */
    const val OPENCODE_LAUNCHER = "$OPENCODE_GUEST_DIR/node_modules/.bin/opencode"

    /**
     * 我方**随包资产**在工作区内的固定目录（app 管理、只读保护）。
     *
     * 与 DSH 自己的数据（profiles / sessions / settings.yaml / storages / credentials）
     * 明确分开：**这里的一切都是我们的固定资产——用户可看、不可改，DSH 也改不动**
     * （其中 `bin/` 目录 0555 / 文件 0444-0555，每次 bootstrap 重新固化，见 SandboxService；
     * 其余子目录保持可写，因为各有运行期写者）。
     *
     * 目录布局以"名称直白、一目录一归属"为准：
     * - `bin/`                     CLI 包装脚本（基础设施）
     * - `dshbox-plugins/`          我方自有插件包的装配暂存
     * - `dsh-official-plugin/`     「DSH官方插件」的覆盖层
     * - `plugin-market/`           「插件市场」的停用行（与安全模式共写，见对应常量）
     * - `safe-mode/`               「绝对安全模式」独占层（将来容纳安全模式自身的产物）
     */
    const val DSHBOX_ASSETS_RELATIVE_DIR = ".dsh/dshbox"

    /** 同上，guest 侧绝对路径（`user-data` 绑定为 `/root/projects`）。 */
    const val DSHBOX_ASSETS_GUEST_DIR = "/root/projects/$DSHBOX_ASSETS_RELATIVE_DIR"

    /**
     * 两个自有插件包的装配暂存在资产目录内的父目录。
     *
     * 与源码目录 `plugin-manager/dshbox-plugins/` 同名。刻意不叫 "official"：那个词在面板里
     * 指的是**上游**官方插件（见 [DSH_OFFICIAL_PLUGIN_OVERLAY_RELATIVE_PATH]），而这两个包是
     * 我们自己做的（`@local/` 前缀）。
     */
    const val DSHBOX_PLUGINS_LEAF = "dshbox-plugins"

    /** 同上，相对 user-data 的完整路径。 */
    const val DSHBOX_PLUGINS_RELATIVE_DIR = "$DSHBOX_ASSETS_RELATIVE_DIR/$DSHBOX_PLUGINS_LEAF"

    /** 同上，guest 侧绝对路径。 */
    const val DSHBOX_PLUGINS_GUEST_DIR = "/root/projects/$DSHBOX_PLUGINS_RELATIVE_DIR"

    /**
     * dsh `web` profile 在 guest 内的目录 —— 两个自有插件包都装配到这里。
     *
     * 宿主侧即 `user-data/.dsh/profiles/web`。由 dsh 自己创建：
     * 该目录不存在（DSH 从没跑过）时我们不装配，也不替它建目录。
     */
    const val DSH_WEB_PROFILE_GUEST_DIR = "/root/projects/.dsh/profiles/web"

    /**
     * **插件市场的**覆盖层（停用行）。
     *
     * ⚠️ 这份文件由**插件市场与安全模式共写**（`DefaultMarketRepository` 的停用/启用、
     * `PluginSafetyMode` 的加载失败隔离与隔离清单恢复），因此文件名带上了两者的名字。
     * **故意共用一份、不要拆**：它与"哪些第三方条目当前被停用"是同一个集合，拆成两份后
     * 两份都是 `disabled` 行、后层覆盖前层，一方"恢复启用"另一方看不见 —— 历史上真机上
     * 就是这么把已隔离的坏插件放回去、导致 DSH 起不来的。
     *
     * `--patch` 可重复，启动时按存在与否逐个追加（见 `DefaultSandboxManager.dshOverlayGuestPaths`）。
     */
    const val DSH_PLUGIN_MARKET_OVERLAY_GUEST_PATH =
        "/root/projects/.dsh/dshbox/plugin-market/market-and-safe-mode-overlay.yml"

    /** 宿主侧相对 user-data 的同一份文件。 */
    const val DSH_PLUGIN_MARKET_OVERLAY_RELATIVE_PATH =
        ".dsh/dshbox/plugin-market/market-and-safe-mode-overlay.yml"

    /**
     * **绝对安全模式独占的**覆盖层：整层只有"停用第三方条目"这一类行。
     *
     * 与市场那份分开是必须的，不是洁癖：该模式的"关闭 = 撤销我改的一切"一旦建立在前一份
     * 文件的快照上，快照过期就会把别人写的停用行翻回启用（同上，真机事故）。独占一层之后，
     * "关闭"只是删掉这个文件，谁也不会被覆盖。
     *
     * 追加在市场层**之后**：`disabled` 后层覆盖前层，绝对安全模式的语义
     * （不加载任何第三方插件）优先于其它层的判断。
     */
    const val DSH_SAFE_MODE_ABSOLUTE_OVERLAY_GUEST_PATH =
        "/root/projects/.dsh/dshbox/safe-mode/absolute-safe-mode.yml"

    /** 宿主侧相对 user-data 的同一份文件。 */
    const val DSH_SAFE_MODE_ABSOLUTE_OVERLAY_RELATIVE_PATH =
        ".dsh/dshbox/safe-mode/absolute-safe-mode.yml"

    /**
     * **「DSH官方插件」的覆盖层**：把上游"按 profile 类型默认关闭"的条目重新打开。
     *
     * 上游把部分 Web 能力做成默认关闭的条目（例如侧边栏浏览器在 web profile 下
     * 由 `disabled: !!js "ctx.get('profileContext')?.name !== 'desktop'"` 关闭）。
     * 宿主不改 DSH 源码、也不动 profile 里用户的 `cordis.patch.yml`，而是用 dsh
     * 官方的 `--patch` 入口（应用在 profile 层之后，按 id 逐键覆盖、后层胜）覆盖它。
     *
     * ⚠️ 这里的 "official" 指**上游官方插件**，与 `dshbox-plugins/` 下我们自己那两个包
     * （`@local/` 前缀）不是一回事。
     *
     * 独占一层的原因与市场/绝对安全模式一致：`--patch` 可重复，分层才能让各写入方互不覆盖。
     * 追加在市场层与绝对安全模式层**之前**：本层只开官方内置条目，
     * 绝对安全模式的语义（不加载第三方插件）应当保持最后发言权。
     */
    const val DSH_OFFICIAL_PLUGIN_OVERLAY_GUEST_PATH =
        "/root/projects/.dsh/dshbox/dsh-official-plugin/dsh-official-plugin-overlay.yml"

    /** 宿主侧相对 user-data 的同一份文件。 */
    const val DSH_OFFICIAL_PLUGIN_OVERLAY_RELATIVE_PATH =
        ".dsh/dshbox/dsh-official-plugin/dsh-official-plugin-overlay.yml"

    /**
     * 「移动端适配插件已装配」的偏好键（持久化于应用偏好，不进 user-data）。
     *
     * 由设置页在装配/移除成功时翻转。bootstrap 用它决定是否要把 profile 里的
     * 插件副本刷新为当前 APK 版本（见 SandboxService.ensureOwnPluginsAssembled）：
     * 用户装配过才刷新，没装配过就不擅自往他的 DSH profile 里塞插件。
     */
    /** 旧资产目录（`.dsh/bin` 等）是否已清理过（一次性，避免每次启动误删用户文件）。 */
    const val PREF_LEGACY_ASSETS_PURGED = "legacy_assets_purged"

    const val PREF_MOBILE_ADAPT_INSTALLED = "mobile_adapt_installed"

    /**
     * 「DSH连接手机（mobile-pilot）」是否装配的偏好键（与 [PREF_MOBILE_ADAPT_INSTALLED] 并列）。
     *
     * 语义与移动端适配包完全一致：**装配成功 = true，卸载成功 = false**，bootstrap 据此在
     * dsh 启动前确保该包装配到位（见 `SandboxService.ensureOwnPluginsAssembled`）。
     */
    const val PREF_MOBILE_PILOT_ENABLED = "mobile_pilot_enabled"

    /**
     * 改名前的旧键（`mobile-pilot` 一度叫 `pilot`，且当时的开关只记状态、不做任何动作）。
     *
     * 保留它**只为一次性迁移**：老用户若拨过那条开关，其选择要跟着搬到新键上，
     * 不能被"默认开启"覆盖掉。迁移完成后本键不再被读写。
     */
    const val PREF_LEGACY_PILOT_ENABLED = "pilot_enabled"

    /**
     * 「最近一次 mobile-pilot 自动装配失败的原因」（偏好键），与 [PREF_MOBILE_ADAPT_LAST_ERROR] 同理：
     * 启动期失败没有前台 UI 可弹提示，但必须让用户可感知。
     */
    const val PREF_MOBILE_PILOT_LAST_ERROR = "mobile_pilot_last_error"

    /**
     * 「最近一次插件自动刷新失败的原因」（偏好键）。
     *
     * 自动刷新（bootstrap 阶段）失败时无法弹 Toast（没有可用的前台 UI 上下文），
     * 但**必须让用户可感知** —— 否则设置页只显示一个灰色开关，用户无从得知
     * 为什么插件没生效。因此把失败原因写在这里，由设置页读取并展示；
     * 刷新成功后清空该键。
     */
    const val PREF_MOBILE_ADAPT_LAST_ERROR = "mobile_adapt_last_error"
}
