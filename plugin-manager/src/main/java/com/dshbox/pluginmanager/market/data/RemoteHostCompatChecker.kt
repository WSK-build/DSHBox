package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.contract.HostContract
import com.dshbox.pluginmanager.market.model.HostCompatibility
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 拉一个 npm 包 manifest 的取数点（抽接口以便单测）。 */
fun interface RemoteManifestFetcher {
    /** 返回 manifest 正文；取不到返回 null（由调用方按"取不到"处理）。 */
    fun fetch(npmName: String): String?
}

/**
 * 浏览期宿主兼容性判定：对**未安装**的目录条目，按 npm manifest 里声明的
 * 宿主要求（`engines.dsh` 双位置 + `@deepseek-ai/dsh*` peer）与当前宿主版本
 * 比对。
 *
 * ## 补这一层的原因
 *
 * 0.2.x 宿主的兼容门禁在**安装时**就会拒绝 peer 不满足的插件；本地已装清单
 * 推导不出未装条目的声明，装前不给结论等于让用户拿安装失败当探测器。
 * 上游的同一能力（discovery-compatibility）即为此设。
 *
 * ## 缓存纪律（与上游一致）
 *
 * **事实可缓存，结论永不缓存**：缓存的只是"对端 manifest 长什么样"，兼容性
 * 结论每次都用当前宿主版本现算——宿主换层后结论立即跟上。失败进短期负缓存，
 * 免得一次网络抖动让同一批条目反复整页超时。
 *
 * ## 判不清楚一律 UNKNOWN
 *
 * 没有 npm 名（`github:` 直装条目）、manifest 取不到、区间读不懂，都返回
 * UNKNOWN（basis 相应区分）。绝不把"不知道"判成"不兼容"，也不判成"兼容"。
 */
class RemoteHostCompatChecker(
    private val hostVersion: () -> String?,
    private val fetcher: RemoteManifestFetcher,
    /** 成功 manifest 的进程内缓存时长。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 失败的负缓存时长。 */
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
        const val DEFAULT_NEGATIVE_TTL_MS = 10 * 60 * 1000L

        /** 默认取数点：npm registry 的 latest manifest（含 peer/engines 声明）。 */
        fun httpFetcher(
            registryBase: String = HostContract.DEFAULT_REGISTRY_BASE,
            timeoutMs: Int = 8_000,
        ): RemoteManifestFetcher =
            RemoteManifestFetcher { npmName ->
                // 作用域包的斜杠必须编码，否则路径会被 registry 当成两级。
                val encoded = npmName.replace("/", "%2F")
                val conn = URL("$registryBase/$encoded/latest").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.setRequestProperty("Accept", "application/json")
                    if (conn.responseCode !in 200..299) return@RemoteManifestFetcher null
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
    }

    private class Cached(val manifestText: String?, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    /**
     * 逐条判定；键与 [names] 一一对应（调用方负责键的唯一性）。
     *
     * @param npmNameOf 键 → npm 包名；返回 null 表示该条目没有 npm 名（不查）。
     */
    suspend fun check(
        names: List<String>,
        npmNameOf: (String) -> String?,
    ): Map<String, HostCompatibility> = withContext(Dispatchers.IO) {
        val host = hostVersion()
        names.associateWith { name ->
            runCatching { judgeOne(name, npmNameOf(name), host) }
                .getOrDefault(HostCompatibility.unknown)
        }
    }

    private fun judgeOne(name: String, npmName: String?, host: String?): HostCompatibility {
        if (npmName.isNullOrBlank()) return HostCompatibility.unknown
        val manifestText = cachedManifest(npmName) ?: return HostCompatibility.unknown
        val requirements = runCatching { parseHostRequirements(manifestText) }.getOrNull()
            ?: return HostCompatibility.unknown
        return HostCompatJudge.judge(requirements, host)
    }

    private fun cachedManifest(npmName: String): String? {
        val now = clock()
        cache[npmName]?.let { cached ->
            val ttl = if (cached.manifestText != null) ttlMs else negativeTtlMs
            if (now - cached.at < ttl) return cached.manifestText
        }
        val manifest = runCatching { fetcher.fetch(npmName) }.getOrNull()
        cache[npmName] = Cached(manifest, now)
        return manifest
    }

    /** 丢弃进程内缓存（显式刷新用）。 */
    fun invalidate() {
        cache.clear()
    }
}
