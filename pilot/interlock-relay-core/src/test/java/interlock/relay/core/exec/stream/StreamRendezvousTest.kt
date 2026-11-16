package interlock.relay.core.exec.stream

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * rendezvous 文件的判据（纯 JVM，临时目录）：
 * - 写读回环：port/token 原样回来，落点在通道 run/ 下、名字固定；
 * - 原子写不留暂存文件；删除后读回 null；形状不对/缺失如实 null；
 * - POSIX 位收口（仅属主可读写）在支持的环境断言，不支持的文件系统跳过。
 */
class StreamRendezvousTest {

    private fun tempEntryDir(): File = Files.createTempDirectory("pilot-entry").toFile()

    @Test
    fun `write and read back the control port and token`() {
        val entry = tempEntryDir()
        val written = StreamRendezvous.write(entry, 41234, "ctl-token-7").getOrThrow()
        assertEquals("run" + File.separator + "stream-endpoint.json", entry.toPath().relativize(written.toPath()).toString())
        val read = StreamRendezvous.read(entry)
        assertEquals(41234, read?.controlPort)
        assertEquals("ctl-token-7", read?.controlToken)
    }

    @Test
    fun `atomic write leaves no temp file behind`() {
        val entry = tempEntryDir()
        StreamRendezvous.write(entry, 40001, "t").getOrThrow()
        val run = entry.toPath().resolve("run")
        val leftovers = Files.list(run).use { stream ->
            stream.filter { it.fileName.toString() != StreamRendezvous.FILE_NAME }.count()
        }
        assertEquals(0L, leftovers)
    }

    @Test
    fun `overwriting replaces the content instead of appending`() {
        val entry = tempEntryDir()
        StreamRendezvous.write(entry, 40001, "first").getOrThrow()
        StreamRendezvous.write(entry, 40002, "second").getOrThrow()
        val read = StreamRendezvous.read(entry)
        assertEquals(40002, read?.controlPort)
        assertEquals("second", read?.controlToken)
    }

    @Test
    fun `delete clears the file and reads back null`() {
        val entry = tempEntryDir()
        StreamRendezvous.write(entry, 40001, "t").getOrThrow()
        StreamRendezvous.delete(entry)
        assertNull(StreamRendezvous.read(entry))
        StreamRendezvous.delete(entry) // 幂等
    }

    @Test
    fun `missing or malformed files read back null`() {
        val entry = tempEntryDir()
        assertNull(StreamRendezvous.read(entry))
        StreamRendezvous.write(entry, 40001, "t").getOrThrow()
        Files.write(entry.toPath().resolve("run/stream-endpoint.json"), "not json at all".toByteArray())
        assertNull(StreamRendezvous.read(entry))
    }

    @Test
    fun `the file is restricted to the owner where posix permissions exist`() {
        val entry = tempEntryDir()
        val written = StreamRendezvous.write(entry, 40001, "secret").getOrThrow()
        val posix = runCatching {
            Files.getPosixFilePermissions(written.toPath())
        }.getOrNull()
        Assume.assumeTrue("filesystem without posix permissions - skipped", posix != null)
        val perms = posix!!
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
        assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
    }
}
