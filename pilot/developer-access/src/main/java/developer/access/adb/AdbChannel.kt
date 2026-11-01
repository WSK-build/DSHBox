package developer.access.adb

import developer.access.core.AssetSource
import developer.access.core.ElevationDirs
import developer.access.core.ElevationPaths
import developer.access.core.GuestCommandRunner
import java.io.File

/**
 * ADB 通道的开关实现。
 *
 * ## 开关语义
 *
 * - **开**：确保运行环境层内有 adb 与依赖 → 把真身复制到开关目录 → 写 wrapper。
 *   此后 guest 内 `adb` 命中 `/opt/elevation/bin/adb`。
 * - **关**：终止 adb server → 删除 wrapper 与真身。此后 `command -v adb` **为空**
 *   （依赖 `AdbAssets.unpackCommand` 已移除 deb 自带的 `/usr/bin/adb` 软链）。
 *
 * ## 为什么开启时要先复检
 *
 * 运行环境层会被"在线获取"或"离线导入"整体替换，替换后 adb 与依赖全消失。
 * 只靠"沙箱启动前复检"不够——用户可能在沙箱未启动时先拨开关，
 * 此时复制源不存在。故**开关开启流程自带一次"缺则解包"复检**。
 *
 * ## 为什么关开关要杀 server
 *
 * adb server 是独立进程，删文件不会让它退出；它仍持有与设备的连接。
 * 但它跑在 guest 进程树内，故按**可执行路径**精确定位——**绝不按 uid 批量杀**：
 * 本 App 与 guest 同 uid，按 uid 杀会把整个 DSH 主进程与 PRoot 树一起杀掉。
 */
class AdbChannel(
    private val dirs: ElevationDirs,
    /** 运行环境层根（`runtime-current/base`）。 */
    private val rootfsDir: File,
    private val guest: GuestCommandRunner,
    private val assets: AssetSource,
) {

    /** 运行环境层内 adb 主程序。 */
    private fun rootfsAdb(): File = File(rootfsDir, AdbAssets.GUEST_ADB_PATH.trimStart('/'))

    /**
     * 探测 adb 与依赖是否在运行环境层内就位。
     *
     * 探测**文件在位**而非标记文件：运行环境层被替换后标记会残留，文件不会。
     */
    fun isInstalled(): Boolean {
        if (!rootfsAdb().isFile) return false
        // 抽查两个关键依赖：任一缺失说明整批解包没跑完或层被替换。
        val androidLibDir = File(rootfsDir, "usr/lib/aarch64-linux-gnu/android")
        return File(androidLibDir, "liblog.so.0").exists() &&
            File(androidLibDir, "libcrypto.so.0").exists()
    }

    /**
     * 缺则解包：把资产复制到开关目录内的暂存区，在 guest 内解包进运行环境层。
     *
     * 暂存区放在开关目录内（guest 可见），解包完成后立即清理——
     * deb 本身没有留在沙箱内的必要。
     *
     * @return 解包后 adb 是否就位。
     */
    suspend fun ensureInstalled(onLine: (String) -> Unit = {}): Boolean {
        if (isInstalled()) return true
        dirs.ensureLayout()
        val stage = File(dirs.elevationDir, STAGE_DIR_NAME).apply { mkdirs() }
        try {
            val guestPaths = mutableListOf<String>()
            for (name in AdbAssets.DEB_FILES) {
                val target = File(stage, name)
                if (!assets.copyTo(name, target) || target.length() == 0L) {
                    onLine("asset missing: $name")
                    return false
                }
                guestPaths += "$GUEST_STAGE_DIR/$name"
            }
            var ok = false
            guest.run(AdbAssets.unpackCommand(guestPaths)) { line ->
                onLine(line)
                if (line.contains("DEVMODE_UNPACK=")) {
                    ok = AdbAssets.parseUnpackResult(line)
                }
            }
            return ok && isInstalled()
        } finally {
            stage.deleteRecursively()
        }
    }

    /**
     * 开关处于开启态时（wrapper 在位）复检安装，缺则补装。
     *
     * 供沙箱启动前调用：运行环境层可能在开关开启之后被整体替换，
     * 而开关状态未变（wrapper 仍在），此时若不复检，guest 内的 adb 会指向不存在的真身。
     * 开关从未开启时直接返回——不做无谓解包。
     *
     * @return 复检后安装是否就位（开关未开启时恒为 true，表示无需处理）。
     */
    suspend fun ensureInstalledIfEnabled(onLine: (String) -> Unit = {}): Boolean {
        if (!dirs.adbWrapperFile().exists()) return true
        val installed = ensureInstalled(onLine)
        // 真身在层替换后一并消失，wrapper 指向失效路径；补装成功后重复制一次。
        if (installed && !dirs.hasAdbReal()) {
            enable(onLine)
        }
        return installed
    }

    /**
     * 开启通道：复检安装 → 复制真身 → 写 wrapper。
     *
     * @return 开启后 guest 内是否具备可执行的 adb。
     */
    suspend fun enable(
        onLine: (String) -> Unit = {},
        /** 沙箱是否在运行；决定是否立即起 server。 */
        sandboxRunning: Boolean = false,
    ): Boolean {
        dirs.ensureLayout()
        if (!ensureInstalled(onLine)) return false
        val source = rootfsAdb()
        if (!source.isFile) return false
        val target = dirs.adbRealFile()
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        dirs.writeAdbWrapper()
        val ready = dirs.hasAdbReal()
        dirs.writeState(ready)
        // 沙箱在跑 → 起 server 并让 adb 自动重连已配对设备；
        // 沙箱未跑 → 跳过起 server 与建连，延后到首次 guest 调用（懒启动）。
        if (ready && sandboxRunning) {
            guest.run(AdbGateway.startServerCommand()) { onLine(it) }
        }
        return ready
    }

    /**
     * 关闭通道：终止 server → 删除 guest 侧入口。
     *
     * 删除顺序无关紧要，但**必须先杀 server 再删文件**：先删文件会让新起的客户端
     * 找不到可执行文件而报出无关错误，掩盖真正原因。
     */
    fun disable(): Int {
        val killed = killServers()
        dirs.removeAdbEntryPoints()
        dirs.writeState(false)
        return killed
    }

    /**
     * 只终止 server，不动入口文件。
     *
     * 供"切换连接方式"使用：改配置后旧 server 仍在旧 socket 上监听，必须杀掉；
     * 但通道本身仍处于开启态——删 wrapper 属于关闭通道，是另一个动作。
     */
    fun terminateServers(): Int = killServers()

    /**
     * 终止本通道启动的 adb server 进程。
     *
     * ## 判据为什么是"elevation 标记 + fork-server"
     *
     * 设备上验证：guest 内的进程由 PRoot 装载，`/proc/<pid>/exe` 指向
     * `libproot-loader.so` 而**不是** adb 真身；server 的 `cmdline` 也只是
     * `adb -L tcp:localhost:5037 fork-server server …`，同样不含真身路径。
     * 因此"按可执行路径匹配"在 PRoot 下无法区分——改用本通道独有的标记：
     *
     * - `fork-server`：server 独有，排除客户端与其它进程；
     * - `/opt/elevation`：出现在 wrapper 注入的 `HOME`/`ADB_VENDOR_KEYS` 环境
     *   （unix 档还出现在 `-L localfilesystem:/opt/elevation/adb.sock` 命令行里）。
     *
     * 两条同时满足才杀，故不会命中：PRoot 主进程（有 `--bind=…:/opt/elevation`
     * 但无 `fork-server`）、DSH 的 node、以及用户自装的另一个 adb（无本通道标记）。
     *
     * @return 被终止的进程数。
     */
    internal fun killServers(): Int {
        var killed = 0
        for (pid in runningPids()) {
            val cmdline = readCmdline(pid) ?: continue
            if (!isOurServer(cmdline, readEnviron(pid))) continue
            if (runCatching { android.os.Process.killProcess(pid) }.isSuccess) killed++
        }
        return killed
    }

    /**
     * 判定某进程是否本通道启动的 adb server（纯函数，便于单测）。
     *
     * @param cmdline `/proc/<pid>/cmdline`（NUL 已替换为空格）。
     * @param environ `/proc/<pid>/environ`（NUL 已替换为空格）；不可读时传 null。
     */
    internal fun isOurServer(cmdline: String, environ: String?): Boolean {
        if (!cmdline.contains("fork-server")) return false
        val marker = ElevationPaths.GUEST_ELEVATION_DIR
        return cmdline.contains(marker) || environ?.contains(marker) == true
    }

    /** 读取进程环境（同 uid 可读）；不可读或异常时返回 null。 */
    internal fun readEnviron(pid: Int): String? = runCatching {
        File("/proc/$pid/environ").readBytes()
            .toString(Charsets.UTF_8)
            .replace('\u0000', ' ')
    }.getOrNull()

    /** 枚举同 uid 的进程号。 */
    private fun runningPids(): List<Int> {
        val procDir = File("/proc")
        return procDir.list()?.mapNotNull { it.toIntOrNull() } ?: emptyList()
    }

    /** 读取进程命令行（参数以 NUL 分隔，此处拼接为单串便于匹配）。 */
    internal fun readCmdline(pid: Int): String? = runCatching {
        File("/proc/$pid/cmdline").readBytes()
            .toString(Charsets.UTF_8)
            .replace('\u0000', ' ')
            .trim()
            .ifEmpty { null }
    }.getOrNull()

    private companion object {
        const val STAGE_DIR_NAME = ".stage"
        const val GUEST_STAGE_DIR = "${ElevationPaths.GUEST_ELEVATION_DIR}/.stage"
    }
}
