package com.dshbox.remote.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 二维码帧解码测试：以 zxing 生成器合成标准 QR 矩阵 → 构造亮度帧 → 解码
 * 往返；覆盖取景裁剪、非紧凑步长抽取与未识出路径。
 */
class QrFrameDecoderTest {

    private val payload = "https://example.com/pair#abc"

    /** 每模块渲染像素数：1:1 合成帧（硬边无灰阶）会使 Hybrid 自适应二值化失效，
     *  真实相机帧自带镜头渐变与噪声，放大量级反而更贴近实际。 */
    private val PX_PER_MODULE = 8

    /** 生成放大渲染的亮度帧（暗模块 = 0x00，白 = 0xFF；行优先，frame[y * size + x]）。
     *  生成器输出自带标准静区。 */
    private fun syntheticFrame(text: String): Triple<ByteArray, Int, Int> {
        val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 21, 21)
        val modules = matrix.width
        val size = modules * PX_PER_MODULE
        val frame = ByteArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix[x / PX_PER_MODULE, y / PX_PER_MODULE]) 0x00.toByte() else 0xFF.toByte()
        }
        return Triple(frame, size, size)
    }

    /** 外补静区的帧（供裁剪负例：二维码外的纯白区域）。 */
    private fun paddedFrame(text: String, quietZone: Int): Triple<ByteArray, Int, Int> {
        val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 21, 21)
        val inner = matrix.width * PX_PER_MODULE
        val size = inner + quietZone * PX_PER_MODULE * 2
        val frame = ByteArray(size * size) { 0xFF.toByte() }
        for (y in 0 until inner) {
            for (x in 0 until inner) {
                if (matrix[x / PX_PER_MODULE, y / PX_PER_MODULE]) {
                    frame[(y + quietZone * PX_PER_MODULE) * size + (x + quietZone * PX_PER_MODULE)] = 0x00
                }
            }
        }
        return Triple(frame, size, size)
    }

    /** 亮度帧顺时针旋转 90°（模拟竖屏手机传感器帧相对取景的朝向）。 */
    private fun rotate90CW(frame: ByteArray, size: Int): Triple<ByteArray, Int, Int> {
        val rotated = ByteArray(frame.size)
        for (y in 0 until size) for (x in 0 until size) {
            // 目标 (x, y) 取自源 (y, size - 1 - x)
            rotated[y * size + x] = frame[(size - 1 - x) * size + y]
        }
        return Triple(rotated, size, size)
    }

    /** 姿态完备解码：90° 旋转帧（竖屏典型朝向）与 180° 旋转帧均可解出。 */
    @Test fun rotatedFramesDecodeRegardlessOfOrientation() {
        val (upright, size, _) = syntheticFrame(payload)
        val (cw90, size90, _) = rotate90CW(upright, size)
        assertEquals(payload, QrFrameDecoder.decodeYuv(cw90, size90, size90))
        val (cw180, size180, _) = rotate90CW(cw90, size90)
        assertEquals(payload, QrFrameDecoder.decodeYuv(cw180, size180, size180))
    }

    @Test fun fullFrameDecodesPayload() {
        val (frame, width, height) = syntheticFrame(payload)
        assertEquals(payload, QrFrameDecoder.decodeYuv(frame, width, height))
    }

    @Test fun cropToCodeRegionDecodesPayload() {
        val (frame, width, height) = syntheticFrame(payload)
        // 取景裁剪到二维码区域内缩 16 像素（仍含定位图案）
        assertEquals(
            payload,
            QrFrameDecoder.decodeYuv(
                frame, width, height,
                cropLeft = 16, cropTop = 16, cropWidth = width - 32, cropHeight = height - 32,
            ),
        )
    }

    @Test fun cropOutsideCodeRegionReturnsNull() {
        val (frame, width, height) = paddedFrame(payload, quietZone = 4)
        // 取景裁剪完全落在二维码之外的纯白区域：未识出
        assertNull(QrFrameDecoder.decodeYuv(frame, width, height, cropLeft = 0, cropTop = 0, cropWidth = 3, cropHeight = 3))
    }

    @Test fun blankFrameReturnsNull() {
        val size = 64
        val blank = ByteArray(size * size) { 0xFF.toByte() }
        assertNull(QrFrameDecoder.decodeYuv(blank, size, size))
    }

    @Test fun malformedInputsReturnNull() {
        val (frame, width, height) = syntheticFrame(payload)
        assertNull(QrFrameDecoder.decodeYuv(frame, 0, height))
        assertNull(QrFrameDecoder.decodeYuv(ByteArray(4), width, height))
    }

    @Test fun extractLuminanceHandlesNonCompactStrides() {
        // 相机平面常带对齐填充：rowStride/pixelStride 大于紧凑排布，抽取须逐像素采样
        val width = 4
        val height = 3
        val rowStride = 12
        val pixelStride = 2
        val buffer = java.nio.ByteBuffer.allocate(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer.put(row * rowStride + col * pixelStride, (row * width + col).toByte())
            }
        }
        val luminance = QrFrameDecoder.extractLuminance(buffer, rowStride, pixelStride, width, height)
        for (i in 0 until width * height) {
            assertEquals(i.toByte(), luminance[i])
        }
        assertEquals(null, QrFrameDecoder.decodeYuv(luminance, width, height))
    }
}
