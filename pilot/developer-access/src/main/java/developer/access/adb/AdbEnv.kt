package developer.access.adb

import developer.access.core.ElevationPaths

/**
 * ADB 通道的 guest 侧环境：**唯一组装点**。
 *
 * ## 为什么必须有这一层
 *
 * adb 的行为由三样环境决定：密钥落点（`HOME`）、附加密钥集（`ADB_VENDOR_KEYS`）、
 * 连接方式（`ADB_SERVER_SOCKET`）。这三项若在多处各写一遍，面板、DSH、外部工具
 * 迟早会看到不同的值——而它们**必须看到同一个**：否则 adb 客户端与 server 对不上，
 * 面板显示的连接方式也不是 DSH 实际用的那一个。
 *
 * ## 为什么只能靠 wrapper 固定
 *
 * 沙箱全局 env 不能改（会波及 `~` 展开、`os.homedir()`、DSH profile 定位），
 * 故这些值由 wrapper 脚本在每次执行时固定，且**尊重外部已设的
 * `ADB_SERVER_SOCKET`**（三层优先级：环境变量 > 配置文件 > 默认值）。
 */
object AdbEnv {

    /** 密钥落点：adb 只认 `HOME`（该构建不含 `ANDROID_SDK_HOME`/`ANDROID_USER_HOME`）。 */
    const val GUEST_HOME = ElevationPaths.GUEST_ELEVATION_HOME_DIR

    /** 附加密钥目录（`load_keys` 会扫描其中文件名含 `.adb_key` 的密钥）。 */
    const val VENDOR_KEYS = ElevationPaths.GUEST_VENDOR_KEYS_DIR

    /** 默认 socket spec（unix socket，落在开关目录内）。 */
    const val DEFAULT_SERVER_SPEC = ElevationPaths.DEFAULT_ADB_SERVER_SPEC

    /** adb 真身在 guest 内的绝对路径。 */
    const val ADB_REAL = "${ElevationPaths.GUEST_ELEVATION_REAL_DIR}/adb"

    /**
     * 生成 `bin/adb` 包装脚本。
     *
     * 三层优先级与局域网翻译都在这里：配置文件读到 `tcp:0.0.0.0:<port>` 时，
     * 先以 `adb -a -P <port> start-server` 起监听全部网卡的 server，
     * 再让客户端连同一端口的回环地址——否则客户端会按默认 5037 另起一个不带 `-a`
     * 的 server，让局域网监听空转。
     *
     * `exec` 是硬要求：不接受换行/管道中转，否则会破坏 `adb shell` 的交互式 pty
     * 与流式输出。
     */
    fun wrapperScript(): String = buildString {
        append("#!/bin/sh\n")
        append("# DSHBox developer mode: ADB entry. Pins key location and connection, passes through the rest.\n")
        append("HOME=$GUEST_HOME\n")
        append("export HOME\n")
        append("ADB_VENDOR_KEYS=$VENDOR_KEYS\n")
        append("export ADB_VENDOR_KEYS\n")
        append("CONF=\"\"\n")
        append("if [ -z \"\$ADB_SERVER_SOCKET\" ]; then\n")
        append("    CONF=\"\$(cat ${ElevationPaths.GUEST_ELEVATION_DIR}/${ElevationPaths.FILE_SERVER_CONF} 2>/dev/null)\"\n")
        append("fi\n")
        append("case \"\$CONF\" in\n")
        append("    tcp:0.0.0.0:*)\n")
        append("        LANPORT=\"\${CONF#tcp:0.0.0.0:}\"\n")
        append("        $ADB_REAL -a -P \"\$LANPORT\" start-server >/dev/null 2>&1\n")
        append("        ADB_SERVER_SOCKET=\"tcp:localhost:\$LANPORT\"\n")
        append("        export ADB_SERVER_SOCKET\n")
        append("        ;;\n")
        append("    *)\n")
        append("        ADB_SERVER_SOCKET=\"\$CONF\"\n")
        append("        [ -n \"\$ADB_SERVER_SOCKET\" ] || ADB_SERVER_SOCKET=\"$DEFAULT_SERVER_SPEC\"\n")
        append("        export ADB_SERVER_SOCKET\n")
        append("        ;;\n")
        append("esac\n")
        append("exec $ADB_REAL \"\$@\"\n")
    }
}
