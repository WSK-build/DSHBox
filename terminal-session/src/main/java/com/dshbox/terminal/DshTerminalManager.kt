package com.dshbox.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level owner of a LIST of terminal sessions.
 *
 * Replaces the old single-sandbox/single-failsafe slots: every window is one
 * entry in an ordered, observable list, so the UI can open several terminals,
 * switch between them with a single shared TerminalView, and close any of
 * them. State survives tab switches / activity recreation because this object
 * lives in the application container.
 *
 * Threading: session-creating and session-switching methods must be called on
 * the main thread — TerminalSession binds its output Handler to the
 * constructing thread's Looper, and attachSession must run on the UI thread.
 * Killing methods may be called from any thread.
 */
class DshTerminalManager(
    private val pathsProvider: () -> TerminalPaths?,
    private val overlayInstaller: TerminalOverlayInstaller? = null,
) {

    enum class Kind { SANDBOX, FAILSAFE }

    /**
     * Immutable snapshot of one window for the UI layer.
     * [running] false + [exited] false => a session we killed (cleared).
     */
    data class SessionUi(
        val id: String,
        val kind: Kind,
        val order: Int,
        val running: Boolean,
        val exited: Boolean,
        val exitCode: Int,
        val signal: Int?,
    ) {
        val displayTitle: String get() = when (kind) {
            Kind.SANDBOX -> "终端 $order"
            Kind.FAILSAFE -> "受限 $order"
        }
    }

    /** Internal mutable holder. */
    private class Handle(
        val id: String,
        val kind: Kind,
        val order: Int,
        val session: TerminalSession,
        var running: Boolean,
        var exited: Boolean,
        var exitCode: Int,
        var signal: Int?,
    )

    private val lock = Any()

    private val handles = ArrayList<Handle>()
    private var nextOrder = 1

    /** Sessions we killed ourselves; their finish callbacks must not surface as Exited. */
    private val intentionallyStopped = HashSet<TerminalSession>()

    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    private val _sessions = MutableStateFlow<List<SessionUi>>(emptyList())
    val sessions: StateFlow<List<SessionUi>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun runtimeAvailable(): Boolean = pathsProvider() != null

    /**
     * Opens a new sandbox login shell and appends it to the list, returning its
     * snapshot (null when the runtime bundle/proot is unavailable).
     * Main thread only.
     */
    fun newSandboxSession(client: TerminalSessionClient): SessionUi? =
        newSession(Kind.SANDBOX, client)

    /** Opens a new failsafe Android shell (no proot). Main thread only. */
    fun newFailsafeSession(client: TerminalSessionClient): SessionUi? =
        newSession(Kind.FAILSAFE, client)

    /**
     * Re-points every live session's client callback target, e.g. after an
     * Activity recreation replaced the UI-owned client instance.
     * Main thread only.
     */
    fun rebindClient(client: TerminalSessionClient) {
        synchronized(lock) {
            handles.forEach { if (it.session.isRunning()) it.session.updateTerminalSessionClient(client) }
        }
    }

    /** Leaves [id] composed as the attached window. Main thread only. */
    fun activate(id: String) {
        synchronized(lock) {
            if (handles.any { it.id == id }) _activeId.value = id
        }
    }

    /** Lifecycle method kept for SandboxService parity: stops every SANDBOX session. Thread-safe. */
    fun stopSandboxSession() {
        killSessions { it.kind == Kind.SANDBOX }
    }

    /** Lifecycle method kept for SandboxService parity: stops everything. Thread-safe. */
    fun stopAll() {
        killSessions { true }
    }

    /** Closes a single window (kills its shell, removes it). Thread-safe. */
    fun closeSession(id: String) {
        killSessions { it.id == id }
    }

    /** Returns the session object behind [id] regardless of liveness. */
    fun sessionById(id: String): TerminalSession? = synchronized(lock) {
        handles.firstOrNull { it.id == id }?.session
    }

    /** Called by the UI client when a session reports process exit. Main thread only. */
    fun notifySessionFinished(session: TerminalSession) {
        val handle = synchronized(lock) { handles.firstOrNull { it.session === session } }
        if (handle == null) {
            // Window already removed by killSessions: just drop any stale
            // bookkeeping so the set never pin-frees dead sessions.
            synchronized(lock) { intentionallyStopped.remove(session) }
            return
        }
        val intentional = synchronized(lock) { session in intentionallyStopped }
        if (intentional) {
            synchronized(lock) { intentionallyStopped.remove(session) }
            // Cleared by us: refresh snapshot (running=false, exited stays as-is).
            refreshLocked()
        } else {
            val code = session.getExitStatus()
            synchronized(lock) {
                handle.running = false
                handle.exited = true
                handle.exitCode = if (code >= 0) code else 0
                handle.signal = if (code < 0) -code else null
                refreshLocked()
            }
        }
    }

    private fun killSessions(selector: (Handle) -> Boolean) {
        synchronized(lock) {
            val toKill = handles.filter(selector)
            for (h in toKill) {
                val running = h.session.isRunning()
                intentionallyStopped.add(h.session)
                if (running) h.session.finishIfRunning()
                h.running = false
                handles.remove(h)
            }
            // Keep activeId coherent: point it at a surviving window or clear it.
            if (_activeId.value != null && handles.none { it.id == _activeId.value }) {
                _activeId.value = handles.firstOrNull()?.id
            }
            // Reset the display numbering once every window is closed, so a new
            // batch starts from "终端 1" instead of accumulating (序号归一).
            if (handles.isEmpty()) nextOrder = 1
            // Refresh the observable list right away. A finish callback may also
            // arrive later for a killed (running) session, but by then its handle
            // is already gone, so that callback only cleans bookkeeping — we must
            // not depend on it to update the UI.
            refreshLocked()
        }
    }

    private fun newSession(kind: Kind, client: TerminalSessionClient): SessionUi? {
        val paths = pathsProvider()
        if (paths == null) {
            refreshLocked()
            return null
        }

        val (command, env) = when (kind) {
            Kind.SANDBOX -> {
                val procBinds = TerminalProcFake.ensureBindArgs(paths)
                val snippet = overlayInstaller?.prepare(paths)
                TerminalCommandFactory.sandboxLoginShell(paths, snippet, procBinds) to
                    TerminalEnvFactory.sandboxEnv(paths)
            }
            Kind.FAILSAFE ->
                TerminalCommandFactory.failsafeShell() to TerminalEnvFactory.failsafeEnv(paths)
        }

        val argv = command.toTypedArray()
        val shellPath = argv[0]
        val session = TerminalSession(shellPath, "/", argv, env, null, client)

        val handle: Handle
        synchronized(lock) {
            handle = Handle(
                id = nextId.incrementAndGet().toString(),
                kind = kind,
                order = nextOrder++,
                session = session,
                running = true,
                exited = false,
                exitCode = 0,
                signal = null,
            )
            handles.add(handle)
            _activeId.value = handle.id
            refreshLocked()
        }
        return handle.toUi()
    }

    private fun Handle.toUi(): SessionUi = SessionUi(
        id, kind, order, running, exited, exitCode, signal,
    )

    /** Recomputes and publishes the observable list (callers hold [lock]). */
    private fun refreshLocked() {
        _sessions.value = handles.map { it.toUi() }
    }
}
