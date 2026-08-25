package com.dshbox.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.files.FilesScreen
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.launch.LaunchScreen
import com.dshbox.app.ui.settings.SettingsScreen
import com.dshbox.app.ui.terminal.TerminalScreen
import com.dshbox.app.ui.theme.AppIcons
import com.dshbox.app.ui.webview.DshWebViewScreen
import com.dshbox.app.service.SandboxService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private data class TabSpec(val labelRes: Int, val icon: AppIcons)

/** Minimum time the brand launch animation stays visible on cold start. */
private const val SPLASH_MIN_MILLIS = 2_000L

@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as DshApp
    val sandboxManager = app.container.sandboxManager

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Runtime truth starts fresh each process; remember() avoids restoring stale
    // READY from a previous process via rememberSaveable.
    var sandboxRunning by remember { mutableStateOf(false) }
    var sandboxError by remember { mutableStateOf(false) }
    var dshState by remember { mutableStateOf(DshState.UNINITIALIZED) }
    var showLaunch by remember { mutableStateOf(true) }
    var runtimeInstalled by remember { mutableStateOf(sandboxManager.isRuntimeInstalled()) }
    val bundledRuntimeAvailable = remember {
        BundledRuntimeInstaller(app, app.container.sandboxConfig).hasBundledBundle()
    }

    val tabs = listOf(
        TabSpec(R.string.tab_home, AppIcons.Home),
        TabSpec(R.string.tab_files, AppIcons.Files),
        TabSpec(R.string.tab_dsh, AppIcons.Dsh),
        TabSpec(R.string.tab_terminal, AppIcons.Terminal),
        TabSpec(R.string.tab_settings, AppIcons.Settings),
    )

    LaunchedEffect(sandboxManager) {
        sandboxManager.sandboxState.collectLatest { state ->
            sandboxRunning = state == SandboxState.RUNNING
            sandboxError = state == SandboxState.ERROR
            runtimeInstalled = sandboxManager.isRuntimeInstalled()
        }
    }

    LaunchedEffect(sandboxManager) {
        sandboxManager.dshState.collectLatest { state ->
            dshState = state
            // Dismiss the launch animation only when DSH settles into a
            // terminal state. STOPPED is NOT terminal here: on every cold
            // start the manager passes through STOPPED before starting.
            if (state == DshState.READY || state == DshState.ERROR) {
                showLaunch = false
            }
        }
    }

    // Brand splash minimum duration.
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS)
        showLaunch = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val splashVisible = showLaunch && dshState != DshState.READY
        val density = LocalDensity.current

        // Hide the bottom tab bar while the soft keyboard is up on the terminal
        // tab: otherwise the terminal content is inset by BOTH the navigation
        // bar height (Scaffold innerPadding) AND the full IME height
        // (TerminalScreen.imePadding()), leaving a blank band the height of the
        // navigation bar between the extra-keys bar and the keyboard.
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val hideBottomBar = imeVisible && selectedTab == 3

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (splashVisible) 0f else 1f),
            contentWindowInsets = if (hideBottomBar) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (!hideBottomBar) {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { tab.icon.Content() },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                TabContent(
                    selectedTab = selectedTab,
                    sandboxRunning = sandboxRunning,
                    sandboxError = sandboxError,
                    dshState = dshState,
                    runtimeInstalled = runtimeInstalled,
                    bundledRuntimeAvailable = bundledRuntimeAvailable,
                    onNavigateToSettings = { selectedTab = 4 },
                )
            }
        }

        if (showLaunch && dshState != DshState.READY) {
            LaunchScreen()
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: Int,
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    dshState: DshState,
    runtimeInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    val dshReady = dshState == DshState.READY
    val dshError = dshState == DshState.ERROR
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 0) 1f else 0f)
                .alpha(if (selectedTab == 0) 1f else 0f)
                .then(if (selectedTab == 0) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            sandboxError = sandboxError,
            dshReady = dshReady,
            dshError = dshError,
            runtimeInstalled = runtimeInstalled,
            bundledRuntimeAvailable = bundledRuntimeAvailable,
            onNavigateToSettings = onNavigateToSettings,
        )
        FilesScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 1) 1f else 0f)
                .alpha(if (selectedTab == 1) 1f else 0f)
                .then(if (selectedTab == 1) Modifier else Modifier.keepAliveHidden()),
            isActiveTab = selectedTab == 1,
        )
        DshWebViewScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 2) 1f else 0f)
                .alpha(if (selectedTab == 2) 1f else 0f)
                .then(if (selectedTab == 2) Modifier else Modifier.keepAliveHidden()),
            url = com.dshbox.app.common.Constants.DSH_BASE_URL,
            dshState = dshState,
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == 2,
            onStartDsh = { SandboxService.startDsh(context) },
            onStartSandbox = { SandboxService.startSandbox(context) },
        )
        TerminalScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 3) 1f else 0f)
                .alpha(if (selectedTab == 3) 1f else 0f)
                .then(if (selectedTab == 3) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == 3,
        )
        SettingsScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 4) 1f else 0f)
                .alpha(if (selectedTab == 4) 1f else 0f)
                .then(if (selectedTab == 4) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            dshReady = dshReady,
        )
    }
}

/**
 * Keeps a non-selected tab composed (state preserved) while removing it from
 * the hit-test graph entirely: measured as usual but placed at zero size.
 *
 * The previous approach (a pointerInput loop consuming every event) sat as a
 * sibling of the ACTIVE tab in the same Box and stole/cancelled the active
 * tab's real touches — most visibly breaking TerminalView scrolling/pinch.
 * Zero-size placement achieves the same "no stray taps" goal without ever
 * touching the pointer stream.
 */
private fun Modifier.keepAliveHidden(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(0, 0) { /* intentionally not placed */ }
}
