package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SandboxProcessRunnerTest {
    private val config = SandboxConfig(appFilesDir = File("/tmp/dshapp-test"))

    @Test
    fun prootCommandUsesAbsolutePathsAndDshStartScript() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotStartCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertEquals("/data/data/com.dshbox.app/files/runtime/runtime-current/proot", command[0])
        assertTrue(command.any { it.startsWith("--rootfs=") })
        assertTrue(command.any { it.endsWith(":/root/projects") })
        assertTrue(command.any { it.contains("/opt/dshapp/start_dsh.sh") })
    }
}
