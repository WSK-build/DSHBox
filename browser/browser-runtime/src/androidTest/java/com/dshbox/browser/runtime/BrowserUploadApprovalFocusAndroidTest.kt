package com.dshbox.browser.runtime

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class BrowserUploadApprovalFocusAndroidTest {
    @Test
    fun approvalFocusObserverAcknowledgesOnceAndIgnoresLaterWindowFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = startProbeActivity(instrumentation)
        var approvalDialog: AlertDialog? = null
        var externalDialog: AlertDialog? = null
        var observer: BrowserUploadApprovalFocusObserver? = null
        val acknowledgements = AtomicInteger()
        val callbackDuringDispatch = AtomicBoolean(false)
        try {
            instrumentation.runOnMainSync {
                approvalDialog = AlertDialog.Builder(activity).setTitle("Approval probe").setPositiveButton("OK", null).create()
                approvalDialog?.show()
                val decor = requireNotNull(approvalDialog?.window?.decorView)
                observer = BrowserUploadApprovalFocusObserver(decor) {
                    if (observer?.isDispatchingFocusChange == true) callbackDuringDispatch.set(true)
                    if (decor.hasWindowFocus()) {
                        acknowledgements.incrementAndGet()
                        true
                    } else false
                }.also { it.requestCheck() }
            }
            assertTrue(waitForFocus(requireNotNull(approvalDialog?.window?.decorView), true))
            assertTrue(waitForCondition { acknowledgements.get() == 1 })
            assertFalse(callbackDuringDispatch.get())

            instrumentation.runOnMainSync {
                externalDialog = AlertDialog.Builder(activity).setTitle("External window probe")
                    .setPositiveButton("OK", null).create()
                externalDialog?.show()
            }
            assertTrue(waitForFocus(requireNotNull(approvalDialog?.window?.decorView), false))
            Thread.sleep(150)
            assertEquals(1, acknowledgements.get())
        } finally {
            instrumentation.runOnMainSync {
                observer?.close()
                approvalDialog?.dismiss()
                externalDialog?.dismiss()
                activity.finish()
            }
        }
    }

    @Test
    fun invalidAuthorizationCanDismissAfterFocusDispatchAndClosedObserverIgnoresLateFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = startProbeActivity(instrumentation)
        var approvalDialog: AlertDialog? = null
        var externalDialog: AlertDialog? = null
        var observer: BrowserUploadApprovalFocusObserver? = null
        val callbacks = AtomicInteger()
        val dialogDismissed = AtomicReference(false)
        val callbackDuringDispatch = AtomicBoolean(false)
        try {
            instrumentation.runOnMainSync {
                approvalDialog = AlertDialog.Builder(activity).setTitle("Approval probe").setPositiveButton("OK", null).create()
                approvalDialog?.show()
                val dialog = requireNotNull(approvalDialog)
                val decor = requireNotNull(dialog.window?.decorView)
                observer = BrowserUploadApprovalFocusObserver(decor) {
                    if (observer?.isDispatchingFocusChange == true) callbackDuringDispatch.set(true)
                    if (decor.hasWindowFocus()) {
                        callbacks.incrementAndGet()
                        dialog.dismiss()
                        dialogDismissed.set(true)
                        true
                    } else false
                }.also { it.requestCheck() }
            }
            assertTrue(waitForCondition { dialogDismissed.get() })
            assertFalse(requireNotNull(approvalDialog).isShowing)
            assertEquals(1, callbacks.get())
            assertFalse(callbackDuringDispatch.get())

            instrumentation.runOnMainSync {
                externalDialog = AlertDialog.Builder(activity).setTitle("Late focus probe")
                    .setPositiveButton("OK", null).create()
                externalDialog?.show()
            }
            assertTrue(waitForFocus(requireNotNull(externalDialog?.window?.decorView), true))
            Thread.sleep(150)
            assertEquals(1, callbacks.get())
        } finally {
            instrumentation.runOnMainSync {
                observer?.close()
                approvalDialog?.dismiss()
                externalDialog?.dismiss()
                activity.finish()
            }
        }
    }

    @Test
    fun approvalDialogOwnWindowFocusSurvivesActivityFocusTransferButExternalWindowDoesNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = startProbeActivity(instrumentation)
        var approvalDialog: AlertDialog? = null
        var externalDialog: AlertDialog? = null
        var approvalDecor: View? = null
        var approvalObserver: ViewTreeObserver? = null
        var approvalFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
        var externalDecor: View? = null
        var externalObserver: ViewTreeObserver? = null
        var externalFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
        try {
            instrumentation.runOnMainSync {
                approvalDialog = AlertDialog.Builder(activity).setTitle("Approval probe").setMessage("Native approval")
                    .setPositiveButton("OK", null).create()
                approvalDecor = requireNotNull(approvalDialog?.window).decorView
                approvalObserver = requireNotNull(approvalDecor).viewTreeObserver
                approvalFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { }
                requireNotNull(approvalObserver).addOnWindowFocusChangeListener(requireNotNull(approvalFocusListener))
                approvalDialog?.show()
            }
            assertTrue(waitForFocus(requireNotNull(approvalDecor), true))
            val activityHasFocus = instrumentation.runOnMainSyncValue { activity.hasWindowFocus() }
            val activityVisible = instrumentation.runOnMainSyncValue {
                !activity.isFinishing && !activity.isDestroyed && activity.window.decorView.windowVisibility == View.VISIBLE
            }
            assertFalse("the dialog window should own focus while its Activity loses window focus", activityHasFocus)
            assertTrue(BrowserUploadApprovalFocusPolicy.canAcknowledge(
                activityVisible = activityVisible,
                currentDialog = true,
                dialogShowing = instrumentation.runOnMainSyncValue { requireNotNull(approvalDialog).isShowing },
                dialogWindowFocused = instrumentation.runOnMainSyncValue { requireNotNull(approvalDecor).hasWindowFocus() },
            ))

            instrumentation.runOnMainSync {
                externalDialog = AlertDialog.Builder(activity).setTitle("External window probe").setMessage("Different window")
                    .setPositiveButton("OK", null).create()
                externalDecor = requireNotNull(externalDialog?.window).decorView
                externalObserver = requireNotNull(externalDecor).viewTreeObserver
                externalFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { }
                requireNotNull(externalObserver).addOnWindowFocusChangeListener(requireNotNull(externalFocusListener))
                externalDialog?.show()
            }
            assertTrue(waitForFocus(requireNotNull(externalDecor), true))
            assertTrue(waitForFocus(requireNotNull(approvalDecor), false))
            val stillVisible = instrumentation.runOnMainSyncValue {
                !activity.isFinishing && !activity.isDestroyed && activity.window.decorView.windowVisibility == View.VISIBLE
            }
            assertFalse(BrowserUploadApprovalFocusPolicy.canAcknowledge(
                activityVisible = stillVisible,
                currentDialog = true,
                dialogShowing = instrumentation.runOnMainSyncValue { requireNotNull(approvalDialog).isShowing },
                dialogWindowFocused = instrumentation.runOnMainSyncValue { requireNotNull(approvalDecor).hasWindowFocus() },
            ))
        } finally {
            instrumentation.runOnMainSync {
                approvalDialog?.dismiss()
                externalDialog?.dismiss()
                approvalFocusListener?.let { listener ->
                    approvalObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
                }
                externalFocusListener?.let { listener ->
                    externalObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
                }
                activity.finish()
            }
        }
    }

    private fun waitForFocus(view: View, expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val focused = InstrumentationRegistry.getInstrumentation().runOnMainSyncValue { view.hasWindowFocus() }
            if (focused == expected) return true
            Thread.sleep(25L)
        }
        return false
    }

    private fun startProbeActivity(instrumentation: Instrumentation) = instrumentation.startActivitySync(
        Intent(instrumentation.context, BrowserApprovalFocusProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as BrowserApprovalFocusProbeActivity

    private fun waitForCondition(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    private fun <T> Instrumentation.runOnMainSyncValue(block: () -> T): T {
        val result = AtomicReference<T>()
        runOnMainSync { result.set(block()) }
        return result.get()
    }
}
