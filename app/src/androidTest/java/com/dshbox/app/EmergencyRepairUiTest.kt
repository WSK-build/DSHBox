package com.dshbox.app

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.home.InstanceManagerScreen
import com.dshbox.app.sandbox.DefaultSandboxManager
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.app.ui.home.EmergencyRepairPrompt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmergencyRepairUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prompt get() = context.getString(R.string.emergency_repair_prompt_body)
    private fun verifyCopy() {
        compose.onNodeWithText(context.getString(R.string.dsh_run_log_copy)).performClick()
        compose.runOnIdle {
            assertEquals(prompt, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
    }
    private fun picture(name: String) {
        val dir = File(context.filesDir, "lock-v2-results").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun foldedCopyAndExpansion() {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) { EmergencyRepairPrompt() }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_title)).assertIsDisplayed()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        verifyCopy()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        picture("folded-copy-320dp")
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_expand)).performClick()
        compose.onNodeWithText(prompt).assertIsDisplayed()
        picture("expanded-320dp")
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_collapse)).performClick()
        compose.onNodeWithText(prompt).assertDoesNotExist()
    }
    @Test fun compactCopyWithoutBody() {
        compose.setContent { MaterialTheme { Box(Modifier.width(280.dp)) { EmergencyRepairPrompt(compact = true) } } }
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_compact_title)).assertIsDisplayed()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_expand)).assertDoesNotExist()
        verifyCopy()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        picture("compact-copy-280dp")
    }
    @Test fun actualInstancePageCopyAndWarning() {
        compose.setContent { MaterialTheme { InstanceManagerScreen(onBack = {}) } }
        compose.onNodeWithText(context.getString(R.string.instance_warning)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        verifyCopy()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        picture("actual-instance-page-folded")
    }
    @Test fun actualRecoveryDialogCopyAndWarning() {
        val manager = (context.applicationContext as DshApp).container.sandboxManager as DefaultSandboxManager
        val signal = DefaultSandboxManager::class.java.getDeclaredField("_dshRecoverySuggested").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<Boolean>
        val old = signal.value
        signal.value = true
        try {
            compose.setContent {
                MaterialTheme {
                    HomeScreen(isActive = true, sandboxRunning = false, sandboxError = false,
                        dshReady = false, dshStarting = false, dshError = true, dshUnresponsive = false,
                        runtimeInstalled = true, bundledRuntimeAvailable = true,
                        onNavigateToSettings = {}, onGetRuntimeOnline = {}, onOpenInBrowser = {}, dshLaunchToken = null,
                        pilotContent = { _, _, _ -> }, cordisContent = { _, _, _ -> }, remoteContent = { _, _, _ -> })
                }
            }
            compose.onNodeWithText(context.getString(R.string.instance_warning)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.emergency_repair_prompt_compact_title)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(prompt).assertDoesNotExist()
            verifyCopy()
            picture("actual-recovery-dialog")
        } finally {
            signal.value = old
        }
    }
}
