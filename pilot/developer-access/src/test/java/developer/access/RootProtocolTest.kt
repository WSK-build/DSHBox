package developer.access.root

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 帧协议字节层的测试。
 *
 * JSON 载荷层依赖 `org.json`，在 JVM 单元测试中为桩实现，故此处只覆盖不依赖它的
 * 长度编解码——那恰是分帧的正确性所在（长度错一位则整条流错位）。
 */
class RootProtocolTest {

    @Test
    fun `长度按四字节大端序编码`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), RootProtocol.encodeLength(1))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), RootProtocol.encodeLength(256))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), RootProtocol.encodeLength(65536))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), RootProtocol.encodeLength(16777216))
    }

    @Test
    fun `编码后解码还原原值`() {
        for (n in listOf(1, 2, 255, 256, 65535, 65536, 1_000_000, RootProtocol.MAX_FRAME_BYTES)) {
            assertEquals(n, RootProtocol.decodeLength(RootProtocol.encodeLength(n)))
        }
    }

    @Test
    fun `解码能处理高位字节`() {
        assertEquals(
            0x01020304,
            RootProtocol.decodeLength(byteArrayOf(0x01, 0x02, 0x03, 0x04)),
        )
    }

    @Test
    fun `解码把字节按无符号处理`() {
        // 0xFF 若按有符号扩展会得到负数
        assertEquals(
            0x000000FF,
            RootProtocol.decodeLength(byteArrayOf(0, 0, 0, 0xFF.toByte())),
        )
    }

    @Test
    fun `帧上限为非零正数`() {
        assertEquals(4 * 1024 * 1024, RootProtocol.MAX_FRAME_BYTES)
    }
}
