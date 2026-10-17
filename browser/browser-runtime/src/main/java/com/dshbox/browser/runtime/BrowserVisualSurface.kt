package com.dshbox.browser.runtime

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.webkit.WebView
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Full bounded frame digest, including dimensions; no sampling that could miss changed media pixels. */
internal object BrowserVisualPixelDigest {
    fun fromBitmap(bitmap: Bitmap): String? = runCatching {
        if (bitmap.isRecycled) return null
        fromRows(bitmap.width, bitmap.height) { y, pixels -> bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1) }
    }.getOrNull()

    fun fromRows(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): String? {
        if (width !in 1..4096 || height !in 1..8192 || width.toLong() * height > 8_000_000) return null
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
        val pixels = IntArray(width)
        val row = ByteBuffer.allocate(width * 4)
        for (y in 0 until height) {
            readRow(y, pixels)
            row.clear()
            for (pixel in pixels) row.putInt(pixel)
            hash.update(row.array())
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Only Browser's WebView receives this tap; every dispatched down is followed by up or cancellation. */
internal object BrowserVisualSurfaceTap {
    fun dispatch(view: WebView, x: Double, y: Double, authorized: () -> Boolean): String {
        fun allowed() = runCatching(authorized).getOrDefault(false)
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= view.width || y >= view.height || !allowed()) return "AUTHORIZATION_REVOKED"
        val downTime = SystemClock.uptimeMillis()
        var downSent = false
        var ended = false
        fun event(action: Int) = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try {
            val down = event(MotionEvent.ACTION_DOWN)
            // Physical gestures still pass through the Activity's takeover listener; these authorized native events do not.
            try { downSent = true; view.onTouchEvent(down) } finally { down.recycle() }
            if (!allowed()) "UNKNOWN" else {
                val up = event(MotionEvent.ACTION_UP)
                try { view.onTouchEvent(up); ended = true } finally { up.recycle() }
                "OK"
            }
        } catch (_: Exception) { if (downSent) "UNKNOWN" else "UNSUPPORTED" }
        finally {
            if (downSent && !ended) runCatching {
                val cancel = event(MotionEvent.ACTION_CANCEL)
                try { view.onTouchEvent(cancel) } finally { cancel.recycle() }
            }
        }
    }
}
