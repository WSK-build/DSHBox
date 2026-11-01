package developer.access

import androidx.test.platform.app.InstrumentationRegistry
import developer.access.core.ElevationDirs
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport
import developer.access.root.AuthLimiter
import developer.access.root.RootAuthCode
import developer.access.root.RootExecutor
import developer.access.root.RootProtocol
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * root 执行器在 TCP 档下的握手。
 *
 * 只覆盖认证这一段（nonce → HMAC 应答 → 结果帧），**不发送命令**：命令经 `su` 执行，
 * 在未授权/无 root 的设备上会弹系统授权框并阻塞，不适合放进仪器测试。
 * 帧编解码的另一半已有 JVM 单测（`RootProtocolTest`）。
 */
class RootTcpHandshakeTest {

    @Test
    fun correctSignatureIsAccepted() {
        val dirs = dirs()
        val code = RootAuthCode.generate()
        val port = freePort()
        val executor = RootExecutor(
            dirs = dirs,
            spec = ServerSpec(ServerTransport.TCP_LOCAL, port),
            accessCode = { code },
            limiter = AuthLimiter(),
        )
        assertTrue(executor.start())
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val challenge = RootProtocol.readFrame(input)
                assertNotNull(challenge)
                val nonce = challenge!!.getString(RootProtocol.KEY_NONCE)

                RootProtocol.writeFrame(output, RootProtocol.authFrame(RootAuthCode.sign(code, nonce)))
                val result = RootProtocol.readFrame(input)
                assertNotNull(result)
                assertTrue(result!!.optBoolean(RootProtocol.KEY_OK))
            }
        } finally {
            executor.stop()
            // 执行器会顺带建 unix 监听；测试退出时把 socket 文件清掉，别在 App 私有目录留痕。
            File(dirs.elevationDir, "root.sock").delete()
        }
    }

    @Test
    fun wrongSignatureIsRejected() {
        val dirs = dirs()
        val code = RootAuthCode.generate()
        val port = freePort()
        val executor = RootExecutor(
            dirs = dirs,
            spec = ServerSpec(ServerTransport.TCP_LOCAL, port),
            accessCode = { code },
            limiter = AuthLimiter(),
        )
        assertTrue(executor.start())
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                assertNotNull(RootProtocol.readFrame(input))

                RootProtocol.writeFrame(output, RootProtocol.authFrame("not-a-valid-signature"))
                val result: JSONObject? = RootProtocol.readFrame(input)
                assertFalse(result == null || result.optBoolean(RootProtocol.KEY_OK))
                assertNull(readOrNull(input))
            }
        } finally {
            executor.stop()
            File(dirs.elevationDir, "root.sock").delete()
        }
    }

    private fun dirs(): ElevationDirs =
        ElevationDirs(InstrumentationRegistry.getInstrumentation().targetContext.filesDir).also {
            it.ensureLayout()
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** 连接被服务端关闭时返回 null。 */
    private fun readOrNull(input: java.io.InputStream): JSONObject? =
        runCatching { RootProtocol.readFrame(input) }.getOrNull()
}
