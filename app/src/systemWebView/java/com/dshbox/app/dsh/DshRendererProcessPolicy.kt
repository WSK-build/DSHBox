package com.dshbox.app.dsh

import android.content.Context

/** The System renderer declares no additional Application processes. */
internal object DshRendererProcessPolicy {
    fun isRendererProcess(context: Context, processName: String): Boolean = false
}
