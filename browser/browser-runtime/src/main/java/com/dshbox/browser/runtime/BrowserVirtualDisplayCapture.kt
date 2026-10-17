package com.dshbox.browser.runtime

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one existing WebView through an app-private Presentation and ImageReader.
 * The WebView instance is only moved between parents on the main thread; its URL and renderer are not recreated.
 */
internal class BrowserVirtualDisplayCapture(
    private val windowHost: BrowserSessionWindowHost,
) : Closeable {
    data class Request(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val isCurrent: () -> Boolean,
    )

    private val host = windowHost.context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var active: Session? = null
    private var pendingRestore: Session? = null
    @Volatile private var closed = false

    fun capture(
        view: WebView,
        request: Request,
        deadlineElapsedMillis: Long,
        callback: (BrowserScreenshotCaptureResult<Bitmap>) -> Unit,
    ): BrowserScreenshotCaptureHandle = captureInternal(view, request, deadlineElapsedMillis, null, callback)

    /** Keeps a private render window alive until the renderer backend has completed. */
    fun captureWithRenderer(
        view: WebView,
        request: Request,
        deadlineElapsedMillis: Long,
        renderer: BrowserScreenshotCaptureSource<Bitmap>,
        callback: (BrowserScreenshotCaptureResult<Bitmap>) -> Unit,
    ): BrowserScreenshotCaptureHandle = captureInternal(view, request, deadlineElapsedMillis, renderer, callback)

    private fun captureInternal(
        view: WebView,
        request: Request,
        deadlineElapsedMillis: Long,
        renderer: BrowserScreenshotCaptureSource<Bitmap>?,
        callback: (BrowserScreenshotCaptureResult<Bitmap>) -> Unit,
    ): BrowserScreenshotCaptureHandle {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                callback(BrowserScreenshotCaptureResult(
                    null,
                    BrowserScreenshotCaptureContract.CAPTURE_BUSY,
                ))
            }
            return BrowserScreenshotCaptureHandle { false }
        }
        if (closed || request.width <= 0 || request.height <= 0 || request.densityDpi <= 0 ||
            request.width.toLong() * request.height.toLong() > MAX_PIXELS
        ) {
            callback(BrowserScreenshotCaptureResult(
                null,
                if (closed) "CLOSED" else BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE,
            ))
            return BrowserScreenshotCaptureHandle { false }
        }
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMillis) {
            callback(BrowserScreenshotCaptureResult(null, "DEADLINE_EXPIRED"))
            return BrowserScreenshotCaptureHandle { false }
        }
        val session = Session(view, request, deadlineElapsedMillis, renderer, callback)
        synchronized(lock) {
            if (closed || active != null) {
                callback(BrowserScreenshotCaptureResult(
                    null,
                    if (closed) "CLOSED" else BrowserScreenshotCaptureContract.CAPTURE_BUSY,
                ))
                return BrowserScreenshotCaptureHandle { false }
            }
            active = session
        }
        session.start()
        return object : BrowserScreenshotCaptureHandle {
            override fun cancel(): Boolean = session.cancel()

            override fun cancelAndRestore(): Boolean = session.cancelAndRestore()
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) { active?.cancel() }
    }

    fun recoverRestore(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val pending = pendingRestore ?: return true
        if (!pending.retryRestore()) return false
        pendingRestore = null
        return true
    }

    private inner class Session(
        private val view: WebView,
        private val request: Request,
        private val deadlineElapsedMillis: Long,
        private val renderer: BrowserScreenshotCaptureSource<Bitmap>?,
        private val callback: (BrowserScreenshotCaptureResult<Bitmap>) -> Unit,
    ) {
        private val terminal = AtomicBoolean(false)
        private val displayThread = HandlerThread("browser-virtual-display")
        private var displayHandler: Handler? = null
        private var reader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var container: FrameLayout? = null
        private var originalParent: ViewGroup? = null
        private var originalLayoutParams: ViewGroup.LayoutParams? = null
        private var originalIndex = -1
        private var originalVisibility = View.VISIBLE
        private var originalFocusable = false
        private var originalFocusableInTouchMode = false
        private var originalOffscreenPreRaster: Boolean? = null
        private var moved = false
        private var frameFloorNanos = 0L
        private var frameReadRequested = false
        private var visualStateReady = false
        private var attachListener: View.OnAttachStateChangeListener? = null
        private var drawListener: ViewTreeObserver.OnDrawListener? = null
        private var bitmap: Bitmap? = null
        private var rendererHandle: BrowserScreenshotCaptureHandle? = null
        @Volatile private var restoreSucceeded: Boolean? = null
        private val deadlineTask = Runnable { onDeadline() }

        fun start() {
            try {
                originalOffscreenPreRaster = view.settings.offscreenPreRaster
                view.settings.offscreenPreRaster = true
                displayThread.start()
                val threadHandler = Handler(displayThread.looper)
                displayHandler = threadHandler
                val imageReader = ImageReader.newInstance(
                    request.width,
                    request.height,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGES,
                )
                reader = imageReader
                val manager = host.getSystemService(DisplayManager::class.java)
                    ?: throw IllegalStateException("DISPLAY_MANAGER_UNAVAILABLE")
                val display = manager.createVirtualDisplay(
                    DISPLAY_NAME,
                    request.width,
                    request.height,
                    request.densityDpi,
                    imageReader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    mainHandler,
                ) ?: throw IllegalStateException("VIRTUAL_DISPLAY_UNAVAILABLE")
                virtualDisplay = display
                val capturePresentation = Presentation(host, display.display)
                presentation = capturePresentation
                capturePresentation.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                val captureContainer = FrameLayout(capturePresentation.context)
                container = captureContainer
                capturePresentation.setContentView(
                    captureContainer,
                    FrameLayout.LayoutParams(request.width, request.height),
                )
                capturePresentation.setOnDismissListener {
                    if (!terminal.get()) fail(BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE)
                }
                capturePresentation.show()
                if (!capturePresentation.isShowing || !attachWebView(captureContainer)) {
                    throw IllegalStateException("PRESENTATION_UNAVAILABLE")
                }
                view.onResume()
                frameFloorNanos = System.nanoTime()
                imageReader.setOnImageAvailableListener(::onImageAvailable, threadHandler)
                val remaining = deadlineElapsedMillis - SystemClock.elapsedRealtime()
                if (remaining <= 0L) throw IllegalStateException("DEADLINE_EXPIRED")
                mainHandler.postDelayed(deadlineTask, remaining)
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        view.removeOnAttachStateChangeListener(this)
                        attachListener = null
                        view.post { requestVisualFrame() }
                    }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                }
                attachListener = listener
                view.addOnAttachStateChangeListener(listener)
                if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
            } catch (error: Throwable) {
                android.util.Log.w("BrowserVirtualCapture", "capture_setup_failed:${error.javaClass.simpleName}", error)
                val code = when (error.message) {
                    "DEADLINE_EXPIRED" -> "DEADLINE_EXPIRED"
                    else -> BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE
                }
                fail(code)
            }
        }

        private fun requestVisualFrame() {
            if (terminal.get()) return
            if (!trusted() || !view.isAttachedToWindow) {
                fail("IDENTITY_CHANGED")
                return
            }
            if (!view.isHardwareAccelerated) {
                fail(BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE)
                return
            }
            if (view.isLayoutRequested || view.width != request.width || view.height != request.height) {
                view.postOnAnimation { requestVisualFrame() }
                return
            }
            try {
                view.postVisualStateCallback(
                    SystemClock.elapsedRealtime(),
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (terminal.get()) return
                            frameFloorNanos = System.nanoTime()
                            val listener = object : ViewTreeObserver.OnDrawListener {
                                override fun onDraw() {
                                    view.post {
                                        removeDrawListener()
                                        if (terminal.get()) return@post
                                        visualStateReady = true
                                        requestFrameRead()
                                    }
                                }
                            }
                            drawListener = listener
                            view.viewTreeObserver.addOnDrawListener(listener)
                            view.invalidate()
                        }
                    },
                )
            } catch (_: RuntimeException) {
                fail(BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE)
            }
        }

        private fun attachWebView(captureContainer: FrameLayout): Boolean {
            if (!trusted()) return false
            val parent = view.parent as? ViewGroup
            originalParent = parent
            originalLayoutParams = view.layoutParams
            originalIndex = parent?.indexOfChild(view) ?: -1
            originalVisibility = view.visibility
            originalFocusable = view.isFocusable
            originalFocusableInTouchMode = view.isFocusableInTouchMode
            moved = true
            runCatching {
                parent?.removeView(view)
                view.layoutParams = FrameLayout.LayoutParams(request.width, request.height)
                view.visibility = View.VISIBLE
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                captureContainer.addView(view)
            }.onFailure {
                restoreWebView()
                return false
            }
            return view.parent === captureContainer
        }

        private fun onImageAvailable(imageReader: ImageReader) {
            mainHandler.post {
                if (terminal.get()) return@post
                val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return@post
                try {
                    if (renderer != null) return@post
                    if (!visualStateReady) return@post
                    if (image.timestamp < frameFloorNanos) return@post
                    if (!trusted()) {
                        fail("IDENTITY_CHANGED")
                        return@post
                    }
                    val copied = copyBitmap(image) ?: run {
                        fail("PIXEL_READ_FAILED")
                        return@post
                    }
                    if (!trusted()) {
                        copied.recycle()
                        fail("IDENTITY_CHANGED")
                        return@post
                    }
                    bitmap = copied
                    complete()
                } catch (_: OutOfMemoryError) {
                    fail("PIXEL_READ_FAILED")
                } catch (_: RuntimeException) {
                    fail("PIXEL_READ_FAILED")
                } finally {
                    runCatching { image.close() }
                }
            }
        }

        private fun requestFrameRead() {
            if (terminal.get() || frameReadRequested) return
            frameReadRequested = true
            if (renderer != null) {
                if (!trusted()) return fail("IDENTITY_CHANGED")
                val handle = try { renderer.capture(deadlineElapsedMillis) { result ->
                    val accept = {
                        when {
                            terminal.get() -> result.value?.recycle()
                            !trusted() -> { result.value?.recycle(); fail("IDENTITY_CHANGED") }
                            result.code != "OK" || result.value == null -> {
                                result.value?.recycle()
                                fail(if (result.code == "OK") "CAPTURE_EMPTY_RESULT" else result.code)
                            }
                            else -> { bitmap = result.value; complete() }
                        }
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) accept() else mainHandler.post { accept() }
                } } catch (_: RuntimeException) {
                    fail("PIXEL_READ_FAILED")
                    return
                }
                if (terminal.get()) handle.cancel() else rendererHandle = handle
                return
            }
            val imageReader = reader ?: return
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                try {
                    if (visualStateReady && image.timestamp >= frameFloorNanos && trusted()) {
                        bitmap = copyBitmap(image)
                        if (bitmap != null) {
                            complete()
                            return
                        }
                    }
                } finally {
                    runCatching { image.close() }
                }
            }
            frameReadRequested = false
        }

        private fun copyBitmap(image: Image): Bitmap? {
            if (image.width != request.width || image.height != request.height || image.planes.size != 1) return null
            val plane = image.planes[0]
            if (plane.pixelStride < BYTES_PER_PIXEL || plane.rowStride < request.width * BYTES_PER_PIXEL) return null
            val buffer = plane.buffer
            val row = ByteArray(plane.rowStride)
            val pixels = IntArray(request.width * request.height)
            for (y in 0 until request.height) {
                val rowOffset = y * plane.rowStride
                if (rowOffset < 0 || rowOffset >= buffer.limit()) return null
                buffer.position(rowOffset)
                val available = minOf(plane.rowStride, buffer.remaining())
                buffer.get(row, 0, available)
                for (x in 0 until request.width) {
                    val offset = x * plane.pixelStride
                    if (offset + 3 >= available) return null
                    val red = row[offset].toInt() and 0xff
                    val green = row[offset + 1].toInt() and 0xff
                    val blue = row[offset + 2].toInt() and 0xff
                    val alpha = row[offset + 3].toInt() and 0xff
                    pixels[y * request.width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            return Bitmap.createBitmap(pixels, request.width, request.height, Bitmap.Config.ARGB_8888)
        }

        fun cancel(): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            val cleanup = {
                bitmap?.recycle()
                bitmap = null
                restoreSucceeded = releaseAndRestore()
                clearActive()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else mainHandler.post(cleanup)
            return true
        }

        fun cancelAndRestore(): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                cancel()
                return false
            }
            if (!terminal.compareAndSet(false, true)) return restoreSucceeded == true
            bitmap?.recycle()
            bitmap = null
            restoreSucceeded = releaseAndRestore()
            clearActive()
            return restoreSucceeded == true
        }

        private fun onDeadline() {
            if (!terminal.get()) fail("DEADLINE_EXPIRED")
        }

        private fun complete() {
            if (!terminal.compareAndSet(false, true)) {
                bitmap?.recycle()
                bitmap = null
                return
            }
            mainHandler.post {
                val restored = releaseAndRestore()
                restoreSucceeded = restored
                if (!restored) {
                    bitmap?.recycle()
                    bitmap = null
                    clearActive()
                    deliver(BrowserScreenshotCaptureResult(
                        null,
                        BrowserScreenshotCaptureContract.RESTORE_FAILED,
                    ))
                    return@post
                }
                val captured = bitmap
                bitmap = null
                clearActive()
                deliver(BrowserScreenshotCaptureResult(captured, "OK"))
            }
        }

        private fun fail(code: String) {
            if (!terminal.compareAndSet(false, true)) return
            mainHandler.post {
                bitmap?.recycle()
                bitmap = null
                val restored = releaseAndRestore()
                restoreSucceeded = restored
                clearActive()
                deliver(BrowserScreenshotCaptureResult(
                    null,
                    if (!restored) BrowserScreenshotCaptureContract.RESTORE_FAILED else code,
                    if (!restored) BrowserScreenshotOutcome(attempts = listOf(
                        BrowserScreenshotAttempt(BrowserScreenshotCaptureContract.PRIMARY_BACKEND, code),
                    )) else BrowserScreenshotOutcome(),
                ))
            }
        }

        private fun clearActive() {
            synchronized(lock) {
                if (active === this@Session) active = null
            }
        }

        private fun releaseAndRestore(): Boolean {
            mainHandler.removeCallbacks(deadlineTask)
            runCatching { rendererHandle?.cancel() }
            rendererHandle = null
            removeDrawListener()
            attachListener?.let(view::removeOnAttachStateChangeListener)
            attachListener = null
            reader?.setOnImageAvailableListener(null, null)
            val restored = restoreWebView()
            val rasterRestored = restoreRasterSetting()
            if (!restored || !rasterRestored) pendingRestore = this
            runCatching { presentation?.dismiss() }
            presentation = null
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { reader?.close() }
            reader = null
            displayHandler?.removeCallbacksAndMessages(null)
            displayHandler = null
            runCatching { displayThread.quitSafely() }
            if (restored) container = null
            return restored && rasterRestored
        }

        private fun restoreRasterSetting(): Boolean {
            val original = originalOffscreenPreRaster ?: return true
            return runCatching {
                view.settings.offscreenPreRaster = original
                (view.settings.offscreenPreRaster == original).also {
                    if (it) originalOffscreenPreRaster = null
                }
            }.getOrDefault(false)
        }

        private fun removeDrawListener() {
            val listener = drawListener ?: return
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
            drawListener = null
        }

        private fun restoreWebView(): Boolean {
            if (!moved) return true
            val restored = runCatching {
                val captureContainer = container
                if (view.parent === captureContainer) captureContainer?.removeView(view)
                view.isFocusable = originalFocusable
                view.isFocusableInTouchMode = originalFocusableInTouchMode
                view.visibility = originalVisibility
                if (!windowHost.hasWindowFocus()) view.onPause()
                val parent = originalParent
                if (parent != null && view.parent == null) {
                    val params = originalLayoutParams
                    val index = originalIndex.coerceIn(0, parent.childCount)
                    if (params != null) parent.addView(view, index, params)
                    else parent.addView(view, index)
                }
                view.parent === parent && view.visibility == originalVisibility &&
                    view.isFocusable == originalFocusable &&
                    view.isFocusableInTouchMode == originalFocusableInTouchMode
            }.getOrDefault(false)
            if (restored) moved = false
            return restored
        }

        fun retryRestore(): Boolean {
            val viewRestored = restoreWebView()
            val restored = restoreRasterSetting() && viewRestored
            if (restored) container = null
            return restored
        }

        private fun trusted(): Boolean =
            runCatching { request.isCurrent() }.getOrDefault(false)

        private fun deliver(result: BrowserScreenshotCaptureResult<Bitmap>) {
            if (Looper.myLooper() == Looper.getMainLooper()) callback(result)
            else mainHandler.post { callback(result) }
        }
    }

    private companion object {
        const val DISPLAY_NAME = "browser-private-capture"
        const val MAX_IMAGES = 2
        const val BYTES_PER_PIXEL = 4
        const val MAX_PIXELS = 16_777_216L
    }
}
