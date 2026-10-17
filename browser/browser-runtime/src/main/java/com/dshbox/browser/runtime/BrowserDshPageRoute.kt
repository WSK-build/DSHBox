package com.dshbox.browser.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.dshbox.browser.api.BrowserContract

/** Builds the explicit route from Browser to the app's existing DSH page. */
object BrowserDshPageRoute {
    fun createIntent(context: Context): Intent = Intent().apply {
        component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS)
        putExtra(BrowserContract.EXTRA_OPEN_DSH_PAGE, true)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )
    }

    private const val MAIN_ACTIVITY_CLASS = "com.dshbox.app.MainActivity"
}
