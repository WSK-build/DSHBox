package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.common.LogRedactor
import java.io.File
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
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

    /**
     * 带轮转的日志追加（策略 A：单文件 2MB，超限滚为 `<file>.prev`，
     * 保留最近两代）。DSH 改坏自身导致无法启动时，"本次失败 + 上一份启动"两份
     * 记录即足够对比排障；无限追加会让日志文件无限膨胀。进程写入在同一 app
     * 进程内，synchronized 即可保证原子性。
     */
    private fun appendRotated(logFile: File, line: String) {
        synchronized(logFile) {
            runCatching {
                if (logFile.length() + line.length.toLong() > MAX_LOG_BYTES) {
                    val prev = File(logFile.parentFile, logFile.name + ".prev")
                    // 覆盖式保留上一代；剩余的更旧代丢弃（策略 A 只保两代）。
                    prev.delete()
                    logFile.renameTo(prev)
                }
                logFile.appendText(line + "\n")
            }
        }
    }

    companion object {
        private const val TAG = "SandboxProcessRunner"

        /** 单文件日志上限（策略 A 轮转）。 */
        private const val MAX_LOG_BYTES = 2L * 1024 * 1024

        /** DSH 角色的 tag：这一份日志按启动分段保留，不参与体积轮转。 */
        private const val DSH_LOG_TAG = "dsh"

        /** guest 命令正常等待上限（npm 全量安装可达 10-20 分钟，兜底需留足）。 */
        private const val GUEST_COMMAND_TIMEOUT_MS = 10L * 60 * 1000
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
        /** 共享区宿主目录（guest `/root/share/<别名>` 的一组绑定）；见 [layeredProotPrefix]。 */
        shareBinds: List<Pair<String, File>> = emptyList(),
    ): List<String> = buildList {
        addAll(
            layeredProotPrefix(
                prootBinary, rootfsDir, workspaceBind, nodeDir, dshDir, shareBinds = shareBinds,
            ),
        )
        add("--cwd=/root")
        add("--kill-on-exit")
        // Guest entry: the rootfs's own bash (no host /system/bin/sh wrapper) —
        // see buildProotDshCommand for why host binaries are avoided. The keepalive
        // echo of [Constants.SANDBOX_KEEPALIVE_MARKER] stays on the cmdline so
        // killAll()'s marker lookup keeps working.
        add("/" + DefaultSandboxManager.GUEST_BASH_RELATIVE)
        add("-c")
        add("echo ${Constants.SANDBOX_KEEPALIVE_MARKER}; trap \"\" HUP INT TERM; while true; do sleep 3600; done")
    }

    /**
     * Builds the PRoot command for DSH. The final command contains
     * [Constants.DSH_START_SCRIPT] so we can locate this PRoot tree at stop time.
     *
     * [shimHostDir] 是宿主侧存放 `link-shim.mjs` 的目录，绑定到 guest 的
     * `/opt/dshbox` 后以 `--import` 预加载。**这是本项目唯一的 Android 兼容注入手段**：
     * 它只在运行期替换 `node:fs/promises` 的 `link`，DSH 源码一个字节都不改。
     * 传 null/空则不加绑定与预加载（测试或垫片缺失时退化为上游原始行为）。
     */
    fun buildProotDshCommand(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        nodeDir: String? = null,
        dshDir: String? = null,
        shimHostDir: String? = null,
        /**
         * 追加给 dsh 启动器的 `--patch` overlay（guest 内路径，可多个）。
         *
         * 这是 dsh **官方**的额外 patch 层入口（应用在 profile 层之后，见 `dsh/lib/bin.js`），
         * 我们用它注入自己需要的覆盖层（功能开关层 / 插件管理停用层 / 绝对安全模式层）——
         * 不修改 DSH 源码、不动 profile 文件。
         * 调用方只在文件确实存在时才传入，否则 dsh 会因找不到 patch 文件而启动失败。
         */
        dshPatchGuestPaths: List<String> = emptyList(),
        /**
         * 工具链缓存宿主目录（pnpm store / corepack 缓存），绑定到 guest 的
         * [Constants.DSHBOX_GUEST_CACHE_DIR]。**必须绑**：base 层里同名路径只是个
         * root 属主的空目录，guest 侧不可写，pnpm 建 store 操作锁时会直接 EPERM
         * （`ERR_PNPM_STORE_DIR_RESOLVE_OPERATION_LOCK`），表现为插件市场装不上插件。
         * 空则不绑（保持 base 层路径，用于没有运行时的测试形态）。
         */
        guestCacheHostDir: String? = null,
        /** 手机助手暴露给 guest 的宿主子树，见 [SandboxConfig.pilotEntryDir]。空则不绑。 */
        pilotHostDir: String? = null,
        /** Browser CLI/broker rendezvous tree, guest path `/opt/browser`. */
        browserHostDir: String? = null,
        /** 共享区绑定清单（guest 别名 → 宿主目录）；见 [layeredProotPrefix]。 */
        shareBinds: List<Pair<String, File>> = emptyList(),
    ): List<String> = buildList {
        addAll(
            layeredProotPrefix(
                prootBinary, rootfsDir, workspaceBind, nodeDir, dshDir, shimHostDir,
                guestCacheHostDir, pilotHostDir, browserHostDir, shareBinds,
            ),
        )
        add("--cwd=/root/projects")
        add("--kill-on-exit")
        // Guest entry: exec the guest's own node directly as argv (no host
        // /system/bin/sh wrapper). This mirrors proot-distro's canonical form
        // (its login entry is the rootfs's own /usr/bin/env, never a host
        // binary): some vendor kernels reject execve of host
        // /system/bin/* binaries from a ptraced process with
        // EPERM, which killed every sandbox boot on those devices. The marker
        // [Constants.DSH_START_SCRIPT] still lands in /proc/<pid>/cmdline, so
        // stop()'s tree lookup is unaffected.
        add("/usr/local/bin/node")
        add("--expose-internals")
        // `--import` 预加载硬链接兼容垫片（见本类 KDoc）：它必须先于 DSH 模块图求值，
        // 因此放在入口脚本之前。垫片缺失时 Node 会直接报错退出——所以调用方只在确认
        // 垫片存在后才传入 shimHostDir。
        if (!shimHostDir.isNullOrBlank()) {
            add("--import")
            add(Constants.DSH_LINK_SHIM_GUEST_PATH)
        }
        add("/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js")
        add("--profile")
        add(Constants.DSH_WEB_PROFILE)
        dshPatchGuestPaths.filter { it.isNotBlank() }.forEach { patch ->
            add("--patch")
            add(patch)
        }
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
     *
     * [shimHostDir]（可选）绑定到 guest `/opt/dshbox`，供 `--import` 预加载
     * 硬链接兼容垫片。挂载点在 base rootfs 里无需预先存在——PRoot 会自行创建
     * （与 /opt/dshapp/runtime、/root/projects 同理）。
     *
     * [shareBinds]（可选）共享区绑定清单（guest 别名 → 宿主目录），逐个绑到
     * `/root/share/<别名>`（见 [Constants.SHARE_AREA_GUEST_DIR]）；目录缺失/别名为空的
     * 条目跳过。调用方负责预建挂载点，并把命名空间壳（`/root/share` 自身）设为只读。
     */
    private fun layeredProotPrefix(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        nodeDir: String?,
        dshDir: String?,
        shimHostDir: String? = null,
        guestCacheHostDir: String? = null,
        pilotHostDir: String? = null,
        browserHostDir: String? = null,
        shareBinds: List<Pair<String, File>> = emptyList(),
    ): List<String> = buildList {
        add(prootBinary)
        add("--rootfs=$rootfsDir")
        add("--bind=/system")
        add("--bind=/apex")
        add("--bind=/proc")
        add("--bind=/dev")
        if (!nodeDir.isNullOrBlank()) add("--bind=$nodeDir:/usr/local")
        if (!dshDir.isNullOrBlank()) add("--bind=$dshDir:/opt/dshapp/runtime")
        if (!shimHostDir.isNullOrBlank()) add("--bind=$shimHostDir:${Constants.DSH_LINK_SHIM_GUEST_DIR}")
        // 工具链缓存：与 runGuestCommand 用同一个宿主目录（不绑就会落到 base 层里
        // 那个 root 属主、guest 不可写的占位目录）。
        if (!guestCacheHostDir.isNullOrBlank()) {
            add("--bind=$guestCacheHostDir:${Constants.DSHBOX_GUEST_CACHE_DIR}")
        }
        if (!pilotHostDir.isNullOrBlank()) add("--bind=$pilotHostDir:/opt/pilot")
        if (!browserHostDir.isNullOrBlank()) add("--bind=$browserHostDir:/opt/browser")
        shareBinds.forEach { (name, dir) ->
            if (name.isNotBlank() && dir.isDirectory) {
                add("--bind=${dir.absolutePath}:${Constants.SHARE_AREA_GUEST_DIR}/$name")
            }
        }
        add("--bind=$workspaceBind:/root/projects")
    }

    fun start(
        command: List<String>,
        tag: String,
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
        /** 在 [redact] 打码**之前**收到进程的每一行原始输出（仅内存使用，
         *  不写日志文件、不进 logcat）。DSH 宿主集成用它解析 `dsh web:` 启动 URL 里的
         *  进程级 launchToken——URL 一旦经过 redact 即被打码无从复原。 */
        onRawLine: (String) -> Unit = {},
    ): RunningProcess {
        val pb = ProcessBuilder(command)
        workingDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val logFile = File(logsDir(), "process-$tag.log")
        // DSH 这一份日志按「启动次数」分段保留（见 [BootSegmentedLog]）；其余角色仍按体积轮转。
        val bootLog = if (tag == DSH_LOG_TAG) BootSegmentedLog(logFile) else null
        val process = pb.start()
        val running = RunningProcess(process, tag)
        // 启动标记行必须排在这次启动的输出**之前**：进程已起来（没起来就不该记一次），
        // 而日志泵还没开始读。
        bootLog?.beginBoot()
        // Non-interactive by design: close the write end at once so a stdin reader
        // sees EOF instead of blocking until the command timeout.
        runCatching { process.outputStream.close() }

        val thread = Thread({
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        runCatching { onRawLine(line) }
                        val cleaned = redact(line)
                        if (bootLog != null) bootLog.append(cleaned) else appendRotated(logFile, cleaned)
                        // Mirror to logcat so sandbox/DSH child output is observable
                        // adb (also useful for on-device diagnostics when the app data
                        // dir is not readable, e.g. non-rooted release builds).
                        Log.i(TAG, "proc-$tag: $cleaned")
                    }
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
     * [onProcess] receives the spawned host Process right after
     * start so long-running callers (guest npm install) can destroy it to
     * cancel; proot's --kill-on-exit cleans the guest-side tree.
     */
    fun runGuestCommand(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
        onProcess: (Process) -> Unit = {},
        shouldAbort: () -> Boolean = { false },
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
        // Non-interactive by design: close the write end at once so a stdin reader
        // sees EOF instead of blocking until the command timeout.
        runCatching { process.outputStream.close() }
        val logFile = File(logsDir(), "process-guest.log")
        val thread = Thread({
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        val cleaned = redact(line)
                        appendRotated(logFile, cleaned)
                        onLine(cleaned)
                    }
                }
            } catch (_: IOException) {
                // stream closed by process exit
            }
        }, "sandbox-log-guest")
        thread.isDaemon = true
        thread.start()
        // waitFor 改为可中断轮询——在线安装点「取消」后立即收敛，
        // 不再干等 proot 残壳自然退出（npm 秒退后 proot 仍会残留 ~20s，
        // 期间取消按钮形同虚设）。取消分支等待 cancel 侧（RuntimeUpdateManager）
        // 的 SIGKILL 整树落地，上限 5s，兜底 destroyForcibly。
        // 正常等待加 10 分钟超时兜底——proot/guest 异常卡死时
        // （现场：装配状态检查 grep 卡住，界面「正在检查…」永转）命令必须有界。
        val code: Int
        if (!shouldAbort()) {
            val deadline = System.currentTimeMillis() + GUEST_COMMAND_TIMEOUT_MS
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (process.isAlive) {
                Log.w(TAG, "guest command timed out; killing process")
                runCatching { process.destroyForcibly() }
                code = -1
            } else {
                code = process.exitValue()
            }
        } else {
            var waited = 0
            while (process.isAlive && waited < 5_000) {
                Thread.sleep(100)
                waited += 100
            }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
                code = -1
            } else {
                code = process.exitValue()
            }
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

    /**
     * 与沙箱相关的进程树在当前时刻的累计 CPU 时间（毫秒）。
     *
     * [markers] 里的每个 cmdline 标记定位一棵树（沙箱 keepalive / DSH 启动脚本），各树
     * 取并集去重后求和——DSH 的树若挂在沙箱树下面，两次求和会把它算两遍。整趟只做
     * **一次** /proc 表遍历，树形与 cmdline 匹配都基于这一份快照。
     *
     * 只读 /proc/<pid>/stat，不改变任何进程状态，也不做常驻动作：由调用方按需调用
     * （监控类界面每若干秒一次）。树找不到或一条 stat 都读不到时返回 null。
     */
    fun cpuMillisForMarkers(markers: List<String>): Long? {
        val myPid = android.os.Process.myPid()
        val table = readProcTable() ?: return null
        val pids = linkedSetOf<Int>()
        for (marker in markers) {
            val root = findDescendantByCmdline(table, myPid, marker) ?: continue
            pids += root
            pids += descendantPids(table, root)
        }
        if (pids.isEmpty()) return null
        var totalTicks = 0L
        var read = 0
        for (pid in pids) {
            val stat = readTextOrNull(File("/proc/$pid/stat")) ?: continue
            val ticks = cpuTicksFromStatLine(stat) ?: continue
            totalTicks += ticks
            read++
        }
        if (read == 0) return null
        return totalTicks * 1000L / resolveProcTicksPerSecond()
    }

    /** Returns pids of all processes whose cmdline contains [needle] and whose ancestor is [rootPid]. */
    private fun findDescendantByCmdline(rootPid: Int, needle: String): Int? =
        readProcTable()?.let { findDescendantByCmdline(it, rootPid, needle) }

    /** 基于已读到的进程表做同样的查找：给需要复用同一份快照的调用方。 */
    private fun findDescendantByCmdline(table: Map<Int, Int>, rootPid: Int, needle: String): Int? {
        val queue = ArrayDeque(childrenOf(table, rootPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            val cmdline = readCmdline(pid)
            if (cmdline != null && cmdline.contains(needle)) return pid
            queue.addAll(childrenOf(table, pid))
        }
        return null
    }

    /** All descendant pids of [rootPid], BFS order (parents before children). */
    private fun descendantPids(rootPid: Int): List<Int> =
        readProcTable()?.let { descendantPids(it, rootPid) } ?: emptyList()

    /** 基于已读到的进程表做同样的遍历：给需要复用同一份快照的调用方。 */
    private fun descendantPids(table: Map<Int, Int>, rootPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        val queue = ArrayDeque(childrenOf(table, rootPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result.add(pid)
            queue.addAll(childrenOf(table, pid))
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
            Os.kill(pid, OsConstants.SIGKILL)
        } catch (t: Throwable) {
            Log.w(TAG, "kill $pid failed: ${t.message}")
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

/** /proc 计数单位 USER_HZ 的兜底值；实际值用 sysconf(_SC_CLK_TCK) 现取（同 AOSP ProcessCpuTracker）。 */
internal const val PROC_TICKS_PER_SECOND = 100L

/** 现取 USER_HZ；取不到（或非法）回退 [PROC_TICKS_PER_SECOND]。 */
internal fun resolveProcTicksPerSecond(): Long =
    runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(PROC_TICKS_PER_SECOND)
        .takeIf { it > 0L } ?: PROC_TICKS_PER_SECOND

/**
 * /proc/<pid>/stat 一行 → 该进程累计 CPU 时间（jiffies = utime + stime）；解析失败返回 null。
 *
 * 字段位置依 proc(5)：去掉行首 "pid (comm) " 之后重新编号，state 为 0，utime 在第 11、
 * stime 在第 12 位。纯函数，便于单测覆盖字段错位这类静默错误。
 */
internal fun cpuTicksFromStatLine(statLine: String): Long? {
    val rest = statLine.substringAfter(") ", missingDelimiterValue = "").trim()
    if (rest.isEmpty()) return null
    val fields = rest.split(' ')
    val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
    val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
    return utime + stime
}
