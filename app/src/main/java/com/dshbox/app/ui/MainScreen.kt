package com.dshbox.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.files.FilesScreen
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.launch.LaunchScreen
import com.dshbox.app.ui.sandbox.SandboxScreen
import com.dshbox.app.ui.settings.SettingsScreen
import com.dshbox.app.ui.theme.AppIcons
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
    // Sandbox state is runtime truth, NOT process state: rememberSaveable
    // would restore READY (sandboxReady=1) from a previous process on every
    // cold start, which hid the splash (or flashed the home UI first) until
    // the state flow re-emitted UNINITIALIZED. remember() starts fresh.
    var sandboxReady by remember { mutableIntStateOf(0) } // 0/1
    var sandboxError by remember { mutableIntStateOf(0) } // 0/1
    var sandboxStopped by remember { mutableIntStateOf(0) } // 0/1
    var showLaunch by remember { mutableStateOf(true) }
    var runtimeInstalled by remember { mutableStateOf(sandboxManager.isRuntimeInstalled()) }
    val bundledRuntimeAvailable = remember {
        BundledRuntimeInstaller(app, app.container.sandboxConfig).hasBundledBundle()
    }

    val tabs = listOf(
        TabSpec(R.string.tab_home, AppIcons.Home),
        TabSpec(R.string.tab_files, AppIcons.Files),
        TabSpec(R.string.tab_sandbox, AppIcons.Sandbox),
        TabSpec(R.string.tab_settings, AppIcons.Settings),
    )

    LaunchedEffect(sandboxManager) {
        sandboxManager.state.collectLatest { state ->
            sandboxReady = if (state == SandboxState.READY) 1 else 0
            sandboxError = if (state == SandboxState.ERROR) 1 else 0
            sandboxStopped = if (state == SandboxState.STOPPED) 1 else 0
            // First boot extracts the bundled runtime asynchronously; refresh
            // the flag with every state change so the "install runtime" banner
            // disappears once the sandbox actually has a runtime.
            runtimeInstalled = sandboxManager.isRuntimeInstalled()
            // Dismiss the launch animation only when the sandbox settles into
            // a terminal state. STOPPED is NOT terminal here: on every cold
            // start the manager passes through STOPPED right after
            // initialize() and before start(), so closing on STOPPED would
            // make the brand splash invisible.
            if (state == SandboxState.READY ||
                state == SandboxState.ERROR
            ) {
                showLaunch = false
            }
        }
    }

    // Brand splash minimum duration: with the runtime already installed the
    // sandbox reaches READY within seconds, so without a floor the launch
    // animation would be invisible on every cold start.
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS)
        showLaunch = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val splashVisible = showLaunch && sandboxReady == 0
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // While the splash is up the main UI must not paint even one
                // frame (otherwise the first frame flashes the home content
                // before the overlay draws). Both changes land in the same
                // recomposition, so there is no intermediate frame.
                .alpha(if (splashVisible) 0f else 1f),
            bottomBar = {
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
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                TabContent(
                    selectedTab = selectedTab,
                    sandboxReady = sandboxReady == 1,
                    sandboxError = sandboxError == 1,
                    sandboxStopped = sandboxStopped == 1,
                    runtimeInstalled = runtimeInstalled,
                    bundledRuntimeAvailable = bundledRuntimeAvailable,
                    onNavigateToSettings = { selectedTab = 3 },
                )
            }
        }

        if (showLaunch && sandboxReady == 0) {
            LaunchScreen()
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: Int,
    sandboxReady: Boolean,
    sandboxError: Boolean,
    sandboxStopped: Boolean,
    runtimeInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 0) 1f else 0f)
                .alpha(if (selectedTab == 0) 1f else 0f)
                .then(if (selectedTab == 0) Modifier else Modifier.hiddenTab()),
            sandboxReady = sandboxReady,
            sandboxError = sandboxError,
            sandboxStopped = sandboxStopped,
            runtimeInstalled = runtimeInstalled,
            bundledRuntimeAvailable = bundledRuntimeAvailable,
            onNavigateToSettings = onNavigateToSettings,
        )
        FilesScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 1) 1f else 0f)
                .alpha(if (selectedTab == 1) 1f else 0f)
                .then(if (selectedTab == 1) Modifier else Modifier.hiddenTab()),
            isActiveTab = selectedTab == 1,
        )
        SandboxScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 2) 1f else 0f)
                .alpha(if (selectedTab == 2) 1f else 0f)
                .then(if (selectedTab == 2) Modifier else Modifier.hiddenTab()),
            sandboxReady = sandboxReady,
            sandboxStopped = sandboxStopped,
            onNavigateToSettings = onNavigateToSettings,
            isActiveTab = selectedTab == 2,
        )
        SettingsScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 3) 1f else 0f)
                .alpha(if (selectedTab == 3) 1f else 0f)
                .then(if (selectedTab == 3) Modifier else Modifier.hiddenTab()),
            sandboxReady = sandboxReady,
        )
    }
}

/**
 * Keeps a non-selected tab composed (so the terminal keeps running) while
 * removing it from the accessibility tree and from hit-testing, so taps on
 * the visible tab can never reach hidden screens below.
 */
private fun Modifier.hiddenTab(): Modifier = this
    .semantics { invisibleToUser() }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    }
