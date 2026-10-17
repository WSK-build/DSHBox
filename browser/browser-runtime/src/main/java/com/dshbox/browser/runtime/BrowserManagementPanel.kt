package com.dshbox.browser.runtime

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Shared full-height shell for Browser-owned management panels. */
internal object BrowserManagementPanel {
    data class Shell(val root: LinearLayout, val content: LinearLayout)

    fun create(
        activity: Activity,
        title: CharSequence,
        closeButton: View,
        headerAction: View? = null,
    ): Shell {
        val density = activity.resources.displayMetrics.density
        val palette = BrowserRuntimePalette.resolve(activity)
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.paper)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            minimumHeight = dp(56)
        }
        header.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(palette.ink)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (headerAction != null) header.addView(headerAction)
        header.addView(closeButton)
        root.addView(header)
        root.addView(View(activity).apply {
            setBackgroundColor(palette.line)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
        return Shell(root, content)
    }

    fun show(activity: Activity, dialog: Dialog, root: View) {
        dialog.setContentView(root)
        dialog.show()
        val density = activity.resources.displayMetrics.density
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val width = if (screenWidth <= 480 * density) ViewGroup.LayoutParams.MATCH_PARENT
        else minOf((520 * density).toInt(), screenWidth - (32 * density).toInt())
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(BrowserRuntimePalette.resolve(activity).paper))
            setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.18f }
        }
    }

}
