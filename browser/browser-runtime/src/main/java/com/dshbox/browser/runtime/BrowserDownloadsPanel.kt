package com.dshbox.browser.runtime

import android.app.Dialog
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.dshbox.app.common.R as CommonR
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Small native presentation for the Browser-owned download manager.
 *
 * All user-facing text is supplied by the caller so the panel does not duplicate localization
 * policy. Call [show] and [update] on the main thread; callbacks are handed back to the caller,
 * which owns authorization and manager-thread dispatch.
 */
internal class BrowserDownloadsPanel(
    private val context: Activity,
    private val labels: Labels,
    private val onCancel: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onLoadMore: (String?) -> Unit,
) {
    data class Labels(
        val title: String,
        val close: String,
        val loading: String,
        val empty: String,
        val loadMore: String,
        val queued: String,
        val downloading: String,
        val cancelling: String,
        val completed: String,
        val failed: String,
        val cancelled: String,
        val cancel: String,
        val save: String,
        val share: String,
        val delete: String,
        val bytesUnknown: String,
        val failedDetail: (String) -> String,
    )

    private val dialog = Dialog(context)
    private val density = context.resources.displayMetrics.density
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        setBackgroundColor(Color.WHITE)
    }
    private val closeButton = ImageButton(context).apply {
        setImageResource(CommonR.drawable.ic_x)
        setColorFilter(INK)
        contentDescription = labels.close
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { dismiss() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }
    private val shell by lazy {
        BrowserManagementPanel.create(context, labels.title, closeButton).also { result ->
            result.content.addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        }
    }
    private var latestPage = BrowserDownloadPage(emptyList(), null, false)
    private var loading = false

    init {
        dialog.setOnDismissListener { loading = false }
    }

    fun show(page: BrowserDownloadPage, isLoading: Boolean = false) {
        if (context.isFinishing || context.isDestroyed) return
        latestPage = page
        loading = isLoading
        render()
        if (!dialog.isShowing) {
            BrowserManagementPanel.show(context, dialog, shell.root)
        }
    }

    fun update(page: BrowserDownloadPage, isLoading: Boolean = false) {
        latestPage = page
        loading = isLoading
        if (dialog.isShowing) render()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing

    private fun render() {
        body.removeAllViews()
        val scroll = ScrollView(body.context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val rows = LinearLayout(body.context).apply { orientation = LinearLayout.VERTICAL }
        when {
            loading && latestPage.items.isEmpty() -> rows.addView(note(labels.loading))
            latestPage.items.isEmpty() -> rows.addView(note(labels.empty))
            else -> latestPage.items.forEach { rows.addView(downloadRow(it)) }
        }
        if (latestPage.nextCursor != null) {
            rows.addView(button(labels.loadMore, enabled = !loading) {
                loading = true
                render()
                onLoadMore(latestPage.nextCursor)
            })
        } else if (loading) {
            rows.addView(note(labels.loading))
        }
        scroll.addView(rows)
        body.addView(scroll)
    }

    private fun downloadRow(item: BrowserDownloadItem): View {
        val row = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(SOFT, dp(10).toFloat())
        }
        val top = LinearLayout(body.context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(body.context).apply {
            text = item.fileName ?: item.id
            textSize = 14f
            setTextColor(INK)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val canCancel = item.state == BrowserDownloadState.QUEUED ||
            item.state == BrowserDownloadState.DOWNLOADING
        if (canCancel) {
            top.addView(button(labels.cancel) { onCancel(item.id) })
        }
        row.addView(top)
        row.addView(TextView(body.context).apply {
            text = statusText(item)
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(5))
        })
        if (item.state == BrowserDownloadState.QUEUED || item.state == BrowserDownloadState.DOWNLOADING) {
            val progress = ProgressBar(body.context, null, android.R.attr.progressBarStyleHorizontal)
            val total = item.totalBytes
            if (total != null && total > 0L) {
                progress.isIndeterminate = false
                progress.max = 1000
                progress.progress = ((item.bytesReceived.coerceAtMost(total) * 1000L) / total).toInt()
            } else {
                progress.isIndeterminate = true
            }
            row.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        }
        item.errorCode?.takeIf { item.state == BrowserDownloadState.FAILED }?.let { code ->
            row.addView(TextView(body.context).apply {
                text = labels.failedDetail(code)
                textSize = 12f
                setTextColor(ERROR)
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (item.state == BrowserDownloadState.COMPLETED && item.fileName != null) {
            val actions = LinearLayout(body.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(2), 0, 0)
            }
            actions.addView(button(labels.save) { onSave(item.id) })
            actions.addView(button(labels.share) { onShare(item.id) })
            actions.addView(button(labels.delete) { onDelete(item.id) })
            row.addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val container = LinearLayout(body.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(body.context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(7)) })
        }
        return container
    }

    private fun statusText(item: BrowserDownloadItem): String {
        val state = when (item.state) {
            BrowserDownloadState.QUEUED -> labels.queued
            BrowserDownloadState.DOWNLOADING -> labels.downloading
            BrowserDownloadState.CANCELLING -> labels.cancelling
            BrowserDownloadState.COMPLETED -> labels.completed
            BrowserDownloadState.FAILED -> labels.failed
            BrowserDownloadState.CANCELLED -> labels.cancelled
        }
        val size = item.totalBytes?.takeIf { it >= 0L }?.let { total ->
            "${formatBytes(item.bytesReceived)} / ${formatBytes(total)}"
        } ?: "${formatBytes(item.bytesReceived)} / ${labels.bytesUnknown}"
        return "$state · $size"
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < 1024L -> "$value B"
            value < 1024L * 1024L -> "${value / 1024L} KB"
            else -> "${value / (1024L * 1024L)} MB"
        }
    }

    private fun button(text: String, enabled: Boolean = true, action: () -> Unit) = TextView(body.context).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (enabled) INK else MUTED)
        isEnabled = enabled
        isClickable = enabled
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(if (enabled) SOFT else DISABLED, dp(8).toFloat())
        setOnClickListener { if (enabled) action() }
    }

    private fun note(text: String) = TextView(body.context).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(26), dp(8), dp(26))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val INK = 0xff28292d.toInt()
        const val MUTED = 0xff777980.toInt()
        const val LINE = 0xffe2e3e6.toInt()
        const val SOFT = 0xfff5f6f7.toInt()
        const val DISABLED = 0xffeeeeef.toInt()
        const val ERROR = 0xffb3261e.toInt()
    }
}
