package com.dshbox.browser.runtime

/** Rejects picker-frame callbacks that belong to an older gesture or picker session. */
internal object BrowserCaptureRequestGate {
    fun isCurrent(
        requestGeneration: Long,
        latestRequestGeneration: Long,
        pickerGeneration: Long,
        activePickerGeneration: Long,
        overlayPresent: Boolean,
    ): Boolean = overlayPresent &&
        requestGeneration == latestRequestGeneration &&
        pickerGeneration == activePickerGeneration
}
