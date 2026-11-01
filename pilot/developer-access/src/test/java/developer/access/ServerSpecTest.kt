package developer.access

import developer.access.core.ElevationPaths
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接方式的序列化/反序列化。
 *
 * 重点守两件事：**默认值与 wrapper 必须是同一个 spec**，以及**局域网档不能被反解
 * 静默降级成本机档**（否则面板显示与实际监听范围不一致）。
 */
class ServerSpecTest {

    @Test
    fun `默认是 unix socket 且与 guest 默认 spec 一致`() {
        val spec = ServerSpec()
        assertEquals(ServerTransport.UNIX_SOCKET, spec.transport)
        assertEquals(ElevationPaths.DEFAULT_ADB_SERVER_PORT, spec.port)
        assertEquals(
            ElevationPaths.DEFAULT_ADB_SERVER_SPEC,
            spec.toSocketSpec(ElevationPaths.GUEST_ADB_SOCKET),
        )
    }

    @Test
    fun `局域网档写 0_0_0_0 而非 localhost`() {
        val spec = ServerSpec(ServerTransport.TCP_LAN, 5099)
        assertEquals("tcp:0.0.0.0:5099", spec.toSocketSpec(ElevationPaths.GUEST_ADB_SOCKET))
    }

    @Test
    fun `从 0_0_0_0 反解仍归入局域网档`() {
        val spec = ServerSpec.fromSocketSpec("tcp:0.0.0.0:5099", 5037)
        assertEquals(ServerTransport.TCP_LAN, spec.transport)
        assertEquals(5099, spec.port)
    }

    @Test
    fun `从 localhost 反解归入本机档`() {
        val spec = ServerSpec.fromSocketSpec("tcp:localhost:5098", 5037)
        assertEquals(ServerTransport.TCP_LOCAL, spec.transport)
        assertEquals(5098, spec.port)
    }

    @Test
    fun `localfilesystem 归入 unix 档，localabstract 仍是自定义`() {
        assertEquals(
            ServerTransport.UNIX_SOCKET,
            ServerSpec.fromSocketSpec("localfilesystem:/opt/elevation/adb.sock", 5037).transport,
        )
        // abstract 命名空间没有文件系统路径可依，隔离强度与 unix 档不同，不能混为一档。
        val abstract = ServerSpec.fromSocketSpec("localabstract:whatever", 5037)
        assertEquals(ServerTransport.CUSTOM, abstract.transport)
        assertEquals("localabstract:whatever", abstract.customSpec)
    }

    @Test
    fun `其他前缀原样保留为自定义并带上原文`() {
        val spec = ServerSpec.fromSocketSpec("vsock:3:5555", 5037)
        assertEquals(ServerTransport.CUSTOM, spec.transport)
        assertEquals("vsock:3:5555", spec.customSpec)
    }

    @Test
    fun `空内容回落到 unix 与给定端口`() {
        val spec = ServerSpec.fromSocketSpec("  ", 5100)
        assertEquals(ServerTransport.UNIX_SOCKET, spec.transport)
        assertEquals(5100, spec.port)
    }

    @Test
    fun `自定义档留空时回落到本机回环`() {
        val spec = ServerSpec(ServerTransport.CUSTOM, 5037, customSpec = "  ")
        assertEquals("tcp:localhost:5037", spec.toSocketSpec(ElevationPaths.GUEST_ADB_SOCKET))
    }

    @Test
    fun `端口越界必须被拒绝`() {
        // 该行为已在设备上验证：越界端口写进 server.conf 后，每条 adb 命令都报 bad port number。
        assertFalse(ServerSpec(ServerTransport.TCP_LOCAL, 555553555).hasValidPort())
        assertFalse(ServerSpec(ServerTransport.TCP_LAN, 0).hasValidPort())
        assertFalse(ServerSpec(ServerTransport.TCP_LOCAL, 65536).hasValidPort())
        assertTrue(ServerSpec(ServerTransport.TCP_LOCAL, 5037).hasValidPort())
        assertTrue(ServerSpec(ServerTransport.TCP_LAN, 65535).hasValidPort())
        // unix 档不使用端口：任何端口值都不影响可用性。
        assertTrue(ServerSpec(ServerTransport.UNIX_SOCKET, 555553555).hasValidPort())
    }
}
