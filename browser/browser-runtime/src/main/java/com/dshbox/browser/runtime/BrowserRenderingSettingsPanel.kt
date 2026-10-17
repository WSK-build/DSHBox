package com.dshbox.browser.runtime

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/** Native rendering controls report an intended change; the caller owns control transfer and persistence. */
internal object BrowserRenderingSettingsPanel {
    fun addTo(body: LinearLayout, context: Context, ink: Int, settings: BrowserRenderingSettings,
        change: (String, Any) -> Unit) {
        fun label(resource: Int) = TextView(context).apply { setText(resource); setTextColor(ink); textSize = 14f }
        fun choices(title: Int, key: String, current: String, options: List<Pair<String, Int>>) {
            body.addView(label(title))
            val group = RadioGroup(context)
            var checkedId = View.NO_ID
            options.forEach { (value, resource) ->
                val option = RadioButton(context).apply {
                    id = View.generateViewId(); setText(resource); setTextColor(ink); tag = value
                    setOnClickListener {
                        if (current != value) change(key, value)
                    }
                }
                // Children are added before any selection is applied.
                group.addView(option)
                if (current == value) checkedId = option.id
            }
            // The stored selection is committed through the group once every child exists, so the
            // group's tracked selection and the child state are established together.
            if (checkedId != View.NO_ID) group.check(checkedId)
            body.addView(group)
        }
        choices(R.string.browser_rendering_user_agent, BrowserRenderingSettings.KEY_USER_AGENT_MODE,
            settings.userAgentMode.wireValue, listOf("default" to R.string.browser_rendering_default,
                "mobile" to R.string.browser_rendering_mobile, "desktop" to R.string.browser_rendering_desktop))
        choices(R.string.browser_rendering_layout, BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE,
            settings.layoutWidthMode.wireValue, listOf("device" to R.string.browser_rendering_device,
                "wide" to R.string.browser_rendering_wide))
        body.addView(CheckBox(context).apply {
            setText(R.string.browser_rendering_overview); setTextColor(ink); isChecked = settings.overviewMode
            setOnClickListener {
                if (isChecked != settings.overviewMode) {
                    change(BrowserRenderingSettings.KEY_OVERVIEW_MODE, isChecked)
                }
            }
        })
        val textLabel = label(R.string.browser_rendering_text_scale)
        textLabel.text = context.getString(R.string.browser_rendering_text_scale, settings.textScalePercent)
        body.addView(textLabel)
        body.addView(SeekBar(context).apply {
            max = 150; progress = settings.textScalePercent - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    textLabel.text = context.getString(R.string.browser_rendering_text_scale, progress + 50)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val intended = seekBar.progress + 50
                    if (intended != settings.textScalePercent) change(BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, intended)
                }
            })
        })
    }
}
