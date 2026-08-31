package com.dshbox.app.sandbox

/**
 * Pure analysis of a runtime-bundle ZIP layout (1.1.0, M1/M2 — MODIFICATION_LOG.md).
 *
 * Deliberately free of android.* imports so the ZIP-matching rules are unit-testable
 * on the JVM. Encodes the two import bugs fixed in 1.1.0:
 *  - layer archives must match EXACTLY `<layer>.tar.<ext>`; the 1.0.0 code used
 *    startsWith("<layer>.tar.") which ALSO matched the `<layer>.tar.zst.sha256`
 *    sidecar — the sidecar then overwrote the archive in the map and every
 *    official zip import failed with "Not in GZIP format";
 *  - a single common top-level folder shared by ALL entries (Windows 右键压缩
 *    文件夹) is packaging noise and is stripped before matching;
 *  - any entry containing a ".." segment makes the whole archive unsafe.
 */
object RuntimeBundleLayout {

    /**
     * Layer archive file name，例如 base.tar.zst —— 锚定匹配，`.sha256` 侧车永不命中。
     * 1.1.0 (M12.4)：接受裸 `base.tar`（无压缩扩展），与解压器「按魔数识别压缩」的口径
     * 对齐——压缩格式只看内容，文件名扩展不再是必需。
     */
    private val ARCHIVE_REGEX = Regex("^(base|node|android-side)\\.tar(\\.[A-Za-z0-9]+)?$")

    const val PROFILE_NAME = "runtime-profile.json"

    /** 文件名若形如 <layer>.tar[.ext] 返回层名（base/node/android-side），否则 null。1.1.0 (M12.4)。 */
    fun layerOfArchiveName(name: String): String? {
        val clean = name.replace('\\', '/').substringAfterLast('/')
        // 显式排除 .sha256 侧车——裸归档（base.tar）的侧车正是 base.tar.sha256，
        // 而 .sha256 全是字母数字，会被下方的「任意扩展名」分支误吞。
        if (clean.endsWith(".sha256")) return null
        return when {
            clean.startsWith("base.tar") -> "base"
            clean.startsWith("node.tar") -> "node"
            clean.startsWith("android-side.tar") -> "android-side"
            else -> null
        }.takeIf { ARCHIVE_REGEX.matches(clean) }
    }

    sealed interface Result {
        /** Ready-to-extract layout. [targets] maps raw zip entry name -> normalized relative path. */
        data class Ok(
            val targets: Map<String, String>,
            val archives: Map<String, String>,
            val sidecars: Map<String, String>,
            val profilePath: String?,
        ) : Result

        /** The archive contains a path-traversal entry and must be rejected. */
        data class Unsafe(val entryName: String) : Result
    }

    fun analyze(rawEntryNames: List<String>): Result {
        if (rawEntryNames.isEmpty()) {
            return Result.Ok(emptyMap(), emptyMap(), emptyMap(), profilePath = null)
        }
        val normalized = rawEntryNames.associateWith(::normalize)
        // One common top-level folder across ALL entries is stripped as packaging noise.
        val firstSeg = normalized.values.first().substringBefore('/', "")
        val prefix = firstSeg.takeIf { seg ->
            seg.isNotEmpty() && normalized.values.all { it.startsWith("$seg/") }
        }?.let { "$it/" }

        val targets = HashMap<String, String>(normalized.size)
        for ((raw, n) in normalized) {
            val stripped = if (prefix != null && n.startsWith(prefix)) n.removePrefix(prefix) else n
            if (stripped.isEmpty()) continue
            if (stripped.split('/').any { it == ".." }) return Result.Unsafe(raw)
            targets[raw] = stripped
        }

        val archives = HashMap<String, String>()
        val sidecars = HashMap<String, String>()
        var profile: String? = null
        val targetNames = targets.values.toHashSet()
        for ((_, stripped) in targets) {
            val layer = layerOfArchiveName(stripped)
            when {
                stripped == PROFILE_NAME -> profile = stripped
                layer != null -> {
                    archives[layer] = stripped
                    val sidecarName = "$stripped.sha256"
                    if (sidecarName in targetNames) {
                        sidecars[layer] = sidecarName
                    }
                }
            }
        }
        return Result.Ok(targets, archives, sidecars, profile)
    }

    internal fun normalize(name: String): String = name.replace('\\', '/').removePrefix("./")
}
