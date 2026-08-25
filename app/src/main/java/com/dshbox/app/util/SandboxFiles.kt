package com.dshbox.app.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 沙盒 / 工作区文件视图的路径映射与列表项模型。
 *
 * ## 为什么需要路径映射
 *
 * PRoot 启动时将宿主 `user-data` 绑定为 guest 的 `/root/projects`：
 *
 * ```
 * --bind=user-data:/root/projects
 * ```
 *
 * 因此 guest 中的 `/root/projects` 与宿主 `user-data` 是**同一份数据**。
 * 沙盒视图展示 rootfs 时，rootfs 里自带的 `root/projects` 是解包时的空目录，
 * 若直接读写会造成「文件目录与虚拟系统（PRoot guest）脱节」。
 * [PathMapper] 把 rootfs 视图中的 `root/projects/...` 逻辑路径映射到 `user-data/...`
 * 物理路径，保证两视图始终看到同一份数据、操作实时同步，绝不固化。
 *
 * ## 视图约定
 *
 * - 沙盒模式：逻辑根 = rootfs（`runtime/runtime-current/base`，分层 L0），对应 guest `/`
 * - 工作区模式：逻辑根 = `user-data`，对应 guest `/root/projects`
 * - UI 全部基于「逻辑路径」导航 / 展示，磁盘读写统一经 [PathMapper.resolvePhysical]
 */
class PathMapper(
    val sandboxRoot: File,
    val workspaceRoot: File,
    val nodeLayer: File? = null,
    val dshLayer: File? = null,
) {
    /** rootfs 中对应 guest `/root/projects` 的逻辑路径（物理映射到 workspaceRoot）。 */
    val rootfsProjects: File = File(File(sandboxRoot, "root"), "projects")

    /** guest `/usr/local`（L1 node 由 PRoot 绑定到此处）。 */
    val rootfsUsrLocal: File = File(File(sandboxRoot, "usr"), "local")

    /** guest `/opt/dshapp/runtime`（L2 DSH 由 PRoot 绑定到此处）。 */
    val rootfsOptDshRuntime: File = File(File(File(sandboxRoot, "opt"), "dshapp"), "runtime")

    /** 进入文件页时兜底创建根目录 + 各层挂载点，确保 guest 目录树可从文件管理器到达。 */
    fun ensureRoots() {
        sandboxRoot.mkdirs()
        workspaceRoot.mkdirs()
        // 镜像 PRoot 层绑定：node -> /usr/local、dsh -> /opt/dshapp/runtime。
        rootfsUsrLocal.mkdirs()
        rootfsOptDshRuntime.mkdirs()
    }

    /** 将逻辑路径解析为真实物理路径；rootfs 视图的 /root/projects、/usr/local、/opt/dshapp/runtime 分别映射到各层。 */
    fun resolvePhysical(logical: File): File {
        val lp = logical.absolutePath
        val sp = rootfsProjects.absolutePath
        if (lp == sp) return workspaceRoot
        if (lp.startsWith("$sp${File.separator}")) {
            return File(workspaceRoot, lp.removePrefix("$sp${File.separator}"))
        }
        nodeLayer?.let { nl ->
            val mp = rootfsUsrLocal.absolutePath
            if (lp == mp) return nl
            if (lp.startsWith("$mp${File.separator}")) {
                return File(nl, lp.removePrefix("$mp${File.separator}"))
            }
        }
        dshLayer?.let { dl ->
            val mp = rootfsOptDshRuntime.absolutePath
            if (lp == mp) return dl
            if (lp.startsWith("$mp${File.separator}")) {
                return File(dl, lp.removePrefix("$mp${File.separator}"))
            }
        }
        return logical
    }

    /** 将物理路径反映射为逻辑路径（breadcrumb / 展示用）。 */
    fun toLogical(physical: File): File {
        nodeLayer?.let { nl ->
            val np = nl.absolutePath
            val p = physical.absolutePath
            if (p == np) return rootfsUsrLocal
            if (p.startsWith("$np${File.separator}")) {
                return File(rootfsUsrLocal, p.removePrefix("$np${File.separator}"))
            }
        }
        dshLayer?.let { dl ->
            val dp = dl.absolutePath
            val p = physical.absolutePath
            if (p == dp) return rootfsOptDshRuntime
            if (p.startsWith("$dp${File.separator}")) {
                return File(rootfsOptDshRuntime, p.removePrefix("$dp${File.separator}"))
            }
        }
        val wp = workspaceRoot.absolutePath
        val p = physical.absolutePath
        if (p == wp) return rootfsProjects
        if (p.startsWith("$wp${File.separator}")) {
            return File(rootfsProjects, p.removePrefix("$wp${File.separator}"))
        }
        return physical
    }

    /** 判断物理路径是否位于 user-data（即是否是通过 /root/projects 映射进入的）。 */
    fun isWorkspaceData(physical: File): Boolean {
        val wp = workspaceRoot.absolutePath
        val p = physical.absolutePath
        return p == wp || p.startsWith("$wp${File.separator}")
    }
}

/** 文件风险级别：用于软保护（可操作，但操作时弹窗提醒）。 */
enum class RiskLevel { NORMAL, SYSTEM_DIR, DSH_DATA }

/**
 * 文件列表项：全部元数据在 IO 线程预计算，避免 Compose 渲染时在主线程执行
 * `isDirectory / length / lastModified` 磁盘 IO（大目录卡顿）。
 */
data class FileEntry(
    val name: String,
    /** 逻辑路径（绝对路径字符串），UI 用其导航。 */
    val logicalPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val risk: RiskLevel,
) {
    val isHidden: Boolean get() = name.startsWith(".")
}

/** rootfs 顶层系统绑定目录（guest 内由 PRoot --bind 提供，修改无意义且风险高）+ 层元数据标记。 */
private val SYSTEM_DIR_NAMES = listOf("proc", "sys", "dev", "system", "apex", "tmp", ".dshbox")

/** 判断单个条目的风险级别：rootfs 顶层系统目录 / DSH 内部数据目录。 */
fun riskOf(name: String, isTopLevelRootfs: Boolean): RiskLevel = when {
    name == ".dsh" -> RiskLevel.DSH_DATA
    isTopLevelRootfs && name in SYSTEM_DIR_NAMES -> RiskLevel.SYSTEM_DIR
    else -> RiskLevel.NORMAL
}

/**
 * 在 IO 线程扫描逻辑目录，返回预计算元数据的列表项。
 * [isTopLevel] 用于 rootfs 视图首层识别系统绑定目录（仅沙盒模式顶层为 true）。
 */
fun scanDirectory(logicalDir: File, mapper: PathMapper, isTopLevel: Boolean): List<FileEntry> {
    val physical = mapper.resolvePhysical(logicalDir)
    return physical.listFiles()?.mapNotNull { f ->
        val name = f.name
        FileEntry(
            name = name,
            logicalPath = File(logicalDir, name).absolutePath,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0L else runCatching { f.length() }.getOrDefault(0L),
            lastModified = runCatching { f.lastModified() }.getOrDefault(0L),
            risk = riskOf(name, isTopLevel),
        )
    } ?: emptyList()
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** 列表副标题：目录显示「目录」，文件显示「大小 · 时间」。 */
fun entrySubtitle(entry: FileEntry): String = if (entry.isDirectory) {
    "目录"
} else {
    "${formatFileSize(entry.size)} · ${timeFmt.format(Date(entry.lastModified))}"
}

/**
 * 加强版名称消毒：拦截空名、`.`、`..`、含路径分隔符、控制字符、前后空白等，
 * 防止路径穿越（Zip Slip / 目录穿越）。返回 null 表示非法。
 */
fun sanitizeFileName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
    if (trimmed.contains('/') || trimmed.contains('\\')) return null
    // 控制字符（含换行）会破坏文件系统 / 列表显示
    if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
    return trimmed
}

/**
 * 冲突命名策略：在 [parent] 下为 [baseName] 生成不冲突的名字。
 * [mode] = OVERWRITE 时返回原名（由调用方覆盖写入）；SKIP 返回 null；RENAME 追加 -1/-2...
 */
enum class ConflictMode { OVERWRITE, SKIP, RENAME }

fun resolveConflictName(parent: File, baseName: String, mode: ConflictMode): String? {
    val target = File(parent, baseName)
    if (!target.exists()) return baseName
    return when (mode) {
        ConflictMode.OVERWRITE -> baseName
        ConflictMode.SKIP -> null
        ConflictMode.RENAME -> {
            val dot = baseName.lastIndexOf('.')
            val stem = if (dot > 0) baseName.substring(0, dot) else baseName
            val ext = if (dot > 0) baseName.substring(dot) else ""
            var counter = 1
            var candidate: File
            do {
                candidate = File(parent, "${stem}-$counter$ext")
                counter++
            } while (candidate.exists())
            candidate.name
        }
    }
}
