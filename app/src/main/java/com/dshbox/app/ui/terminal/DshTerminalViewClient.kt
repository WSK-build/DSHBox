package com.dshbox.app.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * UI-owned implementation of the Termux view callback interface.
 *
 * Providers are passed as lambdas and read at call time so the client can be
 * remembered once per composition while tab/ctrl state keeps changing.
 */
class DshTerminalViewClient(
    private val isTabActive: () -> Boolean,
    private val ctrlModifierActive: () -> Boolean,
    private val onScaleDeltaSp: (Int) -> Unit,
    private val onFocusAndKeyboardRequested: () -> Unit,
) : TerminalViewClient {

    // ---- Gestures / focus ----------------------------------------------------

    /**
     * Pinch-to-zoom font sizing.
     *
     * TerminalView accumulates the gesture into a cumulative factor and passes
     * it here; returning anything other than `scale` becomes the new
     * accumulator base (returning 1f resets it), so each time the accumulated
     * factor crosses ±10% we step once and reset.
     *
     * Convention: pinch OUT (factor > 1) zooms in => larger font.
     */
    override fun onScale(scale: Float): Float {
        return if (scale < PINCH_STEP_THRESHOLD || scale > PINCH_RELEASE_THRESHOLD) {
            onScaleDeltaSp(if (scale > 1f) FONT_STEP_SP else -FONT_STEP_SP)
            1f
        } else {
            scale
        }
    }

    override fun onSingleTapUp(e: MotionEvent) {
        onFocusAndKeyboardRequested()
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun copyModeChanged(copyMode: Boolean) = Unit

    // ---- Input configuration ---------------------------------------------------

    override fun isTerminalViewSelected(): Boolean = isTabActive()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    // ---- Modifier state fed by the extra-keys bar ------------------------------

    override fun readControlKey(): Boolean = ctrlModifierActive()

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    // ---- Key events --------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean {
        // Let TerminalView's default mapping handle everything; special
        // combos (extra-keys modifiers) already flow through readControlKey().
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    /**
     * Ctrl-modified characters committed by an IME: map plain ASCII letters to
     * their control equivalents (^A..^Z). Everything else falls back to the
     * view's default handling.
     */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (!ctrlDown && !ctrlModifierActive()) return false
        val c = Character.toLowerCase(codePoint)
        if (c in 'a'.code..'z'.code) {
            session?.writeCodePoint(false, c - 'a'.code + 1)
            return true
        }
        return false
    }

    override fun onEmulatorSet() = Unit

    // ---- Logging ----------------------------------------------------------------

    private fun log(priority: Int, tag: String, message: String) {
        android.util.Log.println(priority, "termview-$tag", message)
    }

    override fun logError(tag: String, message: String) = log(android.util.Log.ERROR, tag, message)
    override fun logWarn(tag: String, message: String) = log(android.util.Log.WARN, tag, message)
    override fun logInfo(tag: String, message: String) = log(android.util.Log.INFO, tag, message)
    override fun logDebug(tag: String, message: String) = log(android.util.Log.DEBUG, tag, message)
    override fun logVerbose(tag: String, message: String) = log(android.util.Log.VERBOSE, tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        android.util.Log.e("termview-$tag", message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        android.util.Log.e("termview-$tag", "stack trace", e)
    }

    companion object {
        /** Cumulative factor beyond which one font-size step fires (±10%). */
        private const val PINCH_STEP_THRESHOLD = 0.9f
        private const val PINCH_RELEASE_THRESHOLD = 1.1f
        private const val FONT_STEP_SP = 2
    }
}
