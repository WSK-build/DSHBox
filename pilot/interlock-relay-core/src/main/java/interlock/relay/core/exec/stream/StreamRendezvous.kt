package interlock.relay.core.exec.stream

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * 流控制端点的 **rendezvous 文件**：把控制端点的（端口、token）交给 DSH 的 Node 半。
 *
 * 落点在助手通道目录内（宿主 `<files>/pilot`，guest 侧 `/opt/pilot`）的 `run/` 下 ——
 * 该目录整体随通道 bind 给沙盒，本来就不在全局可读路径上；文件与暂存件都在**创建
 * 那一刻**带 POSIX 属主位（仅属主可读写 —— 沙盒与宿主同 uid，DSH 的 Node 读得到，
 * 别的应用读不到）。**边界要说清楚**：同 uid 的任何进程（含沙盒内的其它代码）都
 * 读得到这个 token —— 递它的前提是沙盒内容已经可信，它不构成对沙盒内的额外隔离。
 * token 不进任何日志 —— 本文件是它唯一的落点。
 *
 * 写法：写进**同为 600 的暂存件**，再原子 rename 到正式名 —— Node 半读到的一半都
 * 不会是半份 JSON，且全程不存在"先全局可读再收紧"的窗口。宿主每次起通道都重写
 * （端口随机、token 一次一枚），Node 半按文件现值重读即可跟上宿主重启。
 *
 * JSON 形状：`{"controlPort":N,"controlToken":"…","writtenAtMs":…}`。
 */
object StreamRendezvous {

    const val FILE_NAME = "stream-endpoint.json"

    private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    private val posixSupported: Boolean by lazy {
        runCatching { PosixFilePermissions.asFileAttribute(OWNER_ONLY); true }.getOrDefault(false)
    }

    fun file(entryDir: File): File = File(File(entryDir, "run"), FILE_NAME)

    /**
     * 写 rendezvous：600 暂存件（创建即带属性）+ 原子 rename（`ATOMIC_MOVE`，
     * 不支持原子改名的文件系统退 `REPLACE_EXISTING`，替换对读侧仍是原子的）。
     * 不支持 POSIX 属性的文件系统按默认属性落盘 —— 收口尽力执行。
     */
    fun write(entryDir: File, controlPort: Int, controlToken: String): Result<File> = runCatching {
        val target = file(entryDir)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "$FILE_NAME.tmp-${System.nanoTime()}")
        val body = "{\"controlPort\":$controlPort,\"controlToken\":" + jsonString(controlToken) +
            ",\"writtenAtMs\":" + System.currentTimeMillis() + "}"
        try {
            writeWithOwnerOnly(tmp, body)
            if (posixSupported) {
                Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        target
    }

    /** 创建即带属主位（600）；不支持 POSIX 属性的文件系统按默认属性落盘。 */
    private fun writeWithOwnerOnly(target: File, body: String) {
        val posix = runCatching { PosixFilePermissions.asFileAttribute(OWNER_ONLY) }.getOrNull()
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val channel = runCatching {
            if (posix != null) Files.newByteChannel(target.toPath(), options, posix)
            else Files.newByteChannel(target.toPath(), options)
        }.getOrElse {
            Files.newByteChannel(target.toPath(), options)
        }
        channel.use { opened ->
            val buffer = ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) opened.write(buffer)
        }
    }

    /** 读取并解析；文件缺失/形状不对回 null —— Node 半按"没有控制端点"处理。 */
    fun read(entryDir: File): Rendezvous? = runCatching {
        val text = file(entryDir).readText(Charsets.UTF_8)
        val port = Regex("\"controlPort\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val token = Regex("\"controlToken\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        if (port == null || token.isNullOrEmpty()) return null
        Rendezvous(port, token)
    }.getOrNull()

    fun delete(entryDir: File) {
        runCatching { file(entryDir).delete() }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    data class Rendezvous(val controlPort: Int, val controlToken: String)
}
