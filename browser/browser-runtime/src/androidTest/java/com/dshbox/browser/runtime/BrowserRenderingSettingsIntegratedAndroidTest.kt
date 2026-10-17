package com.dshbox.browser.runtime

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
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
 * Exercises the full rendering-policy chain: preference write/read, WebView application and a
 * panel rebuilt from the persisted values.
 *
 * Preferences are confined to a test-only file inside the instrumented package; the production
 * preference name is never touched.
 */
@RunWith(AndroidJUnit4::class)
class BrowserRenderingSettingsIntegratedAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyRenderingSwitchPersistsAndReachesALiveWebView() {
        val preferences = context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
        val agent = arrayOfNulls<WebView>(1)
        try {
            lateinit var body: LinearLayout
            onMain {
                preferences.edit().clear().commit()
                val view = BrowserPageFactory(
                    context = context,
                    renderingSettings = { BrowserRenderingPreferences.read(preferences) },
                    themePolicy = { BrowserWebThemePolicy(false, 0xFFFFFFFF.toInt()) },
                ).create()
                agent[0] = view
                body = newBody()
                lateinit var render: () -> Unit
                render = {
                    body.removeAllViews()
                    BrowserRenderingSettingsPanel.addTo(
                    body,
                    context,
                    0,
                    BrowserRenderingPreferences.read(preferences),
                ) { key, value ->
                    val change = BrowserRenderingPreferences.read(preferences).update(key, value, revision(preferences))
                    assertTrue(BrowserRenderingPreferences.write(preferences, change.settings))
                    BrowserPageFactory(context, { BrowserRenderingPreferences.read(preferences) }, { BrowserWebThemePolicy(false, 0xFFFFFFFF.toInt()) })
                        .applyRendering(view, BrowserRenderingPreferences.read(preferences))
                    render()
                }
                layout(body)
                }
                render()
            }
            val webView = requireNotNull(agent[0])
            val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
            val desktopUserAgent = BrowserDesktopUserAgent.from(defaultUserAgent)

            onMain { clickOption(body, "desktop") }
            onMain { clickOption(body, "wide") }
            onMain { clickOverview(body) }
            onMain { setTextScale(body, 200) }

            val stored = BrowserRenderingPreferences.read(preferences)
            assertEquals(BrowserRenderingSettings.UserAgentMode.DESKTOP, stored.userAgentMode)
            assertEquals(BrowserRenderingSettings.LayoutWidthMode.WIDE, stored.layoutWidthMode)
            assertTrue(stored.overviewMode)
            assertEquals(200, stored.textScalePercent)
            onMain {
                assertEquals(desktopUserAgent, webView.settings.userAgentString)
                assertTrue(webView.settings.useWideViewPort)
                assertTrue(webView.settings.loadWithOverviewMode)
                assertEquals(200, webView.settings.textZoom)
            }

            onMain { clickOption(body, "mobile") }
            onMain { clickOption(body, "device") }

            val backToMobile = BrowserRenderingPreferences.read(preferences)
            assertEquals(BrowserRenderingSettings.UserAgentMode.MOBILE, backToMobile.userAgentMode)
            assertEquals(BrowserRenderingSettings.LayoutWidthMode.DEVICE, backToMobile.layoutWidthMode)
            onMain {
                assertEquals(defaultUserAgent, webView.settings.userAgentString)
                assertFalse(webView.settings.useWideViewPort)
            }

            onMain { assertRebuiltPanelShowsPersistedValues(preferences) }
        } finally {
            preferences.edit().clear().commit()
            agent[0]?.let { view -> onMain { view.destroy() } }
        }
    }

    private fun assertRebuiltPanelShowsPersistedValues(preferences: android.content.SharedPreferences) {
        val body = newBody()
        BrowserRenderingSettingsPanel.addTo(body, context, 0, BrowserRenderingPreferences.read(preferences)) { _, _ ->
            throw AssertionError("a rebuilt panel must not report a change on its own")
        }
        layout(body)
        val agent = radioGroups(body)[0]
        val layoutGroup = radioGroups(body)[1]
        assertEquals(listOf("mobile"), checkedTags(agent))
        assertEquals(listOf("device"), checkedTags(layoutGroup))
        assertTrue(overviewBox(body).isChecked)
        assertEquals(150, textScaleBar(body).progress)
    }

    private fun revision(preferences: android.content.SharedPreferences): Long {
        val stored = BrowserRenderingPreferences.read(preferences)
        return stored.revision
    }

    private fun setTextScale(body: ViewGroup, percent: Int) {
        val bar = textScaleBar(body)
        bar.progress = percent - 50
        val x = bar.xForProgress(percent - 50)
        bar.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, bar))
        bar.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, bar))
    }

    private fun newBody(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun layout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 1920)
    }

    private fun clickOption(holder: ViewGroup, value: String) {
        children(holder).filterIsInstance<RadioGroup>()
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

    private fun overviewBox(holder: ViewGroup): CheckBox = children(holder).filterIsInstance<CheckBox>().single()

    private fun textScaleBar(holder: ViewGroup): SeekBar = children(holder).filterIsInstance<SeekBar>().single()

    private fun checkedTags(group: RadioGroup): List<String> =
        children(group).filterIsInstance<RadioButton>().filter { it.isChecked }.map { it.tag as String }

    private fun children(group: ViewGroup): List<View> = (0 until group.childCount).map { group.getChildAt(it) }

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

    private companion object {
        const val TEST_PREFERENCES = "browser_rendering_android_test"
    }
}
