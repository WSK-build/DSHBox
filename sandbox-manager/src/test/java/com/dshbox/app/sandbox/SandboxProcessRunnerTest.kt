package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SandboxProcessRunnerTest {
    private val config = SandboxConfig(appFilesDir = File("/tmp/dshapp-test"))

    @Test
    fun prootDshCommandUsesAbsolutePathsAndDshStartScript() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertEquals("/data/data/com.dshbox.app/files/runtime/runtime-current/proot", command[0])
        assertTrue(command.any { it.startsWith("--rootfs=") })
        assertTrue(command.any { it.endsWith(":/root/projects") })
        assertTrue(command.any { it.contains(Constants.DSH_START_SCRIPT) })
    }

    @Test
    fun prootSandboxCommandUsesKeepaliveMarker() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotSandboxCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertEquals("/data/data/com.dshbox.app/files/runtime/runtime-current/proot", command[0])
        assertTrue(command.any { it.startsWith("--rootfs=") })
        assertTrue(command.any { it.contains(Constants.SANDBOX_KEEPALIVE_MARKER) })
    }
}
