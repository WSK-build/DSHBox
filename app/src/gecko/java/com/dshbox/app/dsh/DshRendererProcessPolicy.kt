package com.dshbox.app.dsh

import android.content.Context

/** Exact service process suffixes from the pinned GeckoView AAR manifest. */
internal object DshRendererProcessPolicy {
    fun isRendererProcess(context: Context, processName: String): Boolean {
        val packageName = context.packageName
        if (processName == packageName) return false
        return context.assets.open("dsh-renderer/gecko-processes.txt")
            .bufferedReader(Charsets.UTF_8).useLines { suffixes ->
                suffixes.any { suffix ->
                    suffix.startsWith(":") && processName == packageName + suffix
                }
            }
    }
}
