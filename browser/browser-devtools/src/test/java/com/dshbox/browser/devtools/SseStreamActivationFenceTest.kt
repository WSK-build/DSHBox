package com.dshbox.browser.devtools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class SseStreamActivationFenceTest {
    @Test fun queuedMetadataIsCoveredOnceAndLaterPayloadCompletesSplitUtf8() {
        val first = "id: 1\r\nevent: delta\r\ndata: {\"role\":\"assistant\",\"delta\":\"你好\"}\r\n\r\n".toByteArray(Charsets.UTF_8)
        val second = "id: 2\nevent: delta\ndata: {\"delta\":\"，Browser\"}\n\n".toByteArray(Charsets.UTF_8)
        val last = "id: 3\nevent: done\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        val expected = first + second + last
        assertEquals(149, expected.size)
        val stream = SseStreamWindow("cap-owned", 5L, 57)
        val output = ByteArrayOutputStream().apply { write(first, 0, 57) }
        assertEquals(SseDataDisposition.COVERED_BY_BUFFER, stream.classify(5L, 57, false))
        assertEquals(57, output.size())
        assertEquals(0, stream.bufferedMetadataBytesRemaining)
        val remainder = first.copyOfRange(57, first.size)
        for ((index, bytes) in listOf(remainder, second, last).withIndex()) {
            assertEquals(SseDataDisposition.APPEND_PAYLOAD, stream.classify(6L + index, bytes.size, true))
            output.write(bytes)
        }
        assertEquals(String(expected, Charsets.UTF_8), output.toString(Charsets.UTF_8.name()))
    }

    @Test fun uncoveredOrRepeatedMetadataRejectsWithoutFabricatingBytes() {
        val stream = SseStreamWindow("cap-owned", 5L, 57)
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(4L, 58, false))
        assertEquals(57, stream.bufferedMetadataBytesRemaining)
        assertEquals(SseDataDisposition.COVERED_BY_BUFFER, stream.classify(4L, 57, false))
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(5L, 1, false))
        assertEquals(0, stream.bufferedMetadataBytesRemaining)
    }

    @Test fun missingPostAckDataRejectsDespiteRemainingBufferCoverage() {
        val stream = SseStreamWindow("cap-owned", 5L, 57)
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(6L, 1, false))
        assertEquals(57, stream.bufferedMetadataBytesRemaining)
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(0L, 1, false))
    }

    @Test fun payloadFieldsStayOnDecodePathRatherThanUsingMetadataCoverage() {
        val stream = SseStreamWindow("cap-owned", 5L, 57)
        assertEquals(SseDataDisposition.APPEND_PAYLOAD, stream.classify(4L, 50, true))
        assertEquals(SseDataDisposition.APPEND_PAYLOAD, stream.classify(6L, 33, true))
        assertEquals(57, stream.bufferedMetadataBytesRemaining)
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(6L, -1, true))
    }

    @Test fun emptyMetadataDoesNotSpendCoverageAndCaptureIdentityMustMatch() {
        val stream = SseStreamWindow("cap-owned", 5L, 57)
        assertEquals(SseDataDisposition.EMPTY_METADATA, stream.classify(7L, 0, false))
        assertEquals(57, stream.bufferedMetadataBytesRemaining)
        assertTrue(stream.belongsTo("cap-owned"))
        assertFalse(stream.belongsTo("cap-new"))
        assertFalse(stream.belongsTo(null))
    }

    @Test fun responseFenceIsStampedBeforeLaterEventsAdvanceTheReader() {
        val events = AtomicLong(5L)
        val reply = captureCdpResponseMessage(JSONObject().put("result", JSONObject()), events.get())
        events.addAndGet(2L)
        assertEquals(5L, reply.eventSequenceFence)
        val stream = SseStreamWindow("cap-owned", reply.eventSequenceFence, 57)
        assertEquals(SseDataDisposition.REJECT_STREAM, stream.classify(events.get(), 1, false))
    }
}
