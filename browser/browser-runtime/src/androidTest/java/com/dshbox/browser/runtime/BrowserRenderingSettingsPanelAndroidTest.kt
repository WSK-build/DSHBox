package com.dshbox.browser.runtime

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real native rendering controls through production click handling.
 *
 * The panel only reports an intended change; authorizing, persisting and applying it belong to the
 * caller. Rejection is rendered by the caller rebuilding the panel, so these cases pin the exact
 * key/value handed over and the widget state the user is left looking at.
 *
 * Instrumentation tests run off the main thread, so every view construction, interaction and
 * inspection goes through {@link #onMain}.
 */
@RunWith(AndroidJUnit4::class)
class BrowserRenderingSettingsPanelAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun initialSettingsShowExactlyOneOptionPerGroup() {
        val requests = mutableListOf<Pair<String, Any>>()
        val body = newBody()
        onMain {
            BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings()) { key, value ->
                requests += key to value
            }
            layout(body)
        }

        val state = readState(body)
        assertEquals(listOf("default", "mobile", "desktop"), state.agentTags)
        assertEquals(listOf("device", "wide"), state.layoutTags)
        assertEquals(listOf("default"), state.agentChecked)
        assertEquals(listOf("device"), state.layoutChecked)
        assertFalse(state.overviewChecked)
        assertEquals(50, state.progress)
        assertTrue(requests.isEmpty())
        val trackedSelection = onMainValue {
            val agent = radioGroups(body)[0]
            val layoutGroup = radioGroups(body)[1]
            listOf(
                agent.checkedRadioButtonId == children(agent).filterIsInstance<RadioButton>().first { it.tag == "default" }.id,
                layoutGroup.checkedRadioButtonId == children(layoutGroup).filterIsInstance<RadioButton>().first { it.tag == "device" }.id,
            )
        }
        assertEquals(listOf(true, true), trackedSelection)
    }

    @Test
    fun everyAgentModeReportsItsKeyAndValueAndChecksItsOwnOption() {
        listOf("mobile", "desktop").forEach { value ->
            val requests = mutableListOf<Pair<String, Any>>()
            val body = newBody()
            onMain {
                BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings()) { key, requested ->
                    requests += key to requested
                }
                layout(body)
                clickOption(body, value)
            }

            assertEquals(listOf(BrowserRenderingSettings.KEY_USER_AGENT_MODE to value), requests)
            assertEquals(listOf(value), readState(body).agentChecked)
        }
    }

    @Test
    fun reSelectingTheShownModeReportsNoChange() {
        val requests = mutableListOf<Pair<String, Any>>()
        val body = newBody()
        onMain {
            BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings()) { key, value ->
                requests += key to value
            }
            layout(body)
            clickOption(body, "default")
        }

        assertTrue(requests.isEmpty())
        assertEquals(listOf("default"), readState(body).agentChecked)
    }

    @Test
    fun layoutAndOverviewReportTheirOwnKeyAndValue() {
        val requests = mutableListOf<Pair<String, Any>>()
        val body = newBody()
        onMain {
            BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings()) { key, value ->
                requests += key to value
            }
            layout(body)
            clickOption(body, "wide")
            clickOverview(body)
        }

        assertEquals(
            listOf(
                BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE to "wide",
                BrowserRenderingSettings.KEY_OVERVIEW_MODE to true,
            ),
            requests,
        )
        val state = readState(body)
        assertEquals(listOf("wide"), state.layoutChecked)
        assertTrue(state.overviewChecked)
    }

    @Test
    fun textScaleReportsTheValueAtTouchReleaseAndKeepsIt() {
        val initialPercent = 120
        val requests = mutableListOf<Pair<String, Any>>()
        val body = newBody()
        val percentAtRelease = arrayOf(0)
        onMain {
            BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings(textScalePercent = initialPercent)) { key, value ->
                requests += key to value
            }
            layout(body)
            val bar = textScaleBar(body)
            val destination = bar.xForProgress(90)
            bar.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, destination, bar))
            percentAtRelease[0] = bar.progress + 50
            bar.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, destination, bar))
        }

        assertEquals(listOf(BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT), requests.map { it.first })
        assertEquals(percentAtRelease[0], requests.single().second as Int)
        assertTrue("percentAtRelease=${percentAtRelease[0]}", percentAtRelease[0] in 50..200)
        assertEquals(percentAtRelease[0] - 50, readState(body).progress)
    }

    @Test
    fun aCallerThatRejectsKeepsTheRequestedValueUntilItRebuilds() {
        var decisions = 0
        val body = newBody()
        onMain {
            BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingSettings()) { _, _ ->
                // Rejected without a rebuild: the panel is left on what the user asked for.
                decisions++
            }
            layout(body)
            clickOption(body, "desktop")
            clickOverview(body)
        }

        assertEquals(2, decisions)
        val state = readState(body)
        assertEquals(listOf("desktop"), state.agentChecked)
        assertTrue(state.overviewChecked)
    }

    @Test
    fun callerRebuildWithTheRejectedValueRestoresTheShownValue() {
        val root = onMainValue { FrameLayout(context) }
        var requests = 0
        val current = BrowserRenderingSettings()
        lateinit var rejected: (String, Any) -> Unit
        rejected = { _, _ ->
            requests++
            // The rejected value is discarded: the caller rebuilds from its own model.
            render(root, current, rejected)
        }
        onMain { render(root, current, rejected) }

        onMain { clickOption(agentGroup(root), "desktop") }

        assertEquals(1, requests)
        assertEquals(listOf("default"), readState(root).agentChecked)
    }

    @Test
    fun callerRebuildWithTheAcceptedValueKeepsSwitching() {
        val root = onMainValue { FrameLayout(context) }
        var requests = 0
        var current = BrowserRenderingSettings()
        lateinit var accepted: (String, Any) -> Unit
        accepted = { key, value ->
            requests++
            current = current.set(key, value).settings
            render(root, current, accepted)
        }
        onMain { render(root, current, accepted) }

        onMain { clickOption(agentGroup(root), "desktop") }
        assertEquals(1, requests)
        assertEquals("desktop", current.userAgentMode.wireValue)
        assertEquals(listOf("desktop"), readState(root).agentChecked)

        onMain { clickOption(agentGroup(root), "mobile") }
        assertEquals(2, requests)
        assertEquals("mobile", current.userAgentMode.wireValue)
        assertEquals(listOf("mobile"), readState(root).agentChecked)
    }

    private data class BodyState(
        val agentTags: List<String>,
        val layoutTags: List<String>,
        val agentChecked: List<String>,
        val layoutChecked: List<String>,
        val overviewChecked: Boolean,
        val progress: Int,
    )

    private fun newBody(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun render(root: FrameLayout, settings: BrowserRenderingSettings, change: (String, Any) -> Unit) {
        root.removeAllViews()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        BrowserRenderingSettingsPanel.addTo(body, context, 0, settings, change)
        root.addView(body)
        layout(root)
    }

    private fun layout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 1920)
    }

    private fun readState(body: ViewGroup): BodyState = onMainValue {
        val agent = radioGroups(body)[0]
        val layoutGroup = radioGroups(body)[1]
        BodyState(
            agentTags = tags(agent),
            layoutTags = tags(layoutGroup),
            agentChecked = checkedTags(agent),
            layoutChecked = checkedTags(layoutGroup),
            overviewChecked = overviewBox(body).isChecked,
            progress = textScaleBar(body).progress,
        )
    }

    private fun clickOption(holder: ViewGroup, value: String) {
        (if (holder is RadioGroup) listOf(holder) else children(holder).filterIsInstance<RadioGroup>())
            .flatMap { children(it).filterIsInstance<RadioButton>() }
            .first { it.tag == value }
            .performClick()
    }

    private fun clickOverview(holder: ViewGroup) {
        overviewBox(holder).performClick()
    }

    private fun motion(action: Int, x: Float, bar: SeekBar): MotionEvent =
        MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            action,
            x,
            bar.height / 2f,
            0,
        )

    private fun SeekBar.xForProgress(value: Int): Float =
        (width - paddingLeft - paddingRight).toFloat() * (value - min) / (max - min).coerceAtLeast(1) + paddingLeft

    private fun radioGroups(holder: ViewGroup): List<RadioGroup> = children(holder).filterIsInstance<RadioGroup>()

    private fun agentGroup(holder: ViewGroup): RadioGroup = radioGroups(holder)[0]

    private fun overviewBox(holder: ViewGroup): CheckBox = children(holder).filterIsInstance<CheckBox>().single()

    private fun textScaleBar(holder: ViewGroup): SeekBar = children(holder).filterIsInstance<SeekBar>().single()

    private fun tags(group: RadioGroup): List<String> =
        children(group).filterIsInstance<RadioButton>().map { it.tag as String }

    private fun checkedTags(group: RadioGroup): List<String> =
        children(group).filterIsInstance<RadioButton>().filter { it.isChecked }.map { it.tag as String }

    private fun children(group: ViewGroup): List<View> = (0 until group.childCount).flatMap {
        val child = group.getChildAt(it)
        listOf(child) + if (child is ViewGroup) children(child) else emptyList()
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun <T> onMainValue(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                holder[0] = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }
}
