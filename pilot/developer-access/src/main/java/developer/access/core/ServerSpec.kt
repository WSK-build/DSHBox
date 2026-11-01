package developer.access.core

import java.io.File

/**
 * ADB server 的连接方式（socket spec）。
 *
 * ## 四种形态
 *
 * - **unix socket**：落在 bind 目录内。PRoot 会翻译 AF_UNIX 连接时的路径
 *   （guest 内可连接宿主私有目录中的 unix socket），
 *   而该目录对同机其他 App 不可达——凭文件系统权限隔离，是最受限的一档。
 * - **TCP · 仅本机**：本机任意进程可连。标准 adb 客户端可直连，无需自研握手。
 * - **TCP · 局域网**：同网段设备可连，手机可作为开发台被 PC 接入。
 * - **自定义**：用户直接给 socket spec 原文（`tcp:` / `localabstract:` / `vsock:` 等）。
 *
 * ## 认证
 *
 * ADB 通道**不设认证**，与标准 adb 一致：adb server 对本机客户端本就不做认证，
 * 自定义握手会让标准 adb 客户端连不上，反而堵死外部工具接入的路径。
 */
enum class ServerTransport(val label: String) {
    UNIX_SOCKET("unix"),
    TCP_LOCAL("tcp-local"),
    TCP_LAN("tcp-lan"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromName(name: String?): ServerTransport =
            entries.firstOrNull { it.name == name } ?: UNIX_SOCKET
    }
}

/**
 * 端口可用性。
 *
 * 三态而非两态：无法判定（探测本身失败）与"明确空闲"必须区分，
 * 否则界面会把"不知道"说成"空闲"，用户按此设置后才发现被占用。
 */
enum class PortStatus {
    /** 端口空闲，可绑定。 */
    FREE,

    /** 端口已被占用。 */
    OCCUPIED,

    /** 未能判定（探测本身失败）。 */
    UNKNOWN,
}

/**
 * 连接方式配置：传输形态 + 端口 + 自定义 spec。
 *
 * @param transport 传输形态。
 * @param port TCP 端口（unix socket 形态下无意义）。
 * @param customSpec 自定义形态下的 spec 原文；其余形态为 null。
 */
data class ServerSpec(
    val transport: ServerTransport = ServerTransport.UNIX_SOCKET,
    val port: Int = ElevationPaths.DEFAULT_ADB_SERVER_PORT,
    val customSpec: String? = null,
) {
    /**
     * 转换为 adb 的 socket spec 字符串。
     *
     * unix socket 用 `localfilesystem:` 前缀——**不能用 `localabstract:`**：
     * abstract namespace 没有文件系统路径可依，隔离靠 SELinux 类型标签，
     * 落在共享标签下时同机其他 App 可连；而 `localfilesystem:` 落在 App 私有目录，
     * 靠文件系统权限隔离。
     */
    fun toSocketSpec(socketPath: String): String = when (transport) {
        ServerTransport.UNIX_SOCKET -> "localfilesystem:$socketPath"
        ServerTransport.TCP_LOCAL -> "tcp:localhost:$port"
        // 局域网档在配置文件里仍写 `tcp:0.0.0.0:<port>` 形态：adb 本身拒绝用它监听
        // （"listening on specified hostname currently unsupported"），
        // 但它是**合法可读的 spec**，由 wrapper 翻译成 `adb -a -P <port>` 启动。
        // 这样 server.conf 对 DSH 始终是合法 adb spec。
        ServerTransport.TCP_LAN -> "tcp:0.0.0.0:$port"
        ServerTransport.CUSTOM -> customSpec?.trim().orEmpty().ifEmpty { "tcp:localhost:$port" }
    }

    /**
     * 端口是否落在可绑定的合法范围内。
     *
     * **写配置前必须校验**：越界端口一旦落进 `server.conf`，之后每次 adb 调用都会
     * 以 `bad port number` 失败，且面板只在配对/连接处显示一行原始报错，
     * 用户很难把它和"刚刚填的端口"联系起来（该行为已在设备上验证）。
     * unix 档不使用端口，恒为合法。
     */
    fun hasValidPort(): Boolean =
        transport == ServerTransport.UNIX_SOCKET || port in 1..65535

    /** 反解 socket spec 字符串。 */
    companion object {
        fun fromSocketSpec(spec: String?, fallbackPort: Int): ServerSpec {
            val trimmed = spec?.trim().orEmpty()
            if (trimmed.isEmpty()) return ServerSpec(ServerTransport.UNIX_SOCKET, fallbackPort)
            if (trimmed.startsWith("localfilesystem:")) {
                return ServerSpec(ServerTransport.UNIX_SOCKET, fallbackPort)
            }
            if (trimmed.startsWith("tcp:")) {
                val port = trimmed.substringAfterLast(':', "").toIntOrNull() ?: fallbackPort
                // 监听全部网卡的写法归入局域网档：不能静默降级成本机档，
                // 否则面板显示与实际监听范围不一致。
                val allInterfaces = trimmed.substringBeforeLast(':').endsWith("0.0.0.0")
                return ServerSpec(
                    if (allInterfaces) ServerTransport.TCP_LAN else ServerTransport.TCP_LOCAL,
                    port,
                )
            }
            // 其余前缀（localabstract / localreserved / vsock / dev…）只可能来自用户自定义：
            // 保留原文，不猜测其语义。
            return ServerSpec(ServerTransport.CUSTOM, fallbackPort, trimmed)
        }
    }
}

/**
 * 连接方式的读写。
 *
 * 两处配置的**读写位置不同，但格式一致**：
 *
 * - ADB 通道的配置在 bind 目录内——adb server 跑在 guest 里，它必须能读到；
 *   guest 与本 App 同 uid，故 DSH 也能改（连接方式是配置而非权限）。
 * - Root 通道的配置在宿主私有目录内——执行器跑在本 App 侧，guest 够不到，
 *   DSH 改不动。
 */
object RootSpecStore {

    /**
     * 读取 Root 连接方式。
     *
     * Root 的配置在宿主私有目录，格式与 ADB 侧**不同**：
     * `server.conf` 只存传输方式、`port` 单独成文件——因为 Root 侧不走 adb 的
     * socket spec 语义，没必要把两者挤在一行里。
     */
    fun read(confFile: File, portFile: File, fallbackPort: Int): ServerSpec {
        val transportName = runCatching { confFile.readText().trim() }.getOrNull().orEmpty()
        val transport = ServerTransport.fromName(transportName)
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: fallbackPort
        return ServerSpec(transport, port)
    }

    /** 写入 Root 连接方式。 */
    fun write(confFile: File, portFile: File, spec: ServerSpec) {
        confFile.parentFile?.mkdirs()
        confFile.writeText(spec.transport.name)
        portFile.parentFile?.mkdirs()
        portFile.writeText(spec.port.toString())
    }
}

object ServerSpecStore {

    /** 读取配置；文件缺失或内容不可解时回落到默认值。 */
    fun read(file: File, fallbackPort: Int): ServerSpec {
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        return ServerSpec.fromSocketSpec(text, fallbackPort)
    }

    /** 写入配置。 */
    fun write(file: File, spec: ServerSpec, socketPath: String) {
        file.parentFile?.mkdirs()
        file.writeText(spec.toSocketSpec(socketPath))
    }
}
