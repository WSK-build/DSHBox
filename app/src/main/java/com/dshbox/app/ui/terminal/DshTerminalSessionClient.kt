package com.dshbox.app.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import com.dshbox.terminal.DshTerminalManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.util.concurrent.Executors

/**
 * UI-owned implementation of the Termux session callback interface.
 *
 * Forwards screen updates to the attached [TerminalView] and process-exit
 * events to [DshTerminalManager]. One instance lives per TerminalScreen
 * composition; after an Activity recreation [DshTerminalManager.rebindClient]
 * re-points live sessions at the fresh instance.
 */
class DshTerminalSessionClient(
    context: Context,
    private val manager: DshTerminalManager,
) : TerminalSessionClient {

    /** Currently attached terminal view; updated from composition (main thread). */
    var view: View? = null

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Serial executor for paste writes: ByteQueue blocks when the pty pipe is full. */
    private val pasteExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terminal-paste").apply { isDaemon = true }
    }

    // ---- Screen update pipeline -------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        (view as? TerminalView)?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        manager.notifySessionFinished(finishedSession)
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    // ---- Clipboard ----------------------------------------------------------

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    /**
     * Writes the current clipboard text into the given session's stdin.
     *
     * Routes through [com.termux.terminal.TerminalEmulator.paste] so that
     * bracketed paste mode (DECSET 2004) is honored, ESC/C1 control bytes are
     * stripped and newlines become carriage returns — required for TUI
     * programs (vim, tmux, opencode-style TUIs). Runs off the main thread:
     * the session write queue blocks while full and a large paste must never
     * stall the UI.
     */
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        pasteFromClipboard(session)
    }

    fun pasteFromClipboard(session: TerminalSession?) {
        val target = session ?: return
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() ?: return
        pasteExecutor.execute {
            val emulator = target.getEmulator()
            if (emulator != null) {
                emulator.paste(text)
            } else {
                val bytes = text.toByteArray(Charsets.UTF_8)
                target.write(bytes, 0, bytes.size)
            }
        }
    }

    // ---- Logging -------------------------------------------------------------

    private fun log(priority: Int, tag: String, message: String) {
        Log.println(priority, "term-$tag", message)
    }

    override fun logError(tag: String, message: String) = log(Log.ERROR, tag, message)
    override fun logWarn(tag: String, message: String) = log(Log.WARN, tag, message)
    override fun logInfo(tag: String, message: String) = log(Log.INFO, tag, message)
    override fun logDebug(tag: String, message: String) = log(Log.DEBUG, tag, message)
    override fun logVerbose(tag: String, message: String) = log(Log.VERBOSE, tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e("term-$tag", message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e("term-$tag", "stack trace", e)
    }
}
