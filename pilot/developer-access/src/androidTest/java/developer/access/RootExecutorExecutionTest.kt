package developer.access

import androidx.test.platform.app.InstrumentationRegistry
import developer.access.core.ElevationDirs
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport
import developer.access.root.AuthLimiter
import developer.access.root.RootAuthCode
import developer.access.root.RootExecutor
import developer.access.root.RootProtocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Root 执行器的端到端协议测试（**不依赖真实 root**）。
 *
 * 模拟器/无 root 设备上把 `su` 调用注入成普通 shell，从而验证除"提权"外的全部链路：
 * TCP 挑战应答、命令执行、stdout/stderr 分帧、base64 二进制无损、引号透传、退出码、
 * 以及持续失败的锁定。生产环境的 `su -c` 由 RootExecutor 默认参数提供。
 */
class RootExecutorExecutionTest {

    private var executor: RootExecutor? = null
    private lateinit var dirs: ElevationDirs

    @After
    fun tearDown() {
        executor?.stop()
        executor = null
        if (::dirs.isInitialized) {
            File(dirs.elevationDir, "root.sock").delete()
        }
    }

    @Test
    fun stdoutFramesAndSingleEndFrame() {
        val session = start()
        val result = session.run("echo hello-root")
        assertEquals("hello-root", result.stdout.trim())
        assertEquals(1, result.endFrames)
        assertEquals(0, result.code)
    }

    @Test
    fun exitCodeIsPropagated() {
        val session = start()
        val result = session.run("exit 7")
        assertEquals(7, result.code)
        assertEquals(1, result.endFrames)
    }

    @Test
    fun stderrGoesToStderrStream() {
        val session = start()
        val result = session.run("echo boom 1>&2")
        assertTrue(result.stderr.contains("boom"))
        assertEquals(0, result.code)
    }

    @Test
    fun binaryOutputIsByteExact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "root-binary.bin")
        val payload = ByteArray(256) { it.toByte() }
        file.writeBytes(payload)
        val session = start()
        val result = session.run("cat ${file.absolutePath}")
        assertArrayEquals(payload, result.stdoutBytes)
        assertEquals(0, result.code)
        file.delete()
    }

    @Test
    fun quotesAndSpacesSurvive() {
        val session = start()
        val result = session.run("printf '%s|' \"a b\" \"c'd\"")
        assertEquals("a b|c'd|", result.stdout)
    }

    @Test
    fun persistentFailuresLockTheChannel() {
        val code = RootAuthCode.generate()
        val session = start(code = code, limiter = AuthLimiter(threshold = 1, baseBackoffMs = 10L, lockoutThreshold = 2))
        // 前两次错误签名：第二次触发永久拒绝
        assertTrue(session.authenticate { "bad-signature-1" }.isFailure)
        assertTrue(session.authenticate { "bad-signature-2" }.isFailure)
        // 第三次即使签名正确也必须被拒——锁定不因时间流逝自动解除
        assertTrue(session.authenticate { nonce -> RootAuthCode.sign(code, nonce) }.isFailure)
    }

    // ── helpers ──

    private fun start(
        code: String = RootAuthCode.generate(),
        limiter: AuthLimiter = AuthLimiter(),
    ): Session {
        dirs = ElevationDirs(InstrumentationRegistry.getInstrumentation().targetContext.filesDir)
            .also { it.ensureLayout() }
        val port = ServerSocket(0).use { it.localPort }
        val exec = RootExecutor(
            dirs = dirs,
            spec = ServerSpec(ServerTransport.TCP_LOCAL, port),
            accessCode = { code },
            limiter = limiter,
            suCommand = { listOf("/system/bin/sh", "-c", it) },
        )
        assertTrue(exec.start())
        executor = exec
        return Session(port, code)
    }

    private inner class Session(private val port: Int, private val code: String) {
        fun authenticate(signatureFor: (String) -> String): Result<Unit> = runCatching {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val nonce = RootProtocol.readFrame(socket.getInputStream())
                    ?.getString(RootProtocol.KEY_NONCE).orEmpty()
                RootProtocol.writeFrame(socket.getOutputStream(), RootProtocol.authFrame(signatureFor(nonce)))
                val answer = RootProtocol.readFrame(socket.getInputStream())
                val ok = answer?.optBoolean(RootProtocol.KEY_OK) == true
                if (!ok || nonce.isEmpty()) throw IllegalStateException("rejected")
            }
        }

        fun run(command: String): Outcome = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val nonce = RootProtocol.readFrame(input)?.getString(RootProtocol.KEY_NONCE).orEmpty()
            RootProtocol.writeFrame(output, RootProtocol.authFrame(RootAuthCode.sign(code, nonce)))
            val auth = RootProtocol.readFrame(input)
            require(auth?.optBoolean(RootProtocol.KEY_OK) == true) { "auth failed" }

            RootProtocol.writeFrame(output, RootProtocol.requestFrame(command))

            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            var endFrames = 0
            var exit = -1
            while (true) {
                val frame = readOrNull(input) ?: break
                when {
                    frame.has(RootProtocol.KEY_CODE) -> {
                        endFrames++
                        exit = frame.getInt(RootProtocol.KEY_CODE)
                        break
                    }
                    frame.has(RootProtocol.KEY_ERROR) -> break
                    else -> {
                        val data = RootProtocol.decodeData(frame) ?: continue
                        if (frame.optString(RootProtocol.KEY_STREAM) == RootProtocol.STREAM_STDERR) {
                            err.write(data)
                        } else {
                            out.write(data)
                        }
                    }
                }
            }
            Outcome(out.toByteArray(), err.toByteArray(), endFrames, exit)
        }

        private fun readOrNull(input: InputStream) = runCatching {
            RootProtocol.readFrame(input)
        }.getOrNull()
    }

    private data class Outcome(
        val stdoutBytes: ByteArray,
        val stderrBytes: ByteArray,
        val endFrames: Int,
        val code: Int,
    ) {
        val stdout: String get() = String(stdoutBytes, Charsets.UTF_8)
        val stderr: String get() = String(stderrBytes, Charsets.UTF_8)
    }
}
