package com.dshbox.app.runtime

import android.system.Os
import java.io.File

/**
 * 保证 guest 的 `etc/passwd` 里有**当前运行期 uid** 的条目。
 *
 * ## 为什么需要它
 *
 * PRoot 下进程 uid 是 Android 应用的真实 uid（每次安装一份，随设备与安装变化），
 * 而 guest 的 `etc/passwd` 只带 root 与系统用户，没有这个 uid。后果分两层：
 *
 * 1. **程序侧**：Node 的 `os.userInfo()` 走 libuv `getpwuid_r`，查不到即 `ENOENT`。
 *    DSH 的 Web 终端用它解析 shell（`process.env.SHELL || userInfo()`）——
 *    这一层由启动参数注入 `SHELL` 短路掉（见 `buildProotEnv`）。
 * 2. **shell 侧**：bash 自己也要把 uid 换成用户名来渲染提示符，查不到就回退成
 *    `I have no name!`。**这一步环境变量管不到**，必须真的有 passwd 条目；
 *    同一原因也让 `id` / `whoami` / `ls -l` 的属主显示缺失。
 *
 * 因此这里补上一条属于自己的条目。uid/gid 在**运行时**读取而不写死：换设备或换安装
 * 都会变，写死必然失效。
 *
 * ## 边界
 *
 * - 只动**自己那一条**（按用户名识别）：先剔除同名旧行再追加，绝不改动 root 与系统用户。
 * - 幂等：内容无需变化时直接返回 [Outcome.ALREADY_PRESENT]，不重写文件。
 * - 原有 passwd 缺少 `root` 条目时**不动它**：那种状态说明层已损坏，此时再写只会更糟。
 * - 读写失败一律不抛，以 [Outcome.SKIPPED] 返回：这条缺失只影响提示符观感，不该连累 bootstrap。
 */
object GuestUserProvisioner {

    /** 本次调用的结果。区分"已存在"与"跳过"，便于日志判读（不区分就只能靠猜）。 */
    enum class Outcome {
        /** 写入/对齐了条目。 */
        UPDATED,

        /** 已有完全一致的条目，未触碰文件。 */
        ALREADY_PRESENT,

        /** 未介入：base 层或 passwd 不可用、层已损坏、或取不到 uid/gid。 */
        SKIPPED,
    }

    /** 我们在 passwd 中使用的用户名，也是识别"自己那一行"的唯一依据。 */
    internal const val USER_NAME = "dshbox"

    /** 家目录与登录 shell 与 root 对齐；`HOME=/root` 由 `buildProotEnv` 注入。 */
    private const val HOME_DIR = "/root"
    private const val LOGIN_SHELL = "/bin/bash"

    /**
     * 确保 [baseRoot]（即 `runtime-current/base`）下的 `etc/passwd` 含当前 uid 的条目。
     */
    fun provision(baseRoot: File): Outcome {
        if (!baseRoot.isDirectory) return Outcome.SKIPPED
        val file = File(baseRoot, "etc/passwd")
        val current = runCatching { file.readText() }.getOrNull() ?: return Outcome.SKIPPED
        // 层已损坏（连 root 都没有）：不介入，避免把系统账号一并抹掉。
        if (!current.lineSequence().any { it.startsWith("root:") }) return Outcome.SKIPPED
        val uid = runCatching { Os.getuid() }.getOrDefault(-1)
        val gid = runCatching { Os.getgid() }.getOrDefault(-1)
        if (uid < 0 || gid < 0) return Outcome.SKIPPED
        val updated = withEntry(current, uid, gid)
        if (updated == current) return Outcome.ALREADY_PRESENT
        return runCatching {
            file.writeText(updated)
            Outcome.UPDATED
        }.getOrDefault(Outcome.SKIPPED)
    }

    /**
     * 返回把 [USER_NAME] 条目对齐到 `uid:gid` 之后的正文。
     *
     * 先剔除同名旧行再追加，因此重复调用不会累积，uid 变化后也不会留下旧条目。
     */
    internal fun withEntry(content: String, uid: Int, gid: Int): String {
        val kept = content.lineSequence()
            .filter { it.isNotBlank() && !isOurs(it) }
            .toList()
        val entry = "$USER_NAME:x:$uid:$gid::$HOME_DIR:$LOGIN_SHELL"
        return (kept + entry).joinToString(separator = "\n", postfix = "\n")
    }

    /** 该行是否是我们自己写下的那一条（按第一个字段匹配）。 */
    private fun isOurs(line: String): Boolean = line.substringBefore(':').trim() == USER_NAME
}
