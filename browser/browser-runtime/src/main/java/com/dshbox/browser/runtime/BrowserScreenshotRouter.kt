package com.dshbox.browser.runtime

/** Selects one capture path at dispatch; visible pages keep their native window capture. */
internal class BrowserScreenshotRouter<T>(
    private val foreground: () -> Boolean,
    private val native: BrowserScreenshotCaptureSource<T>,
    private val background: BrowserScreenshotCaptureSource<T>,
) : BrowserScreenshotCaptureSource<T> {
    override fun capture(
        deadlineElapsedMillis: Long,
        callback: (BrowserScreenshotCaptureResult<T>) -> Unit,
    ): BrowserScreenshotCaptureHandle =
        (if (foreground()) native else background).capture(deadlineElapsedMillis, callback)
}
