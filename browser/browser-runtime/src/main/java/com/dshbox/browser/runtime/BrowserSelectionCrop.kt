package com.dshbox.browser.runtime

import android.graphics.Bitmap
import android.graphics.RectF

/** Every crop owns its pixels independently of the frozen selection frame. */
internal object BrowserSelectionCrop {
    fun create(source: Bitmap, bounds: RectF): Bitmap? = runCatching {
        val left = bounds.left.toInt().coerceIn(0, source.width - 1)
        val top = bounds.top.toInt().coerceIn(0, source.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, source.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        if (crop === source) source.copy(Bitmap.Config.ARGB_8888, false) else crop
    }.getOrNull()
}
