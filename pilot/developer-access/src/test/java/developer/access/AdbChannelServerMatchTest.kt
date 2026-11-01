package developer.access

import developer.access.adb.AdbChannel
import developer.access.core.AssetSource
import developer.access.core.ElevationDirs
import developer.access.core.GuestCommandRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * adb server 的识别判据。
 *
 * 该行为已在设备上验证：PRoot 下 `/proc/<pid>/exe` 指向 loader、server 的 cmdline 也不含
 * 真身路径，故只能用"`fork-server` + 本通道的 `/opt/elevation` 标记（命令行或环境）"区分；
 * 这里把该判据钉住，防止以后又退回"按路径匹配"而误杀或漏杀。
 */
class AdbChannelServerMatchTest {

    private val channel = AdbChannel(
        dirs = ElevationDirs(File(System.getProperty("java.io.tmpdir"), "dev-access-test")),
        rootfsDir = File(System.getProperty("java.io.tmpdir"), "dev-access-test/rootfs"),
        guest = object : GuestCommandRunner {
            override suspend fun run(command: String, onLine: (String) -> Unit): Boolean = true
        },
        assets = object : AssetSource {
            override fun copyTo(assetName: String, target: File): Boolean = false
        },
    )

    @Test
    fun `unix 档 server 由命令行里的 socket 路径识别`() {
        val cmdline = "adb -L localfilesystem:/opt/elevation/adb.sock fork-server server --reply-fd 4"
        assertTrue(channel.isOurServer(cmdline, null))
    }

    @Test
    fun `TCP 档 server 由环境里的 HOME 识别`() {
        val cmdline = "adb -L tcp:localhost:5037 fork-server server --reply-fd 4"
        val environ = "HOME=/opt/elevation/home ADB_VENDOR_KEYS=/opt/elevation/home/.android/keys " +
            "ADB_SERVER_SOCKET=tcp:localhost:5037"
        assertTrue(channel.isOurServer(cmdline, environ))
    }

    @Test
    fun `沙箱 PRoot 主进程不算 server`() {
        val cmdline = "libproot.so --rootfs=/x --bind=/data/.../files/elevation:/opt/elevation " +
            "--cwd=/root /usr/bin/bash --login"
        assertFalse(channel.isOurServer(cmdline, "HOME=/root"))
    }

    @Test
    fun `用户自装的 adb server 不算本通道的`() {
        val cmdline = "adb -L tcp:localhost:5037 fork-server server --reply-fd 4"
        assertFalse(channel.isOurServer(cmdline, "HOME=/data/data/com.termux/files/home"))
    }

    @Test
    fun `客户端与其它进程都不算`() {
        val client = "adb -L tcp:localhost:5037 devices"
        assertFalse(channel.isOurServer(client, "HOME=/opt/elevation/home"))
        val node = "/usr/local/bin/node --expose-internals /opt/dshapp/runtime/bin.js"
        assertFalse(channel.isOurServer(node, "HOME=/opt/elevation/home"))
    }
}
