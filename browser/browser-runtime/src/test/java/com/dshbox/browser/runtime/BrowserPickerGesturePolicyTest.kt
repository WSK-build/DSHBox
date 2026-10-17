package com.dshbox.browser.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPickerGesturePolicyTest {
    @Test
    fun elementTapStaysInPicker() {
        assertFalse(
            BrowserPickerGesturePolicy.shouldForward(
                elementMode = true,
                pointerDown = false,
                deltaX = 3f,
                deltaY = 4f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun elementDragBeyondSlopScrollsPage() {
        assertTrue(
            BrowserPickerGesturePolicy.shouldForward(
                elementMode = true,
                pointerDown = false,
                deltaX = 0f,
                deltaY = 9f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun areaDragRemainsASelection() {
        assertFalse(
            BrowserPickerGesturePolicy.shouldForward(
                elementMode = false,
                pointerDown = false,
                deltaX = 24f,
                deltaY = 48f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun pinchForwardsInBothPickerModes() {
        for (elementMode in listOf(true, false)) {
            assertTrue(
                BrowserPickerGesturePolicy.shouldForward(
                    elementMode = elementMode,
                    pointerDown = true,
                    deltaX = 0f,
                    deltaY = 0f,
                    touchSlop = 8f,
                ),
            )
        }
    }
}
