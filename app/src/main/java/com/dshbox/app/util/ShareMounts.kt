package com.dshbox.app.util

import android.content.Context
import com.dshbox.app.common.Constants
import java.io.File

/**
 * 共享区用户挂载：guest `/root/share/<name>` ↔ 宿主 Android 公共存储内的 [path]。
 *
 * 挂载目录属用户资产：这里只记录引用；卸载（取消挂载）只删记录，绝不删除目录内容。
 */
data class ShareMount(val name: String, val path: String)

/**
 * 挂载清单编解码（行式 `别名\t宿主路径`）。
 *
 * 选纯文本而非 JSON：不引入序列化依赖，且能在 JVM 单测里直接覆盖。
 * 空行与缺字段行一律跳过，坏行不影响其余条目。
 */
object ShareMountsCodec {

    fun encode(mounts: List<ShareMount>): String =
        mounts.joinToString("\n") { "${it.name}\t${it.path}" }

    fun decode(text: String): List<ShareMount> = text.lineSequence().mapNotNull { line ->
        val sep = line.indexOf('\t')
        if (sep <= 0 || sep == line.lastIndex) {
            null
        } else {
            val name = sanitize(line.substring(0, sep).trim())
            val path = line.substring(sep + 1).trim()
            if (isPlausibleMountPath(path)) ShareMount(name, path) else null
        }
    }.toList()

    /**
     * 挂载路径的最低校验（清单可能被外部改写，纵深防御）：绝对路径（`/` 或盘符，
     * 后者仅在 JVM 单测环境出现）、至少含一个非空段（拒绝 `/`）、不含上跳段。
     */
    fun isPlausibleMountPath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace('\\', '/')
        val absolute = normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        return absolute && segments.isNotEmpty() && segments.none { it == ".." }
    }

    /**
     * 生成不冲突的 guest 别名：非法字符归一为 `-`，内建 `DSHBox` 为保留名，重名追加序号。
     * 别名只影响 guest 侧路径与列表显示，不改宿主目录名。
     */
    fun uniqueName(rawName: String, existing: List<ShareMount>): String {
        val base = sanitize(rawName)
        val taken = existing.mapTo(HashSet()) { it.name }.apply { add(Constants.SHARE_AREA_DIR_NAME) }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it in "._-") it else '-' }.joinToString("")
        return cleaned.trim('-', '.').take(64).ifEmpty { "mount" }
    }
}

/** 挂载清单持久化：app 私有配置目录内的单一文本文件（清理白名单之外，永不触碰）。 */
object ShareMountsStore {
    private const val FILE_NAME = "share-mounts.txt"

    /** 原子写临时文件后缀（供 [writeMountsFile] 使用）。 */
    internal const val TMP_SUFFIX = ".tmp"

    /**
     * 读取挂载清单：文件缺失 / 不可读 / 内容损坏一律宽容处理，返回已有条目。
     * 内建 `DSHBox` 为保留名，清单中被外部写成的同名条目一律过滤，
     * 避免显示层出现两个同名落点、以及覆盖内建目录的绑定。
     */
    fun load(context: Context): List<ShareMount> {
        val file = File(context.filesDir, FILE_NAME)
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return sanitizeMountsForListing(ShareMountsCodec.decode(text), Constants.SHARE_AREA_DIR_NAME)
    }

    /**
     * 清单的展示过滤（纯函数，便于 JVM 单测直调）：剔除保留名，并按名称、路径双去重。
     *
     * 列表 key 与绑定目标都需唯一，而清单可能被外部改写出重名 / 重路径条目；
     * 保留名（内建 `DSHBox`）被过滤掉，避免显示层出现两个同名落点或覆盖内建绑定。
     */
    internal fun sanitizeMountsForListing(
        mounts: List<ShareMount>,
        reservedName: String,
    ): List<ShareMount> {
        val seenNames = HashSet<String>()
        val seenPaths = HashSet<String>()
        return mounts
            .filter { it.name != reservedName }
            .filter { seenNames.add(it.name) && seenPaths.add(it.path) }
    }

    /**
     * 写入挂载清单（原子写：先写临时文件再改名，失败时回退直接写）。
     * 返回是否落盘成功；调用方据此决定是否更新内存状态，避免「界面显示已挂载、重启后消失」。
     */
    fun save(context: Context, mounts: List<ShareMount>): Boolean =
        writeMountsFile(File(context.filesDir, FILE_NAME), ShareMountsCodec.encode(mounts))
}

/**
 * 挂载清单的原子落盘（纯文件操作，便于 JVM 单测直调）：先写 `<target>.tmp` 再改名，
 * 改名失败（个别文件系统不支持）时回退直接写 [target]。返回是否成功落盘。
 *
 * 临时文件只在整体成功后清理：失败时保留，便于排查「写不进去」的具体原因；不无条件删除。
 */
internal fun writeMountsFile(target: File, text: String): Boolean {
    val tmp = File(target.parentFile, target.name + ShareMountsStore.TMP_SUFFIX)
    val written = runCatching {
        tmp.writeText(text)
        if (tmp.renameTo(target)) {
            true
        } else {
            // 改名失败：回退直接写目标，成功与否如实返回。
            target.writeText(text)
            true
        }
    }.getOrDefault(false)
    // 成功时清理回退路径上可能残留的临时文件；失败路径保留 tmp 不删。
    if (written && tmp.exists()) runCatching { tmp.delete() }
    return written
}

/**
 * 共享区绑定清单（guest 别名 → 宿主目录）：内建 `DSHBox` 在前、用户挂载在后。
 * 只收录存在的目录、按宿主路径去重（界面列举与 guest 绑定共用本函数，口径不会分叉）；
 * PRoot 的 bind 源必须存在，故缺失目录一律跳过。
 *
 * 去重按**物理同一性**而非字符串：同一目录经软链、重复分隔符或大小写别名写成多个
 * 清单条目时只保留首个落点，避免列表与 guest 绑定出现重复目标（见 [normalizedSharePath]）。
 */
fun shareBindsOf(builtInRoot: File?, mounts: List<ShareMount>): List<Pair<String, File>> = buildList {
    val seenPaths = HashSet<String>()
    fun addIfNew(name: String, dir: File) {
        if (name.isNotBlank() && dir.isDirectory && seenPaths.add(normalizedSharePath(dir))) {
            add(name to dir)
        }
    }
    builtInRoot?.let { addIfNew(Constants.SHARE_AREA_DIR_NAME, it) }
    mounts.forEach { mount ->
        if (ShareMountsCodec.isPlausibleMountPath(mount.path)) {
            addIfNew(mount.name, File(mount.path))
        }
    }
}

/**
 * 物理同一性判定的规范化共享区路径：取规范路径（软链解析 + 平台大小写规则），
 * 取不到时回退绝对路径；统一分隔符为 `/` 并去掉尾部斜杠。
 *
 * 不做无条件小写：Android 上路径区分大小写，`Download` 与 `download` 是不同目录；
 * 大小写别名（如 `/sdcard` 与 `/storage/emulated/0`）由规范路径在目标平台上归一。
 */
internal fun normalizedSharePath(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        .replace('\\', '/').trimEnd('/')

/** [shareBindsOf] 的运行时入口：读共享区内建目录与用户挂载清单。 */
fun shareBinds(context: Context): List<Pair<String, File>> =
    shareBindsOf(ShareArea.hostRoot().takeIf { it.isDirectory }, ShareMountsStore.load(context))

/**
 * 共享区落点 [base] → guest 侧别名：与落点判定同取**最深匹配**（条目集合内的最后一个
 * 路径命中项），口径与 [shareBaseDirectoryOf] / [shareCrumbsOf] 一致，避免清单被外部
 * 改写成嵌套条目时列表显示、面包屑与 guest 路径指向不同落点。
 *
 * 找不到（[base] 不在任何落点下）返回 null，调用方回退到其它口径。
 */
fun shareAliasOf(base: File, entries: List<Pair<String, File>>): String? =
    entries.lastOrNull { it.second.absolutePath == base.absolutePath }?.first

/**
 * 共享区面包屑段：命名空间根 +（内建 DSHBox 或挂载目录）落点 + 其下层级。
 *
 * 命名空间是虚拟根，父目录回溯走不通，故由条目集合自建；文件页与落点选择器共用，
 * 避免两处样式/口径分叉。落点取**最深匹配**（条目集合内最后一个命中项），与
 * [shareBaseDirectoryOf] 同口径：清单被外部改写成嵌套条目时两处仍指向同一落点。
 */
fun shareCrumbsOf(
    dir: File,
    namespace: File,
    namespaceLabel: String,
    entries: List<Pair<String, File>>,
): List<Pair<String, File>> {
    val head = namespaceLabel to namespace
    if (dir.absolutePath == namespace.absolutePath) return listOf(head)
    val index = entries.indexOfLast { isUnderRoot(it.second, dir.absolutePath) }
    if (index < 0) return listOf(head)
    val (name, base) = entries[index]
    val tail = ArrayDeque<Pair<String, File>>()
    var cursor: File? = dir
    while (cursor != null && cursor.absolutePath != base.absolutePath) {
        tail.addFirst(cursor.name to cursor)
        cursor = cursor.parentFile
    }
    return listOf(head, name to base) + tail
}

/** 共享区列举条目（内建 DSHBox + 用户挂载）：虚拟条目，路径即宿主落点，风险级别 NORMAL。 */
fun shareEntriesOf(entries: List<Pair<String, File>>): List<FileEntry> = entries.map { (name, target) ->
    FileEntry(
        name = name,
        logicalPath = target.absolutePath,
        isDirectory = true,
        size = 0L,
        lastModified = runCatching { target.lastModified() }.getOrDefault(0L),
        risk = RiskLevel.NORMAL,
    )
}

/**
 * 共享区命名空间列举（供文件页命名空间分支使用）：可用条目 + 清单中未生效的挂载。
 *
 * 返回值第二项为「该条目是否可用」：
 * - 可用条目来自 [shareBindsOf]（内建 DSHBox 与宿主目录仍存在的挂载，guest 绑定同口径）；
 * - 不可用条目为**清单中未被 [shareBindsOf] 收录**的别名：宿主目录已不存在（被外部删除 /
 *   卷已卸载），或该别名与已收录条目物理同一而被去重。标记 [FileEntry.unavailable]，
 *   使其仍可见且可被取消挂载，而不是从列表静默消失。
 *
 * 判据是「是否被 [shareBindsOf] 收录」而不是「目录是否存在」：被去重的别名其目录确实存在，
 * 若按存在性判定就会同时缺席可用列表与不可用列表，用户再也无法在界面里取消该挂载。
 * 条目的 `logicalPath` 沿用清单里的宿主路径，`name` 沿用别名 —— 取消挂载按 `name` 匹配
 * 挂载清单，故每个别名都必须有一条可操作的行。
 *
 * 抽成纯函数（仅做存在性判定，不读写清单）以便 JVM 单测覆盖组装口径。
 */
fun shareNamespaceEntriesOf(
    builtInRoot: File?,
    mounts: List<ShareMount>,
): List<Pair<FileEntry, Boolean>> {
    val binds = shareBindsOf(builtInRoot, mounts)
    val available = shareEntriesOf(binds).map { it to true }
    // 收录过的别名与已占用的行路径：别名用于判定「清单条目是否已生效」，
    // 路径与列表 key 同源（重复会造成 key 冲突）。清单经 [ShareMountsStore.load]
    // 过滤后名称与路径字符串都已唯一，这里的去重只在手工构造入参时生效。
    val boundNames = binds.mapTo(HashSet()) { it.first }
    val takenPaths = binds.mapTo(HashSet()) { it.second.absolutePath }
    val takenNames = HashSet<String>()
    val uncovered = mounts
        .filter { it.name !in boundNames && ShareMountsCodec.isPlausibleMountPath(it.path) }
        .filter { takenNames.add(it.name) && takenPaths.add(it.path) }
        .map { mount ->
            FileEntry(
                name = mount.name,
                logicalPath = mount.path,
                isDirectory = true,
                size = 0L,
                lastModified = 0L,
                risk = RiskLevel.NORMAL,
                unavailable = true,
            ) to false
        }
    return available + uncovered
}

/**
 * 共享区落点根（内建或某个挂载目录）；[dir] 不在任何落点时返回 null。
 *
 * 与 [shareCrumbsOf] 同口径取**最深匹配**（条目集合内的最后一个命中项）：
 * 两处若一处取首、一处取尾，外部改写清单造成嵌套条目时列表显示与面包屑会指向不同落点。
 */
fun shareBaseDirectoryOf(dir: File, entries: List<Pair<String, File>>): File? =
    entries.lastOrNull { isUnderRoot(it.second, dir.absolutePath) }?.second

/**
 * 共享区命名空间内的上溯：落点内部取父目录，到达落点根或不在任何落点时回到命名空间
 * （命名空间是虚拟根，不能直接 parentFile 上溯，否则会逃到公共存储）。
 */
fun shareNamespaceParentOf(dir: File, namespace: File, entries: List<Pair<String, File>>): File {
    val base = shareBaseDirectoryOf(dir, entries)
    return if (base != null && dir.absolutePath != base.absolutePath) {
        dir.parentFile ?: namespace
    } else {
        namespace
    }
}
