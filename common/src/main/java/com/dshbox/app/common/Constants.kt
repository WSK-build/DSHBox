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
     * 「移动端适配插件已装配」的偏好键（持久化于应用偏好，不进 user-data）。
     *
     * 由设置页在装配/移除成功时翻转。bootstrap 用它决定是否要把 profile 里的
     * 插件副本刷新为当前 APK 版本（见 SandboxService.refreshAssembledMobileAdaptPlugin）：
     * 用户装配过才刷新，没装配过就不擅自往他的 DSH profile 里塞插件。
     */
    const val PREF_MOBILE_ADAPT_INSTALLED = "mobile_adapt_installed"

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
