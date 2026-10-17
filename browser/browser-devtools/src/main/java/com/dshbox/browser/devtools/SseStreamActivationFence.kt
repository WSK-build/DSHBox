package com.dshbox.browser.devtools

import org.json.JSONObject

internal data class CdpResponseMessage(val message: JSONObject, val eventSequenceFence: Long)
internal data class CdpCommandResult(val result: JSONObject, val eventSequenceFence: Long)

/** Captures event ordering at reply receipt, independently of later socket reads. */
internal fun captureCdpResponseMessage(message: JSONObject, receivedEventSequence: Long): CdpResponseMessage {
    require(receivedEventSequence >= 0L)
    return CdpResponseMessage(message, receivedEventSequence)
}

internal enum class SseDataDisposition { APPEND_PAYLOAD, COVERED_BY_BUFFER, EMPTY_METADATA, REJECT_STREAM }

/** Accounts only for pre-activation metadata whose bytes already exist in the initial buffer. */
internal class SseStreamWindow(
    val captureId: String,
    private val ackEventSequence: Long,
    initialBufferedBytes: Int,
) {
    var bufferedMetadataBytesRemaining: Int = initialBufferedBytes
        private set

    init {
        require(captureId.isNotBlank() && ackEventSequence >= 0L && initialBufferedBytes >= 0)
    }

    fun belongsTo(activeCaptureId: String?): Boolean = activeCaptureId == captureId

    fun classify(receivedSequence: Long, dataLength: Int, hasPayloadField: Boolean): SseDataDisposition {
        if (dataLength < 0) return SseDataDisposition.REJECT_STREAM
        if (hasPayloadField) return SseDataDisposition.APPEND_PAYLOAD
        if (dataLength == 0) return SseDataDisposition.EMPTY_METADATA
        if (receivedSequence > 0L && receivedSequence <= ackEventSequence &&
            dataLength <= bufferedMetadataBytesRemaining
        ) {
            bufferedMetadataBytesRemaining -= dataLength
            return SseDataDisposition.COVERED_BY_BUFFER
        }
        return SseDataDisposition.REJECT_STREAM
    }
}
