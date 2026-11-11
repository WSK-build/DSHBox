package com.dshbox.remote.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码帧解码：CameraX 亮度平面 → zxing 一次解码（QR 优先）。
 *
 * 纯 JVM 逻辑（无 Android 依赖）：`PlanarYUVLuminanceSource` 与解码器均来自
 * zxing core，YUV 亮度数组由帧分析器按平面步长抽取后传入——单测以合成帧
 * 驱动往返。
 */
object QrFrameDecoder {

    /** 解码提示：本扫码器只面向配对二维码，限定格式避免误识一维码。 */
    private val QR_ONLY_HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * 解码亮度帧中的二维码。
     *
     * @param luminance 亮度数组（行优先，0x00 黑 / 0xFF 白）。
     * @param width/height 帧尺寸。
     * @param cropLeft/cropTop/cropWidth/cropHeight 取景裁剪（null = 全帧）。
     * @return 解码文本；未识出返回 null。
     */
    fun decodeYuv(
        luminance: ByteArray,
        width: Int,
        height: Int,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropWidth: Int = width,
        cropHeight: Int = height,
    ): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < width * height) return null
        var cropLeftMutable = cropLeft
        var cropTopMutable = cropTop
        var cropWidthMutable = cropWidth
        var cropHeightMutable = cropHeight
        // 姿态完备解码：相机传感器帧相对屏幕存在 0/90/180/270 的旋转
        // （竖屏手机典型为 90°），zxing 不自动旋转——亮度数组自行旋转
        // 四个姿态逐次尝试（PlanarYUVLuminanceSource 不支持旋转，旋转
        // 必须落在原始数组上），命中任一即返回。
        var current = luminance
        var currentWidth = width
        var currentHeight = height
        val reader = MultiFormatReader()
        return try {
            // 姿态完备：裁剪框随帧一同旋转（保持「码外裁剪不误识」的语义）
            repeat(4) {
                try {
                    val source = PlanarYUVLuminanceSource(
                        current, currentWidth, currentHeight,
                        cropLeftMutable, cropTopMutable, cropWidthMutable, cropHeightMutable, false,
                    )
                    val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), QR_ONLY_HINTS)
                    return result.text?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    // 当前姿态未识出：旋转 90° 再试
                }
                if (it < 3) {
                    // 源 (x, y) → 顺时针旋转 90° 后 (H-1-y, x)；宽高互换，
                    // 裁剪框同步变换（左上/尺寸随映射换轴）
                    val rotated = ByteArray(current.size)
                    for (y in 0 until currentHeight) {
                        for (x in 0 until currentWidth) {
                            rotated[x * currentHeight + (currentHeight - 1 - y)] =
                                current[y * currentWidth + x]
                        }
                    }
                    val rotatedCropLeft = currentHeight - cropTopMutable - cropHeightMutable
                    val rotatedCropTop = cropLeftMutable
                    cropLeftMutable = rotatedCropLeft
                    cropTopMutable = rotatedCropTop
                    val w = cropWidthMutable
                    cropWidthMutable = cropHeightMutable
                    cropHeightMutable = w
                    current = rotated
                    val cw = currentWidth
                    currentWidth = currentHeight
                    currentHeight = cw
                }
            }
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * 从相机亮度平面抽取行优先亮度数组：按 rowStride/pixelStride 步长逐像素
     * 采样（相机平面常带对齐填充，直接整块拷贝会引入错位条纹）。
     */
    fun extractLuminance(
        planeBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height)
        val limit = planeBuffer.limit()
        for (row in 0 until height) {
            val rowStart = row * rowStride
            var outIndex = row * width
            for (col in 0 until width) {
                val index = rowStart + col * pixelStride
                // 真机 Y 平面的 limit 常小于 rowStride*height（末行无填充）：
                // 越界像素（帧底边）填白，避免绝对 get 抛出 IndexOutOfBounds
                // 被上层静默吞掉、导致解码永不产出
                out[outIndex++] =
                    if (index in 0 until limit) planeBuffer.get(index) else 0xFF.toByte()
            }
        }
        return out
    }
}
