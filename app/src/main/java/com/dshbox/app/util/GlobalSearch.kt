package com.dshbox.app.util

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import com.dshbox.app.util.viewer.FileTypeClassifier
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 全局搜索结果项。 */
data class SearchResult(
    val name: String,
    /** 逻辑路径（UI 据此跳转定位目录）。 */
    val logicalPath: String,
    val isDirectory: Boolean,
    val size: Long,
    /** true 表示命中文本内容（而非仅文件名）。 */
    val matchedInContent: Boolean,
    /** 内容命中时的上下文片段。 */
    val snippet: String? = null,
)

/**
 * 全沙盒全局搜索：文件名匹配 + 文本内容匹配。
 *
 * 策略：
 * - 遍历 rootfs（跳过 proc/sys/dev/system/apex/tmp 系统绑定目录、node_modules/.git 依赖目录）
 *   与工作区 user-data（含 .dsh）；
 * - 文件名匹配所有文件；文本内容匹配仅限常见文本扩展名且大小 ≤ 1MB，只读取前 64KB；
 * - 异步执行，支持取消（输入变化时取消上一轮）；结果上限 500 条防 UI 卡顿。
 */
object GlobalSearch {

    private const val MAX_CONTENT_SIZE = 1L shl 20 // 1MB
    private const val CONTENT_PROBE_BYTES = 64 * 1024 // 读前 64KB
    private const val MAX_RESULTS = 500

    /** 系统绑定目录（rootfs 顶层，遍历跳过）。 */
    private val SKIP_DIR_NAMES = setOf("proc", "sys", "dev", "system", "apex", "tmp")

    /** 纯依赖 / 版本管理 / 缓存目录，遍历跳过以控制耗时（不影响业务文件）。 */
    private val SKIP_NOISE_DIRS = setOf("node_modules", ".git", "__pycache__", ".cache")

    /** 单次搜索最大扫描文件数，防止 rootfs 全量遍历卡死 UI（超限停止并返回部分结果）。 */
    private const val MAX_SCAN_FILES = 30000L

    /**
     * 在多个逻辑根下搜索 [query]。返回结果列表（可能为空）。
     */
    suspend fun search(
        roots: List<File>,
        mapper: PathMapper,
        query: String,
        listener: ProgressListener?,
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val ql = q.lowercase()
        val results = ArrayList<SearchResult>()
        var scanned = 0L
        // 物理 canonical 路径去重：user-data 会经 /root/projects 映射被两个根各遍历一次（S2）
        val visitedPhysical = HashSet<String>()

        fun add(file: File, logicalDir: File, matchedInContent: Boolean, snippet: String?) {
            if (results.size >= MAX_RESULTS) return
            val entry = File(logicalDir, file.name)
            results.add(
                SearchResult(
                    name = file.name,
                    logicalPath = entry.absolutePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    matchedInContent = matchedInContent,
                    snippet = snippet,
                ),
            )
        }

        suspend fun walk(dir: File, logicalDir: File, isRootfsTop: Boolean) {
            if (results.size >= MAX_RESULTS || scanned >= MAX_SCAN_FILES) return
            currentCoroutineContext().ensureActive()
            val physical = mapper.resolvePhysical(logicalDir)
            val canon = runCatching { physical.canonicalPath }.getOrDefault(physical.absolutePath)
            if (!visitedPhysical.add(canon)) return // 已通过另一根遍历过同一物理目录
            val children = physical.listFiles() ?: return
            for (f in children.sortedBy { it.name.lowercase() }) {
                if (results.size >= MAX_RESULTS || scanned >= MAX_SCAN_FILES) return
                currentCoroutineContext().ensureActive()
                val name = f.name
                // 系统绑定目录（rootfs 顶层）、依赖/版本管理目录直接跳过
                if (f.isDirectory && (
                        (isRootfsTop && name in SKIP_DIR_NAMES) ||
                            name in SKIP_NOISE_DIRS
                        )
                ) continue

                val nameHit = name.lowercase().contains(ql)
                var contentHit = false
                var snippet: String? = null

                // 文本候选判定统一改用 FileTypeClassifier（魔数/嗅探/扩展名
                // 三级），消除旧私有清单与预览白名单不一致的分叉（Makefile 搜索可命中、
                // 预览打不开）。已知二进制扩展名免读盘直接跳过。
                val head = if (f.isFile && !nameHit && f.length() in 1..MAX_CONTENT_SIZE &&
                    !FileTypeClassifier.isKnownBinaryByExtension(name)
                ) {
                    FileTypeClassifier.readHead(f, CONTENT_PROBE_BYTES)
                } else {
                    ByteArray(0)
                }
                if (head.isNotEmpty() && FileTypeClassifier.isTextCandidate(name, head)) {
                    runCatching {
                        val probe = String(head, 0, head.size, Charsets.UTF_8)
                        val idx = probe.lowercase().indexOf(ql)
                        if (idx >= 0) {
                            contentHit = true
                            snippet = probe.substring(maxOf(0, idx - 24), minOf(probe.length, idx + q.length + 48))
                                .replace('\n', ' ').trim()
                        }
                    }
                }

                if (nameHit || contentHit) {
                    add(f, logicalDir, contentHit, snippet)
                }

                if (f.isDirectory) {
                    walk(f, File(logicalDir, name), isRootfsTop = false)
                }
                scanned++
                if (scanned % 64L == 0L) {
                    listener?.onProgress(scanned, -1L, UiText.Res(R.string.search_scanning_name, listOf(name)))
                }
            }
        }

        // 仅 rootfs 根目录按顶层规则跳过系统绑定目录；工作区根不适用该规则
        val sandboxPath = mapper.sandboxRoot.absolutePath
        for (root in roots) {
            walk(root, root, isRootfsTop = root.absolutePath == sandboxPath)
        }
        listener?.onProgress(scanned, scanned, UiText.Res(R.string.search_done))
        return results
    }
}
