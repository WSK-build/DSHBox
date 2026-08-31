package com.dshbox.app.util

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.RandomAccessFile

/**
 * 沙盒存储扫描与清理（1.1.0，M12；M12.1 评审修正）。
 *
 * 统计口径：**分配块**（lstat st_blocks × 512），与系统「应用存储」/ du 同口径。
 * 符号链接跳过；硬链接（st_nlink > 1）按 (dev,ino) 只计一次。**scan 与 clean
 * 使用同一口径与同一判定函数**（passesAgeGuard / fileEligible / 同样的硬链接去重），
 * 弹窗「可释放」与「已释放」不会互相打架（M12.1 P1①）。
 *
 * 清理红线（categorize 只放行下列前缀，其余路径一律不触碰）：
 * user-data/（含 .dsh 工作区与对话数据）、runtime-current/{node, android-side, dsh}、
 * base 层除 tmp 与 var/cache/apt/archives 外的一切、runtime-profile.json、
 * .dshbox 哨兵、sandbox/、backups/、updates/（用户推送的升级包，属用户资产）。
 *
 * 智能清理：[Category.GUEST_TMP]（guest /tmp 与 proot 临时目录 runtime-current/tmp）
 * 在沙箱或 DSH 运行中时只清理超过 [AGE_GUARD_MS] 的条目；scan 与 clean 均传
 * guardActive，调用方用同一状态。
 *
 * 并发互斥：本类不感知后台任务；调用方必须在 count>0（[BackgroundOps]）时禁用清理，
 * 参见 M12.1 P1③。
 */
object SandboxCleanup {

    enum class Category { CACHE, GUEST_TMP, LOGS, APT, ROLLBACK }

    /** 沙箱/DSH 运行中时，临时文件的年龄下限（超过才可清理）。 */
    const val AGE_GUARD_MS: Long = 24L * 60 * 60 * 1000

    /**
     * 分类规则（纯函数，JVM 可测）：[relPath] 相对 filesDir，'/' 分隔。
     * 返回 null 表示不属于任何清理项（红线，永不触碰）。
     *
     * 注意归一化方式：只剥 "./" 与前导 "/"，绝不能剥 ".."——
     * `trimStart('.', '/')` 会把 "../evil" 归一成 "evil"，吞掉穿越特征。
     */
    fun categorize(relPath: String): Category? {
        val rel = relPath.replace('\\', '/').trimStart('/').removePrefix("./")
        val rules = listOf(
            "logs" to Category.LOGS,
            "runtime-bundle-staging" to Category.CACHE,
            "bundled-runtime-staging" to Category.CACHE,
            "bundled-runtime-staging.tar.gz" to Category.CACHE,
            "runtime/dsh-staging" to Category.CACHE,
            // proot 临时目录真实位置：PROOT_TMP_DIR = runtime-current/tmp/<role>（M12.1 修正：
            // 旧版写成 runtime/tmp，是凭空臆造的路径，永远匹配不到）。
            "runtime/runtime-current/tmp" to Category.GUEST_TMP,
            "runtime/runtime-current/base/tmp" to Category.GUEST_TMP,
            "runtime/runtime-current/base/var/cache/apt/archives" to Category.APT,
            "runtime/runtime-current/previous" to Category.ROLLBACK,
            "runtime/runtime-new" to Category.ROLLBACK,
            "runtime/runtime-previous" to Category.ROLLBACK,
            "runtime/runtime-failed" to Category.ROLLBACK,
        )
        for ((prefix, category) in rules) {
            if (rel == prefix || rel.startsWith("$prefix/")) return category
        }
        return null
    }

    fun isAgeGuarded(category: Category): Boolean = category == Category.GUEST_TMP

    /**
     * 统一的年龄判定：[Category.GUEST_TMP] 在 [guardActive]（沙箱或 DSH 运行中）时
     * 只放行修改时间早于 now - AGE_GUARD_MS 的条目；其余类别不受限。
     */
    fun passesAgeGuard(category: Category, lastModified: Long, now: Long, guardActive: Boolean): Boolean =
        !isAgeGuarded(category) || !guardActive || lastModified <= now - AGE_GUARD_MS

    /**
     * 扩展名过滤（scan 与 clean 共用，保证口径一致，M12.1 P2⑥⑦）：
     * APT 只认 .deb（等价 apt clean，不动 lock/partial，避免打断进行中的下载）；
     * LOGS 只认 .log。
     */
    fun fileEligible(category: Category, name: String): Boolean = when (category) {
        Category.APT -> name.endsWith(".deb", ignoreCase = true)
        Category.LOGS -> name.endsWith(".log", ignoreCase = true)
        else -> true
    }

    /** 单个条目的统计快照（scan 从 Os.lstat 翻译而来；测试可直接构造）。 */
    data class EntryStat(
        val name: String,
        val relPath: String,
        val isDir: Boolean,
        val isSymlink: Boolean,
        val blocks: Long,
        val dev: Long,
        val ino: Long,
        val nlink: Long,
        val lastModifiedMs: Long,
    ) {
        val bytes: Long get() = blocks * 512L
    }

    /**
     * 逐条目归账器（纯逻辑，JVM 可测，M12.1 P2⑧）：硬链接去重 + 分类 + 年龄过滤 +
     * 扩展名过滤。scan 对 filesDir 与 cacheDir 两个根**共用同一个账本**（cache 根
     * 条目传 isCacheRoot = true 不参与分类）——跨根的硬链接也能去重；账本内
     * 硬链接 (dev,ino) 只计一次。
     */
    class UsageLedger(private val now: Long, private val guardActive: Boolean) {
        private val seenHardlinks = HashSet<String>()
        private val reclaimable = mutableMapOf<Category, Long>()

        /**
         * 把一个条目记入账本。返回计入的字节数（0 = 跳过：符号链接/重复硬链接）。
         * [targetBytes] 接收计入的分配块字节数，用于调用方累加对应根目录的总量。
         */
        fun add(entry: EntryStat, isCacheRoot: Boolean = false): Long {
            if (entry.isSymlink) return 0L
            if (entry.nlink > 1 && !seenHardlinks.add("${entry.dev}:${entry.ino}")) return 0L
            val bytes = entry.bytes
            val category = if (isCacheRoot) null else categorize(entry.relPath)
            if (category != null && !entry.isDir &&
                fileEligible(category, entry.name) &&
                passesAgeGuard(category, entry.lastModifiedMs, now, guardActive)
            ) {
                reclaimable.merge(category, bytes, Long::plus)
            }
            return bytes
        }

        fun reclaimable(): Map<Category, Long> = reclaimable.toMap()
    }

    data class ScanResult(
        /** filesDir 分配块合计（「沙盒数据」行）。 */
        val dataBytes: Long,
        /** cacheDir 分配块合计（「应用缓存」行）。 */
        val cacheBytes: Long,
        /** 各清理项可回收大小（GUEST_TMP 已按智能策略过滤）。CACHE 项已含整个 cacheDir。 */
        val reclaimable: Map<Category, Long>,
    )

    /**
     * 一遍遍历 filesDir + cacheDir，同时得出两行占用与各清理项可回收大小。
     * 每个条目只 stat 一次（isDir/isSymlink/mtime 全部取自同一次 lstat，M12.1 P2⑤）。
     * reclaimable 只按文件计（目录 inode 忽略）——clean 侧按目录整删时实际释放
     * 会略大于显示值，宁少勿多。
     *
     * [checkCancelled] 每进入一个目录时回调一次，供调用方取消长遍历（M12.1 P2⑫）。
     */
    fun scan(context: Context, guardActive: Boolean, checkCancelled: () -> Unit = {}): ScanResult {
        val now = System.currentTimeMillis()
        var dataBytes = 0L
        var cacheBytes = 0L
        val ledger = UsageLedger(now, guardActive)

        fun walk(dir: File, rel: String, isCacheRoot: Boolean) {
            checkCancelled()
            val children = dir.listFiles() ?: return
            for (child in children) {
                val st: StructStat = runCatching { Os.lstat(child.path) }.getOrNull() ?: continue
                val fmt = st.st_mode and OsConstants.S_IFMT
                val isDir = fmt == OsConstants.S_IFDIR
                val isSymlink = fmt == OsConstants.S_IFLNK
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                val entry = EntryStat(
                    name = child.name,
                    relPath = childRel,
                    isDir = isDir,
                    isSymlink = isSymlink,
                    blocks = st.st_blocks,
                    dev = st.st_dev,
                    ino = st.st_ino,
                    nlink = st.st_nlink,
                    // st_mtime 是秒级精度；24h 量级的年龄判定足够。
                    lastModifiedMs = st.st_mtime * 1000L,
                )
                val counted = ledger.add(entry, isCacheRoot)
                if (isCacheRoot) cacheBytes += counted else dataBytes += counted
                if (isDir && !isSymlink) walk(child, childRel, isCacheRoot)
            }
        }

        walk(context.filesDir, "", isCacheRoot = false)
        // cache 根加前缀，categorize 不会命中任何规则（cacheDir 整体属于 CACHE 清理项）。
        walk(context.cacheDir, "cache/", isCacheRoot = true)

        val reclaimable = ledger.reclaimable().toMutableMap()
        // 应用缓存清理项 = 整个 cacheDir + filesDir 内的暂存残留。
        reclaimable[Category.CACHE] = (reclaimable[Category.CACHE] ?: 0L) + cacheBytes
        return ScanResult(dataBytes, cacheBytes, reclaimable)
    }

    /** 分配块口径的单文件大小（0 = 符号链接 / 重复硬链接 / 读取失败）。 */
    private fun allocatedBytes(file: File, seenHardlinks: MutableSet<String>): Long = runCatching {
        val st = Os.lstat(file.path)
        if ((st.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) return@runCatching 0L
        if (st.st_nlink > 1 && !seenHardlinks.add("${st.st_dev}:${st.st_ino}")) return@runCatching 0L
        st.st_blocks * 512L
    }.getOrDefault(0L)

    /** 删除文件/目录，返回释放的分配块字节数（目录 inode 不计，与 scan 口径对齐）。 */
    private fun removeWithSize(target: File, seenHardlinks: MutableSet<String>): Long {
        var bytes = 0L
        target.walkTopDown().forEach { f -> if (f.isFile) bytes += allocatedBytes(f, seenHardlinks) }
        return if (target.deleteRecursively()) bytes else 0L
    }

    /**
     * 执行清理，返回实际释放的字节数（与 scan 同为分配块口径，M12.1 P1①）。
     * 全部为宿主侧文件操作（所有目标都在 filesDir/cacheDir 下，不需要 guest 命令）。
     * 只处理 [categories] 中出现且调用方确认的项；调用方须先用 [BackgroundOps]
     * 确认没有并发安装/导入（M12.1 P1③）。
     */
    fun clean(context: Context, guardActive: Boolean, categories: Set<Category>): Long {
        val cutoff = System.currentTimeMillis() - AGE_GUARD_MS
        var freed = 0L
        val seenHardlinks = HashSet<String>()
        val filesDir = context.filesDir

        if (Category.CACHE in categories) {
            // cacheDir 整体可清（按定义即缓存；各流程的正常临时文件本就在用后即删，
            // 这里清掉的是崩溃/被杀后残留的部分）。
            context.cacheDir.listFiles()?.forEach { freed += removeWithSize(it, seenHardlinks) }
            for (name in listOf("runtime-bundle-staging", "bundled-runtime-staging", "bundled-runtime-staging.tar.gz")) {
                val f = File(filesDir, name)
                if (f.exists()) freed += removeWithSize(f, seenHardlinks)
            }
            val dshStaging = File(filesDir, "runtime/dsh-staging")
            if (dshStaging.exists()) freed += removeWithSize(dshStaging, seenHardlinks)
        }
        if (Category.GUEST_TMP in categories) {
            for (root in listOf(
                File(filesDir, "runtime/runtime-current/base/tmp"),
                File(filesDir, "runtime/runtime-current/tmp"),
            )) {
                if (!root.isDirectory) continue
                // 逐文件按修改时间判断（与 scan 统计口径一致），不动仍在使用的文件；
                // 先收集再删除，避免边遍历边删。
                val staleFiles = root.walkTopDown().filter { it.isFile }
                    .filter { !guardActive || it.lastModified() <= cutoff }
                    .toList()
                for (f in staleFiles) {
                    val bytes = allocatedBytes(f, seenHardlinks)
                    if (f.delete()) freed += bytes
                }
                // 空目录自底向上清理（深路径先删）；运行中只清超龄目录，
                // 会话正在使用的目录（近期 mtime、非空）不受影响（M12.1 P2⑩）。
                val dirs = root.walkTopDown().filter { it.isDirectory && it != root }
                    .sortedByDescending { it.path.length }
                    .toList()
                for (d in dirs) {
                    if ((!guardActive || d.lastModified() <= cutoff) &&
                        d.isDirectory && d.listFiles()?.isEmpty() == true
                    ) {
                        d.delete()
                    }
                }
            }
        }
        if (Category.LOGS in categories) {
            File(filesDir, "logs").listFiles()?.forEach { f ->
                if (!f.isFile || !fileEligible(Category.LOGS, f.name)) return@forEach
                val bytes = allocatedBytes(f, seenHardlinks)
                // 截断而非删除：写日志的是 app 自己的线程，持有句柄继续追加，
                // 删除已打开文件不会释放空间，截断会。
                runCatching { RandomAccessFile(f, "rw").use { it.setLength(0) } }
                    .onSuccess { freed += bytes }
            }
        }
        if (Category.APT in categories) {
            File(filesDir, "runtime/runtime-current/base/var/cache/apt/archives")
                .listFiles()?.forEach { f ->
                    // 只删 .deb（等价 apt clean）；lock 与 partial/ 留给 apt 自己管理，
                    // 避免打断终端里进行中的 apt 操作（M12.1 P2⑥）。
                    if (f.isFile && fileEligible(Category.APT, f.name)) freed += removeWithSize(f, seenHardlinks)
                }
        }
        if (Category.ROLLBACK in categories) {
            for (name in listOf(
                "runtime/runtime-current/previous",
                "runtime/runtime-new",
                "runtime/runtime-previous",
                "runtime/runtime-failed",
            )) {
                val f = File(filesDir, name)
                if (f.exists()) freed += removeWithSize(f, seenHardlinks)
            }
        }
        return freed
    }
}
