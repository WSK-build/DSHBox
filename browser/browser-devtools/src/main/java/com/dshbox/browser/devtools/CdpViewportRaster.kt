package com.dshbox.browser.devtools

import kotlin.math.ceil

/** Android CDP rounds native view bounds to integral DIPs before rasterizing them. */
object CdpViewportRaster {
    fun matchesNativeViewport(nativeWidth: Int, nativeHeight: Int, width: Int, height: Int, density: Float): Boolean {
        if (nativeWidth <= 0 || nativeHeight <= 0 || width <= 0 || height <= 0 ||
            !density.isFinite() || density <= 0 || density > 16) return false
        val remainder = ceil(density.toDouble()).toInt() - 1
        return nativeWidth.toLong() - width in -remainder.toLong()..remainder.toLong() &&
            nativeHeight.toLong() - height in -remainder.toLong()..remainder.toLong()
    }
}
