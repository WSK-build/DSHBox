package com.dshbox.remote.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码帧分析器：命中即停（防重复回调），解析在分析线程、命中回调切主线程。
 *
 * 生命周期由显示槽驱动：识码成功或调用 [stop] 后不再解码（后续帧直接关闭）。
 */
class QrScanAnalyzer(
    /** 识码回调：主线程投递，携带解码文本。 */
    private val onPayload: (String) -> Unit,
    /** 主线程投递器（可注入供单测驱动）。 */
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ImageAnalysis.Analyzer {

    private val stopped = AtomicBoolean(false)
    private val hit = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /** 首次解码故障的日志开关：帧级故障每帧都会复现，只记一次防刷屏。 */
    private val firstFailureLogged = AtomicBoolean(false)

    /** 停止分析：识码成功关闭子页、相机解绑或显示槽销毁时调用。 */
    fun stop() {
        stopped.set(true)
    }

    /** 恢复扫码：建档失败就地显示后允许重新取景（清除停机/命中/忙帧标记；
     *  重绑定后的分析器实例被上一次解绑的 stop 污染时也由此复活）。 */
    fun resume() {
        stopped.set(false)
        hit.set(false)
        busy.set(false)
    }

    override fun analyze(image: ImageProxy) {
        // 命中即停/忙帧丢弃：分析管线串行（KEEP_ONLY_LATEST 语义下冗余兜底）
        if (stopped.get() || hit.get() || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val decoded = try {
            val plane = image.planes[0]
            val luminance = QrFrameDecoder.extractLuminance(
                planeBuffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                width = image.width,
                height = image.height,
            )
            QrFrameDecoder.decodeYuv(luminance, image.width, image.height)
        } catch (t: Throwable) {
            // 真机抽取/解码故障（buffer 越界、格式差异）不得静默：只记首次
            if (firstFailureLogged.compareAndSet(false, true)) {
                android.util.Log.w(
                    "QrScan",
                    "frame decode failed: ${image.width}x${image.height} " +
                        "rot=${image.imageInfo.rotationDegrees} fmt=${image.format}",
                    t,
                )
            }
            null
        }
        if (decoded != null && !stopped.get()) {
            // 命中：切主线程回调后再归还帧（回调期间相机不再收到新分析帧）
            hit.set(true)
            mainHandler.post {
                try {
                    onPayload(decoded)
                } finally {
                    image.close()
                }
            }
        } else {
            busy.set(false)
            image.close()
        }
    }
}

