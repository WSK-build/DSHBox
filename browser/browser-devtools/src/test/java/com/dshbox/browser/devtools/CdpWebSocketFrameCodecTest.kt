package com.dshbox.browser.devtools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CdpWebSocketFrameCodecTest {
    @Test
    fun cdpReaderDoesNotExpireAnIdleConnection() {
        assertEquals(0, CDP_IDLE_READ_TIMEOUT_MILLIS)
    }

    @Test
    fun clientFramesUseRfcLengthFormsAndRoundTripAtBoundarySizes() {
        val sizes = listOf(0, 125, 126, 65_535, 65_536, 131_071)
        for (size in sizes) {
            val payload = ByteArray(size) { (it * 31).toByte() }
            val output = ByteArrayOutputStream()
            CdpWebSocketFrameCodec.writeClientFrame(output, 0x1, payload, 2 * 1024 * 1024)
            val frameBytes = output.toByteArray()
            assertEquals("client frame must be final text frame", 0x81, frameBytes[0].toInt() and 0xff)
            assertEquals("client frame must be masked", 0x80, frameBytes[1].toInt() and 0x80)

            val marker = frameBytes[1].toInt() and 0x7f
            val headerBytes: Int
            val decodedSize: Long
            when (marker) {
                in 0..125 -> {
                    headerBytes = 6
                    decodedSize = marker.toLong()
                }
                126 -> {
                    headerBytes = 8
                    decodedSize = (((frameBytes[2].toInt() and 0xff) shl 8) or (frameBytes[3].toInt() and 0xff)).toLong()
                }
                else -> {
                    headerBytes = 14
                    var length = 0L
                    for (index in 2 until 10) length = (length shl 8) or (frameBytes[index].toLong() and 0xff)
                    decodedSize = length
                }
            }
            assertEquals("encoded payload length", size.toLong(), decodedSize)
            assertEquals("no extraneous bytes in extended header", headerBytes + size, frameBytes.size)

            val decoded = CdpWebSocketFrameDecoder(2 * 1024 * 1024).read(ByteArrayInputStream(frameBytes))
            assertNotNull(decoded)
            assertEquals(0x1, decoded!!.opcode)
            assertArrayEquals(payload, decoded.payload)
        }
    }

    @Test
    fun fragmentedTextIsReassembledAndControlFramesStaySeparate() {
        val wire = byteArrayOf(
            0x01, 0x02, 'a'.code.toByte(), 'b'.code.toByte(),
            0x89.toByte(), 0x01, '!'.code.toByte(),
            0x80.toByte(), 0x02, 'c'.code.toByte(), 'd'.code.toByte(),
        )
        val decoder = CdpWebSocketFrameDecoder(1024)
        val input = ByteArrayInputStream(wire)
        assertNull(decoder.read(input))
        val ping = decoder.read(input)
        assertEquals(0x9, ping!!.opcode)
        assertArrayEquals(byteArrayOf('!'.code.toByte()), ping.payload)
        val text = decoder.read(input)
        assertEquals(0x1, text!!.opcode)
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte()), text.payload)
    }
}
