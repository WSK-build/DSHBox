package developer.access

import developer.access.adb.DeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `adb_known_hosts.pb` 的最小线格式遍历。
 *
 * 没有官方 schema，故只验证"能在真实 wire format 里把长度分隔字段中的可打印串取出来、
 * 跳过其它字段、坏文件不抛异常"这三件事。
 */
class DeviceRegistryTest {

    @Test
    fun `取出顶层长度分隔字段中的设备标识`() {
        val id = "adb-1A2B3C4D5E6F-_adb-tls-connect._tcp"
        val file = tempFile(lengthDelimited(1, id.toByteArray()))
        val result = DeviceRegistry(file).list()
        assertEquals(listOf(id), result.map { it.identifier })
        file.delete()
    }

    @Test
    fun `跳过 varint 与定长字段`() {
        val id = "adb-QWERTYUIOP-_adb-tls-connect._tcp"
        val bytes = byteArrayOf(0x08, 0x05) +          // field 1, varint 5
            byteArrayOf(0x19, 1, 2, 3, 4, 5, 6, 7, 8) + // field 3, fixed64
            lengthDelimited(2, id.toByteArray())
        val file = tempFile(bytes)
        assertEquals(listOf(id), DeviceRegistry(file).list().map { it.identifier })
        file.delete()
    }

    @Test
    fun `嵌套消息会被递归展开`() {
        val id = "adb-NESTED0001-_adb-tls-connect._tcp"
        val inner = lengthDelimited(1, id.toByteArray())
        val file = tempFile(lengthDelimited(1, inner))
        assertEquals(listOf(id), DeviceRegistry(file).list().map { it.identifier })
        file.delete()
    }

    @Test
    fun `过短的串被当作解析残片丢弃`() {
        val file = tempFile(lengthDelimited(1, "abc".toByteArray()))
        assertTrue(DeviceRegistry(file).list().isEmpty())
        file.delete()
    }

    @Test
    fun `文件不存在或为空时返回空列表`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "no-such-known-hosts-${System.nanoTime()}.pb")
        assertTrue(DeviceRegistry(missing).list().isEmpty())
        val empty = tempFile(ByteArray(0))
        assertTrue(DeviceRegistry(empty).list().isEmpty())
        empty.delete()
    }

    @Test
    fun `截断的字节不抛异常`() {
        // 声明长度 200，实际只有 3 字节。
        val file = tempFile(byteArrayOf(0x0A, 0xC8.toByte(), 1, 0x41, 0x42, 0x43))
        assertTrue(DeviceRegistry(file).list().isEmpty())
        file.delete()
    }

    private fun lengthDelimited(field: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(((field shl 3) or 2).toByte())
        val length = ByteArray(2)
        var value = payload.size
        var index = 0
        while (value >= 0x80) {
            length[index++] = ((value and 0x7F) or 0x80).toByte()
            value = value ushr 7
        }
        length[index++] = value.toByte()
        return header + length.copyOf(index) + payload
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("known-hosts-", ".pb").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
}
