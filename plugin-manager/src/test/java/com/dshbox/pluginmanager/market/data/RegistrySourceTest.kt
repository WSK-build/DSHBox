package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录拉取的重试语义。
 *
 * 这里只覆盖失败路径：成功路径要解析 JSON，而 Android 的 `org.json` 在本地
 * 单测里是桩实现。失败路径恰恰是最需要钉住的部分——试了几次、源之间的顺序、
 * 失败说明里有没有原因与次数，都是"失败绝不回喂旧数据"这条规则的具体形态。
 */
class RegistrySourceTest {

    /** 按脚本回放的取回点：给状态码就回响应，给异常就抛。 */
    private class FakeFetcher(private val script: MutableList<Any>) : RegistryFetcher {

        /** 按调用顺序记下被请求过的地址——用来钉住"轮次在外、地址在内"的尝试顺序。 */
        val urls = mutableListOf<String>()

        override fun get(url: String, timeoutMs: Int): RegistryFetch {
            urls += url
            return when (val step = script.removeFirstOrNull()) {
                is Int -> RegistryFetch(step, null)
                is Throwable -> throw step
                else -> RegistryFetch(500, null)
            }
        }
    }

    private fun source(script: List<Any>, sources: List<String> = listOf("https://a.invalid/plugins.json")) =
        RegistrySource(
            sources = sources,
            fetcher = FakeFetcher(script.toMutableList()),
            timeoutMs = 1_000,
        )

    @Test
    fun retriesEachSourceTwiceThenFails() {
        val subject = source(listOf(500, 503))
        val result = runBlocking { subject.load(force = true) }
        assertTrue(result is AppResult.Failure)
        assertEquals(2, (subject.failure.value?.attempts))
        assertTrue(subject.failure.value?.message?.contains("尝试 2 次") == true)
        assertTrue(subject.failure.value?.message?.contains("HTTP 503") == true)
    }

    @Test
    fun walksEverySourceBeforeGivingUp() {
        val subject = source(
            script = listOf(500, 500, 500, 500),
            sources = listOf("https://a.invalid/plugins.json", "https://b.invalid/plugins.json"),
        )
        val result = runBlocking { subject.load(force = true) }
        assertTrue(result is AppResult.Failure)
        assertEquals(4, subject.failure.value?.attempts)
        assertTrue((subject.failure.value?.elapsedMs ?: -1) >= 0)
    }

    /**
     * 尝试顺序：A、B、A、B —— 而不是 A、A、B、B。
     *
     * 后者会让一个黑洞型地址（连不上、又不立刻报错）先吃掉 2 × 超时，
     * 本来能用的第二个地址要等满两轮才被轮到。总次数不变（4 次），
     * 变的是"谁先被试"——这正是"坏地址不饿死好地址"的实现形态。
     */
    @Test
    fun triesEverySourceOnceBeforeRetryingAny() {
        val fetcher = FakeFetcher(mutableListOf(500, 500, 500, 500))
        val subject = RegistrySource(
            sources = listOf("https://a.invalid/plugins.json", "https://b.invalid/plugins.json"),
            fetcher = fetcher,
            timeoutMs = 1_000,
        )
        assertTrue(runBlocking { subject.load(force = true) } is AppResult.Failure)
        assertEquals(4, subject.failure.value?.attempts)
        assertEquals(
            listOf(
                "https://a.invalid/plugins.json",
                "https://b.invalid/plugins.json",
                "https://a.invalid/plugins.json",
                "https://b.invalid/plugins.json",
            ),
            fetcher.urls,
        )
    }

    @Test
    fun transportExceptionBecomesAFailureNotACrash() {
        val subject = source(listOf(IOException("connection reset"), IOException("connection reset")))
        val result = runBlocking { subject.load(force = true) }
        assertTrue(result is AppResult.Failure)
        assertTrue(subject.failure.value?.message?.contains("connection reset") == true)
    }

    @Test
    fun emptyBodyCountsAsAFailure() {
        val subject = source(listOf(200, 200))
        val result = runBlocking { subject.load(force = true) }
        assertTrue(result is AppResult.Failure)
        assertTrue(subject.failure.value?.message?.contains("empty response body") == true)
    }

    @Test
    fun failureIsRecordedAndNeverReportedAsSuccess() {
        val subject = source(listOf(404, 404))
        assertNull(subject.failure.value)
        val result = runBlocking { subject.load(force = true) }
        assertTrue(result is AppResult.Failure)
        assertTrue(subject.failure.value != null)
        // 失败后再次非强制加载仍然走网络，不会因为"有过一次成功"而回喂旧数据。
        val again = runBlocking { subject.load(force = false) }
        assertTrue(again is AppResult.Failure)
    }

    /**
     * 换源：下一次请求必须打到**新地址**。
     *
     * 这里只能证明"地址换了"；"内存副本被丢掉"那一步测不到 —— 成功路径要解析
     * JSON，而 `org.json` 在本地单测里是桩实现（见类注释）。所以那一半靠
     * `setSources` 内部直接调 `forget()` 保证，不给调用方留"忘了清"的机会。
     */
    @Test
    fun setSourcesSendsTheNextRequestToTheNewAddress() {
        val fetcher = FakeFetcher(mutableListOf(500, 500))
        val subject = RegistrySource(
            sources = listOf("https://a.invalid/plugins.json"),
            fetcher = fetcher,
            timeoutMs = 1_000,
        )
        assertTrue(runBlocking { subject.load(force = false) } is AppResult.Failure)
        subject.setSources(listOf("https://b.invalid/plugins.json"))
        assertTrue(runBlocking { subject.load(force = false) } is AppResult.Failure)
        // 每次加载按"每源 2 次"的规则各试两次，所以是 A、A、B、B。
        assertEquals(
            listOf(
                "https://a.invalid/plugins.json",
                "https://a.invalid/plugins.json",
                "https://b.invalid/plugins.json",
                "https://b.invalid/plugins.json",
            ),
            fetcher.urls,
        )
    }
}

/**
 * 目录正文的解析。
 *
 * 重点在"一条坏数据不该让整页打不开"：`name` 会被 UI 当成列表 key，
 * 空 key 或重复 key 会让 Compose 直接抛异常。所以解析层必须把这类条目
 * 丢掉，而不是交给下游去撞。
 */
class RegistryParseTest {

    private fun parse(body: String) = RegistrySource().parse(body)

    private fun catalog(vararg entries: String): String =
        """{"updated":"2026-01-01","plugins":[${entries.joinToString(",")}]}"""

    private fun entry(
        name: String,
        categories: String = "[\"tools\"]",
        owner: String = "owner",
        npm: String? = null,
    ): String {
        val npmField = if (npm == null) "" else ",\"npm\":\"$npm\""
        return "{\"name\":\"$name\",\"owner\":\"$owner\"," +
            "\"url\":\"https://example.com/$name\",\"category\":$categories$npmField}"
    }

    @Test
    fun blankNameEntriesAreDropped() {
        val registry = parse(catalog(entry(""), entry("   "), entry("good")))
        assertEquals(listOf("good"), registry.plugins.map { it.name })
    }

    @Test
    fun sameRepoNameUnderDifferentOwnersKeepsBothEntries() {
        val registry = parse(
            catalog(
                entry("dup", owner = "first"),
                entry("dup", owner = "second"),
                entry("other"),
            ),
        )
        // 去重按**显示身份**（npm 名，或 owner/name）：同名不同 owner 是两个不同条目，
        // 都保留——按仓库名去重会把第二个真插件静默删掉。
        assertEquals(listOf("dup", "dup", "other"), registry.plugins.map { it.name })
        assertEquals(listOf("first", "second"), registry.plugins.take(2).map { it.owner })
    }

    @Test
    fun identicalDisplayIdentityKeepsTheFirstEntry() {
        val registry = parse(
            catalog(
                entry("dup", owner = "same"),
                entry("dup", owner = "same"),
            ),
        )
        assertEquals(1, registry.plugins.size)
    }

    @Test
    fun namesAreTrimmedBeforeDeduplication() {
        val registry = parse(catalog(entry(" spaced "), entry("spaced")))
        assertEquals(listOf("spaced"), registry.plugins.map { it.name })
    }

    @Test
    fun npmFieldIsTrimmedBeforeItBecomesTheDisplayIdentity() {
        // 去重键用的是 trim 版 npm；若 displayName 用未 trim 的原值，两者会对不上。
        val registry = parse(catalog(entry("dup", owner = "owner", npm = "  spaced-npm  ")))
        assertEquals("spaced-npm", registry.plugins.single().displayName)
    }

    @Test
    fun npmIdentityIsDeduplicatedAfterTrimming() {
        val registry = parse(
            catalog(
                entry("a", owner = "o1", npm = " same "),
                entry("b", owner = "o2", npm = "same"),
            ),
        )
        assertEquals("npm 身份 trim 后相同就该去重", 1, registry.plugins.size)
    }

    @Test
    fun entriesWithoutCategoriesAreStillDropped() {
        val registry = parse(catalog(entry("nocat", categories = "[]"), entry("good")))
        assertEquals(listOf("good"), registry.plugins.map { it.name })
    }

    @Test
    fun anAllBadCatalogYieldsAnEmptyListInsteadOfThrowing() {
        // 正文本身是合法目录（plugins 非空），只是每条都不可用。
        val registry = parse(catalog(entry(""), entry("", categories = "[]")))
        assertTrue(registry.plugins.isEmpty())
    }

    @Test
    fun aCatalogWithoutPluginsIsRejected() {
        // 空目录与"一次失败"在用户眼里没有区别，都不该被当成"市场里没有插件"。
        val thrown = runCatching { parse("""{"updated":"x","plugins":[]}""") }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun optionalNumbersStayNullInsteadOfZero() {
        val registry = parse(catalog(entry("good")))
        assertNull("缺失表示没有 npm 包，不是 0", registry.plugins.single().downloads)
        assertNull(registry.plugins.single().stars)
        assertNull(registry.plugins.single().version)
    }
}
