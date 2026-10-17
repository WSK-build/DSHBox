package com.dshbox.browser.runtime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dshbox.app.common.R as CommonR
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/** Browser-owned human panel. All operations are delegated through the serialized controller. */
internal class BrowserDevToolsPanel(
    context: Context,
    private val networkMutationEnabled: Boolean,
    private val execute: (String, JSONObject, (JSONObject) -> Unit) -> Unit,
    private val onResult: (JSONObject) -> Unit,
    private val onEntrySelected: (JSONObject) -> Unit,
    private val onReplayApprovals: () -> Unit,
) : LinearLayout(context) {
    private val palette = BrowserRuntimePalette.resolve(context)
    private val networkRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val enabledBeforeBusy = IdentityHashMap<View, Boolean>()
    private val entryId = EditText(context).apply {
        hint = context.getString(R.string.browser_network_request_id_hint)
        setSingleLine(true)
        setTextColor(palette.ink)
        setHintTextColor(palette.muted)
    }
    private val pausedId = EditText(context).apply {
        hint = context.getString(R.string.browser_network_request_id_hint)
        setSingleLine(true)
        setTextColor(palette.ink)
        setHintTextColor(palette.muted)
    }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setBackgroundColor(palette.paper)
        addView(note(R.string.browser_advanced_help))
        buildDebugSection()
        buildNetworkSection()
    }

    fun setBusy(busy: Boolean) {
        if (busy) {
            if (enabledBeforeBusy.isNotEmpty()) return
            rememberAndDisable(this)
        } else {
            enabledBeforeBusy.forEach { (view, wasEnabled) -> view.isEnabled = wasEnabled }
            enabledBeforeBusy.clear()
        }
    }

    private fun rememberAndDisable(view: View) {
        enabledBeforeBusy[view] = view.isEnabled
        view.isEnabled = false
        if (view is ViewGroup) for (index in 0 until view.childCount) rememberAndDisable(view.getChildAt(index))
    }

    private fun buildDebugSection() {
        addView(section(R.string.browser_advanced_section_connection, R.string.browser_advanced_section_connection_note))
        addView(row(R.string.browser_debug_connection_status, CommonR.drawable.ic_adjustments_horizontal) {
            execute("debug", JSONObject().put("action", "status"), onResult)
        })
        addView(section(R.string.browser_advanced_section_page, R.string.browser_advanced_section_page_note))
        addView(row(R.string.browser_debug_console, CommonR.drawable.ic_file) {
            execute("debug", JSONObject().put("action", "console").put("limit", 20), onResult)
        })
        val selector = EditText(context).apply {
            hint = context.getString(R.string.browser_advanced_dom_selector)
            setSingleLine(true)
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        addView(selector)
        addView(row(R.string.browser_debug_dom, CommonR.drawable.ic_search) {
            val args = JSONObject().put("action", "dom")
            selector.text.toString().trim().takeIf(String::isNotEmpty)?.let { args.put("selector", it) }
            execute("debug", args, onResult)
        })
        val expression = EditText(context).apply {
            hint = context.getString(R.string.browser_debug_expression_hint)
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        addView(expression)
        addView(row(R.string.browser_debug_evaluate, CommonR.drawable.ic_file) {
            execute("debug", JSONObject().put("action", "evaluate").put("expression", expression.text.toString()), onResult)
        })
        addView(row(R.string.browser_debug_emulate, CommonR.drawable.ic_adjustments_horizontal) {
            execute("debug", JSONObject().put("action", "emulate").put("width", 390).put("height", 844).put("mobile", true), onResult)
        })
        addView(row(R.string.browser_debug_emulate_clear, CommonR.drawable.ic_refresh) {
            execute("debug", JSONObject().put("action", "emulate").put("clear", true), onResult)
        })
    }

    private fun buildNetworkSection() {
        addView(section(R.string.browser_advanced_section_network, R.string.browser_advanced_section_network_note))
        val includeBodies = CheckBox(context).apply {
            text = context.getText(R.string.browser_network_include_bodies)
            isChecked = false
            setTextColor(palette.ink)
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.blue)
        }
        val includeRequestBodies = CheckBox(context).apply {
            text = context.getText(R.string.browser_network_request_bodies)
            setTextColor(palette.ink)
        }
        addView(includeBodies)
        addView(includeRequestBodies)
        addView(note(R.string.browser_network_request_bodies_note))
        addView(row(R.string.browser_network_start, CommonR.drawable.ic_upload) {
            execute("network", JSONObject().put("action", "start").put("includeBodies", includeBodies.isChecked)
                .put("includeRequestBodies", includeRequestBodies.isChecked), onResult)
        })
        addView(row(R.string.browser_network_stop, CommonR.drawable.ic_x) {
            execute("network", JSONObject().put("action", "stop"), onResult)
        })
        addView(row(R.string.browser_network_list, CommonR.drawable.ic_list) { refreshNetworkList() })
        addView(entryId)
        addView(row(R.string.browser_network_body, CommonR.drawable.ic_file) {
            execute("network", JSONObject().put("action", "body").put("entryId", entryId.text.toString().trim()), onResult)
        })
        addView(row(R.string.browser_network_har, CommonR.drawable.ic_download) {
            execute("network", JSONObject().put("action", "har").put("includeBodies", includeBodies.isChecked), onResult)
        })
        addView(row(R.string.browser_network_export_frames, CommonR.drawable.ic_download) {
            execute("network", JSONObject().put("action", "export_frames").put("includeBodies", includeBodies.isChecked), onResult)
        })
        addView(row(R.string.browser_network_replay_approvals, CommonR.drawable.ic_check, onReplayApprovals))
        addView(section(R.string.browser_advanced_section_intercept, R.string.browser_advanced_section_intercept_note))
        addView(mutationRow(R.string.browser_network_replay, CommonR.drawable.ic_refresh) {
            if (networkMutationEnabled) {
                execute("network", JSONObject().put("action", "replay").put("entryId", entryId.text.toString().trim()), onResult)
            }
        })
        val rules = EditText(context).apply {
            hint = context.getString(R.string.browser_network_rules_hint)
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        addView(rules)
        addView(mutationRow(R.string.browser_network_rules, CommonR.drawable.ic_adjustments_horizontal) {
            val parsed = runCatching { JSONArray(rules.text.toString()) }.getOrNull()
            if (parsed == null) onResult(errorResult("INVALID_ARGUMENT"))
            else if (networkMutationEnabled) execute("network", JSONObject().put("action", "rules").put("rules", parsed), onResult)
        })
        addView(mutationRow(R.string.browser_network_clear_rules, CommonR.drawable.ic_trash) {
            if (networkMutationEnabled) execute("network", JSONObject().put("action", "clear_rules"), onResult)
        })
        addView(row(R.string.browser_network_paused, CommonR.drawable.ic_alert_triangle) {
            refreshPausedRequests()
        })
        addView(networkRows)
        addView(pausedId)
        addView(mutationRow(R.string.browser_network_continue, CommonR.drawable.ic_check) {
            resolvePaused("continue")
        })
        addView(mutationRow(R.string.browser_network_block, CommonR.drawable.ic_x) {
            resolvePaused("block")
        })
    }

    private fun refreshNetworkList() {
        execute("network", JSONObject().put("action", "list").put("limit", 20)) { result ->
            if (result.optString("code") != "OK") {
                onResult(result)
                return@execute
            }
            val data = result.optJSONObject("data") ?: JSONObject()
            val entries = data.optJSONArray("entries") ?: JSONArray()
            showNetworkEntries(entries)
        }
    }

    private fun showNetworkEntries(entries: JSONArray) {
            networkRows.removeAllViews()
            if (entries.length() == 0) networkRows.addView(note(R.string.browser_network_empty))
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val request = entry.optJSONObject("request") ?: JSONObject()
                val response = entry.optJSONObject("response") ?: JSONObject()
                val duration = if (entry.isNull("durationMillis")) context.getString(R.string.browser_network_duration_pending)
                    else "${entry.optLong("durationMillis")} ms"
                val title = "${request.optString("method")}  ${response.optInt("status")}  $duration\n${request.optString("url")}"
                networkRows.addView(TextView(context).apply {
                    text = title
                    textSize = 12f
                    setTextColor(palette.ink)
                    maxLines = 3
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                    background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
                        val drawable = a.getDrawable(0)
                        a.recycle()
                        drawable
                    }
                    setOnClickListener {
                        entryId.setText(entry.optString("entryId"))
                        onEntrySelected(entry)
                    }
                })
            }
    }

    private fun refreshPausedRequests() {
        execute("network", JSONObject().put("action", "paused")) { result ->
            if (result.optString("code") != "OK") {
                onResult(result)
                return@execute
            }
            val requests = result.optJSONObject("data")?.optJSONArray("requests") ?: JSONArray()
            networkRows.removeAllViews()
            if (requests.length() == 0) networkRows.addView(note(R.string.browser_network_empty))
            for (index in 0 until requests.length()) {
                val request = requests.optJSONObject(index) ?: continue
                networkRows.addView(TextView(context).apply {
                    text = "${request.optString("method")}  ${request.optString("stage")}\n${request.optString("url")}"
                    textSize = 12f
                    setTextColor(palette.ink)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                    setOnClickListener { pausedId.setText(request.optString("pausedId")) }
                })
            }
        }
    }

    private fun resolvePaused(decision: String) {
        if (!networkMutationEnabled) return
        execute("network", JSONObject()
            .put("action", "resolve")
            .put("pausedId", pausedId.text.toString().trim())
            .put("decision", decision), onResult)
    }

    private fun mutationRow(label: Int, icon: Int, onClick: () -> Unit): View =
        row(label, icon, onClick).apply {
            isEnabled = networkMutationEnabled
            alpha = if (networkMutationEnabled) 1f else 0.45f
        }

    private fun row(label: Int, icon: Int, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        minimumHeight = dp(44)
        isClickable = true
        isFocusable = true
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
            val drawable = a.getDrawable(0)
            a.recycle()
            drawable
        }
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = context.getText(label)
            textSize = 13f
            setTextColor(palette.ink)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(palette.muted)
            contentDescription = this@BrowserDevToolsPanel.context.getString(label)
            layoutParams = LayoutParams(dp(18), dp(18))
        })
    }

    private fun note(label: Int): View = TextView(context).apply {
        text = context.getText(label)
        textSize = 12f
        setTextColor(palette.muted)
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }

    private fun section(title: Int, description: Int): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, dp(2))
        addView(TextView(context).apply {
            text = context.getText(title)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.ink)
            setPadding(dp(4), dp(3), dp(4), dp(2))
        })
        addView(note(description))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun errorResult(code: String) = JSONObject()
        .put("code", code).put("executed", false).put("data", JSONObject())

}
