package com.dshbox.app.ui.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dshbox.app.R
import com.dshbox.app.ui.theme.AppIconsTerminal

/**
 * Sandbox page with multi-session terminals. Session tabs and the
 * 新建 / 关闭 / 清屏 actions live in the top row; each session is rendered by
 * [TerminalScreen] and stays alive while switching tabs (all tabs stay
 * composed).
 */
@Composable
fun SandboxScreen(
    modifier: Modifier = Modifier,
    sandboxReady: Boolean,
    sandboxStopped: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    isActiveTab: Boolean = true,
) {
    var terminalOpened by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(listOf(0)) }
    var activeSession by remember { mutableStateOf(0) }
    var clearSignal by remember { mutableIntStateOf(0) }

    if (terminalOpened) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEach { id ->
                    TextButton(onClick = { activeSession = id }) {
                        Text(
                            text = if (id == activeSession) {
                                stringResource(R.string.terminal_session_label, sessions.indexOf(id) + 1) + " ●"
                            } else {
                                stringResource(R.string.terminal_session_label, sessions.indexOf(id) + 1)
                            },
                            color = if (id == activeSession) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val newId = (sessions.maxOrNull() ?: 0) + 1
                    sessions = sessions + newId
                    activeSession = newId
                }) {
                    Text(stringResource(R.string.terminal_new))
                }
                TextButton(onClick = {
                    if (sessions.size == 1) {
                        terminalOpened = false
                    } else {
                        sessions = sessions - activeSession
                        activeSession = sessions.firstOrNull() ?: 0
                    }
                }) {
                    Text(stringResource(R.string.terminal_close))
                }
                TextButton(onClick = { clearSignal++ }) {
                    Text(stringResource(R.string.terminal_clear))
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                sessions.forEach { id ->
                    androidx.compose.runtime.key(id) {
                        TerminalScreen(
                            isActiveTab = isActiveTab,
                            onBack = {
                                if (sessions.size == 1) {
                                    terminalOpened = false
                                } else {
                                    sessions = sessions - id
                                    if (activeSession == id) {
                                        activeSession = sessions.firstOrNull() ?: 0
                                    }
                                }
                            },
                            onNewTerminal = {
                                val newId = (sessions.maxOrNull() ?: 0) + 1
                                sessions = sessions + newId
                                activeSession = newId
                            },
                            clearSignal = clearSignal,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (id == activeSession) 1f else 0f)
                                .alpha(if (id == activeSession) 1f else 0f),
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
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
                Text(
                    text = stringResource(R.string.sandbox_placeholder_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                when {
                                    sandboxStopped -> MaterialTheme.colorScheme.outline
                                    sandboxReady -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                CircleShape,
                            ),
                    )
                    Text(
                        text = stringResource(
                            when {
                                sandboxStopped -> R.string.sandbox_stopped
                                sandboxReady -> R.string.sandbox_ready
                                else -> R.string.sandbox_starting
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onNavigateToSettings) {
                        Text(stringResource(R.string.sandbox_diagnostics))
                    }
                }
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        sessions = listOf(0)
                        activeSession = 0
                        clearSignal = 0
                        terminalOpened = true
                    },
                ) {
                    Text(stringResource(R.string.sandbox_open_terminal))
                }
            }
        }
    }
}
