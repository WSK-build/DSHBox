package developer.access.adb

import developer.access.core.ElevationPaths
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport

/**
 * App → 沙箱「执行 adb」的构造点。
 *
 * ## 为什么需要专门的构造点
 *
 * 面板的动作（配对、连接、生成密钥）与 DSH 自己在沙箱里敲 `adb`，**必须落到同一个
 * adb server**。二者只要 env 有一点不同（密钥落点、附加密钥集、socket spec），
 * 就会各自 fork 一个 server，出现"面板说已配对、DSH 里却看不到设备"。
 *
 * ## 为什么默认走 wrapper
 *
 * 不传 [ServerSpec] 时命令直接调用 `/opt/elevation/bin/adb`——那正是 DSH 在 PATH 上
 * 命中的同一个入口。密钥落点、附加密钥集、连接方式（含局域网翻译）全部由 wrapper
 * 按 `server.conf` 现场解析，故面板与 DSH **读同一份配置、起同一个 server**，
 * 面板侧不复制任何一份 env 解析逻辑。
 *
 * 传了 [ServerSpec] 时才显式钉住 env（用于一次性按指定方式执行，不进配置文件）。
 */
object AdbGateway {

    /**
     * 构造在 guest 内执行 adb 的命令串。
     *
     * @param args adb 子命令与参数（如 `pair`、`host:port`、`code`）。
     * @param spec 连接方式；为 null 时走 wrapper（与 DSH 完全一致）。
     * @return 可直接交给运行环境执行的 shell 命令串。
     */
    fun command(args: List<String>, spec: ServerSpec? = null): String {
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        if (spec == null) {
            return "${ElevationPaths.GUEST_ADB_WRAPPER} $quoted"
        }
        val socket = when (spec.transport) {
            // 局域网档的 server 由 `-a` 起在全部网卡上，客户端仍连回环端口，
            // 与 wrapper 的翻译保持一致；否则客户端会去连 0.0.0.0 而另起 server。
            ServerTransport.TCP_LAN -> "tcp:localhost:${spec.port}"
            else -> spec.toSocketSpec(ElevationPaths.GUEST_ADB_SOCKET)
        }
        val env = listOf(
            "HOME=${AdbEnv.GUEST_HOME}",
            "ADB_VENDOR_KEYS=${AdbEnv.VENDOR_KEYS}",
            "ADB_SERVER_SOCKET=$socket",
        )
        return env.joinToString(" ") + " " + AdbEnv.ADB_REAL + " " + quoted
    }

    /**
     * 构造"起 server"命令。
     *
     * 沙箱在跑时由开关流程调用，让 adb 按当前连接方式建立监听并自动重连已配对设备。
     */
    fun startServerCommand(spec: ServerSpec? = null): String = command(listOf("start-server"), spec)
}
