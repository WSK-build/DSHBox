package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun dshCommandBindsBrowserCliTreeWhenProvided() {
        val runner = SandboxProcessRunner(config)
        val browserDir = "/data/data/com.dshbox.app/files/browser-tools"
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            browserHostDir = browserDir,
        )
        assertTrue(command.any { it == "--bind=$browserDir:/opt/browser" })
    }

    /**
     * 共享区绑定：清单逐个绑到 guest `/root/share/<别名>`（内建 DSHBox 与用户挂载同口径）；
     * 缺省不出现，目录不存在的条目跳过（PRoot 的 bind 源必须存在）。
     */
    @Test
    fun shareBindsAreEmittedOnlyForExistingDirs() {
        val runner = SandboxProcessRunner(config)
        val tmp = Files.createTempDirectory("share-bind").toFile()
        val builtIn = File(tmp, "DSHBox").apply { mkdirs() }
        val mount = File(tmp, "Download").apply { mkdirs() }
        val shareBinds = listOf(
            Constants.SHARE_AREA_DIR_NAME to builtIn,
            "Download" to mount,
            "Ghost" to File(tmp, "ghost"),
        )
        val expected = listOf(
            "--bind=${builtIn.absolutePath}:${Constants.SHARE_AREA_GUEST_DIR}/${Constants.SHARE_AREA_DIR_NAME}",
            "--bind=${mount.absolutePath}:${Constants.SHARE_AREA_GUEST_DIR}/Download",
        )

        val dsh = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            shareBinds = shareBinds,
        )
        assertTrue(expected.all { it in dsh })
        assertTrue("不存在的挂载源必须跳过", dsh.none { it.contains("Ghost") })

        val sandbox = runner.buildProotSandboxCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            shareBinds = shareBinds,
        )
        assertTrue(expected.all { it in sandbox })

        val without = runner.buildProotSandboxCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertTrue(without.none { it.contains(Constants.SHARE_AREA_GUEST_DIR) })
    }
}

/**
 * /proc/<pid>/stat 的 CPU 时间解析。
 *
 * 验证监控卡所用的沙箱 CPU 时间字段位置，避免解析偏移造成占用统计错误。
 */
class CpuTicksFromStatLineTest {

    @Test
    fun sumsUtimeAndStime() {
        // pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime ...
        val stat = "1234 (node) S 1 1234 1234 0 -1 4194560 12345 0 0 0 12 34 0 0 20 0 1 0"
        assertEquals(46L, cpuTicksFromStatLine(stat))
    }

    @Test
    fun handlesCommWithSpaces() {
        val stat = "99 (Web Content) S 1 99 99 0 -1 0 7 0 0 0 5 6 0 0 20 0 1 0"
        assertEquals(11L, cpuTicksFromStatLine(stat))
    }

    @Test
    fun rejectsMalformedLines() {
        assertEquals(null, cpuTicksFromStatLine(""))
        assertEquals(null, cpuTicksFromStatLine("1234 node S 1"))  // 缺 "(comm) " 段
        assertEquals(null, cpuTicksFromStatLine("1234 (node) S 1 1234"))  // 字段不够
        assertEquals(null, cpuTicksFromStatLine("1234 (node) S 1 1234 1234 0 -1 0 0 0 0 0 x y"))
    }
}

/**
 * USER_HZ 读取：JVM 单测里 [Os.sysconf] 使用 Android stub 时抛异常，本测试验证
 * 「不抛异常、且回退到一个正数」这一契约；真机上才会返回内核实际的 CLK_TCK。
 */
class ProcTicksPerSecondTest {

    @Test
    fun resolveFallsBackToPositiveConstant() {
        assertTrue(resolveProcTicksPerSecond() > 0L)
    }
}
