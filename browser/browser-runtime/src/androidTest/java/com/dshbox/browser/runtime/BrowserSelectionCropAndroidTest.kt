package com.dshbox.browser.runtime

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserSelectionCropAndroidTest {
    @Test fun fullFramePreviewAndSavedCropDoNotRecycleEachOtherOrTheFrozenFrame() {
        val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff123456.toInt())
        val bounds = RectF(0f, 0f, 32f, 24f)
        val preview = requireNotNull(BrowserSelectionCrop.create(source, bounds))
        val saved = requireNotNull(BrowserSelectionCrop.create(source, bounds))
        try {
            assertNotSame(source, preview)
            assertNotSame(source, saved)
            assertNotSame(preview, saved)
            preview.recycle()
            source.recycle()
            assertFalse(saved.isRecycled)
            assertEquals(0xff123456.toInt(), saved.getPixel(0, 0))
        } finally {
            preview.recycle()
            saved.recycle()
            source.recycle()
        }
    }
}
