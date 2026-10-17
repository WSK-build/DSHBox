package com.dshbox.browser.runtime

/** Bounded validation shared by the CDP decoder path and its JVM regression tests. */
internal object BrowserBackgroundCaptureGate {
    const val MAX_PNG_BYTES = 1_350_000
    const val MAX_PIXELS = 16_777_216L

    fun validPng(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int): Boolean {
        if (bytes.size < 24 || bytes.size > MAX_PNG_BYTES || expectedWidth <= 0 || expectedHeight <= 0 ||
            expectedWidth.toLong() * expectedHeight.toLong() > MAX_PIXELS
        ) return false
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return false
        fun intAt(offset: Int): Int = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
        return intAt(8) == 13 && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR" &&
            intAt(16) == expectedWidth && intAt(20) == expectedHeight
    }
}
