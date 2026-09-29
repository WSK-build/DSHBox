package com.dshbox.app.util

import java.io.File

/**
 * 「guest 内是否正有包管理工具在跑」的探测。
 *
 * 用途有两处，共用同一判定：① 账本快照/回滚在事务进行中必须让路；② 清理 apt 缓存不得
 * 删掉正在下载的 `.deb`。两者的共同前提是**用户终端里的 apt/dpkg 不在 app 的
 * [BackgroundOps] 忙判断范围内**，只能靠进程表辨认。
 *
 * PRoot 只是被追踪的父进程，guest 里的命令在宿主 `/proc` 中是**真实进程**，
 * 故读宿主进程表即可看到它们。
 */
object PackageToolProbe {

    /**
     * 命令名以这些前缀开头的进程视为「包管理工具」。
     *
     * 按 `argv[0]` 的 basename 前缀匹配，因此 `apt` / `apt-get` / `apt-config` /
     * `dpkg` / `dpkg-deb` / `dpkg-query` 都在内；`unpack` 前缀覆盖 dpkg 解包阶段的
     * 子进程名。
     */
    private val TOOL_PREFIXES = listOf("apt", "dpkg", "unpack")

    /**
     * 扫描 [procRoot] 下的进程表，判断是否有包管理工具在运行。
     * 任何读取失败都按「不在跑」处理：探测只用于**避免误删/误动**，
     * 宁可放过一次让路也不因探测异常阻断既有流程。
     */
    fun isRunning(procRoot: File = File("/proc")): Boolean = runCatching {
        val entries = procRoot.listFiles() ?: return false
        entries.any { looksLikePackageTool(it) }
    }.getOrDefault(false)

    private fun looksLikePackageTool(procDir: File): Boolean {
        // 只认以数字命名的进程目录，顺带排除 /proc/self 等符号链接。
        if (!procDir.isDirectory || procDir.name.toIntOrNull() == null) return false
        val cmdline = File(procDir, "cmdline")
        if (!cmdline.isFile) return false
        val raw = runCatching { cmdline.readBytes() }.getOrNull() ?: return false
        val argv0 = raw.toString(Charsets.UTF_8).substringBefore('\u0000')
        val name = argv0.substringAfterLast('/')
        return TOOL_PREFIXES.any { name.startsWith(it) }
    }
}
