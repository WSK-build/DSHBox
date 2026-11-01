package com.dshbox.app.runtime

import android.content.Context
import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.SandboxManager
import developer.access.adb.AdbAssets
import developer.access.adb.AdbChannel
import developer.access.core.AssetSource
import developer.access.core.ElevationDirs
import developer.access.core.ServerSpec
import developer.access.core.ServerSpecStore
import developer.access.core.GuestCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 开发者模式在 app 侧的装配入口。
 *
 * 能力模块不依赖本工程 app 模块（依赖方向不允许），故它把「解包所需的 guest 执行」
 * 与「资产取用」定义为接口，由这里实现并注入。同时这里提供**沙箱启动前的复检入口**：
 * 运行环境层被整体替换后会带走 adb 与依赖，而开关状态（wrapper 在位）不变，
 * 故每次进入沙箱前按需补装。
 */
class DeveloperAccessBootstrap(
    private val context: Context,
    private val sandboxManager: SandboxManager,
    private val runtimeDir: File,
) {

    /** 运行环境层根：分层装配下 adb 的解包目标是这一层。 */
    private fun baseRootfs(): File = File(File(runtimeDir, "runtime-current"), "base")

    private fun channel(): AdbChannel = AdbChannel(
        dirs = ElevationDirs(context.filesDir),
        rootfsDir = baseRootfs(),
        guest = object : GuestCommandRunner {
            override suspend fun run(command: String, onLine: (String) -> Unit): Boolean =
                when (sandboxManager.runGuestCommand(command, onLine = onLine)) {
                    is AppResult.Success -> true
                    is AppResult.Failure -> false
                }
        },
        assets = object : AssetSource {
            override fun copyTo(assetName: String, target: File): Boolean = try {
                context.assets.open("${AdbAssets.ASSET_DIR}/$assetName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "asset copy failed: $assetName", t)
                false
            }
        },
    )

    /**
     * 沙箱启动前复检：开关处于开启态时确保 adb 与依赖在位。
     *
     * 失败不阻断启动——开发者模式是附加能力，其资产缺失不应影响 DSH 与终端可用性。
     */
    suspend fun ensureAdbReady() {
        runCatching {
            val ok = channel().ensureInstalledIfEnabled { line ->
                android.util.Log.i(TAG, line)
            }
            if (!ok) android.util.Log.w(TAG, "adb runtime recheck failed; channel stays unusable")
        }.onFailure { android.util.Log.w(TAG, "adb runtime recheck failed", it) }
    }

    /**
     * 装配并注册面板控制器。
     *
     * 由宿主在启动时调用一次；重复调用以最后一次为准。
     */
    fun installController(): Unit {
        val dirs = ElevationDirs(context.filesDir)
        dirs.ensureLayout()
        val channel = channel()
        // mDNS 会话的停止是挂起操作，宿主侧用独立作用域触发，避免阻塞调用方（UI）。
        val discoveryScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )
        // 进程重启后执行器必然不在运行；上一条命的 CLI 脚本若还在，guest 会看到
        // "命令存在但连不上执行器"——与"关闭即不可见"不符，故启动时一律清掉。
        runCatching { removeRootCli() }
        // Root 执行器按当前配置构造；配置变更时由宿主重建。
        var server: developer.access.root.RootExecutor? = null
        val limiter = developer.access.root.AuthLimiter()

        val host = object : developer.access.DeveloperAccessHost {
            override fun isAdbEnabled(): Boolean = dirs.adbWrapperFile().exists()

            override suspend fun enableAdb(onLine: (String) -> Unit): Boolean =
                // 解包/复制/写脚本都是阻塞 IO，统一放到 IO 线程，避免占用调用方（UI）线程。
                withContext(Dispatchers.IO) {
                    channel.enable(onLine, sandboxRunning = isSandboxRunning())
                }

            override fun disableAdb(): Int = channel.disable()

            override fun adbSpec(): ServerSpec = ServerSpecStore.read(
                dirs.adbServerConfFile(),
                developer.access.core.ElevationPaths.DEFAULT_ADB_SERVER_PORT,
            )

            override fun setAdbSpec(spec: ServerSpec) {
                ServerSpecStore.write(
                    dirs.adbServerConfFile(),
                    spec,
                    developer.access.core.ElevationPaths.GUEST_ADB_SOCKET,
                )
            }

            override fun suAvailable(): Boolean = resolveSu() != null

            override fun isRootRunning(): Boolean = server?.isRunning == true

            override suspend fun startRoot(
                spec: ServerSpec,
            ): developer.access.RootStartResult = withContext(Dispatchers.IO) {
                server?.stop()
                // TCP 档必须有访问码；unix 档不生成（用不到，且避免无谓地产生秘密）。
                if (spec.transport != developer.access.core.ServerTransport.UNIX_SOCKET) {
                    ensureRootAuthCode(dirs)
                }
                server = developer.access.root.RootExecutor(
                    dirs = dirs,
                    spec = spec,
                    accessCode = { readRootAuthCode(dirs) },
                    limiter = limiter,
                    suCommand = { cmd -> listOf(resolveSu() ?: "su", "-c", cmd) },
                )
                val started = server?.start() == true
                if (!started) {
                    removeRootCli()
                    return@withContext developer.access.RootStartResult.FAILED
                }
                // CLI 落盘失败时通道对 DSH 就是不可用（没有 android-root 可调），
                // 按启动失败处理并回收执行器——不能留下"开关开着、命令却不存在"的中间态。
                val provisioned = runCatching { provisionRootCli() }
                    .onFailure { android.util.Log.w(TAG, "android-root provisioning failed", it) }
                    .isSuccess
                if (!provisioned) {
                    server?.stop()
                    server = null
                    return@withContext developer.access.RootStartResult.PROVISION_FAILED
                }
                developer.access.RootStartResult.STARTED
            }

            override suspend fun stopRoot() = withContext(Dispatchers.IO) {
                server?.stop()
                server = null
                removeRootCli()
            }

            override fun rootSpec(): ServerSpec = developer.access.core.RootSpecStore.read(
                dirs.rootServerConfFile(),
                dirs.rootPortStorage(),
                developer.access.core.ElevationPaths.DEFAULT_ROOT_SERVER_PORT,
            )

            override fun setRootSpec(spec: ServerSpec) {
                developer.access.core.RootSpecStore.write(
                    dirs.rootServerConfFile(),
                    dirs.rootPortStorage(),
                    spec,
                )
            }

            override fun rootAccessCode(): String = readRootAuthCode(dirs)

            override fun ensureRootAuthCode(): String = ensureRootAuthCode(dirs)

            private val keys = developer.access.adb.KeyStore(dirs.guestAndroidDir)

            override fun localKeys() = keys.listKeys()

            override fun deleteLocalKey(id: String): Boolean = keys.deleteKey(id)

            override suspend fun generateLocalKey(): Boolean = withContext(Dispatchers.IO) {
                // 附加密钥经 adb keygen 生成：走 guest 内的 adb，故要求 ADB 通道已开启。
                // 取第一个未占用的序号：按"现有数量 + 1"会在删除过密钥后续写已有文件。
                var n = 1
                while (File(dirs.guestVendorKeysDir, "key$n.adb_key").exists()) n++
                val target = File(dirs.guestVendorKeysDir, "key$n.adb_key")
                target.parentFile?.mkdirs()
                val guestPath = "${developer.access.core.ElevationPaths.GUEST_VENDOR_KEYS_DIR}/key$n.adb_key"
                var ran = false
                sandboxManager.runGuestCommand(
                    developer.access.adb.AdbGateway.command(listOf("keygen", guestPath)),
                ).let { ran = it is AppResult.Success }
                ran && File(target.parentFile, "${target.name}.pub").isFile
            }

            override fun hasPairedDevices(): Boolean = keys.hasPairedDevices()

            override fun clearPairedDevices(): Boolean = keys.clearPairedDevices()

            override suspend fun pairDevice(
                host: String,
                port: Int,
                code: String,
            ): developer.access.adb.PairingResult = runAdb("pair", "$host:$port", code)

            override suspend fun connectDevice(
                host: String,
                port: Int,
            ): developer.access.adb.PairingResult = runAdb("connect", "$host:$port")

            override fun probePort(port: Int, forLan: Boolean): developer.access.core.PortStatus =
                if (port !in 1..65535) {
                    developer.access.core.PortStatus.UNKNOWN
                } else {
                    runCatching {
                        // 局域网档的 server 绑 0.0.0.0，只测回环会漏掉绑在其他网卡上的占用。
                        val address = if (forLan) "0.0.0.0" else "127.0.0.1"
                        java.net.ServerSocket().use { socket ->
                            socket.reuseAddress = false
                            socket.bind(java.net.InetSocketAddress(address, port))
                        }
                        developer.access.core.PortStatus.FREE
                    }.getOrElse { developer.access.core.PortStatus.OCCUPIED }
                }

            override fun applyAdbSpec(spec: ServerSpec): Int {
                ServerSpecStore.write(
                    dirs.adbServerConfFile(),
                    spec,
                    developer.access.core.ElevationPaths.GUEST_ADB_SOCKET,
                )
                // 「切换生效」：配置已落盘，旧 server 仍在旧 socket 上监听，故只杀 server。
                // 通道入口（wrapper 与真身）必须保留——删掉它们等于关闭通道。
                return channel.terminateServers()
            }

            override fun applyRootSpec(spec: ServerSpec): Boolean {
                developer.access.core.RootSpecStore.write(
                    dirs.rootServerConfFile(),
                    dirs.rootPortStorage(),
                    spec,
                )
                val wasRunning = server?.isRunning == true
                if (!wasRunning) return false
                server?.stop()
                server = developer.access.root.RootExecutor(
                    dirs = dirs,
                    spec = spec,
                    accessCode = { readRootAuthCode(dirs) },
                    limiter = limiter,
                    suCommand = { cmd -> listOf(resolveSu() ?: "su", "-c", cmd) },
                )
                val restarted = server?.start() == true
                // 按新配置起不来时，执行器已不在；CLI 必须一并撤掉，
                // 否则 guest 里会留下"命令存在但连不上"的中间态（与关闭口径不符）。
                if (!restarted) {
                    server = null
                    removeRootCli()
                }
                return restarted
            }

            override fun isSandboxRunning(): Boolean =
                sandboxManager.sandboxState.value ==
                    com.dshbox.app.sandbox.SandboxState.RUNNING

            override fun devOptionsEnabled(): Boolean = runCatching {
                android.provider.Settings.Global.getInt(
                    context.contentResolver,
                    android.provider.Settings.Global.ADB_ENABLED,
                ) == 1
            }.getOrDefault(false)

            private val nsd = NsdDiscovery(context)

            override fun stopPairingDiscovery() {
                discoveryScope.launch {
                    nsd.stop(NsdDiscovery.PAIRING_SERVICE)
                }
            }

            override suspend fun discoverPairingAddress(): String? =
                nsd.discover(NsdDiscovery.PAIRING_SERVICE)

            override suspend fun discoverConnectAddress(): String? =
                nsd.discover(NsdDiscovery.CONNECT_SERVICE)

            override fun pairedDevices(): List<String> =
                developer.access.adb.DeviceRegistry(dirs.guestAndroidDir.let {
                    File(it, "adb_known_hosts.pb")
                }).list().map { it.identifier }

            override suspend fun connectedDevices(): List<String> {
                val text = adbText("devices")
                    ?: throw IllegalStateException("adb devices failed")
                return text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
                    .mapNotNull { line ->
                        val tokens = line.split(Regex("\\s+"))
                        if (tokens.size < 2) return@mapNotNull null
                        val state = tokens[1]
                        if (state !in DEVICE_STATES) return@mapNotNull null
                        "${tokens[0]} · $state"
                    }
                    .toList()
            }

            override suspend fun disconnectAllDevices(): Boolean =
                withContext(Dispatchers.IO) {
                    val out = StringBuilder()
                    sandboxManager.runGuestCommand(
                        developer.access.adb.AdbGateway.command(listOf("disconnect")),
                        onLine = { out.append(it) },
                    ) is AppResult.Success
                }

            /**
             * `su -c id` 预检。
             *
             * 只探测可执行文件存在无法区分"已授权"与"用户拒绝"：后者在开关开启时看不出来，
             * 直到每条命令都失败。这里实际执行一次，8 秒内没有结果按"未获授权"处理
             * （用户可能把 root 管理器的弹窗划掉了）。
             */
            override suspend fun probeRootPermission(): Boolean = withContext(Dispatchers.IO) {
                runCatching {
                    // 用解析出的实际路径：Magisk 在 Android 11+ 可能把 su 放在
                    // /debug_ramdisk，APatch 默认 /system/bin/kp——都不在应用 PATH 上。
                    val su = resolveSu() ?: return@runCatching false
                    val process = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
                    val finished = process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        return@runCatching false
                    }
                    val text = process.inputStream.bufferedReader().readText()
                    process.exitValue() == 0 && text.contains("uid=0")
                }.getOrDefault(false)
            }

            /**
             * 执行一条 adb 子命令并按输出判定结果。
             *
             * 命令由 [developer.access.adb.AdbGateway] 生成——它调用 `/opt/elevation/bin/adb`，
             * 即 DSH 在 PATH 上命中的同一个 wrapper，故密钥、附加密钥集与连接方式
             * 与 DSH 完全一致，配对结果落在 DSH 也会读的那个 server 里。
             */
            private suspend fun runAdb(vararg args: String): developer.access.adb.PairingResult {
                val command = developer.access.adb.AdbGateway.command(args.toList())
                val out = StringBuilder()
                val done = withContext(Dispatchers.IO) {
                    sandboxManager.runGuestCommand(
                        command,
                        onLine = { line -> out.append(line).append('\n') },
                    )
                }
                // 分类必须先于退出码：配对/连接失败时 adb 以非零退出，但输出里已经带着
                // "incorrect code" / "failed to connect" 这类可判定的原因；只看退出码
                // 会把整段原始输出直接展示给用户（该行为已在设备上验证）。
                val text = out.toString()
                // 同一句 protocol fault：pair 表示设备拒绝了配对握手（配对码错误或已过期），
                // connect 才是"对端不是连接服务"。
                val operation = if (args.firstOrNull() == "pair") {
                    developer.access.adb.AdbOutput.Operation.PAIR
                } else {
                    developer.access.adb.AdbOutput.Operation.CONNECT
                }
                // 不在失败后探测端口：设备在配对失败后会立即关闭配对监听，
                // 探测必然超时，会把配对码错误误判为地址不可达。分类只依据输出文本——
                // 地址不可达时 adb 输出 failed to connect，与握手被拒的 protocol fault 不同。
                val classified = developer.access.adb.AdbOutput.classify(text, operation)
                return if (classified == developer.access.adb.PairingResult.Failed("") &&
                    done !is AppResult.Success
                ) {
                    developer.access.adb.PairingResult.Failed("adb exited without output")
                } else {
                    classified
                }
            }

            /** 执行 adb 子命令并取回全部输出；进程失败时返回 null。 */
            private suspend fun adbText(vararg args: String): String? {
                val out = StringBuilder()
                val done = withContext(Dispatchers.IO) {
                    sandboxManager.runGuestCommand(
                        developer.access.adb.AdbGateway.command(args.toList()),
                        onLine = { line -> out.append(line).append('\n') },
                    )
                }
                return if (done is AppResult.Success) out.toString() else null
            }

            override fun regenerateRootAuthCode(): String {
                val code = developer.access.root.RootAuthCode.generate()
                dirs.ensureLayout()
                dirs.rootSecretFile().writeText(code)
                // 新码即新凭据：连续失败的锁定必须一并解除，否则换了码仍然被拒。
                limiter.reset()
                return code
            }

            override fun isRootAuthLocked(): Boolean = limiter.isLockedOut()
        }
        developer.access.DeveloperAccessEntry.install(
            developer.access.DeveloperAccessController(host),
        )
    }

    /**
     * 能力 CLI 目录：本能力自己的开关目录（宿主 `files/elevation/bin`）。
     *
     * 该目录 bind 在 guest `/opt/elevation`，由 [ElevationDirs.ensureLayout] 常驻创建；
     * 内容随开关增删——开则写入、关则删除，guest 侧 `command -v` 随之出现与消失。
     *
     * **不放工作区 `.dsh/dshbox/bin`**：那是随包 CLI 与插件目录，bootstrap 每次整体覆写，
     * 且被锁为 App 管理资产；能力命令混进去会被清掉，也破坏包/能力两种语义的边界。
     */
    private fun cliBinDir(): File = ElevationDirs(context.filesDir).guestBinDir

    /**
     * 解析可执行的 `su`。
     *
     * 不能只看三个历史路径：各家 root 管理器的 su 落点并不统一——
     * Magisk 在 Android 11+ 可能落在 `/debug_ramdisk/su`，APatch 默认是
     * `/system/bin/kp`，SuperSU 系统化安装会在 `/su/bin/su`。
     * 这些路径都不在应用进程的 PATH 上，因此既要在这里找，也要把找到的**绝对路径**
     * 交给执行器，而不是继续依赖 `ProcessBuilder("su", ...)` 的 PATH 查找。
     *
     * 只做"存在且可执行"的静态判断（不触发授权弹窗——面板刷新会频繁调用）；
     * 真正的权限确认由开启开关时的 `su -c id` 预检负责。
     */
    private fun resolveSu(): String? {
        val explicit = SU_PATHS.firstOrNull { isExecutableFile(File(it)) }
        if (explicit != null) return explicit
        // 兜底：某些 root 管理器把 su 链接进应用的 PATH（如旧版 Magisk 的 /sbin 软链）。
        return System.getenv("PATH").orEmpty().split(':')
            .filter { it.isNotBlank() }
            .map { File(it, "su") }
            .firstOrNull(::isExecutableFile)
            ?.absolutePath
    }

    private fun isExecutableFile(file: File): Boolean =
        runCatching { file.isFile && file.canExecute() }.getOrDefault(false)

    /**
     * 落 `android-root` 脚本。
     *
     * 只写一处：`/opt/elevation/bin`。它同时是 adb wrapper 的目录，且已在终端与 DSH
     * 两端 PATH 里（终端由 `TerminalEnvFactory` 注入，DSH 由 `buildProotEnv` 注入），
     * 因此**没有第二份拷贝**，也就不存在两处不同步的问题。
     */
    private fun provisionRootCli() {
        developer.access.root.RootGateway.provision(cliBinDir())
    }

    /**
     * 删除 `android-root`：与 ADB 通道的关闭口径对称——命令彻底不可见，
     * 而不是"存在但执行失败"。
     */
    private fun removeRootCli() {
        developer.access.root.RootGateway.remove(cliBinDir())
    }

    /** 读取访问码（只读，不产生副作用；仅宿主私有目录，guest 不可达）。 */
    private fun readRootAuthCode(dirs: ElevationDirs): String =
        runCatching { dirs.rootSecretFile().readText().trim() }.getOrNull().orEmpty()

    /**
     * 取访问码，缺失时生成并落盘。
     *
     * 只在真正需要 TCP 认证时调用（选择 TCP 档 / 以 TCP 启动执行器）：
     * 首次开启 TCP 档才随机生成，unix 档不该无谓地产生一个秘密。
     */
    private fun ensureRootAuthCode(dirs: ElevationDirs): String {
        val existing = readRootAuthCode(dirs)
        if (existing.isNotEmpty()) return existing
        val code = developer.access.root.RootAuthCode.generate()
        dirs.ensureLayout()
        dirs.rootSecretFile().writeText(code)
        return code
    }

    private companion object {
        const val TAG = "DevAccess"

        /**
         * su 的候选路径，按通用到专用排列。
         *
         * 只覆盖静态文件；KernelSU 的 `/system/bin/su` 是内核层重定向，仍会命中这一项。
         * 顺序即优先级——存在但不可执行的位置会被跳过。
         */
        val SU_PATHS = listOf(
            "/system/bin/su",        // Magisk / KernelSU / APatch 的兼容位置
            "/system/xbin/su",       // 旧版 Magisk / SuperSU（系统化）
            "/sbin/su",              // Android 10 及更早的 Magisk
            "/debug_ramdisk/su",     // Magisk（Android 11+ 的 base 目录）
            "/su/bin/su",            // SuperSU（系统化安装）
            "/system/sbin/su",
            "/vendor/bin/su",
            "/system/bin/kp",        // APatch 默认路径
        )

        /** `adb devices` 第二列可能出现的状态，用于把提示行与设备行区分开。 */
        val DEVICE_STATES = setOf("device", "offline", "unauthorized", "recovery", "sideload", "bootloader")
    }
}
