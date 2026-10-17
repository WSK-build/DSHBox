package com.dshbox.browser.devtools

import org.junit.Assert.*
import org.junit.Test

class CdpViewportRasterTest {
    @Test fun fractionalDipRemainderIsAcceptedWithoutChangingImageDimensions() {
        assertTrue(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2695, 3.5f))
        assertTrue(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2696, 3.5f))
        assertTrue(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2693, 3.5f))
        assertTrue(CdpViewportRaster.matchesNativeViewport(1080, 2400, 1082, 2402, 2.625f))
        assertTrue(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2699, 3.5f))
    }

    @Test fun fullDipDifferenceAndInvalidDensityAreRejected() {
        assertFalse(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2692, 3.5f))
        assertFalse(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1264, 2696, 3.5f))
        assertFalse(CdpViewportRaster.matchesNativeViewport(1080, 2400, 1083, 2400, 2.625f))
        assertFalse(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2695, 1f))
        assertFalse(CdpViewportRaster.matchesNativeViewport(1260, 2696, 1260, 2695, Float.NaN))
        assertFalse(CdpViewportRaster.matchesNativeViewport(1260, 2696, 0, 2695, 3.5f))
    }
}
