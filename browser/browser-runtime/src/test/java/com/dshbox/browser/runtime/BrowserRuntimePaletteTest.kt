package com.dshbox.browser.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BrowserRuntimePaletteTest {
    @Test
    fun constructorPaletteNeedsNoAndroidContextAndKeepsReadableText() {
        for (dark in listOf(false, true)) {
            val palette = BrowserRuntimePalette.create(dark)
            assertTrue(palette.paper ushr 24 == 255)
            assertTrue(palette.ink ushr 24 == 255)
            val background = luminance(palette.paper)
            val foreground = luminance(palette.ink)
            val contrast = (maxOf(background, foreground) + 0.05) /
                (minOf(background, foreground) + 0.05)
            assertTrue("Browser text must remain readable", contrast >= 4.5)
        }
    }

    private fun luminance(color: Int): Double {
        fun component(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * component(16) + 0.7152 * component(8) + 0.0722 * component(0)
    }
}
