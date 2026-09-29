package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 安装 spec → 来源种类。
 *
 * 与上游的 `kind` 取值保持一致（`npm` / `linked` / `file` / `github`），
 * 因为 [UpdateStatus.kind] 会被 UI 用来解释"为什么没检测"。
 */
internal fun kindOfSpec(spec: String?): String = when {
    spec == null -> KIND_NPM
    spec.startsWith("link:") -> KIND_LINKED
    spec.startsWith("file:") -> KIND_FILE
    spec.startsWith("github:") || spec.startsWith("git+") || spec.contains("github.com") -> KIND_GITHUB
    else -> KIND_NPM
}

internal const val KIND_NPM = "npm"
internal const val KIND_GITHUB = "github"
internal const val KIND_LINKED = "linked"
internal const val KIND_FILE = "file"

/**
 * 从 spec 里取真正的 npm 包名。
 *
 * `npm:alias@1.0.0` 这种别名安装（pnpm 的 `npm:` 协议）在清单里的**键**是别名，
 * 而 registry 上真实存在的是冒号后面的包名。拿别名去查 registry 一定查不到，
 * 去装也一定装不上，所以更新路径必须先脱掉别名。
 */
internal fun npmNameOf(name: String, spec: String?): String {
    if (spec == null || !spec.startsWith("npm:")) return name
    val rest = spec.removePrefix("npm:")
    val at = rest.lastIndexOf('@')
    return if (at > 0) rest.substring(0, at) else rest
}

/** 一键更新的目标判定。 */
internal sealed class UpdateTarget {
    /** 可以更新，且给出**真实包名**（别名已脱掉）。 */
    data class Npm(val packageName: String) : UpdateTarget()

    /** 该来源没有"最新版"这个概念（`link:` / `file:` / `github:` / `generation`）。 */
    data object Unsupported : UpdateTarget()
}

/**
 * 决定"这个名字能不能一键更新、用哪个包名更新"。
 *
 * `link:` / `file:` 指向本地开发件，`github:` 要问 git ref，两者都没有
 * "最新版"可言：对它们跑 `add <name>@latest` 要么失败、要么把一个本地开发件
 * 换成 registry 上的另一个包——两种结果都不是用户想要的。所以这里直接拒绝，
 * 由调用方把原因告诉用户。
 */
internal fun updateTargetFor(name: String, spec: String?): UpdateTarget =
    if (kindOfSpec(spec) == KIND_NPM) UpdateTarget.Npm(npmNameOf(name, spec)) else UpdateTarget.Unsupported

/**
 * 更新检测。
 *
 * ## 为什么 `checked` 是核心字段
 *
 * "没查到"和"已是最新"是两件完全不同的事。这一层只回答"我到底比过没有"：
 * 拿到对端版本、且本地版本可比，才算真的比过（[UpdateStatus.checked] = true）。
 * 非 npm 来源、registry 查询失败、本地版本读不出来，都只是"没查到"。
 * UI 据此三分支渲染，绝不能把后者显示成「已是最新」。
 *
 * ## 为什么缓存的是"对端版本"而不是"整份结论"
 *
 * 缓存整份 `name → UpdateStatus` 会在**插件刚更新完**的那一刻骗人：本地版本
 * 变了，缓存里的旧结论还在说"有更新"。所以这里只缓存最贵的那一步（npm 查询
 * 的结果），比较每次都用**当前**本地版本重算。
 *
 * ## 失败也要缓存，但是**短期负缓存**
 *
 * 查询失败不缓存会让"用户拨一次开关 / 写一次备注"都重跑整轮 npm 查询：每个包
 * 8 秒超时、有限并发，几十个包就是几十秒的无反馈卡顿。所以失败结果也进缓存，
 * 但只记 [negativeTtlMs]（默认 2 分钟）：期内不重试，过期后自动重试。用户
 * 显式刷新（`force = true`）绕过正负两种缓存。
 *
 * ## 为什么 `checked` 只在两端都能解析时才置位
 *
 * `Semver.compare` 在解析失败时按字典序兜底（保证排序有全序），但**字典序不是
 * 版本序**：`0.9` vs `0.10.0` 会被判成"不更新"，UI 于是显示「已是最新」，
 * 用户永远看不到更新入口。所以只有 `Semver.parse(current) != null &&
 * Semver.parse(latest) != null` 才算真的比过；解析不了 → `checked = false`，
 * UI 走"未检测"。字典序兜底只留给排序。
 */
class UpdateDetector(
    private val lookup: LatestVersionLookup,
    /** 成功查询的进程内 TTL。默认 10 分钟。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** npm 查询的最大并发；目录里几十个包串行查询会让整页等上十几秒。 */
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 失败查询的负缓存 TTL：期内不重试，过期后自动重试。默认 2 分钟。 */
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
) {

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_NEGATIVE_TTL_MS = 2 * 60 * 1000L
        const val DEFAULT_CONCURRENCY = 4
    }

    /** [version] 为 null 表示一次失败的查询（负缓存条目）。 */
    private class Cached(val version: String?, val at: Long)

    // 四路并发协程会同时读写这份缓存，裸 HashMap 会丢更新甚至死循环。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    /** 单包判定：不做网络以外的任何猜测。 */
    fun detect(name: String, spec: String?, current: String?): UpdateStatus {
        val kind = kindOfSpec(spec)
        if (kind != KIND_NPM) {
            // 该来源没有"最新版"，不是"已是最新"。
            return UpdateStatus(current = current, kind = kind, checked = false)
        }
        val latest = lookupLatest(name, spec, force = false)
            ?: return UpdateStatus(current = current, kind = kind, checked = false)
        if (current.isNullOrBlank()) {
            // 拿到了对端版本，但本地版本读不出来 —— 没比过，不能下结论。
            return UpdateStatus(current = null, latest = latest, kind = kind, checked = false)
        }
        val newer = updateAvailable(latest, current)
            // 版本号有一端解析不了：字典序不能当版本序，只能报"未检测"。
            ?: return UpdateStatus(current = current, latest = latest, kind = kind, checked = false)
        return UpdateStatus(
            updateAvailable = newer,
            current = current,
            latest = latest,
            kind = kind,
            checked = true,
        )
    }

    /**
     * 批量检测。
     *
     * @param dependencies 包名 → 安装 spec（profile 清单原样）。
     * @param currentVersion 取本地版本；读不出来返回 null。
     * @param force 绕过进程内 TTL 缓存（用户显式刷新时）。
     */
    suspend fun check(
        dependencies: Map<String, String>,
        currentVersion: (String) -> String?,
        force: Boolean = false,
    ): Map<String, UpdateStatus> {
        val entries = dependencies.entries.toList()
        val latest = withContext(Dispatchers.IO) {
            val out = HashMap<String, String?>(entries.size)
            // 有限并发：按 maxConcurrency 分组，组内并行、组间串行，任何时刻
            // 在飞的请求数都不超过 maxConcurrency。
            entries.chunked(maxConcurrency.coerceAtLeast(1)).forEach { chunk ->
                coroutineScope {
                    chunk.map { (name, spec) ->
                        async { name to lookupLatest(name, spec, force) }
                    }.awaitAll()
                }.forEach { (name, value) -> out[name] = value }
            }
            out
        }
        val result = LinkedHashMap<String, UpdateStatus>(entries.size)
        for ((name, spec) in entries) {
            val kind = kindOfSpec(spec)
            val current = currentVersion(name)
            val latestVersion = latest[name]
            result[name] = when {
                kind != KIND_NPM -> UpdateStatus(current = current, kind = kind, checked = false)
                latestVersion == null -> UpdateStatus(current = current, kind = kind, checked = false)
                current.isNullOrBlank() ->
                    UpdateStatus(current = null, latest = latestVersion, kind = kind, checked = false)

                else -> {
                    val newer = updateAvailable(latestVersion, current)
                    if (newer == null) {
                        // 版本号有一端解析不了：不能拿字典序当版本序。
                        UpdateStatus(current = current, latest = latestVersion, kind = kind, checked = false)
                    } else {
                        UpdateStatus(
                            updateAvailable = newer,
                            current = current,
                            latest = latestVersion,
                            kind = kind,
                            checked = true,
                        )
                    }
                }
            }
        }
        return result
    }

    /** 丢弃进程内缓存。 */
    fun invalidate() {
        cache.clear()
    }

    /**
     * 只有在两端都能解析成 semver 时才给出"是否有更新"；否则返回 null。
     *
     * null 的含义是"我们没比过"，由调用方表达成 `checked = false`——绝不能
     * 让 `Semver.compare` 的字典序兜底把 `0.9` vs `0.10.0` 说成"已是最新"。
     */
    private fun updateAvailable(latest: String, current: String?): Boolean? {
        if (current.isNullOrBlank()) return null
        if (Semver.parse(latest) == null || Semver.parse(current) == null) return null
        return Semver.compare(latest, current) > 0
    }

    private fun lookupLatest(name: String, spec: String?, force: Boolean): String? {
        if (kindOfSpec(spec) != KIND_NPM) return null
        val key = npmNameOf(name, spec)
        val now = clock()
        if (!force) {
            val hit = cache[key]
            if (hit != null) {
                // 成功按 ttlMs、失败按 negativeTtlMs 判定新鲜度。
                val ttl = if (hit.version != null) ttlMs else negativeTtlMs
                if (now - hit.at < ttl) return hit.version
            }
        }
        // 单个包的网络失败不该让整页没有结果：查不到就是 null，交给调用方
        // 表达成"未检测"。失败也进负缓存，免得每次本地重载都重跑一轮会超时的
        // npm 查询。
        val version = runCatching { lookup.lookup(key) }.getOrNull()
        cache[key] = Cached(version, now)
        return version
    }
}
