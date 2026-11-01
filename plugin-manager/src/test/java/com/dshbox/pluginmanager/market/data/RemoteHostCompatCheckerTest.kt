package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.HostCompatibility
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览期宿主兼容性判定的**缓存纪律**与**降级语义**。
 *
 * 这一层只做两件事，两件都容易在补丁里悄悄写坏：
 *
 * 1. **事实可缓存、结论永不缓存**。缓存的只是"对端 manifest 长什么样"；
 *    兼容性结论每次都用**当前**宿主版本现算。宿主换层后若结论被缓存住，
 *    界面会一直显示旧结论——而用户刚换了层，正是最需要看到新结论的时候。
 * 2. **判不清楚一律 UNKNOWN**。没有 npm 名、manifest 取不到、区间读不懂，
 *    都必须落在 UNKNOWN。把"不知道"说成"不兼容"会放弃可安装的插件；
 *    插件；说成"兼容"会让用户拿安装失败当探测器。两个方向都不能走。
 *
 * 另外钉住负缓存的**存在**与**作用域**：失败也进缓存（免得一次网络抖动让
 * 同一批条目反复整页超时），但负缓存过期后必须重试，不能变成"一次失败永久
 * 判未知"。
 */
class RemoteHostCompatCheckerTest {

    /** 可变时钟：让 TTL 边界可测，不依赖真实时间流逝。 */
    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    /** 计数取数点：既当假数据源，也用来断言"到底查了几次"。 */
    private class CountingFetcher(
        private val manifestOf: (String) -> String?,
    ) : RemoteManifestFetcher {
        val calls = mutableListOf<String>()
        override fun fetch(npmName: String): String? {
            calls += npmName
            return manifestOf(npmName)
        }
    }

    /** 声明 `engines.dsh: ^0.2.0` 的 manifest。 */
    private fun manifestRequiring(range: String): String =
        """{"name":"demo","engines":{"dsh":"$range"}}"""

    private fun checker(
        fetcher: RemoteManifestFetcher,
        clock: () -> Long,
        host: () -> String?,
        ttlMs: Long = 6 * 60 * 60 * 1000L,
        negativeTtlMs: Long = 10 * 60 * 1000L,
    ) = RemoteHostCompatChecker(
        hostVersion = host,
        fetcher = fetcher,
        ttlMs = ttlMs,
        negativeTtlMs = negativeTtlMs,
        clock = clock,
    )

    // ---------------------------------------------------------------- 事实缓存

    @Test
    fun `同一包重复判定只取一次 manifest`() = runBlocking {
        val fetcher = CountingFetcher { manifestRequiring("^0.2.0") }
        val subject = checker(fetcher, FakeClock(), host = { "0.2.0" })

        repeat(3) { subject.check(listOf("demo"), npmNameOf = { it }) }

        assertEquals("manifest 是事实，TTL 内应只取一次", listOf("demo"), fetcher.calls)
    }

    @Test
    fun `事实缓存过期后重新取数`() = runBlocking {
        val clock = FakeClock()
        val fetcher = CountingFetcher { manifestRequiring("^0.2.0") }
        val subject = checker(fetcher, clock, host = { "0.2.0" }, ttlMs = 1_000L)

        subject.check(listOf("demo"), npmNameOf = { it })
        clock.now += 1_001L
        subject.check(listOf("demo"), npmNameOf = { it })

        assertEquals("TTL 过后应重新取一次", 2, fetcher.calls.size)
    }

    // -------------------------------------------------------- 结论不缓存（核心）

    @Test
    fun `宿主版本变化时结论立即跟随——结论不随事实一起被缓存`() = runBlocking {
        val fetcher = CountingFetcher { manifestRequiring("^0.2.0") }
        var host = "0.2.0"
        val subject = checker(fetcher, FakeClock(), host = { host })

        val before = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")
        assertEquals(HostCompatibility.Status.COMPATIBLE, before.status)

        // 宿主换层：manifest 事实可以继续用缓存，但结论必须重算。
        host = "0.3.0"
        val after = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(
            "结论必须用当前宿主版本现算，不能被上一次的缓存住",
            HostCompatibility.Status.INCOMPATIBLE,
            after.status,
        )
        assertEquals("事实仍应命中缓存，不应重新取数", listOf("demo"), fetcher.calls)
    }

    // -------------------------------------------------------------- 负缓存

    @Test
    fun `取数失败进负缓存，负缓存期内不重复取数`() = runBlocking {
        val fetcher = CountingFetcher { null }
        val subject = checker(fetcher, FakeClock(), host = { "0.2.0" })

        repeat(3) { subject.check(listOf("demo"), npmNameOf = { it }) }

        assertEquals("失败也应被负缓存，免得整页反复超时", listOf("demo"), fetcher.calls)
    }

    @Test
    fun `负缓存过期后重试，不是一次失败永久判未知`() = runBlocking {
        val clock = FakeClock()
        var manifest: String? = null
        val fetcher = CountingFetcher { manifest }
        val subject = checker(
            fetcher, clock, host = { "0.2.0" },
            ttlMs = 6 * 60 * 60 * 1000L, negativeTtlMs = 1_000L,
        )

        val failed = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")
        assertEquals(HostCompatibility.Status.UNKNOWN, failed.status)

        // 网络恢复 + 负缓存过期 → 必须重试并拿到真结论。
        manifest = manifestRequiring("^0.2.0")
        clock.now += 1_001L
        val recovered = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(HostCompatibility.Status.COMPATIBLE, recovered.status)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `负缓存比正缓存短——失败不会被当成长期事实`() = runBlocking {
        assertTrue(
            "负缓存必须短于正缓存，否则一次抖动会长期压住重试",
            RemoteHostCompatChecker.DEFAULT_NEGATIVE_TTL_MS < RemoteHostCompatChecker.DEFAULT_TTL_MS,
        )
    }

    // -------------------------------------------------------------- invalidate

    @Test
    fun `invalidate 清空缓存后重新取数`() = runBlocking {
        val fetcher = CountingFetcher { manifestRequiring("^0.2.0") }
        val subject = checker(fetcher, FakeClock(), host = { "0.2.0" })

        subject.check(listOf("demo"), npmNameOf = { it })
        subject.invalidate()
        subject.check(listOf("demo"), npmNameOf = { it })

        assertEquals("显式刷新必须真的重新取数", 2, fetcher.calls.size)
    }

    // ------------------------------------------------------- 判不清楚一律 UNKNOWN

    @Test
    fun `没有 npm 名的条目不查也不猜，判 UNKNOWN`() = runBlocking {
        val fetcher = CountingFetcher { error("不该被调用：没有 npm 名就不该取数") }
        val subject = checker(fetcher, FakeClock(), host = { "0.2.0" })

        val verdict = subject.check(listOf("owner/repo")) { null }.getValue("owner/repo")

        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertTrue("取不到信息应落在 UNAVAILABLE", fetcher.calls.isEmpty())
    }

    @Test
    fun `manifest 取不到时判 UNKNOWN 而非不兼容`() = runBlocking {
        val subject = checker(CountingFetcher { null }, FakeClock(), host = { "0.2.0" })

        val verdict = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertEquals(HostCompatibility.Basis.UNAVAILABLE, verdict.basis)
    }

    @Test
    fun `manifest 读不懂（非法 JSON）时判 UNKNOWN 而非不兼容`() = runBlocking {
        val subject = checker(CountingFetcher { "{ not json" }, FakeClock(), host = { "0.2.0" })

        val verdict = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
    }

    @Test
    fun `插件未声明宿主要求时判 UNKNOWN 且基据为 UNDECLARED`() = runBlocking {
        val subject = checker(
            CountingFetcher { """{"name":"demo","version":"1.0.0"}""" },
            FakeClock(), host = { "0.2.0" },
        )

        val verdict = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertEquals(
            "「我们没读到」与「插件没说」必须分开",
            HostCompatibility.Basis.UNDECLARED,
            verdict.basis,
        )
    }

    @Test
    fun `宿主版本读不到时判 UNKNOWN，不拿空版本去比对`() = runBlocking {
        val subject = checker(CountingFetcher { manifestRequiring("^0.2.0") }, FakeClock(), host = { null })

        val verdict = subject.check(listOf("demo"), npmNameOf = { it }).getValue("demo")

        assertEquals(HostCompatibility.Status.UNKNOWN, verdict.status)
        assertEquals(HostCompatibility.Basis.MANIFEST, verdict.basis)
    }

    @Test
    fun `取数点抛异常不致命，该条目判 UNKNOWN，其余条目照常`() = runBlocking {
        val fetcher = RemoteManifestFetcher { name ->
            if (name == "boom") throw java.io.IOException("network down")
            manifestRequiring("^0.2.0")
        }
        val subject = checker(fetcher, FakeClock(), host = { "0.2.0" })

        val verdicts = subject.check(listOf("boom", "ok"), npmNameOf = { it })

        assertEquals(HostCompatibility.Status.UNKNOWN, verdicts.getValue("boom").status)
        assertEquals(
            "一个条目取数失败不能带走整批判定",
            HostCompatibility.Status.COMPATIBLE,
            verdicts.getValue("ok").status,
        )
    }

    @Test
    fun `结果键与输入条目一一对应，顺序与数量都不变`() = runBlocking {
        val subject = checker(CountingFetcher { manifestRequiring("^0.2.0") }, FakeClock(), host = { "0.2.0" })
        val names = listOf("a", "b", "c")

        val verdicts = subject.check(names, npmNameOf = { it })

        assertEquals(names, verdicts.keys.toList())
    }
}
