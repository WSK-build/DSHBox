package com.dshbox.app.ui.terminal

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppIconsTerminal
import com.dshbox.app.ui.theme.AppIconsStop
import com.dshbox.terminal.DshTerminalManager
import com.termux.view.TerminalView

/**
 * The terminal tab: one shared TerminalView displays the ACTIVE session from
 * an ordered, observable list of windows (see DshTerminalManager). A
 * translucent green floating button on the right opens a control panel to
 * create / switch / close windows.
 *
 * The single [DshTerminalSessionClient] is shared by every session: terminal
 * output of non-active sessions is simply not attached to the view, and
 * process-exit callbacks are routed to the manager by session identity.
 */
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    sandboxRunning: Boolean = false,
    isActiveTab: Boolean = true,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DshApp
    val manager = app.container.dshTerminalManager

    val sessions by manager.sessions.collectAsState()
    val activeSessionId by manager.activeId.collectAsState()

    var fontSizeSp by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_SP) }
    var ctrlEnabled by remember { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    // True once a window has been created in this composition lifetime, so the
    // auto-open helper only acts on the FIRST landing on an online tab —
    // afterwards the user decides via the panel (closing the last window must
    // NOT immediately re-open one).
    var everHadSession by remember { mutableStateOf(false) }

    // Stable client instances reading fresh state through closures.
    val currentActive by rememberUpdatedState(isActiveTab)
    val currentCtrl by rememberUpdatedState(ctrlEnabled)
    val sessionClient = remember { DshTerminalSessionClient(app, manager) }
    val viewClient = remember {
        DshTerminalViewClient(
            isTabActive = { currentActive },
            ctrlModifierActive = { currentCtrl },
            onScaleDeltaSp = { delta ->
                fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            },
            onFocusAndKeyboardRequested = { terminalView?.let(::showIme) },
        )
    }

    // Keep live sessions pointed at the current client instance on every
    // (re)composition — including after Activity recreation while this tab is
    // not active — so stale clients can never pin an old Activity's view.
    LaunchedEffect(sessionClient) {
        manager.rebindClient(sessionClient)
    }

    val activeSession = sessions.firstOrNull { it.id == activeSessionId }
        ?: sessions.lastOrNull()

    // Auto-open ONE sandbox window on the first landing on an online tab.
    // After that the user drives everything through the panel; closing the last
    // window stays closed (it would be surprising to have it instantly reopen).
    LaunchedEffect(isActiveTab, sandboxRunning) {
        if (!isActiveTab) return@LaunchedEffect
        if (!everHadSession && sessions.isEmpty() && sandboxRunning && manager.runtimeAvailable()) {
            everHadSession = true
            manager.newSandboxSession(sessionClient)
        }
    }

    // Focus / soft-keyboard / redraw protocol across tab switches.
    LaunchedEffect(isActiveTab, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        view.visibility = if (isActiveTab) View.VISIBLE else View.INVISIBLE
        if (isActiveTab) {
            view.requestFocus()
        } else {
            view.clearFocus()
            hideIme(view)
        }
    }

    LaunchedEffect(fontSizeSp, terminalView) {
        terminalView?.setTextSize(fontSizeSp)
    }

    // The window currently shown in the embedded view (running or exited-with-content).
    val displayedSession = activeSession?.let { manager.sessionById(it.id) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (displayedSession != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            // Must come first: mRenderer starts null and
                            // setTextSize() is its null-guarded initializer.
                            setTextSize(fontSizeSp)
                            isFocusableInTouchMode = true
                            setTerminalViewClient(viewClient)
                        }
                    },
                    update = { view ->
                        terminalView = view
                        sessionClient.view = view
                        if (view.currentSession !== displayedSession) {
                            view.attachSession(displayedSession)
                        }
                    },
                    onRelease = { view ->
                        if (sessionClient.view === view) sessionClient.view = null
                    },
                )

                if (activeSession?.kind == DshTerminalManager.Kind.FAILSAFE) {
                    FailsafeBanner(
                        sandboxRunning = sandboxRunning,
                        onStartSandbox = { SandboxService.startSandbox(context) },
                        onSwitchToSandbox = { manager.newSandboxSession(sessionClient) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                if (activeSession?.exited == true) {
                    ExitedOverlay(
                        exitCode = activeSession.exitCode,
                        signal = activeSession.signal,
                        kind = activeSession.kind,
                        sandboxRunning = sandboxRunning,
                        onRestart = {
                            ctrlEnabled = false
                            when (activeSession.kind) {
                                DshTerminalManager.Kind.SANDBOX ->
                                    if (sandboxRunning) {
                                        val ui = manager.newSandboxSession(sessionClient)
                                        ui?.let { manager.activate(it.id) }
                                    } else {
                                        Toast.makeText(context, R.string.terminal_offline_title, Toast.LENGTH_SHORT).show()
                                        SandboxService.startSandbox(context)
                                    }
                                DshTerminalManager.Kind.FAILSAFE ->
                                    if (sandboxRunning) {
                                        val ui = manager.newSandboxSession(sessionClient)
                                        ui?.let { manager.activate(it.id) }
                                    } else {
                                        val ui = manager.newFailsafeSession(sessionClient)
                                        ui?.let { manager.activate(it.id) }
                                    }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            } else {
                when {
                    !manager.runtimeAvailable() -> PlaceholderCard(
                        titleRes = R.string.terminal_noruntime_title,
                        bodyRes = R.string.terminal_noruntime_body,
                    )
                    sandboxRunning -> PlaceholderCard(
                        titleRes = R.string.terminal_nowindow_title,
                        bodyRes = R.string.terminal_nowindow_body,
                    )
                    else -> OfflineCard(
                        onStartSandbox = { SandboxService.startSandbox(context) },
                        onFailsafe = { manager.newFailsafeSession(sessionClient) },
                    )
                }
            }

            // Translucent green floating button -> control panel.
            FloatingControls(
                open = panelOpen,
                onToggle = { panelOpen = !panelOpen },
                sessions = sessions,
                activeId = activeSessionId,
                onActivate = { id -> manager.activate(id) },
                onNewSandbox = { manager.newSandboxSession(sessionClient)?.let { manager.activate(it.id) } },
                onNewFailsafe = { manager.newFailsafeSession(sessionClient)?.let { manager.activate(it.id) } },
                onClose = { id -> manager.closeSession(id) },
                onCloseAll = { manager.stopAll() },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // Bottom: extra keys only apply to the active window.
        val activeUi = activeSession
        if (activeUi != null && isActiveTab) {
            val target = displayedSession
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                TerminalExtraKeysBar(
                    ctrlEnabled = ctrlEnabled,
                    onToggleCtrl = { ctrlEnabled = !ctrlEnabled },
                    onKeyBytes = { bytes ->
                        target?.write(bytes, 0, bytes.size)
                    },
                    onPaste = { sessionClient.pasteFromClipboard(target) },
                    cursorKeyAppMode = {
                        target?.getEmulator()?.isCursorKeysApplicationMode() == true
                    },
                )
            }
        }
    }
}

// ---- Floating window controls -------------------------------------------------

@Composable
private fun FloatingControls(
    open: Boolean,
    onToggle: () -> Unit,
    sessions: List<DshTerminalManager.SessionUi>,
    activeId: String?,
    onActivate: (String) -> Unit,
    onNewSandbox: () -> Unit,
    onNewFailsafe: () -> Unit,
    onClose: (String) -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            if (open) {
                Surface(
                    modifier = Modifier
                        .width(200.dp)
                        .alpha(0.88f),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Header row: title on the left, close (✕) on the upper
                        // RIGHT so the corner control never covers the terminal.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.terminal_panel_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onToggle,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.terminal_panel_toggle),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onNewSandbox, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.terminal_panel_new_sandbox))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNewFailsafe, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.terminal_panel_new_failsafe))
                            }
                        }
                        sessions.forEach { ui ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val label = if (ui.id == activeId) {
                                    "${ui.displayTitle} ●"
                                } else {
                                    ui.displayTitle
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onActivate(ui.id) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = AppIconsTerminal,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onClose(ui.id) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = AppIconsStop,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (sessions.isNotEmpty()) {
                            OutlinedButton(onClick = onCloseAll, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.terminal_panel_close_all))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            FloatingActionButton(
                onClick = onToggle,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .size(48.dp)
                    .padding(top = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.terminal_panel_toggle),
                )
            }
        }
    }
}

// ---- Overlay cards -----------------------------------------------------------

@Composable
private fun PlaceholderCard(titleRes: Int, bodyRes: Int?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = AppIconsTerminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            if (bodyRes != null) {
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OfflineCard(
    onStartSandbox: () -> Unit,
    onFailsafe: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.terminal_offline_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.terminal_offline_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onStartSandbox) {
                Text(stringResource(R.string.terminal_action_start_sandbox))
            }
            OutlinedButton(onClick = onFailsafe) {
                Text(stringResource(R.string.terminal_action_failsafe))
            }
        }
    }
}

@Composable
private fun FailsafeBanner(
    sandboxRunning: Boolean,
    onStartSandbox: () -> Unit,
    onSwitchToSandbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.terminal_banner_failsafe),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (sandboxRunning) {
                Button(onClick = onSwitchToSandbox) {
                    Text(stringResource(R.string.terminal_action_open_sandbox_terminal))
                }
            } else {
                OutlinedButton(onClick = onStartSandbox) {
                    Text(stringResource(R.string.terminal_banner_failsafe_action))
                }
            }
        }
    }
}

@Composable
private fun ExitedOverlay(
    exitCode: Int,
    signal: Int?,
    kind: DshTerminalManager.Kind,
    sandboxRunning: Boolean,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (kind) {
        DshTerminalManager.Kind.SANDBOX ->
            if (sandboxRunning) R.string.terminal_action_restart else R.string.terminal_action_start_sandbox
        DshTerminalManager.Kind.FAILSAFE ->
            if (sandboxRunning) {
                R.string.terminal_action_open_sandbox_terminal
            } else {
                R.string.terminal_action_restart
            }
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (signal != null) {
                    stringResource(R.string.terminal_exited_signal, signal)
                } else {
                    stringResource(R.string.terminal_exited_code, exitCode)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onRestart) {
                Text(stringResource(label))
            }
        }
    }
}

// ---- IME helpers ---------------------------------------------------------------

private fun showIme(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(view, 0)
}

private fun hideIme(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

private const val DEFAULT_FONT_SP = 28
private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 40
