package developer.access

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * filesystem unix socket 服务端的端到端冒烟。
 *
 * 验证的是「公开 API 能否创建 filesystem 类型的监听端」这条事实：
 * `LocalServerSocket(String)` 固定走 abstract 命名空间，而本通道要求 socket 落在
 * App 私有目录、靠文件系统权限隔离。可行路径是先用 `LocalSocket.bind(FILESYSTEM)`
 * 取得已绑定的 fd，再交给 `LocalServerSocket(FileDescriptor)` 监听。
 *
 * 同时验证 `getPeerCredentials()` 可取到对端 uid——这是同 uid 校验的依据。
 */
class UnixSocketSmokeTest {

    @Test
    fun filesystemSocketBindsListensAndReportsPeerUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.filesDir, "smoke-unix.sock").absolutePath
        File(path).delete()

        // 1. 绑定 filesystem 路径，取得已绑定的 fd
        val binder = LocalSocket()
        binder.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        assertTrue("bind 后 socket 文件应存在", File(path).exists())

        // 2. 用该 fd 创建监听端（构造器内部即 listen）
        val server = LocalServerSocket(binder.getFileDescriptor())

        // 3. 同进程客户端连接（同 uid）
        val client = LocalSocket().apply {
            connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        val accepted = server.accept()

        // 4. 对端凭据即 SO_PEERCRED
        val peer = accepted.peerCredentials
        assertTrue("对端 uid 应为有效值", peer.uid >= 0)
        assertEquals("同进程连接的对端应为自身 uid", android.os.Process.myUid(), peer.uid)

        // 5. 数据可双向通行
        client.outputStream.write("ping".toByteArray())
        client.outputStream.flush()
        val buffer = ByteArray(8)
        val read = accepted.inputStream.read(buffer)
        assertEquals("ping", String(buffer, 0, read))

        accepted.close()
        client.close()
        server.close()
        // fd 构造的监听端 close() 不关闭底层 fd，绑定用的 socket 需单独关闭
        binder.close()
        File(path).delete()
    }
}
