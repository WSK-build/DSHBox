package com.dshbox.app.sandbox

/**
 * guest 进程 PATH 的合并工具。
 *
 * 背景：`pnpm` 不是 guest 自带的命令，而是宿主在 `$DSH_HOME/dshbox/bin` 里生成的垫片
 * （见 `SandboxService.PNPM_CLI_SHIM`）。但层内的 env 片段（`env.d/base.sh`）里的 PATH
 * 是硬编码的 `/usr/local…`，不含该目录；而层一旦装好就不会被重写，于是宿主进程
 * `spawn pnpm` 只会拿到 ENOENT —— 用户看到的是「插件市场装不上插件」「agent 装插件失败」。
 *
 * 合并放在启动装配阶段（见 [DefaultSandboxManager.buildProotEnv]）做，而不是去改模板：
 * 不需要重装运行时层，重启一次 DSH 即生效。
 */
internal object GuestPath {

    /**
     * 把 [entry] 放到 PATH 最前；已存在则保持原样（不重复、不改变其原有位置）。
     *
     * 前置而不是追加：将来 `/usr/local/bin` 里若真的出现同名命令，仍以宿主生成的垫片为准；
     * 垫片本身只是 corepack 的转发，与裸跑 pnpm 行为一致。
     */
    fun withEntryFirst(path: String?, entry: String): String {
        val parts = path.orEmpty().split(':').filter { it.isNotEmpty() }
        if (entry.isEmpty() || entry in parts) return parts.joinToString(":")
        return (listOf(entry) + parts).joinToString(":")
    }
}
