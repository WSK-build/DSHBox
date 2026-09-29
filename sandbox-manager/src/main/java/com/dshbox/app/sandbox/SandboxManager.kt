package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import com.dshbox.app.common.UiText
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade for the Linux Sandbox and DSH runtime.
 *
 * The UI never starts PRoot/DSH directly; it talks only to this manager.
 * Sandbox (Debian) and DSH are intentionally decoupled:
 * - [sandboxState] tracks only the Debian/PRoot lifecycle.
 * - [dshState] tracks only the DSH web service lifecycle.
 * - Starting/stopping/restarting one must not affect the other.
 */
interface SandboxManager {
    val sandboxState: StateFlow<SandboxState>
    val dshState: StateFlow<DshState>

    /**
     * 最近一次健康探测持续失败是否已达展示阈值（[DshHealthPolicy.UNRESPONSIVE_HINT_MS]）。
     *
     * 仅供展示层提示「实例可能无响应」；是否重启仍由健康循环按不应答窗口独立判定，
     * 两者不耦合。任何一次探测成功都会立即复位。
     */
    val dshUnresponsive: StateFlow<Boolean>

    /** Currently installed DSH layer version (runtime-current/dsh), or null. */
    val dshVersion: StateFlow<String?>

    /** Monotonic in-progress flag / message for DSH update. */
    val dshUpdateProgress: StateFlow<String?>

    /**
     * DSH 0.1.2-rc.1 起 web 服务使用进程级 launchToken 认证
     * （`dsh web:` 启动 URL 携带）。app 从 DSH 进程原始输出解析后经此暴露给
     * WebView：首次加载 `/?token=<值>` 完成 token→签名 cookie 交换，
     * 之后凭持久 cookie 访问。null = 尚未解析到（无认证的 DSH 版本可忽略）。
     */
    val dshLaunchToken: StateFlow<String?>

    /** One-time directory initialization. Idempotent. */
    suspend fun initialize()

    /** Start the Debian sandbox (PRoot keepalive). Independent of DSH. */
    suspend fun startSandbox()

    /** Stop the Debian sandbox. DSH keeps running if it was started separately. */
    suspend fun stopSandbox()

    /** Restart the Debian sandbox. */
    suspend fun restartSandbox()

    /** Force-stop both sandbox and DSH. */
    suspend fun forceStop()

    /** Health snapshot combining sandbox state and DSH port checks. */
    suspend fun healthCheck(): AppResult<SandboxHealth>

    /**
     * Start DSH. Requires the sandbox to be [SandboxState.RUNNING];
     * otherwise returns a recoverable error telling the user to wake the sandbox first.
     */
    suspend fun startDsh(): AppResult<DshRuntimeStatus>

    /** Stop DSH only. The sandbox keeps running. */
    suspend fun stopDsh()

    /** Restart DSH only. The sandbox keeps running. */
    suspend fun restartDsh(): AppResult<DshRuntimeStatus>

    /** Recover according to [level]. */
    suspend fun recover(level: RecoveryLevel): AppResult<Unit>

    /** Enter safe mode: stop everything and stay stopped. */
    suspend fun enterSafeMode()

    /** Returns true when runtime-current contains both PRoot and the Debian rootfs. */
    fun isRuntimeInstalled(): Boolean

    /**
     * node 层是否已导入（runtime-current/node/bin/node 在位）。
     * 在线导入拆分后 base 与 node 是两步：仅装 Linux 层时运行环境视为已安装，
     * 但 DSH 无法启动——UI 以此给出"还需导入 node 层"的引导，startDsh 也以此快速拒绝。
     */
    fun isNodeLayerInstalled(): Boolean

    /** Scans the updates dir for the first .tar.gz with a valid .sha256 sidecar and installs it. */
    suspend fun installFirstAvailableBundle(): AppResult<java.io.File>

    /** Installs a verified Runtime Bundle into runtime-new (does not switch). */
    suspend fun installRuntimeBundle(bundleFile: java.io.File, expectedSha256: String): AppResult<java.io.File>

    /** Switches runtime-new to runtime-current after the sandbox is stopped. */
    suspend fun promoteRuntimeBundle(): AppResult<Unit>

    /** Restores the previous Runtime slot after stopping the sandbox. */
    suspend fun rollbackRuntime(): AppResult<Unit>

    /**
     * Updates the standalone DSH layer (runtime-current/dsh) from a DSH bundle
     * (tar.gz / tar.zst / plain tar) with version arbitration: installed-newer
     * wins, incoming-newer replaces (old -> previous/dsh). Does not touch
     * user-data/.dsh.
     *
     * [allowDowngrade] overrides the arbitration for EXPLICIT user choices
     * (the online-update screen offers older versions with a double confirm);
     * the bundled-provision path never sets it, so APK baselines still never
     * downgrade an installed layer.
     */
    suspend fun updateDsh(
        bundle: java.io.File,
        expectedSha256: String?,
        newVersion: String?,
        allowDowngrade: Boolean = false,
    ): AppResult<DshUpdateOutcome>

    /**
     * online DSH update step 2: build a fresh DSH layer from the
     * npm registry [registryUrl] by running npm INSIDE the guest Debian
     * (replicating runtime-bundle/scripts/install_dsh.sh, the exact way the
     * bundled layer is produced), packing it and installing through the normal
     * updateDsh pipeline (staging -> validate -> previous/dsh -> Android patch
     * -> version record -> auto restart).
     *
     * Starts the sandbox when it is not running (npm needs the guest). Streams
     * human-readable stage names via [onStage] and the raw npm/tar output lines
     * via [onLog]; hands the spawned guest Process to [onProcess] so the caller
     * can offer cancellation (destroying the proot root tears the guest tree
     * down via --kill-on-exit).
     */
    suspend fun installDshFromNpm(
        registryUrl: String,
        version: String,
        allowDowngrade: Boolean = false,
        onStage: (UiText) -> Unit = {},
        onLog: (String) -> Unit = {},
        onProcess: (java.lang.Process) -> Unit = {},
        /** 为 true 时 guest 命令等待循环立即中止（在线取消用）。 */
        shouldAbort: () -> Boolean = { false },
    ): AppResult<DshUpdateOutcome>

    /**
     * Offline-import a runtime bundle (a zip holding the layered body:
     * base/node/android-side <layer>.tar.* + .sha256 sidecars + runtime-profile.json).
     * Cleanly replaces the runtime body (old body -> previous/, single copy) while
     * PROTECTING the DSH layer (runtime-current/dsh) and the user data
     * (user-data / user-data/.dsh). Sandbox must be stopped first.
     */
    suspend fun importRuntimeBundle(source: java.io.File): AppResult<Unit>

    /**
     * 在线组装专用（OnlineRuntimeImportManager）：把设备上组装完成的分层 body
     * （[staging] 下须含 base/、node/、android-side/ 目录与 runtime-profile.json）
     * 按 [importRuntimeBundle] 同款语义替换 runtime-current：旧 body → previous/
     * （单副本），dsh 层与 user-data 全程不碰。沙箱必须先停止。
     *
     * 与离线导入的差异只在来源：staging 不是解包 zip 得到，而是组装流水线的产物
     * （profile 的 hash 为安装期现算）。各层完整性哨兵由调用方在 staging 内写好。
     */
    suspend fun installAssembledRuntime(staging: java.io.File): AppResult<Unit>

    /**
     * 在线导入 node 层专用（OnlineRuntimeImportManager）：把 [stagingNodeDir]
     * （须为已解包并写好 `.dshbox/layer-node.sha256` 哨兵的 node 层目录）装入
     * runtime-current/node——旧 node → previous/node（单副本），随后**按当前实际
     * 在位的层目录重建 runtime-profile.json**（base/node/android-side 缺哪层就不
     * 声明哪层，hash 现算，与哨兵自洽）。要求 base 层已在位（先导入 Linux 层），
     * 沙箱必须先停止。dsh 层与 user-data 全程不碰。
     */
    suspend fun installNodeLayer(stagingNodeDir: java.io.File): AppResult<Unit>

    /**
     * Inject a one-off command into the DSH guest (fresh PRoot process) and
     * stream each output line to [onLine]. Used for 指令注入 — e.g. running a
     * plugin's install.sh inside the guest. Does NOT require the sandbox
     * keepalive to be running. [onProcess] (1.1.0) receives the spawned host
     * Process so long-running callers can destroy it to cancel the command.
     */
    suspend fun runGuestCommand(
        command: String,
        onLine: (String) -> Unit = {},
        onProcess: (java.lang.Process) -> Unit = {},
        /** 为 true 时等待循环立即中止。 */
        shouldAbort: () -> Boolean = { false },
    ): AppResult<Unit>
}
