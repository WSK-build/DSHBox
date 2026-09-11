package com.dshbox.app.sandbox

import java.io.File

data class SandboxConfig(
    val appFilesDir: File,
    val nativeLibraryDir: String? = null,
    /** 应用 cacheDir（可随系统/清理功能释放）；1.1.1 起 npm 下载缓存放这里。 */
    val appCacheDir: File? = null,
    val dshHost: String = "127.0.0.1",
    val dshPort: Int = 3080,
    val healthPath: String = "/",
    val maxAutoRestartAttempts: Int = 3,
    val dshReadyTimeoutMs: Long = 120_000L,
) {
    val runtimeDir: File = File(appFilesDir, "runtime")
    val sandboxDir: File = File(appFilesDir, "sandbox")
    val userDataDir: File = File(appFilesDir, "user-data")
    val logsDir: File = File(appFilesDir, "logs")
    val backupsDir: File = File(appFilesDir, "backups")
    val updatesDir: File = File(appFilesDir, "updates")

    /**
     * 在线安装的 npm 下载缓存宿主目录。在 guest 侧 bind 为 /root/.npm
     * （npm 默认缓存位置，guest HOME=/root），下载中间产物不再落 base/root/.npm
     * （运行环境本体红线区，清理功能清不到，曾实测膨胀 446MB）；归 cacheDir 后
     * 随「应用缓存」类别可一键清理。未提供 cacheDir 时兜底到 appFilesDir（测试）。
     */
    val npmCacheDir: File = File(appCacheDir ?: appFilesDir, "npm-cache")

    /**
     * 宿主侧存放「Android 硬链接兼容垫片」的目录，启动 DSH 时 bind 到 guest 的
     * `/opt/dshbox`（见 [com.dshbox.app.common.Constants.DSH_LINK_SHIM_GUEST_DIR]）。
     *
     * 独立于 DSH 层：垫片随 APK 发布、在**运行期**注入，因此无论 DSH 来自内置基线、
     * 在线 npm 更新还是离线导入，都无需重新打补丁，也绝不改写 DSH 源码。
     * 放在 appFilesDir 下而非 user-data，避免进入用户可见的工作区与备份范围。
     */
    val dshShimDir: File = File(appFilesDir, "dshbox")

    /** 垫片文件名（宿主侧与 guest 侧同名；与 [dshShimDir] 组合出 `--import` 目标）。 */
    val dshShimFile: File = File(dshShimDir, "link-shim.mjs")
}
