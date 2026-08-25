package com.dshbox.terminal

import java.io.File

// Fabricates a tiny readable /proc subset that Android 16 denies to
// untrusted_app (SELinux blocks /proc/stat, /proc/uptime and /proc/loadavg;
// this device returns EACCES for all three, which makes htop crash at once).
//
// proot cannot override the kernel's SELinux check, so instead we shadow the
// unreadable files with binder sources: files with static, well-formed
// contents are created in the rootfs and mapped onto the guest /proc via proot
// --bind. htop then opens them successfully (CPU/uptime numbers are static
// placeholders; meminfo stays real because it is readable).
//
// Sources must exist before the session starts; ensureBindArgs() is called on
// every sandbox session creation.
object TerminalProcFake {

    fun ensureBindArgs(paths: TerminalPaths): List<String> {
        val dir = File(paths.debianRootfs, "root/.procfake")
        dir.mkdirs()
        write(dir, "stat", STAT)
        write(dir, "uptime", UPTIME)
        write(dir, "loadavg", LOADAVG)
        return listOf(
            "--bind=${File(dir, "stat").absolutePath}:/proc/stat",
            "--bind=${File(dir, "uptime").absolutePath}:/proc/uptime",
            "--bind=${File(dir, "loadavg").absolutePath}:/proc/loadavg",
        )
    }

    private fun write(dir: File, name: String, content: String) {
        val f = File(dir, name)
        if (f.length() == 0L) f.writeText(content)
    }

    // 8 CPU cores, static (htop computes % from deltas; with a static file the
    // cores just show ~0% but the UI renders and stays usable).
    private val STAT = buildString {
        append("cpu  1000 0 1000 1000000 0 0 0 0 0 0\n")
        for (i in 0 until 8) {
            append("cpu$i 125 0 125 125000 0 0 0 0 0 0\n")
        }
        append("intr 0 0 0\n")
        append("ctxt 0\n")
        append("btime 0\n")
        append("processes 0\n")
        append("procs_running 0\n")
        append("procs_blocked 0\n")
        append("softirq 0 0 0 0\n")
    }

    private const val UPTIME = "100000.00 500000.00\n"
    private const val LOADAVG = "0.00 0.01 0.05 1/100 1000\n"
}
