package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.common.LogRedactor
import java.io.File
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper around process execution. On Android, long-running PRoot/DSH
 * processes must be owned by a Foreground Service (see lifecycle spec), never
 * by an Activity.
 *
 * This runner now supports two independent PRoot processes:
 * - Sandbox process: a keepalive bash loop inside Debian.
 * - DSH process: the DSH launcher script inside Debian.
 * Both share the same rootfs/workspace but have separate pid trees, so stopping
 * one does not stop the other.
 */
class SandboxProcessRunner(
    private val config: SandboxConfig,
) {

    data class RunningProcess(
        val process: Process,
        val tag: String,
    )

    fun logsDir(): File = config.logsDir.also { it.mkdirs() }

    companion object {
        private const val TAG = "SandboxProcessRunner"
    }

    fun redact(line: String): String = LogRedactor.redact(line)

    /**
     * Builds the PRoot command for the Debian sandbox keepalive process.
     * The command contains [Constants.SANDBOX_KEEPALIVE_MARKER] so we can
     * distinguish this PRoot tree from the DSH PRoot tree at stop time.
     */
    fun buildProotSandboxCommand(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        nodeDir: String? = null,
        dshDir: String? = null,
    ): List<String> = buildList {
        addAll(layeredProotPrefix(prootBinary, rootfsDir, workspaceBind, nodeDir, dshDir))
        add("--cwd=/root")
        add("--kill-on-exit")
        add("/system/bin/sh"); add("-c")
        add("exec /usr/bin/bash -c 'echo ${Constants.SANDBOX_KEEPALIVE_MARKER}; trap \"\" HUP INT TERM; while true; do sleep 3600; done'")
    }

    /**
     * Builds the PRoot command for DSH. The final command contains
     * [Constants.DSH_START_SCRIPT] so we can locate this PRoot tree at stop time.
     */
    fun buildProotDshCommand(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        nodeDir: String? = null,
        dshDir: String? = null,
    ): List<String> = buildList {
        addAll(layeredProotPrefix(prootBinary, rootfsDir, workspaceBind, nodeDir, dshDir))
        add("--cwd=/root/projects")
        add("--kill-on-exit")
        add("/system/bin/sh"); add("-c")
        // Run the DSH entry (node_modules/@deepseek-ai/dsh/lib/bin.js) directly
        // under the L1 node (bound at /usr/local). There is NO separate
        // start_dsh.sh in the layered base; the guest env (HOME/PATH/DSH_*) is
        // injected by the app via buildProotEnv. Boot the web profile so the
        // DSH server serves http://127.0.0.1:3080 for the app's WebView/health.
        // Constants.DSH_START_SCRIPT (/opt/dshapp/runtime) is the cmdline marker
        // stop() uses to locate this PRoot and SIGKILL its tree.
        add("exec /usr/local/bin/node --expose-internals /opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web")
    }

    /**
     * Common PRoot prefix for the layered runtime.
     *
     * The rootfs is the L0 base layer. The L1 node layer is bound at the guest
     * /usr/local (where Node is mounted by the build), and the L2 DSH layer is
     * bound at /opt/dshapp/runtime when present (DSH is a separate product,
     * managed by the app, and is never part of the runtime bundle). PRoot
     * --bind overlays the source directory over the destination (this is the
     * same mechanism the project already uses for user-data -> /root/projects,
     * so multi-layer assembly via bind is proven compatible).
     */
    private fun layeredProotPrefix(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        nodeDir: String?,
        dshDir: String?,
    ): List<String> = buildList {
        add(prootBinary)
        add("--rootfs=$rootfsDir")
        add("--bind=/system")
        add("--bind=/apex")
        add("--bind=/proc")
        add("--bind=/dev")
        if (!nodeDir.isNullOrBlank()) add("--bind=$nodeDir:/usr/local")
        if (!dshDir.isNullOrBlank()) add("--bind=$dshDir:/opt/dshapp/runtime")
        add("--bind=$workspaceBind:/root/projects")
    }

    fun start(
        command: List<String>,
        tag: String,
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): RunningProcess {
        val pb = ProcessBuilder(command)
        workingDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val logFile = File(logsDir(), "process-$tag.log")
        val process = pb.start()
        val running = RunningProcess(process, tag)

        val input: InputStream = process.inputStream
        val thread = Thread({
            try {
                input.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    val cleaned = redact(line)
                    logFile.appendText(cleaned + "\n")
                    // Mirror to logcat so sandbox/DSH child output is observable
                    // adb (also useful for on-device diagnostics when the app data
                    // dir is not readable, e.g. non-rooted release builds).
                    Log.i(TAG, "proc-$tag: $cleaned")
                }
            } catch (_: IOException) {
                // stream closed by process exit
            }
        }, "sandbox-log-$tag")
        thread.isDaemon = true
        thread.start()

        return running
    }

    /**
     * Runs a one-off guest command in a fresh PRoot process, streaming each
     * stdout/stderr line to [onLine] (for live assembly status) and returning
     * an [AppResult] based on the process exit code. Used for "指令注入" — e.g.
     * running a plugin's install.sh inside the DSH guest.
     *
     * 1.1.0 (M7): [onProcess] receives the spawned host Process right after
     * start so long-running callers (guest npm install) can destroy it to
     * cancel; proot's --kill-on-exit cleans the guest-side tree.
     */
    fun runGuestCommand(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
        onProcess: (Process) -> Unit = {},
    ): AppResult<Unit> {
        val pb = try {
            ProcessBuilder(command).also {
                it.environment().putAll(env)
                it.redirectErrorStream(true)
            }
        } catch (t: Throwable) {
            return AppResult.Failure(AppError("GUEST_SPAWN_FAILED", "cannot build guest command: ${t.message}"))
        }
        val process = try {
            pb.start()
        } catch (t: Throwable) {
            return AppResult.Failure(AppError("GUEST_SPAWN_FAILED", "cannot spawn guest command: ${t.message}"))
        }
        runCatching { onProcess(process) }
        val logFile = File(logsDir(), "process-guest.log")
        val thread = Thread({
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    val cleaned = redact(line)
                    runCatching { logFile.appendText(cleaned + "\n") }
                    onLine(cleaned)
                }
            } catch (_: IOException) {
                // stream closed by process exit
            }
        }, "sandbox-log-guest")
        thread.isDaemon = true
        thread.start()
        val code = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            -1
        }
        thread.join(2000)
        return if (code == 0) AppResult.Success(Unit)
        else AppResult.Failure(AppError("GUEST_CMD_FAILED", "guest command exit=$code"))
    }

    /**
     * Stops the process tree identified by [running.tag].
     *
     * Because the sandbox and DSH are two sibling PRoot processes, we cannot
     * rely on a single Process object. We locate the correct PRoot root by
     * scanning /proc for a process whose cmdline contains the tag's marker
     * (either [Constants.SANDBOX_KEEPALIVE_MARKER] or
     * [Constants.DSH_START_SCRIPT]) under our own pid, then SIGKILL the whole
     * descendant tree leaf-first.
     */
    fun stop(running: RunningProcess) {
        Log.i(TAG, "stop(): stopping ${running.tag}")
        val myPid = android.os.Process.myPid()
        val marker = when (running.tag) {
            "sandbox" -> Constants.SANDBOX_KEEPALIVE_MARKER
            "dsh" -> Constants.DSH_START_SCRIPT
            else -> running.tag
        }
        val prootPid = findDescendantByCmdline(myPid, marker)
        if (prootPid != null) {
            val tree = descendantPids(prootPid)
            Log.i(TAG, "stop(): proot=$prootPid descendants=${tree.size} marker=$marker")
            // Children first, then the proot root.
            (tree + prootPid).forEach { killPid(it) }
        } else {
            Log.w(TAG, "stop(): proot process not found under pid $myPid for marker $marker")
        }
        // Best-effort cleanup of the Process object itself.
        try {
            running.process.destroy()
        } catch (_: Throwable) {
        }
    }

    /** Returns pids of all processes whose cmdline contains [needle] and whose ancestor is [rootPid]. */
    private fun findDescendantByCmdline(rootPid: Int, needle: String): Int? {
        val all = readProcTable() ?: return null
        val children = childrenOf(all, rootPid)
        val queue = ArrayDeque(children)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            val cmdline = readCmdline(pid)
            if (cmdline != null && cmdline.contains(needle)) return pid
            queue.addAll(childrenOf(all, pid))
        }
        return null
    }

    /** All descendant pids of [rootPid], BFS order (parents before children). */
    private fun descendantPids(rootPid: Int): List<Int> {
        val all = readProcTable() ?: return emptyList()
        val result = mutableListOf<Int>()
        val queue = ArrayDeque(childrenOf(all, rootPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result.add(pid)
            queue.addAll(childrenOf(all, pid))
        }
        return result
    }

    private fun readProcTable(): Map<Int, Int>? {
        val dir = File("/proc")
        val entries = dir.listFiles { f -> f.name.all { it.isDigit() } } ?: return null
        val map = HashMap<Int, Int>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = readTextOrNull(File(entry, "stat")) ?: continue
            // Format: pid (comm) state ppid ...
            val rest = stat.substringAfter(") ").trim()
            val ppid = rest.split(' ').getOrNull(1)?.toIntOrNull() ?: continue
            map[pid] = ppid
        }
        return map
    }

    private fun childrenOf(all: Map<Int, Int>, parent: Int): List<Int> =
        all.filterValues { it == parent }.keys.sorted()

    private fun readCmdline(pid: Int): String? =
        readTextOrNull(File("/proc/$pid/cmdline"))?.replace('\u0000', ' ')

    private fun readTextOrNull(file: File): String? =
        try {
            file.readText()
        } catch (_: Throwable) {
            null
        }

    private fun killPid(pid: Int) {
        try {
            val exit = ProcessBuilder("/system/bin/kill", "-KILL", pid.toString()).start().waitFor()
            if (exit != 0) Log.w(TAG, "kill $pid failed exit=$exit")
        } catch (t: Throwable) {
            Log.w(TAG, "kill $pid threw: ${t.message}")
        }
    }

    /**
     * Last-resort process-tree kill: finds every pid whose cmdline contains
     * [prefix], collects all descendants, and terminates them in post-order
     * (children before parents) so no PRoot/DSH orphan survives. Cheap /proc
     * scan, safe to call repeatedly; a no-op when no matching process exists.
     */
    fun killAll(prefix: String) {
        val table = readProcTable() ?: return
        val roots = table.keys.filter { readCmdline(it)?.contains(prefix) == true }
        if (roots.isEmpty()) {
            Log.i(TAG, "killAll('$prefix'): no matching process")
            return
        }
        val allDesc = mutableSetOf<Int>()
        fun addTree(parent: Int) {
            for (c in childrenOf(table, parent)) if (allDesc.add(c)) addTree(c)
        }
        roots.forEach { addTree(it); allDesc.add(it) }

        val order = ArrayList<Int>()
        val visited = HashSet<Int>()
        fun postOrder(pid: Int) {
            if (!visited.add(pid)) return
            for (c in childrenOf(table, pid)) postOrder(c)
            order.add(pid)
        }
        allDesc.forEach { postOrder(it) }

        // Reverse post-order => deepest children die before their parents.
        order.asReversed().forEach { killPid(it) }
        Log.i(TAG, "killAll('$prefix'): killed ${order.size} pid(s)")
    }
}
