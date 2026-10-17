package com.dshbox.browser.devtools

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

internal data class CdpWebSocketFrame(val opcode: Int, val payload: ByteArray)

/** RFC 6455 frame codec shared with protocol tests. Client writes are always masked. */
internal object CdpWebSocketFrameCodec {
    private val random = SecureRandom()

    fun writeClientFrame(output: OutputStream, opcode: Int, payload: ByteArray, maxBytes: Int) {
        require(opcode in 0..15)
        require(payload.size <= maxBytes)
        val mask = ByteArray(4).also(random::nextBytes)
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size <= 0xffff -> {
                frame.write(0x80 or 126)
                frame.write(payload.size ushr 8)
                frame.write(payload.size)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write(payload.size.toLong().ushr(shift).toInt())
            }
        }
        frame.write(mask)
        payload.indices.forEach { frame.write(payload[it].toInt() xor mask[it and 3].toInt()) }
        output.write(frame.toByteArray())
        output.flush()
    }
}

internal class CdpWebSocketFrameDecoder(private val maxBytes: Int) {
    private var fragments: ByteArrayOutputStream? = null
    private var fragmentOpcode = 0

    fun read(input: InputStream): CdpWebSocketFrame? {
        val first = input.read()
        if (first < 0) throw EOFException("CDP_CLOSED")
        val second = input.read()
        if (second < 0) throw EOFException("CDP_FRAME_TRUNCATED")
        check(first and 0x70 == 0) { "CDP_BAD_FRAME" }
        val fin = first and 0x80 != 0
        val opcode = first and 0x0f
        check(opcode in setOf(0, 1, 2, 8, 9, 10)) { "CDP_BAD_FRAME" }
        var size = (second and 0x7f).toLong()
        if (size == 126L) size = ((readByte(input) shl 8) or readByte(input)).toLong()
        else if (size == 127L) {
            size = 0L
            repeat(8) {
                val next = readByte(input)
                if (size == 0L) check(next and 0x80 == 0) { "CDP_BAD_FRAME" }
                size = (size shl 8) or next.toLong()
            }
        }
        check(size <= maxBytes.toLong()) { "FRAME_TOO_LARGE" }
        if (opcode >= 8) check(fin && size <= 125L) { "CDP_BAD_FRAME" }
        val masked = second and 0x80 != 0
        val mask = if (masked) readExactly(input, 4) else ByteArray(0)
        var payload = readExactly(input, size.toInt())
        if (masked) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        if (opcode == 0) {
            val current = fragments ?: return null
            current.write(payload)
            check(current.size() <= maxBytes) { "FRAME_TOO_LARGE" }
            if (!fin) return null
            val assembled = current.toByteArray()
            val originalOpcode = fragmentOpcode
            fragments = null
            fragmentOpcode = 0
            return CdpWebSocketFrame(originalOpcode, assembled)
        }
        if (opcode == 1 || opcode == 2) {
            check(fragments == null) { "CDP_BAD_FRAME" }
            if (!fin) {
                fragments = ByteArrayOutputStream().apply { write(payload) }
                fragmentOpcode = opcode
                return null
            }
        }
        return CdpWebSocketFrame(opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("CDP_FRAME_TRUNCATED")
        return value
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("CDP_FRAME_TRUNCATED")
            offset += read
        }
        return bytes
    }
}
