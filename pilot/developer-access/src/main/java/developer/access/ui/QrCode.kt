package developer.access.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 二维码生成。
 *
 * 只做**生成**：访问码由本 App 产生，交给外部工具扫。
 * 解码能力随依赖一并打包但当前不启用（无相机管线），留待后续功能。
 */
object QrCode {

    /**
     * 把文本编码为方形二维码位图。
     *
     * @param content 待编码文本。
     * @param size 目标边长（像素）。
     * @param margin 静区模块数；1 即可，过大会让小尺寸下码点过密。
     */
    fun encode(content: String, size: Int, margin: Int = 1): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) FOREGROUND else BACKGROUND
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }.getOrNull()

    private const val FOREGROUND = 0xFF171717.toInt()
    private const val BACKGROUND = 0xFFFFFFFF.toInt()
}
