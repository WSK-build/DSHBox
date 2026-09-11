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

    /**
     * 垫片注入：传入 [shimHostDir] 时必须同时出现 bind 与 `--import`，且 `--import`
     * 必须排在 DSH 入口脚本**之前**（否则 node 先执行入口，垫片来不及生效）。
     *
     * 这是「不修改 DSH 源码」这条约束的落地点——硬链接兼容全靠这个预加载。
     */
    @Test
    fun dshCommandInjectsLinkShimWhenProvided() {
        val runner = SandboxProcessRunner(config)
        val shimDir = "/data/data/com.dshbox.app/files/dshbox"
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            shimHostDir = shimDir,
        )
        assertTrue(
            "应把垫片目录 bind 到 guest ${Constants.DSH_LINK_SHIM_GUEST_DIR}",
            command.any { it == "--bind=$shimDir:${Constants.DSH_LINK_SHIM_GUEST_DIR}" },
        )
        val shell = command.last()
        assertTrue(
            "应以 --import 预加载垫片",
            shell.contains("--import ${Constants.DSH_LINK_SHIM_GUEST_PATH}"),
        )
        assertTrue(
            "--import 必须排在 DSH 入口脚本之前",
            shell.indexOf("--import") < shell.indexOf(Constants.DSH_START_SCRIPT),
        )
    }

    /** 未提供垫片目录时不得注入：`--import` 指向不存在的文件会让 node 直接退出。 */
    @Test
    fun dshCommandOmitsShimWhenAbsent() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertTrue("不应出现垫片绑定", command.none { it.contains(Constants.DSH_LINK_SHIM_GUEST_DIR) })
        assertTrue("不应出现 --import", !command.last().contains("--import"))
        // DSH 入口标记必须保持——stop() 靠它定位进程树
        assertTrue(command.any { it.contains(Constants.DSH_START_SCRIPT) })
    }
}
