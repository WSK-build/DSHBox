package com.dshbox.browser.runtime

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class BrowserRenderingPreferencesTest {
    @Test fun independentPreferenceInstanceReadsCommittedPolicyAndPreservesOtherKeys() {
        val memory = MemoryPreferences(mapOf("unrelated" to "keep"))
        val policy = BrowserRenderingSettings(BrowserRenderingSettings.UserAgentMode.DESKTOP,
            BrowserRenderingSettings.LayoutWidthMode.WIDE, true, 150, 3)
        assertTrue(BrowserRenderingPreferences.write(memory.preferences, policy))
        val restarted = MemoryPreferences(memory.disk)
        assertEquals(policy, BrowserRenderingPreferences.read(restarted.preferences))
        assertEquals("keep", restarted.values["unrelated"])
        assertFalse(restarted.values.keys.any { it.contains("userAgentString") })
    }

    @Test fun malformedStoredTypesFallBackPerFieldWithoutDiscardingValidFields() {
        val memory = MemoryPreferences(mapOf("render_user_agent_mode" to 12,
            "render_layout_width_mode" to "wide", "render_overview_mode" to "true",
            "render_text_scale_percent" to 201, "render_revision" to -1L))
        val policy = BrowserRenderingPreferences.read(memory.preferences)
        assertEquals(BrowserRenderingSettings.UserAgentMode.DEFAULT, policy.userAgentMode)
        assertEquals(BrowserRenderingSettings.LayoutWidthMode.WIDE, policy.layoutWidthMode)
        assertFalse(policy.overviewMode)
        assertEquals(100, policy.textScalePercent)
        assertEquals(0L, policy.revision)
    }

    @Test fun commitFailureIsNotReportedAsDurableAndMemoryMayDifferFromRestart() {
        val memory = MemoryPreferences(emptyMap(), commitResult = false)
        val policy = BrowserRenderingSettings(textScalePercent = 120, revision = 1)
        assertFalse(BrowserRenderingPreferences.write(memory.preferences, policy))
        assertEquals(policy, BrowserRenderingPreferences.read(memory.preferences))
        assertEquals(BrowserRenderingSettings(), BrowserRenderingPreferences.read(MemoryPreferences(memory.disk).preferences))
    }

    private class MemoryPreferences(initial: Map<String, Any>, private val commitResult: Boolean = true) {
        val values = initial.toMutableMap()
        var disk: Map<String, Any> = initial.toMap()
        val preferences: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "MemoryPreferences"
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val staged = values.toMutableMap()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putInt", "putLong" -> {
                        staged[args!![0] as String] = args[1]!!
                        proxy
                    }
                    "commit" -> {
                        values.clear(); values.putAll(staged)
                        if (commitResult) disk = values.toMap()
                        commitResult
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
