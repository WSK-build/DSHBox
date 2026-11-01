package developer.access.adb

/**
 * ADB 通道随包分发的运行时资产。
 *
 * ## 为什么随包分发
 *
 * ADB 客户端及其动态库依赖**不在运行环境层内**：运行环境层是按功能裁剪的 Debian 精简集，
 * 不含 `libbase`/`libcutils`/`liblog`/`libziparchive` 等 Android 专有库，
 * 也不含 `adbkey` 认证所需的 `libcrypto`/`libssl`（BoringSSL 版本）。
 * 故这里把完整依赖闭包随 APK 分发，在设备侧解包进运行环境层。
 *
 * ## 解包方式
 *
 * 解包**必须保留符号链接**：`libprotobuf.so.32`、`libusb-1.0.so.0`、`libfmt.so.11`
 * 三个 soname 是指向具体版本的符号链接，链接丢失则动态链接器找不到库。
 * 因此沿用既有做法——`dpkg-deb --fsys-tarfile` 取出数据段后交给 tar 解包，
 * GNU tar 默认保留符号链接；**不得改用 `dpkg -x`**（伪 root 下的 chown 会被内核拒绝）。
 */
object AdbAssets {

    /** 资产在 APK 内的目录（与既有小工具包同一目录，沿用同一套解包链）。 */
    const val ASSET_DIR = "terminal-packages"

    /**
     * 资产文件名（Debian 官方归档的原始文件名，含版本号）。
     *
     * 顺序即解包顺序：先落依赖、后落主程序，使主程序就位时其依赖已在同一目录树内。
     */
    val DEB_FILES: List<String> = listOf(
        "android-libbase_35.0.2-1~exp7_arm64.deb",
        "android-libcutils_35.0.2-1~exp7_arm64.deb",
        "android-liblog_35.0.2-1~exp7_arm64.deb",
        "android-libziparchive_35.0.2-1~exp7_arm64.deb",
        "android-libboringssl_14.0.0+r45-3+b2_arm64.deb",
        "libfmt11_11.1.1+ds1-2_arm64.deb",
        "libprotobuf32t64_3.21.12-17_arm64.deb",
        "libusb-1.0-0_1.0.30-1_arm64.deb",
        "adb_35.0.2-1~exp7_arm64.deb",
    )

    /**
     * adb 主程序在运行环境层内的路径。
     *
     * 该路径**不在 PATH 里**，这是需要把它复制到开关目录的原因：一是让它出现在 PATH 上，
     * 二是让开关关闭时命令彻底不可见。
     *
     * 注意 deb 的**数据段自带** `/usr/bin/adb` 软链（该软链在 PATH 上），
     * 故解包后必须移除它——见 [unpackCommand]。
     */
    const val GUEST_ADB_PATH = "/usr/lib/android-sdk/platform-tools/adb"

    /**
     * 构造 guest 侧解包命令。
     *
     * 逐个 deb 解包：`dpkg-deb --fsys-tarfile` 输出数据段，交给 tar 落到运行环境层根。
     * `--no-same-owner` 使解包不做 chown——伪 root 下的 chown 会被内核按真实 uid 拒绝。
     *
     * **末尾必须移除 `/usr/bin/adb`**：adb 的 deb 数据段里自带这条软链
     * （`/usr/bin/adb -> ../lib/android-sdk/platform-tools/adb`），而 `/usr/bin` 在 guest PATH 上。
     * 若留在原处，开关关闭后 `command -v adb` 仍会命中它，`/opt/elevation` 的开关语义
     * （关闭即不可见）就失效了。移除后 adb 只能经开关目录的 wrapper 进入 PATH。
     *
     * @param debPaths guest 视角下各 deb 的绝对路径。
     */
    fun unpackCommand(debPaths: List<String>): String {
        val parts = mutableListOf<String>()
        parts += "ok=1"
        for (path in debPaths) {
            val quoted = path.replace("'", "'\\''")
            parts += "dpkg-deb --fsys-tarfile '$quoted' | tar -x --no-same-owner -p -C / || ok=0"
        }
        // /usr/bin/adb 是 deb 自带的软链，必须移除，否则开关关闭后命令仍可达。
        return parts.joinToString("; ") +
            "; rm -f /usr/bin/adb; echo DEVMODE_UNPACK=\$ok"
    }

    /**
     * 从解包输出中取出结论标记。
     *
     * @return true 表示全部解包成功。
     */
    fun parseUnpackResult(output: String): Boolean {
        val flag = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith(UNPACK_MARKER) }
            ?.substringAfter('=')
            ?.trim()
        return flag == "1"
    }

    /** 解包结论标记的前缀。 */
    internal const val UNPACK_MARKER = "DEVMODE_UNPACK="
}
