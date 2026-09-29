package com.dshbox.app.runtime

import com.dshbox.app.sandbox.SandboxManager
import java.io.File

/**
 * 把 `.deb` 的**数据部分**解包进沙箱，不经过 dpkg。
 *
 * ## 为什么需要它
 *
 * 本环境的包管理链有若干先天限制（属主操作、服务启停、账本备份），虽然已有专门的
 * 兼容手段，但用户「只想装个命令行工具」时最稳的路径仍然是**根本不走事务**：
 * 直接把数据部分摊到根文件系统里，工具即可用。
 *
 * ## 边界（刻意的设计，不是缺陷）
 *
 * - **不写 dpkg 账本**：既不改 `var/lib/dpkg/status`，也不调 `dpkg -i` / `--unpack`。
 *   两个写入方共用一个数据库是必然出错的模式，而账本由运行环境层的组装方按整层视角生成。
 *   代价是这类安装对 `dpkg` 不可见（`dpkg -l` 里没有它），也无法用 `apt` 卸载。
 * - **不执行维护脚本**：因此不会创建用户、不注册 `update-alternatives`、不启动服务。
 *   需要这些的包应当走「随包 overlay」这条路。
 * - **会随运行环境层一起被替换**：解包落点在层内，层更新后这些文件不再存在。
 *   宿主侧的安装清单（[inventoryFile]）保留了包名与来源，便于诊断与重装。
 *
 * ## 结果为什么走文件而不是输出流
 *
 * `runGuestCommand` 的输出由**独立线程**逐行回调，主流程在进程退出后即返回，
 * 命令末尾几行存在竞态；而结论标记恰恰在最后。因此命令把结论写进工作区里的一个
 * 报告文件，调用方读该文件——结论与输出时序无关，且报告内容可留档排查。
 */
object DebUnpackInstaller {

    /** 安装清单文件名（放调用方给定的宿主侧目录）。 */
    const val INVENTORY_NAME = "deb-installs.log"

    /** 报告文件（guest 视角，落在工作区里我们自己的资产目录下）。 */
    const val REPORT_GUEST_PATH = "/root/projects/.dsh/dshbox/deb-install.report"

    /** 报告文件相对工作区根（宿主侧同一份）。 */
    private const val REPORT_REL = ".dsh/dshbox/deb-install.report"

    /** 解包是否成功的判据标记。 */
    internal const val MARK_CONTROL = "DSHBOX_DEB_CONTROL"
    internal const val MARK_ENTRIES = "DSHBOX_DEB_ENTRIES"
    internal const val MARK_OK = "DSHBOX_DEB_OK"
    internal const val MARK_FAIL = "DSHBOX_DEB_FAIL"

    data class DebInfo(
        val name: String,
        val version: String,
        val architecture: String,
        /** 归档内条目数（`dpkg-deb -c | wc -l`），仅用于清单诊断。 */
        val entries: Int,
        /** 数据部分是否已成功摊开。 */
        val unpacked: Boolean,
    )

    /** 文件名是不是可安装的 `.deb`。 */
    fun isDeb(name: String): Boolean = name.endsWith(".deb", ignoreCase = true)

    /**
     * guest 内执行的命令。
     *
     * 只做三件事：读控制字段（供清单用）、统计归档条目数、把数据部分摊到 `/`，
     * 最后跑一次 `ldconfig` 让新库可被找到（缺失时忽略）。所有会写账本的写法都不在其中。
     */
    fun buildCommand(debGuestPath: String): String {
        val q = shellQuote(debGuestPath)
        return "echo $MARK_CONTROL; " +
            // 字段名必须显式列出（不带字段名时本环境的 dpkg-deb 不输出内容）；
            // 且必须把 TMPDIR 指到 guest 的 /tmp：`-f` 会把控制区解到临时目录，
            // 而宿主注入的环境里 TMPDIR 在 rootfs 内并不存在，届时它会失败并只往 stderr 抱怨
            // （真机实测：报告里控制字段区为空，而解包本身成功）。
            // 这里**不**屏蔽 stderr：失败原因要落进报告，便于下次一眼看清。
            "TMPDIR=/tmp dpkg-deb -f $q Package Version Architecture; " +
            "echo $MARK_ENTRIES; " +
            "dpkg-deb -c $q 2>/dev/null | wc -l; " +
            "if dpkg-deb --fsys-tarfile $q | tar -x --no-same-owner -p -C /; then " +
            "echo $MARK_OK; else echo $MARK_FAIL; fi; " +
            "ldconfig 2>/dev/null || true"
    }

    /**
     * 把 [buildCommand] 的结论写进报告文件（工作区内，宿主侧可读）。
     * 目录先建：报告落在我们自己的资产目录下，不能假定它已存在。
     */
    fun buildReportCommand(debGuestPath: String, reportGuestPath: String = REPORT_GUEST_PATH): String =
        "mkdir -p ${shellQuote(reportGuestPath.substringBeforeLast('/'))}; " +
            "{ ${buildCommand(debGuestPath)}; } > ${shellQuote(reportGuestPath)} 2>&1"

    /**
     * 解析报告（纯函数，JVM 可测）。
     *
     * `dpkg-deb -f` 打印的是 `字段: 值` 形式，因此按**行首前缀**取字段；
     * 不做 trim 前匹配，避免把多行 `Description` 的续行误当成字段。
     */
    fun parseReport(output: List<String>): DebInfo? {
        val controlAt = output.indexOfFirst { it.trim() == MARK_CONTROL }
        if (controlAt < 0) return null
        val entriesAt = output.indexOfFirst { it.trim() == MARK_ENTRIES }
        if (entriesAt < controlAt) return null

        val name = field(output, controlAt, entriesAt, "Package")
        val version = field(output, controlAt, entriesAt, "Version")
        val arch = field(output, controlAt, entriesAt, "Architecture")
        if (name == null || version == null || arch == null) return null

        val unpacked = output.any { it.trim() == MARK_OK }
        // 既没有成功也没有失败标记：命令被中途打断，不当成结论。
        if (!unpacked && output.none { it.trim() == MARK_FAIL }) return null

        val countLine = output.drop(entriesAt + 1)
            .map { it.trim() }
            .firstOrNull { it.toIntOrNull() != null }
        return DebInfo(
            name = name,
            version = version,
            architecture = arch,
            entries = countLine?.toIntOrNull() ?: 0,
            unpacked = unpacked,
        )
    }

    /** 在 [MARK_CONTROL] 与 [MARK_ENTRIES] 之间取首个 `字段: 值`。 */
    private fun field(lines: List<String>, from: Int, to: Int, name: String): String? {
        val prefix = "$name:"
        for (i in from + 1 until minOf(to, lines.size)) {
            val line = lines[i]
            if (line.startsWith(prefix)) {
                return line.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** 清单文件（宿主侧；不存在时由 [appendInventory] 创建）。 */
    fun inventoryFile(dir: File): File = File(dir, INVENTORY_NAME)

    /**
     * 追加一条安装记录。只写我们自己的一份文本清单，不碰沙箱、不碰 dpkg 账本。
     * 记录失败不抛出：清单只是诊断用，不该让安装本身变成失败。
     */
    fun appendInventory(dir: File, info: DebInfo, sourceName: String, nowMs: Long) {
        runCatching {
            dir.mkdirs()
            inventoryFile(dir).appendText(
                "$nowMs\t${info.name}\t${info.version}\t${info.architecture}\t" +
                    "${info.entries}\t${info.unpacked}\t$sourceName\n",
            )
        }
    }

    /**
     * 在 guest 内安装 [debGuestPath]（须是 guest 视角的绝对路径），并把结果记入
     * [inventoryDir] 下的清单。返回 null 表示没能得出可靠结论（不是可用的 deb，或命令被打断）。
     *
     * @param workspaceDir 宿主侧工作区根（guest 的 `/root/projects`），报告文件经它读回。
     */
    suspend fun install(
        sandboxManager: SandboxManager,
        debGuestPath: String,
        workspaceDir: File,
        inventoryDir: File,
        nowMs: Long = System.currentTimeMillis(),
        onLine: (String) -> Unit = {},
    ): DebInfo? {
        val report = File(workspaceDir, REPORT_REL)
        runCatching { report.delete() }
        sandboxManager.runGuestCommand(
            buildReportCommand(debGuestPath),
            onLine = onLine,
        )
        val lines = runCatching { report.readLines() }.getOrDefault(emptyList())
        val info = parseReport(lines)
        // 结论可用时清掉报告；不可用时**留下**——那是唯一能说明当时发生了什么的现场。
        if (info != null) runCatching { report.delete() }
        if (info == null) return null
        appendInventory(inventoryDir, info, File(debGuestPath).name, nowMs)
        return info
    }

    /** 单引号包裹（POSIX shell 里单引号内除单引号本身都是字面量）。 */
    internal fun shellQuote(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"
}
