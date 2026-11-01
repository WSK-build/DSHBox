package developer.access

import com.dshbox.app.common.UiText
import developer.access.R
import developer.access.core.PortStatus
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 面板与宿主之间的执行端口。
 *
 * 面板只发动作、读结果；真正的文件操作、进程管理与 su 执行由宿主实现
 * （本模块不依赖 `:app`，故以接口注入）。
 */
interface DeveloperAccessHost {

    /** ADB 通道当前是否开启（guest 侧命令可达）。 */
    fun isAdbEnabled(): Boolean

    /** 开启 ADB 通道：复检安装、复制真身、写 wrapper。 */
    suspend fun enableAdb(onLine: (String) -> Unit): Boolean

    /** 关闭 ADB 通道：终止 server、删除入口。返回被终止的进程数。 */
    fun disableAdb(): Int

    /** 停止配对服务的常驻 mDNS 发现（向导收起时调用；不实现则保持常驻）。 */
    fun stopPairingDiscovery() = Unit

    /** 读取 ADB 连接方式。 */
    fun adbSpec(): ServerSpec

    /** 写入 ADB 连接方式（生效需重启 server）。 */
    fun setAdbSpec(spec: ServerSpec)

    /** 本机是否存在可用的 su。 */
    fun suAvailable(): Boolean

    /** Root 执行器是否在监听。 */
    fun isRootRunning(): Boolean

    /** 按给定连接方式启动 Root 执行器。 */
    suspend fun startRoot(spec: ServerSpec): RootStartResult

    /** 停止 Root 执行器。 */
    suspend fun stopRoot()

    /** 读取 Root 连接方式。 */
    fun rootSpec(): ServerSpec

    /** 写入 Root 连接方式（仅用户可改：配置在宿主私有目录，guest 不可达）。 */
    fun setRootSpec(spec: ServerSpec)

    /** 当前访问码（未生成时为空串，不在只读刷新里产生副作用）。 */
    fun rootAccessCode(): String

    /** 取访问码，缺失时生成并落盘（首次选择 TCP 模式或启动 TCP 执行器时调用）。 */
    fun ensureRootAuthCode(): String

    /** 重新生成访问码，返回新值。 */
    fun regenerateRootAuthCode(): String

    /** 访问码是否因连续认证失败被拒绝（需重新生成才能解除）。 */
    fun isRootAuthLocked(): Boolean

    /** 列出本机密钥（主密钥在前）。 */
    fun localKeys(): List<developer.access.adb.KeyStore.KeyInfo>

    /** 删除一把密钥；主密钥删除后所有设备都需重新配对。 */
    fun deleteLocalKey(id: String): Boolean

    /** 生成一把附加密钥（需 ADB 通道已开启，因为要调用 guest 内的 adb keygen）。 */
    suspend fun generateLocalKey(): Boolean

    /** 是否已有已配对设备清单。 */
    fun hasPairedDevices(): Boolean

    /** 清除已配对设备清单（保留本机身份）。 */
    fun clearPairedDevices(): Boolean

    /** 配对：对配对端口执行 adb pair。 */
    suspend fun pairDevice(host: String, port: Int, code: String): developer.access.adb.PairingResult

    /** 连接：对连接端口执行 adb connect（配对成功后的第二步）。 */
    suspend fun connectDevice(host: String, port: Int): developer.access.adb.PairingResult

    /**
     * 探测端口是否可绑定（不杀任何他人进程）。
     *
     * @param forLan 局域网档需按"全部网卡"探测——只测回环会漏掉绑在其他接口上的占用。
     */
    fun probePort(port: Int, forLan: Boolean): PortStatus

    /**
     * 应用 ADB 连接方式：写配置，并在通道开启时终止旧 server 使其按新方式重建
     * 。
     */
    fun applyAdbSpec(spec: ServerSpec): Int

    /** 应用 Root 连接方式：写配置，并在执行器运行时按新方式重启。 */
    fun applyRootSpec(spec: ServerSpec): Boolean

    /** 沙箱当前是否在运行（决定设备操作类功能项是否可用）。 */
    fun isSandboxRunning(): Boolean

    /** 系统开发者选项（USB 调试）是否已开启。 */
    fun devOptionsEnabled(): Boolean

    /** 已配对设备清单（每条为可读的一行）。 */
    fun pairedDevices(): List<String>

    /**
     * 当前**在线**的设备（`adb devices` 的结果，每条形如 `序列号 · 状态`）。
     *
     * 与 [pairedDevices] 不同：那个读的是"配对记录"，这个问的是 adb server 现在
     * 真正握着的连接——是"是否真的连上了"的唯一直接证据。
     */
    suspend fun connectedDevices(): List<String>

    /** 断开全部无线/TCP 连接（`adb disconnect`，不动 USB 设备）。 */
    suspend fun disconnectAllDevices(): Boolean

    /**
     * `su` 授权预检：以 `su -c id` 实际执行一次。
     *
     * 仅探测文件存在不够——su 存在但用户拒绝授权时，开关看起来开了、命令却全部失败。
     */
    suspend fun probeRootPermission(): Boolean

    /**
     * 自动发现配对端口（mDNS `_adb-tls-pairing._tcp`）。
     *
     * 端口每次开启无线调试都会变，必须现查。
     *
     * @return `ip:port`；未发现时返回 null。
     */
    suspend fun discoverPairingAddress(): String?

    /** 自动发现连接端口（mDNS `_adb-tls-connect._tcp`）。 */
    suspend fun discoverConnectAddress(): String?
}


/**
 * Root 执行器的启动结果。
 *
 * 必须区分"监听失败（端口/socket 被占用）"与"CLI 落盘失败"——两者对用户的处置完全不同，
 * 统一报"启动失败"会把人引到错误方向。
 */
enum class RootStartResult {
    STARTED,

    /** 监听建立失败（socket 路径或端口被占用）。 */
    FAILED,

    /** 监听已建立，但 `android-root` 写不进 CLI 目录。 */
    PROVISION_FAILED,
}

/** 解析 `host:port`；格式不合法（缺主机、端口越界）返回 null。 */
internal fun parseHostPort(raw: String): Pair<String, Int>? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val host = text.substringBeforeLast(':', "")
    val port = text.substringAfterLast(':', "").toIntOrNull()
    if (host.isBlank() || port == null || port !in 1..65535) return null
    return host to port
}


/**
 * 面板控制器：对外唯一入口。
 *
 * 只做两件事——把宿主状态读成快照、把用户动作转成宿主调用。
 * 不持有任何策略判断：不做逐次审批，不设能力档位，也不限制调用频次。
 */
class DeveloperAccessController(private val host: DeveloperAccessHost) {

    private val _state = MutableStateFlow(DeveloperAccessUiState())
    val state: StateFlow<DeveloperAccessUiState> = _state.asStateFlow()

    /** 端口探测这类轻量阻塞操作放 IO：面板在 LaunchedEffect 里触发，不该占主线程。 */
    private val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * 串行化"读宿主 → 写状态"的整段动作。
     *
     * IO 是多线程的：连点两次[应用]或应用与刷新交错时，多个 `_state.update` 会乱序，
     * 用户看到的可能是先发那次的结果。用一把互斥锁把动作与状态写回排成一条线。
     */
    private val ioMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 重新采集宿主状态。
     *
     * 读到的是配置文件、密钥目录与 `/proc` 之类的东西，一律在 IO 线程完成后再写回快照：
     * 本方法会被 LaunchedEffect 与点击回调调用，不能在其中做阻塞 IO。
     */
    fun refresh() {
        io.launch {
            ioMutex.withLock {
                val adbSpecRead = runCatching { host.adbSpec() }.getOrDefault(ServerSpec())
                // 越界端口（历史版本/手工改配置）会让每条 adb 命令都报 `bad port number`。
                // 这里就地改回默认端口并写盘，让通道立刻恢复可用；提示同时告知用户。
                val adbPortRepaired = !adbSpecRead.hasValidPort()
                val adbSpec = if (adbPortRepaired) {
                    adbSpecRead
                        .copy(port = developer.access.core.ElevationPaths.DEFAULT_ADB_SERVER_PORT)
                        .also { fixed -> runCatching { host.setAdbSpec(fixed) } }
                } else {
                    adbSpecRead
                }
                val rootSpecRead = runCatching { host.rootSpec() }.getOrDefault(ServerSpec())
                val rootSpec = if (rootSpecRead.hasValidPort()) {
                    rootSpecRead
                } else {
                    rootSpecRead.copy(
                        port = developer.access.core.ElevationPaths.DEFAULT_ROOT_SERVER_PORT,
                    )
                }
                val sandboxRunning = host.isSandboxRunning()
                // TCP 档必须有码可展示/可扫；unix 档只读（不生成）。
                val rootCode = if (rootSpec.transport != ServerTransport.UNIX_SOCKET) {
                    runCatching { host.ensureRootAuthCode() }.getOrDefault("")
                } else {
                    runCatching { host.rootAccessCode() }.getOrDefault("")
                }
                val keys = runCatching { host.localKeys() }.getOrDefault(emptyList())
                val hasPaired = runCatching { host.hasPairedDevices() }.getOrDefault(false)
                val paired = runCatching { host.pairedDevices() }.getOrDefault(emptyList())
                val locked = runCatching { host.isRootAuthLocked() }.getOrDefault(false)
                _state.update {
                    // 刷新是异步的：若用户在刷新落地前改了档位/端口（草稿与落盘不一致），
                    // 保留草稿，只更新"已落盘值"，否则用户正在编辑的内容会被悄悄打回。
                    val adbDirty = it.adbSpec != it.adbPersistedSpec
                    val rootDirty = it.rootSpec != it.rootPersistedSpec
                    it.copy(
                        adbEnabled = host.isAdbEnabled(),
                        // 端口被自动修复时告知用户，否则会以为端口"自己变了"。
                        hint = if (adbPortRepaired) {
                            UiText.Res(R.string.dev_msg_port_reset, listOf(adbSpecRead.port.toString()))
                        } else {
                            it.hint
                        },
                        hintIsError = it.hintIsError || adbPortRepaired,
                        hintFromRoot = if (adbPortRepaired) false else it.hintFromRoot,
                        adbSpec = if (adbDirty) it.adbSpec else adbSpec,
                        adbPersistedSpec = adbSpec,
                        adbDraftPort = if (adbDirty) it.adbDraftPort else adbSpec.port,
                        adbDraftCustom = if (adbDirty) it.adbDraftCustom else adbSpec.customSpec.orEmpty(),
                        rootEnabled = host.isRootRunning(),
                        rootSpec = if (rootDirty) it.rootSpec else rootSpec,
                        rootPersistedSpec = rootSpec,
                        rootDraftPort = if (rootDirty) it.rootDraftPort else rootSpec.port,
                        rootAccessCode = rootCode,
                        rootAuthLocked = locked,
                        suAvailable = host.suAvailable(),
                        keys = keys,
                        hasPairedDevices = hasPaired,
                        pairedDevices = paired,
                        sandboxRunning = sandboxRunning,
                        // 沙箱停掉后 server 也随之消失，之前查到的在线设备不再成立。
                        connectedDevices = if (sandboxRunning) it.connectedDevices else emptyList(),
                        devicesLoaded = sandboxRunning && it.devicesLoaded,
                        devOptionsEnabled = host.devOptionsEnabled(),
                    )
                }
            }
        }
    }

    /**
     * 只重读设备授权相关状态。
     *
     * 与 [refresh] 分开是为了不让"刷新列表"这类局部动作**覆盖连接方式草稿**：
     * 用户可能已经选好档位、改好端口却还没点 [应用]，此时全量刷新会把草稿打回
     * 已落盘值，看起来像按钮失效。
     */
    fun refreshKeys() {
        io.launch {
            ioMutex.withLock {
                val sandboxRunning = host.isSandboxRunning()
                val keys = runCatching { host.localKeys() }.getOrDefault(emptyList())
                val hasPaired = runCatching { host.hasPairedDevices() }.getOrDefault(false)
                val paired = runCatching { host.pairedDevices() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        keys = keys,
                        hasPairedDevices = hasPaired,
                        pairedDevices = paired,
                        sandboxRunning = sandboxRunning,
                        connectedDevices = if (sandboxRunning) it.connectedDevices else emptyList(),
                        devicesLoaded = sandboxRunning && it.devicesLoaded,
                    )
                }
            }
        }
    }

    /** 切换 ADB 通道。 */
    suspend fun setAdbEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(busy = true, hint = null, hintIsError = false)
        try {
            var hint: UiText? = null
            var isError = false
            if (enabled) {
                // 启停涉及文件复制与 /proc 扫描；异常与阻塞都不得留在调用方（UI）线程。
                val ok = withContext(Dispatchers.IO) {
                    runCatching { host.enableAdb { } }.getOrDefault(false)
                }
                // 这两项都是 Binder 调用（Settings.Global / 沙箱状态），同样不在主线程取。
                val devOptions = withContext(Dispatchers.IO) { host.devOptionsEnabled() }
                val sandboxUp = withContext(Dispatchers.IO) { host.isSandboxRunning() }
                when {
                    !ok -> {
                        hint = UiText.Res(R.string.dev_msg_adb_enable_failed)
                        isError = true
                    }
                    // 装机成功但系统侧没开 USB 调试：此后任何设备都连不上，必须先说清。
                    !devOptions -> {
                        hint = UiText.Res(R.string.dev_msg_adb_enabled_no_dev_options)
                        isError = true
                    }
                    // server 跑在 guest 里，沙箱没跑时开关只能把入口装好。
                    !sandboxUp -> hint = UiText.Res(R.string.dev_msg_adb_enabled_sandbox_down)
                }
            } else {
                withContext(Dispatchers.IO) { runCatching { host.disableAdb() } }
                hint = UiText.Res(R.string.dev_msg_adb_disabled)
            }
            _state.value = _state.value.copy(
                busy = false,
                hint = hint,
                hintIsError = isError,
                hintFromRoot = false,
                // 关闭后 server 已不在，之前查到的在线设备列表不再成立。
                connectedDevices = if (enabled) _state.value.connectedDevices else emptyList(),
                devicesLoaded = if (enabled) _state.value.devicesLoaded else false,
                // 关闭开关属于"用户以为已经清净"的时刻，必须弹窗说明手机侧授权仍在。
                showAdbDisableNotice = !enabled,
            )
            refresh()
        } finally {
            // 页面销毁等取消场景下，上面的收尾可能没走到；busy 必须复位，否则开关永久变灰。
            _state.update { if (it.busy) it.copy(busy = false) else it }
        }
    }

    /** 切换 Root 通道。 */
    suspend fun setRootEnabled(enabled: Boolean) {
        if (enabled) {
            // 探测 su 要读系统路径，放 IO 线程。
            if (!withContext(Dispatchers.IO) { host.suAvailable() }) {
                _state.value = _state.value.copy(
                    hint = UiText.Res(R.string.dev_msg_no_root),
                    hintIsError = true,
                    hintFromRoot = true,
                )
                return
            }
            // 先真的用 su 执行一次：文件存在不等于拿到授权，拒绝授权时开关必须给明确原因。
            _state.value = _state.value.copy(busy = true)
            val authorized = try {
                host.probeRootPermission()
            } finally {
                // 预检可能因取消而抛，busy 必须复位。
                _state.update { it.copy(busy = false) }
            }
            if (!authorized) {
                _state.value = _state.value.copy(
                    hint = UiText.Res(R.string.dev_msg_root_not_authorized),
                    hintIsError = true,
                    hintFromRoot = true,
                )
                refresh()
                return
            }
            // 宿主侧要做文件与进程操作；任何异常都必须变成提示，不能从 UI 协程逃逸。
            val result = runCatching { host.startRoot(host.rootSpec()) }
                .getOrDefault(RootStartResult.FAILED)
            _state.value = _state.value.copy(
                hint = when (result) {
                    RootStartResult.STARTED -> null
                    // 落盘失败与监听失败是两回事，提示必须分开，否则用户会去查端口占用。
                    RootStartResult.PROVISION_FAILED -> UiText.Res(R.string.dev_msg_root_provision_failed)
                    RootStartResult.FAILED -> UiText.Res(R.string.dev_msg_root_start_failed)
                },
                hintIsError = result != RootStartResult.STARTED,
                hintFromRoot = true,
            )
        } else {
            host.stopRoot()
            _state.value = _state.value.copy(hint = null, hintFromRoot = true)
        }
        refresh()
    }

    /** 选择 ADB 传输形态（仅改草稿，点 [应用] 才落盘）。 */
    fun setAdbTransport(transport: ServerTransport) {
        _state.value = _state.value.copy(adbSpec = _state.value.adbSpec.copy(transport = transport))
    }

    /** 编辑 ADB 端口（仅改草稿）。 */
    fun setAdbDraftPort(port: Int) {
        _state.value = _state.value.copy(
            adbDraftPort = port,
            adbSpec = _state.value.adbSpec.copy(port = port),
        )
    }

    /** 编辑 ADB 自定义 spec（仅改草稿）。 */
    fun setAdbDraftCustom(spec: String) {
        _state.value = _state.value.copy(
            adbDraftCustom = spec,
            adbSpec = _state.value.adbSpec.copy(customSpec = spec),
        )
    }

    /** 探测 ADB 端口占用（局域网档按全部网卡探测；不阻塞主线程）。 */
    fun probeAdbPort() {
        val spec = _state.value.adbSpec
        val forLan = spec.transport == ServerTransport.TCP_LAN
        io.launch {
            ioMutex.withLock {
                val status = runCatching { host.probePort(spec.port, forLan) }
                    .getOrDefault(PortStatus.UNKNOWN)
                _state.update { it.copy(adbPortStatus = status) }
            }
        }
    }

    /**
     * 应用 ADB 连接方式：写配置；若通道已开启，按终止旧 server，
     * 下次调用会自动按新方式重建。
     */
    fun applyAdb() {
        // 防重入：连点两次会并发写同一份配置、各扫一遍 /proc，且提示顺序会乱。
        if (_state.value.busy) return
        val spec = _state.value.adbSpec
        // 越界端口不落盘：写进 server.conf 会让之后每次 adb 调用都以
        // `bad port number` 失败，而面板上只看到一行原始报错。
        if (!spec.hasValidPort()) {
            _state.value = _state.value.copy(
                hint = UiText.Res(R.string.dev_msg_port_out_of_range),
                hintIsError = true,
                hintFromRoot = false,
            )
            return
        }
        _state.value = _state.value.copy(busy = true)
        io.launch {
            // finally 放在等锁之外：即使等锁期间被取消，busy 也会复位。
            try {
                ioMutex.withLock {
                    // 写配置 + 按路径扫描 /proc 杀旧 server，都是阻塞操作。
                    val killed = runCatching { host.applyAdbSpec(spec) }.getOrDefault(0)
                    val specText = spec.toSocketSpec(developer.access.core.ElevationPaths.GUEST_ADB_SOCKET)
                    _state.update {
                        it.copy(
                            hint = if (killed > 0) {
                                UiText.Res(R.string.dev_msg_spec_applied_restarted, listOf(specText, killed))
                            } else {
                                UiText.Res(R.string.dev_msg_spec_applied, listOf(specText))
                            },
                            hintIsError = false,
                            hintFromRoot = false,
                        )
                    }
                    refresh()
                    probeAdbPort()
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 选择 Root 传输形态（仅改草稿）；切到 TCP 档时按需生成访问码。 */
    fun setRootTransport(transport: ServerTransport) {
        _state.value = _state.value.copy(rootSpec = _state.value.rootSpec.copy(transport = transport))
        // 首次开启 TCP 档才生成访问码：unix 档用不到，不该提前产生一个秘密。
        if (transport != ServerTransport.UNIX_SOCKET && _state.value.rootAccessCode.isBlank()) {
            // 生成要读写 secret 文件，放 IO 完成后再写回，别占点击回调所在的线程。
            io.launch {
                ioMutex.withLock {
                    val code = runCatching { host.ensureRootAuthCode() }.getOrDefault("")
                    if (code.isNotBlank()) {
                        _state.update { it.copy(rootAccessCode = code) }
                    }
                }
            }
        }
    }

    /** 编辑 Root 端口（仅改草稿）。 */
    fun setRootDraftPort(port: Int) {
        _state.value = _state.value.copy(
            rootDraftPort = port,
            rootSpec = _state.value.rootSpec.copy(port = port),
        )
    }

    /** 探测 Root 端口占用（局域网档按全部网卡探测；不阻塞主线程）。 */
    fun probeRootPort() {
        val spec = _state.value.rootSpec
        val forLan = spec.transport == ServerTransport.TCP_LAN
        io.launch {
            ioMutex.withLock {
                val status = runCatching { host.probePort(spec.port, forLan) }
                    .getOrDefault(PortStatus.UNKNOWN)
                _state.update { it.copy(rootPortStatus = status) }
            }
        }
    }

    /** 应用 Root 连接方式：写配置；执行器在跑时按新方式重启。 */
    fun applyRoot() {
        if (_state.value.busy) return
        val spec = _state.value.rootSpec
        // 与 ADB 同一口径：越界端口不落盘（Root 侧同样以端口起监听）。
        if (!spec.hasValidPort()) {
            _state.value = _state.value.copy(
                hint = UiText.Res(R.string.dev_msg_port_out_of_range),
                hintIsError = true,
                hintFromRoot = true,
            )
            return
        }
        val wasRunning = _state.value.rootEnabled
        _state.value = _state.value.copy(busy = true)
        io.launch {
            try {
                ioMutex.withLock {
                    val restarted = runCatching { host.applyRootSpec(spec) }.getOrDefault(false)
                    // 三种结果要分开说：重启成功 / 本来没开（无需重启）/ 开了但按新配置起不来。
                    // 把第三种说成"通道关闭"会让用户以为是自己关的。
                    val failed = wasRunning && !restarted
                    _state.update {
                        it.copy(
                            hint = UiText.Res(
                                when {
                                    restarted -> R.string.dev_msg_root_applied_restarted
                                    failed -> R.string.dev_msg_root_restart_failed
                                    else -> R.string.dev_msg_root_applied
                                },
                            ),
                            hintIsError = failed,
                            hintFromRoot = true,
                        )
                    }
                    refresh()
                    probeRootPort()
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 重新生成访问码（旧码立即失效，已配置的外部工具需重配；同时解除失败锁定）。 */
    fun regenerateAccessCode() {
        io.launch {
            ioMutex.withLock {
                val code = runCatching { host.regenerateRootAuthCode() }.getOrDefault("")
                _state.update {
                    it.copy(
                        rootAccessCode = code.ifBlank { it.rootAccessCode },
                        // 重新生成即解除"持续失败"的拒绝态，面板的警示随之消失。
                        rootAuthLocked = false,
                        hint = UiText.Res(R.string.dev_msg_code_regenerated),
                        hintIsError = false,
                        hintFromRoot = true,
                    )
                }
            }
        }
    }

    /** 删除一把密钥。主密钥删除后本机身份消失，须明确告知影响面。 */
    fun deleteKey(id: String) {
        io.launch {
            ioMutex.withLock {
                val ok = runCatching { host.deleteLocalKey(id) }.getOrDefault(false)
                val primary = id == developer.access.adb.KeyStore.PRIMARY_ID
                _state.update {
                    it.copy(
                        hint = when {
                            !ok -> UiText.Res(R.string.dev_msg_key_delete_failed)
                            primary -> UiText.Res(R.string.dev_msg_key_deleted_primary)
                            else -> UiText.Res(R.string.dev_msg_key_deleted_other)
                        },
                        hintIsError = !ok,
                        hintFromRoot = false,
                    )
                }
                refresh()
            }
        }
    }

    /** 生成附加密钥。 */
    suspend fun generateKey() {
        val ok = runCatching { host.generateLocalKey() }.getOrDefault(false)
        _state.value = _state.value.copy(
            hint = UiText.Res(if (ok) R.string.dev_msg_key_generated else R.string.dev_msg_key_generate_failed),
            hintIsError = !ok,
            hintFromRoot = false,
        )
        refresh()
    }

    /** 清除已配对设备清单（保留本机身份）。 */
    fun clearPairedDevices() {
        io.launch {
            ioMutex.withLock {
                val ok = runCatching { host.clearPairedDevices() }.getOrDefault(false)
                _state.update {
                    it.copy(
                        hint = if (ok) {
                            UiText.Res(R.string.dev_msg_pair_cleared)
                        } else {
                            UiText.Res(R.string.dev_msg_clear_failed)
                        },
                        hintIsError = !ok,
                        hintFromRoot = false,
                    )
                }
                refresh()
            }
        }
    }

    /** 查看在线设备：向 adb server 现查一次 `adb devices`。 */
    suspend fun refreshDevices() {
        _state.value = _state.value.copy(devicesBusy = true)
        val devices = runCatching { host.connectedDevices() }.getOrNull()
        _state.value = _state.value.copy(
            devicesBusy = false,
            devicesLoaded = devices != null || _state.value.devicesLoaded,
            connectedDevices = devices.orEmpty(),
            hint = if (devices == null) UiText.Res(R.string.dev_msg_devices_failed) else null,
            hintIsError = devices == null,
            hintFromRoot = false,
        )
    }

    /** 断开全部无线连接（`adb disconnect`）。 */
    suspend fun disconnectDevices() {
        _state.value = _state.value.copy(devicesBusy = true)
        val ok = runCatching { host.disconnectAllDevices() }.getOrDefault(false)
        _state.value = _state.value.copy(
            devicesBusy = false,
            hint = UiText.Res(
                if (ok) R.string.dev_msg_devices_disconnected else R.string.dev_msg_devices_disconnect_failed,
            ),
            hintIsError = !ok,
            hintFromRoot = false,
        )
        if (ok) refreshDevices()
    }

    /** 打开配对向导（第一步）。 */
    fun openPairing() {
        _state.value = _state.value.copy(
            pairingOpen = true,
            pairingStep = developer.access.ui.PairingStep.INTRO,
            pairingCode = "",
            pairingError = null,
            pairingNeedsConnectTarget = false,
            pairingConnectDraft = "",
            pairingConnectAddress = "",
            pairingPairDraft = "",
        )
    }

    /**
     * 第一步 → 第二步：**无论自动发现是否成功都进入输码页**。
     *
     * 早前版本发现失败就停在第一步，用户既看不到配对码输入框、也没有手填入口——
     * 等于把"mDNS 不可用"直接变成死路。现在自动发现只负责预填，找不到就提示手填。
     */
    suspend fun pairingNext() {
        _state.value = _state.value.copy(pairingBusy = true, pairingError = null)
        val addr = runCatching { host.discoverPairingAddress() }.getOrNull()
        val target = addr?.let { parseHostPort(it) }
        _state.value = _state.value.copy(
            pairingBusy = false,
            pairingStep = developer.access.ui.PairingStep.CODE,
            pairingHost = target?.first ?: _state.value.pairingHost,
            pairingPort = target?.second ?: _state.value.pairingPort,
            pairingAddress = if (target != null) addr.orEmpty() else _state.value.pairingAddress,
            // 发现到手就覆盖（含自动值刷新）；没发现则保留现值，交给自动重试。
            pairingPairDraft = if (target != null) addr!! else _state.value.pairingPairDraft,
            pairingAddressAuto = if (target != null) true else _state.value.pairingAddressAuto,
            pairingError = if (target == null) UiText.Res(R.string.dev_msg_pairing_not_found) else null,
        )
        // 无论这次是否发现到地址都开始跟随：用户可能稍后重开配对弹窗，端口会变。
        schedulePairingDiscovery()
    }

    /**
     * 第二步期间的持续地址跟随。
     *
     * 配对服务只在手机侧「使用配对码配对」弹窗打开期间广播，且每次重开弹窗都会换端口。
     * 进入第二步时往往还查不到，之后也可能因重开弹窗而变化——因此只要停在第二步且
     * 地址仍是自动值，就按固定间隔持续比对；发现变化立即更新。
     * 手动编辑地址后停止跟随，避免覆盖用户输入。
     */
    private var discoveryJob: Job? = null

    private fun schedulePairingDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = io.launch {
            while (true) {
                delay(DISCOVERY_INTERVAL_MS)
                val snapshot = _state.value
                // 已关闭、已离开第二步、或用户手填过地址 → 停。
                // 自动填的值**不阻止**后续刷新：手机侧一换端口就要跟着换。
                if (!snapshot.pairingOpen ||
                    snapshot.pairingStep != developer.access.ui.PairingStep.CODE ||
                    (!snapshot.pairingAddressAuto && snapshot.pairingPairDraft.isNotBlank())
                ) {
                    return@launch
                }
                val found = runCatching { host.discoverPairingAddress() }.getOrNull()
                val target = found?.let { parseHostPort(it) }
                if (target == null) continue
                // 与现值相同则不写状态：避免每轮都触发一次重组。
                if (found == snapshot.pairingPairDraft &&
                    target.first == snapshot.pairingHost &&
                    target.second == snapshot.pairingPort
                ) {
                    continue
                }
                _state.update {
                    it.copy(
                        pairingHost = target.first,
                        pairingPort = target.second,
                        pairingAddress = found,
                        pairingPairDraft = found,
                        pairingAddressAuto = true,
                        pairingError = null,
                    )
                }
            }
        }
    }

    /**
     * 第二步的「自动检测地址」。
     *
     * 与 [pairingNext] 的区别：不把 [DeveloperAccessUiState.pairingBusy] 置位，
     * 因此不会锁住配对码输入框——用户可以在检测的同时继续敲码。
     * 结果只写地址相关字段，码不会被清。
     */
    suspend fun redetectPairingAddress() {
        if (_state.value.pairingDiscovering) return
        _state.value = _state.value.copy(pairingDiscovering = true, pairingError = null)
        val found = runCatching { host.discoverPairingAddress() }.getOrNull()
        val target = found?.let { parseHostPort(it) }
        _state.update {
            it.copy(
                pairingDiscovering = false,
                pairingHost = target?.first ?: it.pairingHost,
                pairingPort = target?.second ?: it.pairingPort,
                pairingAddress = if (target != null) found else it.pairingAddress,
                pairingPairDraft = if (target != null) found else it.pairingPairDraft,
                pairingAddressAuto = if (target != null) true else it.pairingAddressAuto,
                pairingError = if (target == null) {
                    UiText.Res(R.string.dev_msg_pairing_not_found)
                } else {
                    null
                },
            )
        }
        if (target == null) schedulePairingDiscovery()
    }

    /** 编辑手填的配对地址（`ip:端口`）。 */
    fun setPairingPairDraft(raw: String) {
        // 一旦手改就视为用户意图：停止自动覆盖，避免把用户输入冲掉。
        if (raw.isNotBlank()) discoveryJob?.cancel()
        _state.value = _state.value.copy(
            pairingPairDraft = raw,
            pairingAddressAuto = raw.isBlank(),
        )
    }

    /** 编辑传统授权方式的连接地址（`ip:端口`）。 */
    fun setDirectConnectDraft(raw: String) {
        _state.value = _state.value.copy(directConnectDraft = raw)
    }

    /**
     * 传统授权方式：直接 `adb connect <ip:端口>`，由手机弹「允许调试」完成授权。
     *
     * 与配对码路径并列保留：适用于已允许过调试的设备，
     * 或用户先 `adb tcpip <port>` 固定端口的场景；不需要配对码。
     */
    suspend fun connectDirect() {
        val raw = _state.value.directConnectDraft
        val target = parseHostPort(raw)
        if (target == null) {
            // 就地反馈：全局提示卡在这一屏之外，点了只会像"没反应"。
            _state.value = _state.value.copy(
                directConnectBusy = false,
                directConnectStatus = UiText.Res(R.string.dev_msg_direct_addr_invalid),
                directConnectStatusIsError = true,
            )
            return
        }
        _state.value = _state.value.copy(
            directConnectBusy = true,
            directConnectStatus = UiText.Res(R.string.dev_direct_status_connecting),
            directConnectStatusIsError = false,
        )
        val result = runCatching { host.connectDevice(target.first, target.second) }
            .getOrNull()
        _state.update {
            it.copy(
                directConnectBusy = false,
                directConnectStatus = when (result) {
                    is developer.access.adb.PairingResult.Success ->
                        UiText.Res(R.string.dev_msg_direct_connected, listOf(raw.trim()))
                    // 设备上还没允许本机：此时手机会弹「允许调试」，等用户点完再重试即可。
                    developer.access.adb.PairingResult.CodeRejected ->
                        UiText.Res(R.string.dev_msg_direct_auth_needed)
                    developer.access.adb.PairingResult.Unreachable ->
                        UiText.Res(R.string.dev_msg_direct_unreachable)
                    else -> UiText.Res(R.string.dev_msg_direct_failed)
                },
                directConnectStatusIsError =
                    result !is developer.access.adb.PairingResult.Success,
            )
        }
    }

    /** 关闭配对向导。 */
    fun closePairing() {
        discoveryJob?.cancel()
        host.stopPairingDiscovery()
        _state.value = _state.value.copy(pairingOpen = false, pairingBusy = false, pairingError = null)
    }

    /** 输入配对码；填满 6 位即提交。 */
    suspend fun setPairingCode(raw: String) {
        val next = developer.access.adb.PairingCode.normalize(raw)
        _state.value = _state.value.copy(pairingCode = next, pairingError = null)
        if (developer.access.adb.PairingCode.isComplete(next)) submitPairing()
    }

    /** 提交配对，成功后再对连接端口执行一次 connect（配对 ≠ 连接）。 */
    suspend fun submitPairing() {
        var s = _state.value
        // 自动值在提交前再确认一次：手机侧重开配对弹窗会换端口，轮询可能尚未完成。
        // 手填地址不覆盖，以保证用户指定的目标优先。
        if (s.pairingAddressAuto || s.pairingPairDraft.isBlank()) {
            val fresh = runCatching { host.discoverPairingAddress() }.getOrNull()
            val freshTarget = fresh?.let { parseHostPort(it) }
            if (freshTarget != null) {
                s = s.copy(
                    pairingHost = freshTarget.first,
                    pairingPort = freshTarget.second,
                    pairingAddress = fresh,
                    pairingPairDraft = fresh,
                    pairingAddressAuto = true,
                )
                _state.value = s
            }
        }
        // 手填优先：自动发现失败、或用户从「使用配对码配对」弹窗里抄了地址时用它。
        val manual = parseHostPort(s.pairingPairDraft)
        val pairHost = manual?.first ?: s.pairingHost
        val pairPort = manual?.second ?: s.pairingPort
        if (pairHost.isBlank() || pairPort <= 0) {
            _state.value = s.copy(pairingError = UiText.Res(R.string.dev_msg_pairing_address_required))
            return
        }
        _state.value = s.copy(
            pairingBusy = true,
            pairingError = null,
            pairingHost = pairHost,
            pairingPort = pairPort,
            pairingAddress = manual?.let { s.pairingPairDraft.trim() } ?: s.pairingAddress,
        )
        val paired = host.pairDevice(pairHost, pairPort, s.pairingCode)
        if (paired !is developer.access.adb.PairingResult.Success) {
            _state.value = _state.value.copy(
                pairingBusy = false,
                pairingError = when (paired) {
                    developer.access.adb.PairingResult.CodeRejected ->
                        UiText.Res(R.string.dev_msg_pairing_bad_code)
                    developer.access.adb.PairingResult.Unreachable ->
                        UiText.Res(R.string.dev_msg_pairing_unreachable)
                    // 归不了类时把 adb 原文附上——只说"失败"等于没说，用户无从下手。
                    is developer.access.adb.PairingResult.Failed ->
                        if (paired.detail.isBlank()) {
                            UiText.Res(R.string.dev_msg_pairing_generic_failed)
                        } else {
                            UiText.Res(R.string.dev_msg_pairing_failed_detail, listOf(paired.detail))
                        }
                    else -> UiText.Res(R.string.dev_msg_pairing_generic_failed)
                },
            )
            return
        }
        // 配对成功只代表公钥进了设备；连接端口是**另一个** mDNS 服务，必须再连一次。
        val connectTarget = host.discoverConnectAddress()
        if (connectTarget.isNullOrBlank()) {
            // 拿配对端口去 connect 必然失败（两个端口不同），故不猜：让用户抄一次。
            _state.value = _state.value.copy(
                pairingBusy = false,
                pairingNeedsConnectTarget = true,
                pairingError = UiText.Res(R.string.dev_msg_pairing_connect_manual),
            )
            refresh()
            return
        }
        val connectHost = connectTarget.substringBeforeLast(':', "").ifBlank { s.pairingHost }
        val connectPort = connectTarget.substringAfterLast(':', "").toIntOrNull() ?: 0
        val connected = if (connectPort > 0) host.connectDevice(connectHost, connectPort) else null
        val done = connected is developer.access.adb.PairingResult.Success
        if (done) discoveryJob?.cancel()
        _state.value = _state.value.copy(
            pairingBusy = false,
            pairingStep = if (done) developer.access.ui.PairingStep.DONE else s.pairingStep,
            pairingNeedsConnectTarget = !done,
            // 连接成功时把地址留在面板上：这是本侧唯一可复制的"配对结果"。
            pairingConnectAddress = if (done) "$connectHost:$connectPort" else s.pairingConnectAddress,
            pairingError = if (done) null else UiText.Res(R.string.dev_msg_pairing_connect_manual),
        )
        refresh()
    }

    /** 编辑手填的连接地址。 */
    fun setPairingConnectDraft(raw: String) {
        _state.value = _state.value.copy(pairingConnectDraft = raw)
    }

    /** 用用户抄来的连接地址再连一次（自动发现失败时的回落路径）。 */
    suspend fun submitManualConnect() {
        val s = _state.value
        val target = s.pairingConnectDraft.trim()
        val targetHost = target.substringBeforeLast(':', "")
        val targetPort = target.substringAfterLast(':', "").toIntOrNull() ?: 0
        if (targetHost.isBlank() || targetPort !in 1..65535) {
            _state.value = s.copy(
                pairingError = UiText.Res(R.string.dev_msg_connect_address_invalid),
                pairingNeedsConnectTarget = true,
            )
            return
        }
        _state.value = s.copy(pairingBusy = true, pairingError = null)
        val connected = host.connectDevice(targetHost, targetPort)
        val ok = connected is developer.access.adb.PairingResult.Success
        _state.value = _state.value.copy(
            pairingBusy = false,
            pairingStep = if (ok) developer.access.ui.PairingStep.DONE else s.pairingStep,
            pairingNeedsConnectTarget = !ok,
            pairingConnectAddress = if (ok) "$targetHost:$targetPort" else s.pairingConnectAddress,
            pairingError = if (ok) null else UiText.Res(R.string.dev_msg_pairing_connect_manual),
        )
        if (ok) refresh()
    }

    /** 重新获取配对码（码过期时的高频操作）：回到第二步并重新发现配对端口。 */
    suspend fun restartPairing() {
        discoveryJob?.cancel()
        _state.value = _state.value.copy(
            pairingStep = developer.access.ui.PairingStep.CODE,
            pairingCode = "",
            pairingError = null,
            pairingBusy = true,
            pairingNeedsConnectTarget = false,
        )
        // 每次重开手机侧配对对话框，配对端口都会变，故必须重查而不是复用旧地址。
        val addr = host.discoverPairingAddress()
        if (addr.isNullOrBlank()) {
            _state.value = _state.value.copy(
                pairingBusy = false,
                pairingError = UiText.Res(R.string.dev_msg_pairing_not_found),
            )
            // 与第二步同一口径：手机侧弹窗稍后才开时，自动补上地址。
            schedulePairingDiscovery()
            return
        }
        _state.value = _state.value.copy(
            pairingBusy = false,
            pairingHost = addr.substringBeforeLast(':', ""),
            pairingPort = addr.substringAfterLast(':', "").toIntOrNull() ?: 0,
            pairingAddress = addr,
            pairingPairDraft = addr,
            pairingAddressAuto = true,
            pairingError = null,
        )
        // 重开配对码后端口仍可能变化（反复开关配对弹窗），继续跟随。
        schedulePairingDiscovery()
    }

    /** 关闭"手机侧授权仍在"的告知弹窗。 */
    fun dismissAdbDisableNotice() {
        _state.value = _state.value.copy(showAdbDisableNotice = false)
    }

    /** 清除提示。 */
    fun clearHint() {
        _state.value = _state.value.copy(hint = null)
    }

    private companion object {
        /**
         * 第二步地址跟随的轮询间隔。
         *
         * 发现会话常驻且带缓存，命中时开销可忽略；未命中时单次超时上限为 6 秒。
         * 不设次数上限：跟随持续到用户离开第二步，重开配对弹窗换端口也能跟上。
         */
        const val DISCOVERY_INTERVAL_MS = 2_000L
    }
}
