package com.dshbox.browser.runtime

import android.graphics.drawable.GradientDrawable

/** Rounded Browser chrome surface whose fill and outline follow the active palette. */
internal class BrowserThemedGradientDrawable(
    private var fillColor: Int,
    private var outlineColor: Int,
    private val radiusPx: Float,
    private val outlineWidthPx: Int,
) : GradientDrawable() {
    init {
        shape = RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        setStroke(outlineWidthPx, outlineColor)
    }

    fun remapColors(remap: (Int) -> Int) {
        fillColor = remap(fillColor)
        outlineColor = remap(outlineColor)
        setColor(fillColor)
        setStroke(outlineWidthPx, outlineColor)
    }
}
