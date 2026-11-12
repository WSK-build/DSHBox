package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshInstance
import java.io.File

data class SandboxConfig(
    val appFilesDir: File,
    val nativeLibraryDir: String? = null,
    /** 应用 cacheDir（可随系统/清理功能释放）；1.1.1 起 npm 下载缓存放这里。 */
    val appCacheDir: File? = null,
    val dshHost: String = "127.0.0.1",
    val dshPort: Int = 3080,
    /**
     * 本机 DSH 端口自适应是否启用：true = 启动前探测 3080..3099 第一个空闲端口
     * 写入 webserver 覆盖层并把该端口作为本次请求值；false = 只请求 [dshPort]
     * （跳过探测与覆盖层，健康检查跟随 [dshPort]）。
     *
     * 由 app 层按构造变体注入：systemWebView 变体启用（加载地址与健康检查全走
     * 运行期地址）；gecko 变体关闭——其状态桥扩展 manifest/content script 与
     * 构建期 page-compat 配置都固化 3080，端口一变即失效，该变体**固定 3080**
     * 不自适应。
     */
    val dshPortAdaptationEnabled: Boolean = true,
    val healthPath: String = "/",
    val maxAutoRestartAttempts: Int = 3,
    /**
     * 首次启动的就绪超时（毫秒）：进程仍存活却始终不就绪时，超过该时长即判启动失败
     * （清理进程并置 ERROR），不再重启。
     *
     * 与 [dshUnresponsiveGraceMs] 语义不同、当前默认值相同（都是两分钟）：前者管
     * 「启动阶段始终不就绪」，后果是失败退场；后者管「已就绪后持续不应答」，后果是
     * 有界重启。两者是彼此独立的旋钮，调整其一时不要顺手改动另一个。
     */
    val dshReadyTimeoutMs: Long = 120_000L,
    /**
     * 已就绪的 DSH 实例「探测持续失败多久」才判定不可用（毫秒）。
     *
     * 进程句柄仍存活时，单次探测失败不足以说明 DSH 出了故障：设备休眠或系统冻结
     * 后台应用期间，整个 guest 会被挂起——端口仍在监听、HTTP 不应答，属可自愈的
     * 暂时状态。只有连续失败超过该窗口才进入有界重启。
     */
    val dshUnresponsiveGraceMs: Long = 120_000L,
    /** Browser CLI assets and screenshots, exposed only to guest as `/opt/browser`. */
    val browserEntryDir: File = File(appFilesDir, "browser-tools"),
    /**
     * 共享区绑定清单提供者（guest 别名 → 宿主目录）：内建 `DSHBox` + 用户挂载目录。
     *
     * 每次起会话时现取——用户新授权或新增挂载后无需重建配置；目录缺失的条目由
     * 调用侧按存在性跳过，会话照常启动。
     */
    val shareBindsProvider: () -> List<Pair<String, File>> = { emptyList() },
    /**
     * 追加的 PRoot 绑定参数（完整 `--bind=宿主路径[:guest路径]` 形式）。
     *
     * 由 app 层按启用中的能力注入，本模块不解释其语义；网页端 DSH 与 guest 命令
     * 共用同一组追加项。宿主目录须由注入方先建好——PRoot 对不存在的绑定目标会报错。
     */
    val extraGuestBindsProvider: () -> List<String> = { emptyList() },
    /**
     * 追加到 guest PATH 的目录（位于 CLI 包装脚本目录之后、系统路径之前）。
     *
     * 与 [extraGuestBindsProvider] 配套：绑定提供目录，此处让该目录出现在 PATH 上。
     */
    val extraPathEntriesProvider: () -> List<String> = { emptyList() },
    /**
     * 活跃 DSH 实例提供者：启动 DSH 时现取，`DSH_HOME`、覆盖层与资产路径随槽切换。
     * 缺省主实例——未接线的调用方（测试等）行为与单实例模型一致。
     */
    val activeDshInstanceProvider: () -> DshInstance = { DshInstance.MAIN },
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
     * （运行环境本体红线区，清理功能清不到，可膨胀至数百 MB）；归 cacheDir 后
     * 随「应用缓存」类别可一键清理。未提供 cacheDir 时兜底到 appFilesDir（测试）。
     */
    val npmCacheDir: File = File(appCacheDir ?: appFilesDir, "npm-cache")

    /**
     * guest 侧「工具链缓存」挂载点的宿主目录（pnpm 的 store、corepack 缓存、
     * pnpm 镜像源覆盖文件），绑定到 guest 的 `/opt/dshbox-cache`。
     *
     * 放在 appFilesDir 下而非 `user-data`：与 [dshShimDir] 同理 —— 不进用户可见的工作区、
     * 不进备份范围。这个挂载点解决两处落盘问题：
     *  - corepack 默认把 pnpm 二进制缓存放 `$HOME` → 会落进**工作区**（约 20 MB 量级）；
     *  - pnpm 默认把内容寻址 store 放 `$HOME/.local/share/pnpm` → 会落进 **base 层**：
     *    既不在 Files 页可见，也不在清理规则覆盖范围内（与当年 npm 缓存膨胀 446MB 同类）。
     * 终端与 guest 命令都会绑它；宿主目录在绑定时 mkdirs 创建。
     */
    val guestCacheDir: File = File(appFilesDir, Constants.GUEST_CACHE_DIR_LEAF)

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

    /**
     * 手机助手暴露给 guest 的宿主目录（guest 侧 `/opt/pilot`）：入口脚本、能力清单与
     * 使用说明，以及其下的 `run/` 信箱与产物中转。助手侧的授权记录与配额账本**不在**
     * 本目录内（助手能改写它们就等于能改写自己的权限）。目录为空时 guest 只看到一条
     * 挂空的挂载点，无副作用，故无需等助手启用。
     */
    val pilotEntryDir: File = File(appFilesDir, "pilot")
}
