package com.dshbox.browser.runtime

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.browser.api.BrowserCloseReason
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs only in the independent instrumentation application, never the installed user application. */
@RunWith(AndroidJUnit4::class)
class BrowserSessionRendererExitAndroidTest {
    @Test fun sessionCloseNotifiesRetainedWindowAfterDisposalOnlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        instrumentation.runOnMainSync {
            val gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
            gateway.open(BrowserOwner.AI)
            var notifications = 0
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                BrowserSessionUiHost::class.java.classLoader, arrayOf(BrowserSessionUiHost::class.java),
            ) { _, method, args ->
                if (method.name == "onSessionClosed") {
                    notifications++
                    assertEquals(BrowserCloseReason.AI_CLOSE, args?.first())
                    assertEquals(BrowserLifecycle.CLOSED, gateway.lifecycleController.snapshot().lifecycle)
                    assertNull(gateway.host.surfaceOrNull())
                    assertNull(BrowserSessionGatewayRegistry.current())
                }
                if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            } as BrowserSessionUiHost
            try {
                gateway.setUiHost(listener)
                gateway.close()
                gateway.close()
                assertEquals(1, notifications)
                val fresh = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                assertNotSame(gateway, fresh)
                fresh.open(BrowserOwner.HUMAN)
                assertNotNull(fresh.host.surfaceOrNull())
            } finally {
                BrowserSessionGatewayRegistry.current()?.close()
            }
        }
    }

    @Test fun backgroundControlAdapterUsesLiveSessionWithoutAnActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        try {
            instrumentation.runOnMainSync {
                val preferences = BrowserRuntimeSettings.preferences(context)
                assertTrue(preferences.edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, false).commit())
                val gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                val before = gateway.open(BrowserOwner.HUMAN)
                val host = gateway.controlApprovalHost()
                assertNotNull(host.identity())
                assertFalse(host.aiEnabled)
                assertEquals(BrowserOwner.HUMAN, host.owner)
                assertTrue(host.grantAfterVerifiedApproval())
                assertTrue(host.aiEnabled)
                assertEquals(BrowserOwner.AI, host.owner)
                assertTrue(gateway.lifecycleController.snapshot().controlGeneration > before.controlGeneration)
                gateway.close()
                assertTrue(preferences.edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, false).commit())
                assertFalse(host.grantAfterVerifiedApproval())
                assertFalse(host.aiEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { BrowserSessionGatewayRegistry.current()?.close() }
        }
    }

    @Test fun backgroundRendererExitClosesSessionWithoutKillingApplicationAndAllowsFreshSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        val process = Process.myPid()
        lateinit var original: BrowserSessionGateway
        var generation = 0L
        try {
            instrumentation.runOnMainSync {
                original = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                generation = original.open(BrowserOwner.AI).sessionGeneration
                requireNotNull(original.host.surfaceOrNull()).loadDataWithBaseURL(
                    "https://example.test/", "<title>Before renderer exit</title><p>ready</p>", "text/html", "UTF-8", null,
                )
            }
            awaitMain(15_000) {
                val view = original.host.surfaceOrNull()
                view?.title == "Before renderer exit" && view.webViewRenderProcess != null
            }
            instrumentation.runOnMainSync {
                assertTrue(requireNotNull(original.host.surfaceOrNull()?.webViewRenderProcess).terminate())
            }
            awaitMain(15_000) { BrowserSessionGatewayRegistry.current() == null }
            instrumentation.runOnMainSync {
                assertEquals(process, Process.myPid())
                val closed = original.lifecycleController.snapshot()
                assertEquals(BrowserLifecycle.CLOSED, closed.lifecycle)
                assertEquals(BrowserCloseReason.RENDERER_GONE, closed.closeReason)
                val fresh = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                assertNotSame(original, fresh)
                assertTrue(fresh.open(BrowserOwner.AI).sessionGeneration > generation)
                requireNotNull(fresh.host.surfaceOrNull()).loadDataWithBaseURL(
                    "https://example.test/", "<title>After renderer exit</title><p>recovered</p>", "text/html", "UTF-8", null,
                )
            }
            awaitMain(15_000) { BrowserSessionGatewayRegistry.current()?.host?.surfaceOrNull()?.title == "After renderer exit" }
        } finally {
            instrumentation.runOnMainSync { BrowserSessionGatewayRegistry.current()?.close() }
        }
    }

    private fun awaitMain(timeoutMillis: Long, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val result = AtomicReference(false)
            instrumentation.runOnMainSync { result.set(condition()) }
            if (result.get()) return
            CountDownLatch(1).await(50, TimeUnit.MILLISECONDS)
        }
        fail("Timed out waiting for renderer lifecycle condition")
    }
}
