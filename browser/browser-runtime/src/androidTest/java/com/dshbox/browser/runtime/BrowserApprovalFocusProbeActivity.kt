package com.dshbox.browser.runtime

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class BrowserApprovalFocusProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Focus probe" })
    }
}
