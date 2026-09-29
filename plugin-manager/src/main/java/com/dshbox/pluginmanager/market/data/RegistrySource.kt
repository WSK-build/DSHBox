package com.dshbox.pluginmanager.market.data

import android.util.Log
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.RegistryPlugin
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一次目录请求拿到的响应；[body] 为 null 表示这一跳没有可读正文。 */
data class RegistryFetch(
    val status: Int,
    val body: String?,
)

/**
 * 目录的 HTTP 取回点。
 *
 * 抽成接口是为了让重试与超时语义可以脱离网络单测：目录是市场唯一的
 * 外部输入，它的失败路径（试了几次、花了多久、为什么失败）比成功路径
 * 更需要被验证。
 */
fun interface RegistryFetcher {
    /** 执行一次 GET。实现可以直接抛异常，由 [RegistrySource] 统一归因。 */
    fun get(url: String, timeoutMs: Int): RegistryFetch
}

/**
 * 生产实现：用 [HttpURLConnection]，不引第三方 HTTP 栈。
 *
 * ## 取数这一层**不加**任何缓存控制
 *
 * 这里刻意不发 `Cache-Control` / `Pragma`，也不给 URL 挂时间戳参数：
 * "总数偏小是不是中间层缓存造成的"**没有取得证据** —— 早先按这个猜测加过那两样，
 * 真机上数字没有变化，已按用户指示回退。
 * 上游 `registry.ts` 用 ETag / Last-Modified 条件请求 + 落盘副本处理新鲜度，
 * 我们没有移植那套；要不要移植，要等现场证据，不再凭猜。
 *
 * 保留**一行**诊断日志：总数对不上时，先看这一次到底取到了多长的正文 ——
 * 正文长度直接说明拿到的是不是一份完整目录。
 */
class HttpRegistryFetcher : RegistryFetcher {

    override fun get(url: String, timeoutMs: Int): RegistryFetch {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            // 部分镜像对没有 UA 的请求直接拒绝，给一个稳定标识。
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            // 一行诊断（每次取数一条，不在重试循环里）：Age / ETag 只当旁证，
            // 判断以 chars 为准 —— 完整目录是 7 位数的字符数。
            Log.i(
                TAG,
                "status=$status chars=${body?.length ?: 0} age=${conn.getHeaderField("Age")} " +
                    "etag=${conn.getHeaderField("ETag") != null} " +
                    "lastMod=${conn.getHeaderField("Last-Modified") != null} url=$url",
            )
            return RegistryFetch(status, body)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "DSHBox-Market/1.0"
        const val TAG = "RegistryFetch"
    }
}

/**
 * 分类 id 的归一。
 *
 * 目录 JSON 是外部输入，`category` 既可能是单个字符串也可能是数组，元素还
 * 可能是空串。归一后为空的条目在上游是**直接拒收**的（没有分类等于这条目
 * 无法在 UI 里被检索到），所以这里把"归一"独立出来，让"拒收"这一步成为
 * 调用方看得见的判断，而不是散落在解析代码里。
 */
object RegistryCategories {

    /**
     * 把 `category` 的原始值归一成去重列表。
     *
     * @param raw 已从 JSON 解出的值：字符串、列表，或其它（其它一律视为无效）。
     *   刻意只接受已解出的 Kotlin 值，不在这里碰 JSON 类型，归一逻辑才能纯函数单测。
     */
    fun normalize(raw: Any?): List<String> {
        val values: List<Any?> = when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is Collection<*> -> raw.toList()
            is Array<*> -> raw.toList()
            else -> listOf(raw)
        }
        val out = LinkedHashSet<String>()
        for (value in values) {
            if (value !is String) continue
            if (value.isEmpty()) continue
            out.add(value)
        }
        return out.toList()
    }
}

/**
 * 从 `JSONObject.opt` 取到的值里读出文本。
 *
 * **绝对不要用 `JSONObject.optString`**：Android 的实现对 JSON `null` 返回**字符串 `"null"`**，
 * 而 JVM 参考实现（单测里挂的那份）返回空串 —— 这个差异在真机上会要命：
 * 目录里大量条目写的是 `"npm": null`，用 `optString` 取回来全是 `"null"`，
 * 于是它们的去重身份（`npm ?: owner/name`）全都相同，**第一条之后全被当成重复条目丢掉**。
 * 真机实测：上游目录 4347 条只剩 2185 条、移动端 45 条只剩 33 条 —— 而取数日志显示
 * 正文是完整的（4,735,725 / 50,930 字符），少的全在解析这一步；JVM 探针永远复现不出来。
 *
 * 走 `opt` + 显式判 [JSONObject.NULL] 在两种实现下行为一致，因此这条规则**能被单测钉住**。
 */
internal fun jsonText(value: Any?): String =
    if (value == null || value === JSONObject.NULL) "" else value.toString().trim()

/**
 * 策展目录的拉取与解析。
 *
 * ## 为什么失败时什么都不回喂
 *
 * 目录回答的是"现在有哪些插件"。它每天新增，所以一份旧目录不是"降级答案"
 * 而是**错误答案**：今早刚发布的插件在旧目录里读作"不存在"。因此这里没有
 * 落盘缓存、没有随包快照——网络失败就是失败，调用方把原因展示给用户并
 * 提供重试，这是用户可以处理的状态。唯一的例外是同一进程内上一次**成功**
 * 拿到的副本：`force = false` 时直接复用它，省掉一次几 MB 的下载；一旦
 * 发起请求并失败，即使副本在手也返回失败。
 *
 * ## 为什么每源试两次，而且先把每个源都走一遍
 *
 * 目录是市场发起的第一个请求，而它往往要跨一段很长、很差的链路。一次
 * 瞬时失败会让整个市场看起来"一个插件都没有"，而重试一次的代价只有一两秒。
 *
 * 但重试是**第二轮**才发生的事：第一轮先把每个源各试一次。否则一个黑洞型
 * 源（连不上、又不立刻报错）会先吃掉 2 × 超时，把后面本来能用的源饿死。
 *
 * 注：上游按"下载区域"维护多条目录源；本版本只有官方一条，区域路由属于
 * 另一个模块，因此这里保留"多源 + 每源重试"的结构但默认只配一条。
 *
 * ## 文案语言
 *
 * 失败说明（[describeFailure]）是"中文 / English"双语串，由 UI 的
 * `localizeBilingual` 按界面语言择半。**同一时刻只支持 zh / en**：
 * ar / es / fr / ru 会回退英文那一半——这不是"已完整本地化"。
 */
class RegistrySource(
    private var sources: List<String> = listOf(DEFAULT_URL),
    private val fetcher: RegistryFetcher = HttpRegistryFetcher(),
    private val timeoutMs: Int = FETCH_TIMEOUT_MS,
    private val attemptsPerSource: Int = ATTEMPTS_PER_SOURCE,
) {

    companion object {
        /** 官方策展目录。 */
        const val DEFAULT_URL = "https://awesome-dsh-plugin.com/plugins.json"

        /**
         * 单次请求的超时。
         *
         * 刻意给得宽松：目录有几百 KB，从远端网络取回来本就不是四秒能完成的事，
         * 而过早掐断一条**本来能答**的链路，比多等几秒的代价大得多。
         */
        const val FETCH_TIMEOUT_MS = 15_000

        /** 每源尝试次数。 */
        const val ATTEMPTS_PER_SOURCE = 2
    }

    private val _failure = MutableStateFlow<RegistryFailure?>(null)

    /** 最近一次失败；成功后被清空。 */
    val failure: StateFlow<RegistryFailure?> = _failure

    /** 进程内上一次成功的目录副本。刻意只在内存里。 */
    @Volatile
    private var cached: Registry? = null

    suspend fun load(force: Boolean): AppResult<Registry> = withContext(Dispatchers.IO) {
        if (!force) {
            cached?.let { return@withContext AppResult.Success(it) }
        }
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var lastReason = "unknown error"
        // 轮次在外、地址在内：**先把每个地址各试一次，全都失败再回头重试**。
        // 原先地址在外、重试在内，于是一个黑洞型地址（连不上但不立刻报错）会先用掉
        // 2 × FETCH_TIMEOUT_MS，第二个本来能用的地址要等满 30 秒才被轮到；多地址时
        // 最坏延迟还会随地址数线性累加，界面看起来就是"卡死"。
        // 改成本结构后最坏一轮（= 一个超时），且坏地址不会饿死好地址；
        // 只有一个地址时行为与原先完全一致（仍是试 attemptsPerSource 次）。
        for (round in 0 until attemptsPerSource) {
            for (source in sources) {
                attempts += 1
                val attempt = attempt(source)
                val registry = attempt.registry
                if (registry != null) {
                    cached = registry
                    _failure.value = null
                    return@withContext AppResult.Success(registry)
                }
                lastReason = attempt.reason
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val failure = RegistryFailure(
            message = describeFailure(lastReason, elapsed, attempts),
            attempts = attempts,
            elapsedMs = elapsed,
        )
        // 连同内存副本一起丢掉：一次失败之后不能再有任何路径把旧目录当成
        // 当前目录交出去，否则"失败"与"成功"在界面上就分不清了。
        cached = null
        _failure.value = failure
        AppResult.Failure(AppError(code = "REGISTRY_UNAVAILABLE", message = failure.message))
    }

    /** 丢弃内存副本，让下一次调用重新联网。 */
    fun forget() {
        cached = null
    }

    /**
     * 换用另一组地址（切换数据源时调用）。
     *
     * 同时丢掉内存副本是**必须**的：`cached` 里存的是上一个源的目录，不丢的话
     * 下一次非强制加载会把旧源的目录当成当前目录交出去 —— 界面看起来就是
     * "切了没反应"。所以这里不把这个责任留给调用方。
     */
    fun setSources(newSources: List<String>) {
        sources = newSources
        forget()
    }

    private class Attempt(val registry: Registry?, val reason: String)

    private fun attempt(source: String): Attempt = try {
        val response = fetcher.get(source, timeoutMs)
        when {
            response.status !in 200..299 -> Attempt(null, "HTTP ${response.status}")
            response.body.isNullOrBlank() -> Attempt(null, "empty response body")
            else -> Attempt(parse(response.body), "")
        }
    } catch (t: Throwable) {
        Attempt(null, t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName)
    }

    /**
     * 解析并校验目录正文。
     *
     * `plugins` 必须是非空数组，否则整份目录不可用——一个空目录和一次失败在
     * 用户眼里没有区别，都不该被当成"市场里没有插件"。
     */
    fun parse(body: String): Registry {
        val root = JSONObject(body)
        val pluginsJson = root.optJSONArray("plugins")
        if (pluginsJson == null || pluginsJson.length() == 0) {
            throw IllegalArgumentException("the catalog came back empty")
        }
        val plugins = ArrayList<RegistryPlugin>(pluginsJson.length())
        // 归一后的 name 必须唯一且非空：UI 用它当列表 key（配合下标兜底），
        // 空 key 或重复 key 会让 Compose 直接抛异常——一条坏数据不该让整个
        // 市场页打不开，所以这里把坏条目丢掉而不是交给下游。
        val seenNames = HashSet<String>()
        for (index in 0 until pluginsJson.length()) {
            val item = pluginsJson.optJSONObject(index) ?: continue
            val rawCategory = item.opt("category")
            val categoryValue: Any? =
                if (rawCategory is JSONArray) jsonArrayToList(rawCategory) else rawCategory
            val categories = RegistryCategories.normalize(categoryValue)
            // 归一后没有分类的条目直接丢弃：它在 UI 里既排不进分类也无法被筛出来。
            if (categories.isEmpty()) continue
            val name = jsonText(item.opt("name"))
            // 去空白后为空的 name 直接丢弃：没有名字的条目在界面上无法被指认。
            if (name.isEmpty()) continue
            // owner 与 npm 都先 trim 再当身份：去重键用的是 trim 版（见下），
            // 若 displayName 用未 trim 的原值，两者会对不上——`" foo "` 与
            // `"foo"` 会被当成两个不同条目，同一个插件的第二份被静默保留。
            val owner = jsonText(item.opt("owner"))
            val npm = jsonText(item.opt("npm")).ifBlank { null }
            // 重名只保留首条：**按显示身份去重**（有 npm 名就用它，否则 owner/name）。
            // 若只按仓库名去重，两条同仓库名但指向不同 npm 包的条目里，
            // 第二条会被丢掉——那是真插件被静默删除，比列表里出现近似重复更糟。
            val identity = npm ?: (owner + "/" + name)
            if (!seenNames.add(identity)) continue
            plugins.add(
                RegistryPlugin(
                    name = name,
                    owner = owner,
                    url = item.optString("url"),
                    categories = categories,
                    description = stringMap(item.optJSONObject("description")),
                    install = jsonText(item.opt("install")),
                    added = jsonText(item.opt("added")),
                    npm = npm,
                    tarball = item.stringOrNull("tarball"),
                    stars = item.intOrNull("stars"),
                    // 缺失表示"没有 npm 包"，不是 0；因此这里保持 null，不折叠成 0。
                    downloads = item.intOrNull("downloads"),
                    version = item.stringOrNull("version"),
                    deprecated = item.optBoolean("deprecated", false),
                    replacement = item.stringOrNull("replacement"),
                    screenshots = screenshotUrls(item.optJSONArray("screenshots")),
                ),
            )
        }
        return Registry(
            updated = root.optString("updated"),
            count = root.optInt("count", plugins.size),
            categories = nestedStringMap(root.optJSONObject("categories")),
            plugins = plugins,
        )
    }

    /**
     * 失败说明。
     *
     * 这句话会直接出现在界面上，也会进日志导出，所以"原因 + 耗时 + 尝试次数"
     * 必须都在里面：只说"加载失败"无法区分慢链路、被墙、以及本进程用不了的
     * 代理——而这三种的处理办法完全不同。
     */
    private fun describeFailure(reason: String, elapsedMs: Long, attempts: Int): String {
        val seconds = elapsedMs / 1000
        return "$reason（$seconds 秒，尝试 $attempts 次） / $reason ($seconds s, $attempts attempts)"
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> =
        (0 until array.length()).map { array.opt(it) }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }

    private fun JSONObject.intOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return if (opt(key) is Number) optInt(key) else null
    }

    /** 单条目录最多展示这么多张策展截图。 */
    private val MAX_SCREENSHOTS = 6

    /** 允许加载图片的主机白名单（目录是外部输入，不能让任意主机拿到用户请求）。 */
    private val ALLOWED_IMAGE_HOSTS = listOf(
        "https://raw.githubusercontent.com/",
        "https://user-images.githubusercontent.com/",
        "https://github.com/",
        "https://camo.githubusercontent.com/",
    )

    /**
     * 策展截图：只收 https 的 GitHub 图床地址。
     *
     * 目录是外部输入，图片地址会直接进网络请求，所以这里做白名单——
     * 非 https、非 GitHub 域名一律丢弃，避免把用户的 IP 暴露给目录里任意主机。
     */
    private fun screenshotUrls(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isEmpty() || out.size >= MAX_SCREENSHOTS) continue
            val lower = value.lowercase()
            if (!lower.startsWith("https://")) continue
            if (!ALLOWED_IMAGE_HOSTS.any { lower.startsWith(it) }) continue
            out.add(value)
        }
        return out
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) out[key] = obj.optString(key)
        return out
    }

    private fun nestedStringMap(obj: JSONObject?): Map<String, Map<String, String>> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        for (key in obj.keys()) out[key] = stringMap(obj.optJSONObject(key))
        return out
    }
}
