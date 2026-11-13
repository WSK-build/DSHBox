package com.dshbox.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DshInstance
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.service.SandboxService
import com.dshbox.app.util.BackgroundOps
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeWriterLockIntegrationTest {
    @Test fun actualStartupProtectsPausedWriterAndRecoversKilledWriter() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DshApp
        val container = app.container
        val manager = container.sandboxManager
        val config = container.sandboxConfig
        val registry = container.instanceRegistry
        val originalSlot = registry.active.value
        val home = DshInstance.EMERGENCY.hostHome(config.userDataDir)
        val resultDir = File(app.filesDir, "lock-v2-results").apply { mkdirs() }
        val report = File(resultDir, "integration.txt").apply { writeText("") }
        fun event(text: String) { report.appendText("${System.currentTimeMillis()} $text\n") }
        val ready = File(config.dshShimDir, "test-writer-ready")
        val release = File(config.dshShimDir, "test-writer-release")
        var ownedHome = false
        suspend fun guest(js: String): AppResult<Unit> = manager.runGuestCommand(
            "/usr/bin/timeout 60 /usr/local/bin/node --input-type=module -e '" + js.replace("'", "'\\''") + "'",
            { event("guest $it") },
        )
        try {
            withTimeout(240_000) {
                instrumentation.runOnMainSync {
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                withTimeout(15_000) {
                    while (true) {
                        var resumed = false
                        instrumentation.runOnMainSync {
                            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .firstOrNull { it is MainActivity }
                            if (activity != null) {
                                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                resumed = true
                            }
                        }
                        if (resumed) break
                        delay(100)
                    }
                }
                withTimeout(90_000) {
                    while (manager.sandboxState.value == SandboxState.UNINITIALIZED || BackgroundOps.isBusy) delay(200)
                    delay(1_000)
                    while (BackgroundOps.isBusy) delay(200)
                }
                app.stopService(Intent(app, SandboxService::class.java))
                delay(1_000)
                manager.stopDsh()
                manager.stopSandbox()
                assertFalse("requires fresh test-only emergency home", home.exists())
                assertTrue(registry.createEmergencyHome() is AppResult.Success)
                ownedHome = true
                registry.switchTo(DshInstance.EMERGENCY)
                manager.startSandbox()
                assertTrue(manager.startDsh() is AppResult.Success)
                assertEquals(DshState.READY, manager.dshState.value)
                event("PASS baseline with atomic lock shim")
                manager.stopDsh()
                if (ready.exists()) assertTrue(ready.delete())
                if (release.exists()) assertTrue(release.delete())
                val lock = File(home, ".credentials.yaml.lock")
                assertFalse(lock.exists())
                val paused = async {
                    guest("import{open}from\"node:fs/promises\";import{writeFileSync,existsSync}from\"node:fs\";const h=await open(\"/root/projects/.dsh-emergency/.credentials.yaml.lock\",\"wx\",0o600);writeFileSync(\"/opt/dshbox/test-writer-ready\",String(process.pid));while(!existsSync(\"/opt/dshbox/test-writer-release\"))await new Promise(r=>setTimeout(r,50));process.kill(process.pid,\"SIGKILL\");")
                }
                withTimeout(10_000) { while (!ready.exists()) delay(50) }
                val holder = ready.readText().trim()
                val firstStart = async { manager.startDsh() }
                delay(3_000)
                assertTrue(File("/proc/$holder").exists())
                assertTrue(lock.exists())
                assertEquals(0L, lock.length())
                assertFalse(paused.isCompleted)
                event("PASS live legacy writer paused 3s: startup sweep retained empty lock")
                manager.stopDsh()
                firstStart.await()
                release.writeText("kill holder")
                assertTrue(paused.await() is AppResult.Failure)
                assertTrue(lock.exists())
                assertEquals(0L, lock.length())
                val unknown = File(home, "plugin-empty-marker.lock").apply { writeText("") }
                assertTrue(manager.startDsh() is AppResult.Success)
                assertEquals(DshState.READY, manager.dshState.value)
                assertTrue(!lock.exists() || lock.length() > 0)
                assertTrue(unknown.exists())
                event("PASS actual startup recovered SIGKILL orphan; unknown empty marker retained")
                val image = instrumentation.uiAutomation.takeScreenshot()!!
                File(resultDir, "actual-ready.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            event("SCENARIOS_PASS")
        } catch (t: Throwable) {
            event("FAIL ${t.message}")
            throw t
        } finally {
            withContext(NonCancellable) {
                release.writeText("release")
                manager.stopDsh()
                manager.stopSandbox()
                registry.switchTo(originalSlot)
                if (ownedHome) {
                    assertTrue(registry.deleteEmergencyHome() is AppResult.Success)
                    assertFalse(home.exists())
                }
                ready.delete()
                release.delete()
                event("CLEANUP restored original slot and deleted test home")
            }
        }
        event("ALL_PASS")
    }
}
